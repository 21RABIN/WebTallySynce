import json
import xmltodict
import httpx
import inspect
import re
import copy
from pathlib import Path
from fastapi import APIRouter, Body, Depends, HTTPException, Header, Query, Request
from fastapi.routing import APIRoute
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Optional, Dict, Any, List

from .config import get_settings, Settings
from .auth import get_token_claims, get_token_error, issue_token
from .tally_client import TallyClient
from .xml_builder import (
    _default_voucher_fetch,
    build_master_upsert,
    build_master_list,
    build_collection_export,
    build_company_create,
    build_company_list,
    build_company_open,
    build_company_update,
    build_daybook_voucher_export,
    build_stock_item_price_list_upsert,
    build_tdl_gateway_report,
    build_voucher_upsert,
    build_voucher_list,
    build_report_export,
)

router = APIRouter()

RESPONSE_VIEWS = {"summary", "full", "raw"}
FULL_FETCH_TOKEN = "*.*"
STATUTORY_REPORT_ENDPOINTS = {
    "Form 24Q": "form-24q",
    "Form 26Q": "form-26q",
    "Form 27EQ": "form-27eq",
    "TDS Outstandings": "tds-outstandings",
}
STATUTORY_REPORT_ROUTES = {
    "/reports/form-24q",
    "/reports/form-26q",
    "/reports/form-27eq",
    "/reports/tds-outstandings",
}
TDL_FEATURE_DEFAULTS: Dict[str, Dict[str, Any]] = {
    "company_open": {
        "routes": ["/companies/open"],
        "status": "scaffold",
        "report_name": "LC Company Open Report",
        "write_report_name": "LC Company Open Report",
        "mode": "tdl-report",
    },
    "company_create": {
        "routes": ["/companies/create"],
        "status": "scaffold",
        "report_name": "LC Company Create Report",
        "write_report_name": "LC Company Create Report",
        "mode": "tdl-report",
    },
    "security_roles": {
        "routes": ["/settings/security-roles"],
        "status": "scaffold",
        "report_name": "LC Security Roles Report",
        "write_report_name": "LC Security Roles Write Report",
        "collection_name": "LC_SECURITY_ROLES",
        "mode": "tdl-collection",
    },
    "price_levels": {
        "routes": ["/price-levels", "/settings/price-structures"],
        "status": "scaffold",
        "report_name": "LC Price Levels Report",
        "write_report_name": "LC Price Levels Write Report",
        "collection_name": "LC_PRICE_LEVELS",
        "mode": "tdl-report",
    },
    "payroll_exports": {
        "routes": ["/employees", "/employee-groups", "/pay-heads", "/attendance-types"],
        "status": "scaffold",
        "report_name": "LC Payroll Exports Report",
        "mode": "tdl-collection",
        "route_collections": {
            "/employees": "LC_EMPLOYEES",
            "/employee-groups": "LC_EMPLOYEE_GROUPS",
            "/pay-heads": "LC_PAY_HEADS",
            "/attendance-types": "LC_ATTENDANCE_TYPES",
        },
    },
    "tds_outstandings": {
        "routes": ["/reports/tds-outstandings"],
        "status": "scaffold",
        "report_name": "LC TDS Outstandings Report",
        "mode": "tdl-report",
    },
}


INVALID_XML_REF_RE = re.compile(r"&#(x?[0-9A-Fa-f]+);")
INVALID_XML_CHAR_RE = re.compile(
    "[^\u0009\u000A\u000D\u0020-\uD7FF\uE000-\uFFFD\U00010000-\U0010FFFF]"
)


def _is_valid_xml_codepoint(codepoint: int) -> bool:
    return (
        codepoint in (0x9, 0xA, 0xD)
        or 0x20 <= codepoint <= 0xD7FF
        or 0xE000 <= codepoint <= 0xFFFD
        or 0x10000 <= codepoint <= 0x10FFFF
    )


def sanitize_xml(xml_text: str) -> str:
    def replace_ref(match: re.Match[str]) -> str:
        raw = match.group(1)
        base = 16 if raw.lower().startswith("x") else 10
        value = raw[1:] if base == 16 else raw
        try:
            codepoint = int(value, base)
        except ValueError:
            return ""
        return match.group(0) if _is_valid_xml_codepoint(codepoint) else ""

    xml_text = INVALID_XML_REF_RE.sub(replace_ref, xml_text)
    return INVALID_XML_CHAR_RE.sub("", xml_text)


def xml_to_json(xml_text: str) -> Dict[str, Any]:
    try:
        return xmltodict.parse(sanitize_xml(xml_text))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"XML parse failed: {exc}") from exc


def prune_export_response(obj: Dict[str, Any]) -> Dict[str, Any]:
    """
    Strip envelope/desc and return the most relevant payload:
    - DATA.COLLECTION if present
    - else DATA
    - else BODY without DESC
    - else original object
    """
    try:
        env = obj.get("ENVELOPE") or {}
        body = env.get("BODY") or {}
        data = body.get("DATA")
        if data:
            collection = data.get("COLLECTION") if isinstance(data, dict) else None
            if collection is not None:
                return collection
            return data
        if "DESC" in body:
            body = {k: v for k, v in body.items() if k != "DESC"}
            return body or obj
        return body or obj
    except Exception:
        return obj


def _normalize_view(view: Optional[str]) -> str:
    normalized = (view or "summary").strip().lower()
    if normalized not in RESPONSE_VIEWS:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": f"Unsupported view '{view}'. Use one of: summary, full, raw.",
            },
        )
    return normalized


def _needs_full_fetch(view: str) -> bool:
    return _normalize_view(view) in {"full", "raw"}


def _full_fetch(fetch: Optional[List[str]], extra_fields: Optional[List[str]] = None) -> List[str]:
    fields = list(fetch or [])
    if FULL_FETCH_TOKEN not in fields:
        fields.insert(0, FULL_FETCH_TOKEN)
    for field in extra_fields or []:
        if field not in fields:
            fields.append(field)
    return fields


def _collection_name(prefix: str, source: Optional[str]) -> str:
    raw = (source or "").strip()
    if not raw:
        raw = prefix
    token = re.sub(r"[^A-Za-z0-9]+", "_", raw).strip("_").upper()
    if not token:
        token = prefix
    if not token.startswith(prefix):
        token = f"{prefix}_{token}"
    return token


def _master_collection_prefix(master_type: str) -> str:
    normalized = (master_type or "").strip().lower()
    if normalized in {
        "stock item",
        "stock group",
        "stock category",
        "godown",
        "unit",
        "bom",
    }:
        return "PYSTOCK"
    if normalized in {
        "ledger",
        "ledger group",
        "group",
        "cost category",
        "cost centre",
        "cost center",
        "project",
        "employee",
        "employee group",
        "pay head",
        "attendance type",
    }:
        return "PYLEDGER"
    return "PYMASTER"


def _caller_collection_name(prefix: str) -> str:
    frame = inspect.currentframe()
    try:
        caller = frame.f_back.f_back if frame and frame.f_back and frame.f_back.f_back else None
        caller_name = caller.f_code.co_name if caller else prefix
    finally:
        del frame
    return _collection_name(prefix, caller_name)


def _shape_xml_response(parsed: Dict[str, Any], view: str) -> Dict[str, Any]:
    return parsed if _normalize_view(view) == "raw" else prune_export_response(parsed)


def _is_null_envelope_response(payload: Any) -> bool:
    return isinstance(payload, dict) and set(payload.keys()) == {"ENVELOPE"} and payload.get("ENVELOPE") is None


def _report_filter_summary(
    *,
    stock_item: Optional[str] = None,
    godown: Optional[str] = None,
    ledger_name: Optional[str] = None,
    from_date: Optional[str] = None,
    to_date: Optional[str] = None,
) -> Dict[str, Any]:
    summary: Dict[str, Any] = {}
    if isinstance(stock_item, str) and stock_item.strip():
        summary["stock_item"] = stock_item.strip()
    if isinstance(godown, str) and godown.strip():
        summary["godown"] = godown.strip()
    if isinstance(ledger_name, str) and ledger_name.strip():
        summary["ledger_name"] = ledger_name.strip()
    normalized_from = _normalize_tally_date(from_date)
    if normalized_from:
        summary["from_date"] = normalized_from
    normalized_to = _normalize_tally_date(to_date)
    if normalized_to:
        summary["to_date"] = normalized_to
    return summary


def _empty_or_ambiguous_report_response(
    report_name: str,
    filters: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    return {
        "source_report": report_name,
        "report_state": "empty_or_unsupported",
        "filters": dict(filters or {}),
        "items": [],
        "reason": (
            "Tally returned ENVELOPE=null for this report. In this Tally build that usually means either "
            "the report had no rows for the supplied filters, or the report/screen is not available through "
            "the XML interface for the selected company or feature set."
        ),
    }


def _empty_company_list_response(reason: str) -> Dict[str, Any]:
    return {
        "source_collection": "List of Companies",
        "report_state": "empty_or_unsupported",
        "items": [],
        "reason": reason,
    }


def _load_tdl_manifest(settings: Settings) -> Dict[str, Any]:
    manifest_path = settings.resolved_tdl_manifest_path()
    if not manifest_path.is_file():
        return {}
    try:
        parsed = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _tdl_feature_contract(settings: Settings, feature_name: str) -> Dict[str, Any]:
    contract = copy.deepcopy(TDL_FEATURE_DEFAULTS.get(feature_name, {}))
    manifest = _load_tdl_manifest(settings)
    manifest_features = manifest.get("features") if isinstance(manifest.get("features"), dict) else {}
    manifest_feature = manifest_features.get(feature_name) if isinstance(manifest_features, dict) else {}
    if isinstance(manifest_feature, dict):
        contract.update(copy.deepcopy(manifest_feature))
    contract.setdefault("name", feature_name)
    contract.setdefault("routes", [])
    contract.setdefault("status", "missing")
    contract.setdefault("manifest_path", str(settings.resolved_tdl_manifest_path()))
    return contract


def _tdl_feature_is_ready(settings: Settings, feature_name: str) -> bool:
    contract = _tdl_feature_contract(settings, feature_name)
    ready_status = str(contract.get("status") or "").strip().lower()
    return (
        settings.tdl_integration_enabled
        and settings.resolved_tdl_package_path().is_file()
        and settings.resolved_tdl_manifest_path().is_file()
        and ready_status in {"ready", "implemented", "active"}
    )


def _tdl_feature_route_collection(contract: Dict[str, Any], route_path: str) -> Optional[str]:
    route_collections = contract.get("route_collections")
    if isinstance(route_collections, dict):
        value = route_collections.get(route_path)
        if isinstance(value, str) and value.strip():
            return value.strip()
    value = contract.get("collection_name")
    if isinstance(value, str) and value.strip():
        return value.strip()
    return "Sales A/C"


def _tdl_feature_unavailable_detail(
    settings: Settings,
    feature_name: str,
    route_path: str,
    *,
    action: Optional[str] = None,
) -> Dict[str, Any]:
    contract = _tdl_feature_contract(settings, feature_name)
    return {
        "supported": False,
        "resource": route_path,
        "fallback_applied": False,
        "error_type": "tdl_feature_unavailable",
        "required_tdl_feature": feature_name,
        "requested_action": action,
        "tdl": {
            "enabled": settings.tdl_integration_enabled,
            "package_path": str(settings.resolved_tdl_package_path()),
            "package_exists": settings.resolved_tdl_package_path().is_file(),
            "manifest_path": str(settings.resolved_tdl_manifest_path()),
            "manifest_exists": settings.resolved_tdl_manifest_path().is_file(),
            "feature_contract": contract,
        },
        "reason": (
            f"The connector route {route_path} is wired for the TDL feature '{feature_name}', but that feature is "
            "not marked ready in the local TDL manifest yet."
        ),
        "what_to_do": [
            "Implement the required TDL collections/reports in the local connector package.",
            "Update tdl/manifest.json and set the feature status to ready only after live verification succeeds.",
            "Enable TDL_INTEGRATION_ENABLED=true and restart the connector.",
        ],
    }


def _normalize_voucher_type_name(value: Optional[str]) -> Optional[str]:
    if not isinstance(value, str):
        return None
    normalized = re.sub(r"\s+", " ", value.strip()).lower()
    return normalized or None


def _voucher_matches_requested_type(voucher: Any, expected_type: Optional[str]) -> bool:
    expected = _normalize_voucher_type_name(expected_type)
    if not expected:
        return True
    if not isinstance(voucher, dict):
        return False

    candidates = [
        voucher.get("VOUCHERTYPENAME"),
        voucher.get("@VOUCHERTYPENAME"),
        voucher.get("VCHTYPE"),
        voucher.get("@VCHTYPE"),
        voucher.get("VOUCHERTYPE"),
        voucher.get("@VOUCHERTYPE"),
    ]
    for candidate in candidates:
        normalized = _normalize_voucher_type_name(candidate if isinstance(candidate, str) else None)
        if normalized == expected:
            return True
    return False


def _unwrap_single_voucher_payload(payload: Any) -> Dict[str, Any]:
    if isinstance(payload, list) and len(payload) == 1 and isinstance(payload[0], dict):
        return payload[0]
    if isinstance(payload, dict):
        voucher_value = payload.get("VOUCHER")
        if isinstance(voucher_value, list) and len(voucher_value) == 1 and isinstance(voucher_value[0], dict):
            return voucher_value[0]
        if isinstance(voucher_value, dict):
            return voucher_value
        return payload
    raise HTTPException(
        status_code=400,
        detail={
            "supported": True,
            "fallback_applied": False,
            "error_type": "validation",
            "reason": "Voucher create/alter expects one voucher object, or a single-item voucher array.",
        },
    )


def _filter_voucher_payload_by_type(payload: Any, voucher_type: Optional[str]) -> Any:
    expected = _normalize_voucher_type_name(voucher_type)
    if not expected:
        return payload
    if isinstance(payload, list):
        filtered_items = [
            _filter_voucher_payload_by_type(item, voucher_type)
            for item in payload
        ]
        return [item for item in filtered_items if item not in (None, {}, [], ())]
    if not isinstance(payload, dict):
        return payload

    updated = copy.deepcopy(payload)
    for key, value in list(updated.items()):
        if key == "VOUCHER":
            vouchers = _listify(value)
            filtered_vouchers = [
                copy.deepcopy(voucher)
                for voucher in vouchers
                if _voucher_matches_requested_type(voucher, voucher_type)
            ]
            updated[key] = filtered_vouchers
            continue
        if key == "TALLYMESSAGE":
            messages = _listify(value)
            filtered_messages: List[Any] = []
            for message in messages:
                if not isinstance(message, dict):
                    continue
                voucher = message.get("VOUCHER")
                if _voucher_matches_requested_type(voucher, voucher_type):
                    filtered_messages.append(copy.deepcopy(message))
            updated[key] = filtered_messages
            continue
        updated[key] = _filter_voucher_payload_by_type(value, voucher_type)

    return updated


def _shape_report_vouchers(
    report_data: Dict[str, Any],
    report_name: str,
    voucher_type: Optional[str] = None,
    fetch: Optional[List[str]] = None,
    limit: Optional[int] = None,
) -> Dict[str, Any]:
    source_voucher_type = _first_non_empty_string(voucher_type)
    messages = report_data.get("TALLYMESSAGE") if isinstance(report_data, dict) else None
    if isinstance(messages, dict):
        messages = [messages]
    if not isinstance(messages, list):
        empty_payload = {
            "source_report": report_name,
            "report_state": "empty",
            "VOUCHER": [],
        }
        if source_voucher_type:
            empty_payload["source_voucher_type"] = source_voucher_type
        return empty_payload

    vouchers: List[Dict[str, Any]] = []
    keep_fields = set(fetch or [])
    for message in messages:
        if not isinstance(message, dict):
            continue
        voucher = message.get("VOUCHER")
        if not isinstance(voucher, dict):
            continue
        if not _voucher_matches_requested_type(voucher, voucher_type):
            continue
        if keep_fields:
            shaped = {k: v for k, v in voucher.items() if k.startswith("@") or k in keep_fields}
        else:
            shaped = voucher
        vouchers.append(shaped)

    if limit is not None:
        vouchers = vouchers[: max(limit, 0)]

    response = {
        "source_report": report_name,
        "report_state": "records_found" if vouchers else "empty",
        "VOUCHER": vouchers,
    }
    if source_voucher_type:
        response["source_voucher_type"] = source_voucher_type
    return response

def _report_summary_fetch(fetch: Optional[List[str]], voucher_type: Optional[str]) -> Optional[List[str]]:
    if fetch:
        return fetch
    default_fetch = _default_voucher_fetch(voucher_type)
    compact_fetch = [field for field in default_fetch if "." not in field]
    return compact_fetch or default_fetch


async def get_client(settings: Settings = Depends(get_settings)) -> TallyClient:
    return TallyClient(
        settings.tally_base_url,
        agent_key=settings.agent_key,
        max_retries=settings.tally_request_max_retries,
        retry_backoff_sec=settings.tally_request_backoff_sec,
        trace_enabled=settings.tally_xml_trace_enabled,
        trace_dir=settings.resolved_tally_xml_trace_dir() if settings.tally_xml_trace_enabled else None,
    )


async def ensure_auth(x_agent_key: Optional[str], settings: Settings):
    claims = get_token_claims()
    if claims is not None:
        return
    token_error = get_token_error()
    if token_error:
        raise HTTPException(status_code=401, detail=f"unauthorized: {token_error}")
    if settings.auth_accept_static_key and settings.agent_key and x_agent_key == settings.agent_key:
        return
    if settings.agent_key:
        raise HTTPException(status_code=401, detail="unauthorized")


@router.post("/auth/token")
async def auth_token(
    payload: Dict[str, Any],
    settings: Settings = Depends(get_settings),
):
    client_id = (payload.get("username") or payload.get("client_id") or payload.get("clientId") or "").strip()
    client_secret = (payload.get("password") or payload.get("client_secret") or payload.get("clientSecret") or "").strip()
    audience = (payload.get("audience") or "tally-api").strip() or "tally-api"
    if client_id != settings.auth_client_id or client_secret != settings.auth_client_secret:
        raise HTTPException(status_code=401, detail="invalid_client")
    token_payload = issue_token(
        settings.auth_token_secret,
        subject=client_id,
        issuer="tally-connector",
        audience=audience,
        ttl_sec=settings.auth_token_ttl_sec,
        extra_claims={"scope": "connector-api"},
    )
    token_payload["message"] = "Login successful."
    token_payload["user"] = {
        "username": client_id,
        "displayName": client_id,
        "roles": ["Connector API"],
    }
    return token_payload


def resolve_company(
    default_company: Optional[str],
    payload: Optional[Dict[str, Any]] = None,
    company_query: Optional[str] = None,
    company_header: Optional[str] = None,
) -> Optional[str]:
    # Priority: explicit query param, header, payload field COMPANY/name, else default from settings.
    # If nothing is provided, return None so Tally uses the currently active/open company.
    for val in (company_query, company_header, (payload or {}).get("COMPANY"), (payload or {}).get("company"), default_company):
        if isinstance(val, str) and val.strip():
            return val.strip()
    return "Sales A/C"


def _ensure_company_dates(payload: Dict[str, Any]):
    # Tally requires FINANCIALYEARFROM and BOOKSFROM (and often STARTINGFROM) in YYYYMMDD format.
    if "FINANCIALYEARFROM" not in payload or "BOOKSFROM" not in payload:
        today = date.today()
        # Indian FY: starts 1 April; if before April, FY start is 1 April of prev year
        fy_year = today.year if today.month >= 4 else today.year - 1
        fy_start = date(fy_year, 4, 1)
        fmt = "%Y%m%d"
        payload.setdefault("FINANCIALYEARFROM", fy_start.strftime(fmt))
        payload.setdefault("BOOKSFROM", fy_start.strftime(fmt))
    if "STARTINGFROM" not in payload:
        payload["STARTINGFROM"] = payload.get("BOOKSFROM") or payload.get("FINANCIALYEARFROM")


def _ensure_company_defaults(payload: Dict[str, Any]):
    # Mailing name defaults to NAME
    if payload.get("NAME") and "MAILINGNAME.LIST" not in payload:
        payload["MAILINGNAME.LIST"] = {"MAILINGNAME": payload["NAME"]}
    # Address list default empty to satisfy structure
    payload.setdefault("ADDRESS.LIST", {"ADDRESS": []})
    payload.setdefault("COUNTRYNAME", "India")
    payload.setdefault("STATENAME", "Not Applicable")
    payload.setdefault("PINCODE", "")
    payload.setdefault("EMAIL", "")
    payload.setdefault("WEBSITE", "")
    payload.setdefault("PHONENUMBER", "")
    payload.setdefault("MOBILENUMBER", "")
    payload.setdefault("BASECURRENCYNAME", "₹")
    payload.setdefault("CURRSYMBOL", "₹")
    payload.setdefault("FORMALNAME", "INR")
    payload.setdefault("ISOCURRENCYCODE", "INR")
    payload.setdefault("CURRENCYNAME", "Rupees")
    payload.setdefault("CURRENCYSYMBOL", "₹")
    payload.setdefault("DECIMALSYMBOL", ".")
    payload.setdefault("THOUSANDSYMBOL", ",")


def _normalize_company_write_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = copy.deepcopy(payload or {})

    alias_pairs = [
        ("STATE", "STATENAME"),
        ("COUNTRY", "COUNTRYNAME"),
        ("MOBILENO", "MOBILENUMBER"),
    ]
    for source_key, target_key in alias_pairs:
        if source_key in normalized and target_key not in normalized:
            normalized[target_key] = normalized.pop(source_key)

    if "ADDRESS" in normalized and "ADDRESS.LIST" not in normalized:
        address_value = normalized.pop("ADDRESS")
        if isinstance(address_value, list):
            normalized["ADDRESS.LIST"] = {"ADDRESS": address_value}
        else:
            normalized["ADDRESS.LIST"] = {"ADDRESS": [address_value]}

    if "MAILINGNAME" in normalized and "MAILINGNAME.LIST" not in normalized:
        mailing_value = normalized.pop("MAILINGNAME")
        if isinstance(mailing_value, list):
            normalized["MAILINGNAME.LIST"] = {"MAILINGNAME": mailing_value}
        else:
            normalized["MAILINGNAME.LIST"] = {"MAILINGNAME": [mailing_value]}

    if "AUDITORADDRESS" in normalized and "AUDITORADDRESS.LIST" not in normalized:
        auditor_address_value = normalized.pop("AUDITORADDRESS")
        if isinstance(auditor_address_value, list):
            normalized["AUDITORADDRESS.LIST"] = {"AUDITORADDRESS": auditor_address_value}
        else:
            normalized["AUDITORADDRESS.LIST"] = {"AUDITORADDRESS": [auditor_address_value]}

    if "BASECURRENCYNAME" in normalized:
        base_currency_symbol = _first_non_empty_string(
            normalized.get("BASECURRENCYNAME"),
            normalized.get("CURRSYMBOL"),
            normalized.get("CURRENCYSYMBOL"),
        )
        if base_currency_symbol:
            normalized["BASECURRENCYNAME"] = base_currency_symbol
            normalized.setdefault("CURRSYMBOL", base_currency_symbol)
            normalized.setdefault("CURRENCYSYMBOL", base_currency_symbol)

    _ensure_company_dates(normalized)
    _ensure_company_defaults(normalized)
    return normalized


COMPANY_ALTER_IGNORED_FIELDS = {
}

COMPANY_ALTER_FEATURE_FIELDS = {
    "ISINTEGRATED",
    "MCROPTION",
    "ISGSTON",
    "USEFORVAT",
    "ISVATON",
    "USEFORPAYROLL",
    "USEFORINTEREST",
    "ISBILLWISEON",
    "ISCOSTCENTRESON",
    "USEFORFBT",
    "USEFORCOSTTRACKING",
    "USEFORJOBCOSTING",
    "ISINVENTORYON",
    "MAINTAINSTOCKCATEGORY",
    "USEFORCOMPOUND",
    "ISSALARYON",
}

COMPANY_ALTER_GST_FIELDS = {
    "GSTREGISTRATIONTYPE",
    "PARTYGSTIN",
    "STATENAME",
}

COMPANY_ALTER_CURRENCY_FIELDS = {
    "BASECURRENCYNAME",
    "CURRSYMBOL",
    "CURRENCYSYMBOL",
    "FORMALNAME",
    "ISOCURRENCYCODE",
    "CURRENCYNAME",
    "DECIMALSYMBOL",
    "THOUSANDSYMBOL",
}

COMPANY_ALTER_DATE_FIELDS = {
    "BOOKSFROM",
    "FINANCIALYEARFROM",
    "STARTINGFROM",
}

COMPANY_ALTER_MASTER_CHUNKS = [
    ["NAME", "RESERVEDNAME", "MAILINGNAME.LIST"],
    ["NAME", "ADDRESS.LIST", "PINCODE", "STATENAME", "COUNTRYNAME"],
    ["NAME", "PHONENUMBER", "MOBILENUMBER", "FAXNUMBER", "EMAIL", "WEBSITE"],
    ["NAME", "PANNUMBER", "INCOMETAXNUMBER", "TANUMBER"],
    ["NAME", "AUDITORNAME", "AUDITORADDRESS.LIST", "AUDITORPINCODE", "AUDITORPHONE", "AUDITOREMAIL"],
]


def _slice_payload(payload: Dict[str, Any], allowed_fields: List[str]) -> Dict[str, Any]:
    sliced: Dict[str, Any] = {}
    for field in allowed_fields:
        if field in payload:
            sliced[field] = copy.deepcopy(payload[field])
    return sliced


async def _apply_company_alter_chunks(
    client: TallyClient,
    company_name: str,
    payload: Dict[str, Any],
) -> Dict[str, Any]:
    normalized = _normalize_company_write_payload(payload)
    normalized.setdefault("NAME", company_name)

    ignored_fields = sorted(
        key for key in normalized.keys()
        if key in COMPANY_ALTER_IGNORED_FIELDS
    )

    remaining = {
        key: value
        for key, value in normalized.items()
        if key not in COMPANY_ALTER_IGNORED_FIELDS
    }

    results: List[Dict[str, Any]] = []

    for chunk_fields in COMPANY_ALTER_MASTER_CHUNKS:
        chunk_payload = _slice_payload(remaining, chunk_fields)
        if set(chunk_payload.keys()) <= {"NAME"}:
            continue
        results.append({
            "step": "company_master",
            "fields": sorted(k for k in chunk_payload.keys() if k != "NAME"),
            "result": await _update_company_settings(client, company_name, chunk_payload, "Alter"),
        })

    gst_payload = _slice_payload(remaining, ["NAME"] + sorted(COMPANY_ALTER_GST_FIELDS | COMPANY_ALTER_DATE_FIELDS))
    if set(gst_payload.keys()) - {"NAME"}:
        results.append({
            "step": "gst_registration",
            "fields": sorted(k for k in gst_payload.keys() if k != "NAME"),
            "result": await _update_company_settings(client, company_name, gst_payload, "Alter"),
        })

    features_payload = _slice_payload(remaining, ["NAME"] + sorted(COMPANY_ALTER_FEATURE_FIELDS | COMPANY_ALTER_DATE_FIELDS))
    if set(features_payload.keys()) - {"NAME"}:
        results.append({
            "step": "company_features",
            "fields": sorted(k for k in features_payload.keys() if k != "NAME"),
            "result": await _update_company_settings(client, company_name, features_payload, "Alter"),
        })

    currency_payload = _slice_payload(remaining, ["NAME"] + sorted(COMPANY_ALTER_CURRENCY_FIELDS))
    if set(currency_payload.keys()) - {"NAME"}:
        results.append({
            "step": "company_currency",
            "fields": sorted(k for k in currency_payload.keys() if k != "NAME"),
            "result": await _update_company_settings(client, company_name, currency_payload, "Alter"),
        })

    handled_fields = {"NAME"}
    for entry in results:
        handled_fields.update(entry["fields"])
    unhandled_fields = sorted(
        key for key in remaining.keys()
        if key not in handled_fields
    )

    return {
        "supported": True,
        "resource": "company alter",
        "applied_steps": results,
        "ignored_fields": ignored_fields,
        "unhandled_fields": unhandled_fields,
        "note": (
            "This Tally build is unstable for single-shot full company alter payloads. "
            "The connector applied the request in smaller safe chunks and skipped "
            "company bootstrap/currency/date fields that are not stable on alter."
        ),
    }


def _coerce_tally_yes_no(value: Any) -> Optional[str]:
    if isinstance(value, str):
        text = value.strip().lower()
        if text in {"yes", "y", "true", "1"}:
            return "Yes"
        if text in {"no", "n", "false", "0"}:
            return "No"
    if isinstance(value, bool):
        return "Yes" if value else "No"
    return "Sales A/C"


def _first_non_empty_string(*values: Any) -> Optional[str]:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _first_present_value(mapping: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in mapping and mapping.get(key) is not None:
            return mapping.get(key)
    return None


def _normalize_yes_no_alias(
    payload: Dict[str, Any],
    target_key: str,
    *source_keys: str,
) -> Optional[str]:
    value = _coerce_tally_yes_no(_first_present_value(payload, target_key, *source_keys))
    if value:
        payload[target_key] = value
    return value


def _is_name_only_block(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    keys = {key for key, nested in value.items() if nested is not None}
    return bool(keys) and keys.issubset({"NAME", "@NAME"})


def _normalize_location_block(value: Any) -> Any:
    normalized_items: List[Any] = []
    for item in _listify(value):
        if not isinstance(item, dict):
            continue

        entry = copy.deepcopy(item)
        location_name = _first_non_empty_string(
            entry.get("LOCATIONNAME"),
            entry.get("NAME"),
            entry.get("@NAME"),
            entry.get("LOCATION"),
            entry.get("PRINTLOCATION"),
            entry.get("PAYABLELOCATION"),
            entry.get("BANKLOCATION"),
        )
        if location_name:
            entry["LOCATIONNAME"] = location_name
        for alias_key in [
            "NAME",
            "@NAME",
            "LOCATION",
            "PRINTLOCATION",
            "PAYABLELOCATION",
            "BANKLOCATION",
        ]:
            if alias_key != "LOCATIONNAME":
                entry.pop(alias_key, None)

        if _is_name_only_block(item) and not location_name:
            continue
        if not entry:
            continue

        normalized_items.append(entry)

    if not normalized_items:
        return None
    return normalized_items if len(normalized_items) > 1 else normalized_items[0]


def _normalize_cheque_ranges(value: Any) -> Any:
    normalized_items: List[Any] = []
    for item in _listify(value):
        if not isinstance(item, dict):
            normalized_items.append(item)
            continue

        entry = copy.deepcopy(item)
        from_number = _first_non_empty_string(
            entry.get("FROMNUMBER"),
            entry.get("FROM_NUMBER"),
            entry.get("FROM"),
            entry.get("STARTNUMBER"),
            entry.get("START"),
            entry.get("CHEQUEFROM"),
        )
        to_number = _first_non_empty_string(
            entry.get("TONUMBER"),
            entry.get("TO_NUMBER"),
            entry.get("TO"),
            entry.get("ENDNUMBER"),
            entry.get("END"),
            entry.get("CHEQUETO"),
        )
        cheque_count = _first_non_empty_string(
            entry.get("NUMBEROFCHEQUES"),
            entry.get("NUMBER_OF_CHEQUES"),
            entry.get("COUNT"),
            entry.get("CHEQUECOUNT"),
        )
        cheque_book_name = _first_non_empty_string(
            entry.get("CHEQUEBOOKNAME"),
            entry.get("CHEQUEBOOK"),
            entry.get("CHEQUE_BOOK_NAME"),
            entry.get("NAME"),
        )

        if from_number:
            entry["FROMNUMBER"] = from_number
        if to_number:
            entry["TONUMBER"] = to_number
        if cheque_book_name:
            entry["CHEQUEBOOKNAME"] = cheque_book_name

        try:
            from_int = int(from_number) if from_number else None
            to_int = int(to_number) if to_number else None
            count_int = int(cheque_count) if cheque_count else None
        except ValueError:
            from_int = to_int = count_int = None

        if count_int is None and from_int is not None and to_int is not None and to_int >= from_int:
            count_int = (to_int - from_int) + 1
        if to_int is None and from_int is not None and count_int is not None and count_int > 0:
            to_int = from_int + count_int - 1

        if count_int is not None:
            entry["NUMBEROFCHEQUES"] = str(count_int)
        if to_int is not None and "TONUMBER" not in entry:
            entry["TONUMBER"] = str(to_int)

        normalized_items.append(entry)

    if not normalized_items:
        return None
    return normalized_items if len(normalized_items) > 1 else normalized_items[0]


def _normalize_config_entries(
    value: Any,
    field_aliases: Dict[str, List[str]],
    yes_no_fields: Optional[List[str]] = None,
) -> Any:
    normalized_items: List[Any] = []
    yes_no_targets = set(yes_no_fields or [])

    for item in _listify(value):
        if not isinstance(item, dict):
            continue

        entry = copy.deepcopy(item)
        for target_key, aliases in field_aliases.items():
            present_value = _first_present_value(entry, target_key, *aliases)
            if present_value is None:
                continue

            if target_key in yes_no_targets:
                normalized_value = _coerce_tally_yes_no(present_value)
                if normalized_value:
                    entry[target_key] = normalized_value
            elif isinstance(present_value, str):
                if present_value.strip():
                    entry[target_key] = present_value.strip()
            else:
                entry[target_key] = copy.deepcopy(present_value)

        for target_key, aliases in field_aliases.items():
            for alias in aliases:
                if alias != target_key:
                    entry.pop(alias, None)

        if entry:
            normalized_items.append(entry)

    if not normalized_items:
        return None
    return normalized_items if len(normalized_items) > 1 else normalized_items[0]


def _normalize_ledger_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    Normalize user-friendly banking inputs into the Tally ledger methods that
    actually persist through the generic XML master import path.

    Verified working fields in this build:
    - BANKDETAILS = Yes
    - ISEBANKINGENABLED = Yes
    - BANKACCHOLDERNAME
    - BANKINGCONFIGBANK
    - IFSCODE
    - BANKCONFIGIFSC

    The user may send friendlier shapes like PROVIDEBANKDETAILS or
    BANKDETAILS.LIST; we flatten those into the verified top-level methods.
    """
    normalized = copy.deepcopy(payload)

    provide_bank_details = _coerce_tally_yes_no(
        normalized.get("PROVIDEBANKDETAILS", normalized.get("BANKDETAILS"))
    )

    bank_details_value = normalized.get("BANKDETAILS.LIST")
    primary_bank_detail: Optional[Dict[str, Any]] = None
    for candidate in _listify(bank_details_value):
        if isinstance(candidate, dict):
            primary_bank_detail = candidate
            break

    if provide_bank_details == "Yes" or primary_bank_detail:
        normalized["BANKDETAILS"] = "Yes"
        normalized.setdefault("ISEBANKINGENABLED", "Yes")

        account_holder_name = _first_non_empty_string(
            normalized.get("BANKACCHOLDERNAME"),
            normalized.get("ACCOUNTNAME"),
            normalized.get("ACCOUNTHOLDERNAME"),
            normalized.get("MAILINGNAME"),
            normalized.get("NAME"),
            (primary_bank_detail or {}).get("ACCOUNTNAME"),
            (primary_bank_detail or {}).get("ACCOUNTHOLDERNAME"),
            (primary_bank_detail or {}).get("ACCOUNT HOLDER NAME"),
        )
        if account_holder_name:
            normalized["BANKACCHOLDERNAME"] = account_holder_name

        bank_name = _first_non_empty_string(
            normalized.get("BANKINGCONFIGBANK"),
            normalized.get("BANKNAME"),
            (primary_bank_detail or {}).get("BANKNAME"),
            (primary_bank_detail or {}).get("BANK NAME"),
        )
        if bank_name:
            normalized["BANKINGCONFIGBANK"] = bank_name

        ifsc_code = _first_non_empty_string(
            normalized.get("IFSCODE"),
            normalized.get("BANKCONFIGIFSC"),
            (primary_bank_detail or {}).get("IFSCODE"),
            (primary_bank_detail or {}).get("IFS CODE"),
        )
        if ifsc_code:
            normalized["IFSCODE"] = ifsc_code
            normalized.setdefault("BANKCONFIGIFSC", ifsc_code)

        existing_payment_details = normalized.get("PAYMENTDETAILS.LIST")
        payment_details = (
            copy.deepcopy(existing_payment_details)
            if isinstance(existing_payment_details, dict)
            else {}
        )

        account_number = _first_non_empty_string(
            normalized.get("ACCOUNTNUMBER"),
            normalized.get("BANKACCOUNTNUMBER"),
            normalized.get("BANKACCNUMBER"),
            normalized.get("ACCOUNTNO"),
            normalized.get("ACNO"),
            normalized.get("ACCOUNTNUM"),
            (primary_bank_detail or {}).get("ACCOUNTNUMBER"),
            (primary_bank_detail or {}).get("BANKACCOUNTNUMBER"),
            (primary_bank_detail or {}).get("BANKACCNUMBER"),
            (primary_bank_detail or {}).get("ACCOUNTNO"),
            (primary_bank_detail or {}).get("ACNO"),
            (primary_bank_detail or {}).get("ACCOUNTNUM"),
            (primary_bank_detail or {}).get("A/C NO"),
        )
        if account_number:
            payment_details["ACCOUNTNUMBER"] = account_number

        for target_key, *source_keys in [
            ("BRANCHNAME", "BRANCHNAME", "BRANCH"),
            ("MICRCODE", "MICRCODE"),
            ("SWIFTCODE", "SWIFTCODE"),
            ("ACCOUNTTYPE", "ACCOUNTTYPE", "ACCOUNTTYPE"),
            ("DELIVERYMODE", "DELIVERYMODE"),
            ("DELIVERYTO", "DELIVERYTO"),
            ("BANKLOCATION", "BANKLOCATION"),
            ("CITY", "CITY"),
            ("PRINTLOCATION", "PRINTLOCATION"),
            ("PAYABLELOCATION", "PAYABLELOCATION"),
            ("CHEQUECROSSCOMMENT", "CHEQUECROSSCOMMENT"),
            ("TRANSFERMODE", "TRANSFERMODE"),
            ("TRANSACTIONID", "TRANSACTIONID"),
            ("LOCALBANKCHANGES", "LOCALBANKCHANGES"),
            ("BENEFICIARYBANKCHANGES", "BENEFICIARYBANKCHANGES"),
            ("IMBCODE", "IMBCODE"),
            ("BANKID", "BANKID"),
        ]:
            source_value = _first_non_empty_string(
                normalized.get(source_keys[0]),
                *[(primary_bank_detail or {}).get(key) for key in source_keys],
            )
            if source_value:
                payment_details[target_key] = source_value

        payment_favouring = _first_non_empty_string(
            normalized.get("PAYMENTFAVOURING"),
            normalized.get("PAYMENTFAVORING"),
            normalized.get("BENEFICIARYNAME"),
            (primary_bank_detail or {}).get("PAYMENTFAVOURING"),
            (primary_bank_detail or {}).get("PAYMENTFAVORING"),
            (primary_bank_detail or {}).get("BENEFICIARYNAME"),
            account_holder_name,
            normalized.get("MAILINGNAME"),
            normalized.get("NAME"),
        )
        if payment_favouring:
            payment_details["PAYMENTFAVOURING"] = payment_favouring

        transaction_name = _first_non_empty_string(
            normalized.get("TRANSACTIONNAME"),
            normalized.get("NICKNAME"),
            (primary_bank_detail or {}).get("TRANSACTIONNAME"),
            (primary_bank_detail or {}).get("NICKNAME"),
            "Primary",
        )
        if transaction_name:
            payment_details["TRANSACTIONNAME"] = transaction_name

        default_transaction_type = _first_non_empty_string(
            normalized.get("DEFAULTTRANSACTIONTYPE"),
            normalized.get("TRANSACTIONTYPE"),
            (primary_bank_detail or {}).get("DEFAULTTRANSACTIONTYPE"),
            (primary_bank_detail or {}).get("TRANSACTIONTYPE"),
        )
        if default_transaction_type:
            payment_details["DEFAULTTRANSACTIONTYPE"] = default_transaction_type

        set_as_default = _coerce_tally_yes_no(
            normalized.get("SETASDEFAULT", (primary_bank_detail or {}).get("SETASDEFAULT"))
        )
        if set_as_default:
            payment_details["SETASDEFAULT"] = set_as_default

        if ifsc_code:
            payment_details.setdefault("IFSCODE", ifsc_code)
        if bank_name:
            payment_details.setdefault("BANKNAME", bank_name)

        beneficiary_code_details = (primary_bank_detail or {}).get("BENEFICIARYCODEDETAILS.LIST")
        if beneficiary_code_details is not None:
            payment_details["BENEFICIARYCODEDETAILS.LIST"] = beneficiary_code_details

        if payment_details:
            normalized["PAYMENTDETAILS.LIST"] = payment_details

        # Keep user-supplied nested payload for transparency, but normalize the
        # verified persistence flags/methods above so Tally stores banking as Yes.
        if provide_bank_details is None:
            normalized["PROVIDEBANKDETAILS"] = "Yes"

    epayment_config_container = _first_present_value(
        normalized,
        "EPAYMENTSCONFIGURATION",
        "EPAYMENTCONFIGURATION",
        "EPAYMENTSCONFIG",
    )
    auto_brs_config_container = _first_present_value(
        normalized,
        "AUTORECONCILIATIONCONFIGURATION",
        "AUTORECONCILIATIONCONFIG",
        "AUTORECONCILIATIONSETTINGS",
    )
    service_tax_config_container = _first_present_value(
        normalized,
        "SERVICETAXCONFIGURATION",
    )
    epayment_config_container = epayment_config_container if isinstance(epayment_config_container, dict) else {}
    auto_brs_config_container = auto_brs_config_container if isinstance(auto_brs_config_container, dict) else {}
    service_tax_config_container = service_tax_config_container if isinstance(service_tax_config_container, dict) else {}

    cheque_printing_enabled = _normalize_yes_no_alias(
        normalized,
        "ISCHEQUEPRINTINGENABLED",
        "ENABLECHEQUEPRINTING",
        "CHEQUEPRINTINGENABLED",
        "ENABLE_CHEQUE_PRINTING",
    )
    auto_reconciliation_enabled = _normalize_yes_no_alias(
        normalized,
        "ISBANKSTATUSAPP",
        "ENABLEAUTORECONCILIATION",
        "AUTORECONCILIATIONENABLED",
        "ENABLE_AUTO_RECONCILIATION",
    )
    epayments_enabled = _normalize_yes_no_alias(
        normalized,
        "ISPAYUPLOAD",
        "ENABLEEPAYMENTS",
        "EPAYMENTSENABLED",
        "ENABLE_EPAYMENTS",
        "SETALTEREPAYMENTSCONFIGURATION",
        "SETALTEREPAYMENTSCONFIG",
    )
    service_tax_enabled = _normalize_yes_no_alias(
        normalized,
        "FORSERVICETAX",
        "SETALTERSERVICETAXDETAILS",
        "ENABLESERVICETAX",
        "ENABLE_SERVICE_TAX",
    )
    _normalize_yes_no_alias(
        normalized,
        "ISEXPORTONVCHCREATE",
        "EXPORTUPLOADPAYMENTINSTRUCTIONSONVOUCHERCREATION",
        "EXPORTONVOUCHERCREATION",
        "EXPORTUPLOADONVOUCHERCREATION",
    )
    if "ISEXPORTONVCHCREATE" not in normalized:
        export_on_create = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "ISEXPORTONVCHCREATE",
                "EXPORTUPLOADPAYMENTINSTRUCTIONSONVOUCHERCREATION",
                "EXPORTONVOUCHERCREATION",
                "EXPORTUPLOADONVOUCHERCREATION",
            )
        )
        if export_on_create:
            normalized["ISEXPORTONVCHCREATE"] = export_on_create
    _normalize_yes_no_alias(
        normalized,
        "ALLOWEXPORTWITHERRORS",
        "ALLOWEXPORTOFTRANSACTIONSWITHMISMATCHINBANKDETAILS",
        "ALLOWEXPORTWITHMISMATCHINBANKDETAILS",
        "ALLOWEXPORTWITHMISMATCHBANKDETAILS",
    )
    if "ALLOWEXPORTWITHERRORS" not in normalized:
        allow_export_with_errors = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "ALLOWEXPORTWITHERRORS",
                "ALLOWEXPORTOFTRANSACTIONSWITHMISMATCHINBANKDETAILS",
                "ALLOWEXPORTWITHMISMATCHINBANKDETAILS",
                "ALLOWEXPORTWITHMISMATCHBANKDETAILS",
            )
        )
        if allow_export_with_errors:
            normalized["ALLOWEXPORTWITHERRORS"] = allow_export_with_errors
    _normalize_yes_no_alias(
        normalized,
        "ISBENEFICIARYCODEON",
        "ENABLEBENEFICIARYCODE",
        "BENEFICIARYCODEON",
    )
    if "ISBENEFICIARYCODEON" not in normalized:
        beneficiary_code_enabled = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "ISBENEFICIARYCODEON",
                "ENABLEBENEFICIARYCODE",
                "BENEFICIARYCODEON",
            )
        )
        if beneficiary_code_enabled:
            normalized["ISBENEFICIARYCODEON"] = beneficiary_code_enabled
    _normalize_yes_no_alias(
        normalized,
        "PAYINSISBATCHAPPLICABLE",
        "BULKPAYMENTFACILITY",
        "ISBATCHAPPLICABLE",
        "PAYMENTBATCHAPPLICABLE",
    )
    if "PAYINSISBATCHAPPLICABLE" not in normalized:
        batch_applicable = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "PAYINSISBATCHAPPLICABLE",
                "BULKPAYMENTFACILITY",
                "ISBATCHAPPLICABLE",
                "PAYMENTBATCHAPPLICABLE",
            )
        )
        if batch_applicable:
            normalized["PAYINSISBATCHAPPLICABLE"] = batch_applicable
    _normalize_yes_no_alias(
        normalized,
        "PAYINSISFILENUMAPP",
        "FILE_NUMBER_APPLICABLE",
        "FILENUMBERAPPLICABLE",
        "PAYMENTFILENUMBERAPPLICABLE",
    )
    if "PAYINSISFILENUMAPP" not in normalized:
        file_number_applicable = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "PAYINSISFILENUMAPP",
                "FILE_NUMBER_APPLICABLE",
                "FILENUMBERAPPLICABLE",
                "PAYMENTFILENUMBERAPPLICABLE",
            )
        )
        if file_number_applicable:
            normalized["PAYINSISFILENUMAPP"] = file_number_applicable
    _normalize_yes_no_alias(
        normalized,
        "ISPRODUCTCODEBASED",
        "PRODUCTCODEBASED",
        "ENABLEPRODUCTCODE",
    )
    if "ISPRODUCTCODEBASED" not in normalized:
        product_code_based = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "ISPRODUCTCODEBASED",
                "PRODUCTCODEBASED",
                "ENABLEPRODUCTCODE",
            )
        )
        if product_code_based:
            normalized["ISPRODUCTCODEBASED"] = product_code_based
    _normalize_yes_no_alias(
        normalized,
        "HASCLIENTCODE",
        "CLIENTCODEENABLED",
        "ENABLECLIENTCODE",
    )
    if "HASCLIENTCODE" not in normalized:
        client_code_enabled = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "HASCLIENTCODE",
                "CLIENTCODEENABLED",
                "ENABLECLIENTCODE",
            )
        )
        if client_code_enabled:
            normalized["HASCLIENTCODE"] = client_code_enabled
    _normalize_yes_no_alias(
        normalized,
        "ISFILENAMEFORMATSUPPORTED",
        "FILENAMEFORMATSUPPORTED",
        "ENABLEFILENAMEFORMAT",
    )
    if "ISFILENAMEFORMATSUPPORTED" not in normalized:
        filename_format_supported = _coerce_tally_yes_no(
            _first_present_value(
                epayment_config_container,
                "ISFILENAMEFORMATSUPPORTED",
                "FILENAMEFORMATSUPPORTED",
                "ENABLEFILENAMEFORMAT",
            )
        )
        if filename_format_supported:
            normalized["ISFILENAMEFORMATSUPPORTED"] = filename_format_supported
    _normalize_yes_no_alias(
        normalized,
        "USEASNOTIONALBANK",
        "ASNOTIONALBANK",
        "ISNOTIONALBANK",
    )

    reconciliation_starting_from = _first_non_empty_string(
        normalized.get("STARTINGFROM"),
        normalized.get("RECONCILIATIONBEGINNINGDATE"),
        normalized.get("RECONCILIATION_START_DATE"),
        normalized.get("BANKRECONCILIATIONFROM"),
        auto_brs_config_container.get("STARTINGFROM"),
        auto_brs_config_container.get("RECONCILIATIONBEGINNINGDATE"),
        auto_brs_config_container.get("RECONCILIATION_START_DATE"),
        auto_brs_config_container.get("BANKRECONCILIATIONFROM"),
    )
    if reconciliation_starting_from:
        normalized["STARTINGFROM"] = _normalize_tally_date(reconciliation_starting_from)

    bank_statement_path_aliases = [
        ("BANKNEWSTATEMENTS", ("BANKNEWSTATEMENTS", "LOCATIONOFNEWBANKSTATEMENTS", "NEWBANKSTATEMENTSPATH")),
        ("BANKIMPORTEDSTATEMENTS", ("BANKIMPORTEDSTATEMENTS", "LOCATIONOFIMPORTEDBANKSTATEMENTS", "IMPORTEDBANKSTATEMENTSPATH")),
        ("NEWIMFLOCATION", ("NEWIMFLOCATION", "LOCATIONOFNEWINTERMEDIATEFILES", "NEWINTERMEDIATEFILELOCATION")),
        ("IMPORTEDIMFLOCATION", ("IMPORTEDIMFLOCATION", "LOCATIONOFIMPORTEDINTERMEDIATEFILES", "IMPORTEDINTERMEDIATEFILELOCATION")),
    ]
    for target_key, source_keys in bank_statement_path_aliases:
        path_value = _first_non_empty_string(
            *[_first_present_value(normalized, key) for key in source_keys],
            *[_first_present_value(auto_brs_config_container, key) for key in source_keys],
            *[_first_present_value(epayment_config_container, key) for key in source_keys],
        )
        if path_value:
            normalized[target_key] = path_value

    if normalized.get("BANKNEWSTATEMENTS") or normalized.get("BANKIMPORTEDSTATEMENTS"):
        normalized.setdefault("ISBANKSTATUSAPP", "Yes")
        normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")
    if normalized.get("NEWIMFLOCATION") or normalized.get("IMPORTEDIMFLOCATION"):
        normalized.setdefault("ISPAYUPLOAD", "Yes")
        normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")

    bank_scalar_aliases = [
        ("BANKBSRCODE", ("BANKBSRCODE", "BSRCODE", "BRSCODE")),
        ("CLIENTCODE", ("CLIENTCODE", "BANKCLIENTCODE")),
        ("SWIFTCODE", ("SWIFTCODE", "SWIFT", "SWIFTCODE")),
    ]
    for target_key, source_keys in bank_scalar_aliases:
        scalar_value = _first_non_empty_string(
            *[_first_present_value(normalized, key) for key in source_keys],
            *[_first_present_value(epayment_config_container, key) for key in source_keys],
        )
        if scalar_value:
            normalized[target_key] = scalar_value

    if normalized.get("ISEXPORTONVCHCREATE") == "Yes" or normalized.get("ALLOWEXPORTWITHERRORS") == "Yes":
        normalized.setdefault("ISPAYUPLOAD", "Yes")
        normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")

    cheque_ranges = _first_present_value(
        normalized,
        "CHEQUERANGE.LIST",
        "CHEQUEBOOKS.LIST",
        "CHEQUEBOOKRANGE.LIST",
        "CHEQUEBOOKRANGES.LIST",
    )
    normalized_cheque_ranges = _normalize_cheque_ranges(cheque_ranges)
    if normalized_cheque_ranges is not None:
        normalized["CHEQUERANGE.LIST"] = normalized_cheque_ranges
        if cheque_printing_enabled is None:
            normalized["ISCHEQUEPRINTINGENABLED"] = "Yes"

    default_cheque_details = _first_present_value(
        normalized,
        "DEFAULTCHEQUEDETAILS.LIST",
        "CHEQUEPRINTINGCONFIGURATION.LIST",
        "CHEQUEPRINTINGCONFIGURATION",
        "CHEQUECONFIGURATION.LIST",
        "CHEQUECONFIGURATION",
    )
    if default_cheque_details is not None:
        normalized_cheque_details = _normalize_config_entries(
            default_cheque_details,
            {
                "DELIVERYMODE": ["MODEOFDELIVERY", "CHEQUEDELIVERYMODE"],
                "DELIVERYTO": ["DELIVERTO", "CHEQUEDELIVERYTO"],
                "PRINTLOCATION": ["PRINTAT", "CHEQUEPRINTLOCATION"],
                "PAYABLELOCATION": ["PAYABLEAT", "CHEQUEPAYABLELOCATION"],
                "BANKLOCATION": ["BANKBRANCHLOCATION", "CHEQUEBANKLOCATION"],
                "CITY": ["PRINTCITY", "CHEQUECITY"],
                "CHEQUECROSSCOMMENT": ["CROSSCOMMENT", "CHEQUECROSSINGCOMMENT"],
            },
        )
        if normalized_cheque_details is not None:
            normalized["DEFAULTCHEQUEDETAILS.LIST"] = normalized_cheque_details
        if cheque_printing_enabled is None:
            normalized["ISCHEQUEPRINTINGENABLED"] = "Yes"

        if isinstance(normalized.get("DEFAULTCHEQUEDETAILS.LIST"), dict):
            cheque_config = normalized["DEFAULTCHEQUEDETAILS.LIST"]
            for field_name, flag_name in [
                ("DELIVERYMODE", "HASECHEQUEDELIVERYMODE"),
                ("DELIVERYTO", "HASECHEQUEDELIVERYTO"),
                ("PRINTLOCATION", "HASECHEQUEPRINTLOCATION"),
                ("PAYABLELOCATION", "HASECHEQUEPAYABLELOCATION"),
                ("BANKLOCATION", "HASECHEQUEBANKLOCATION"),
                ("CITY", "HASECHEQUECITY"),
            ]:
                if _first_non_empty_string(cheque_config.get(field_name)):
                    normalized[flag_name] = "Yes"

    opening_cheque_details = _first_present_value(
        normalized,
        "DEFAULTOPENINGCHEQUEDETAILS.LIST",
        "OPENINGCHEQUEDETAILS.LIST",
        "OPENINGCHEQUECONFIGURATION.LIST",
    )
    if opening_cheque_details is not None:
        normalized_opening_cheque_details = _normalize_config_entries(
            opening_cheque_details,
            {
                "DATE": ["BANKDATE", "CHEQUEDATE", "ENTRYDATE"],
                "PARTICULARS": ["LEDGERNAME", "PARTYLEDGERNAME"],
                "NATUREOFTRANSACTION": ["TRANSACTIONNATURE", "NATURE"],
                "TRANSACTIONTYPE": ["MODE", "BANKTRANSACTIONTYPE"],
                "REMARKS": ["REMARK", "NARRATION"],
                "AMOUNT": ["DEPOSIT", "WITHDRAWAL", "DEPOSITWITHDRAWAL"],
                "INSTRUMENTNUMBER": ["INSTRUMENTNO", "CHEQUENO", "CHEQUENUMBER"],
                "INSTRUMENTDATE": ["CHEQUEINSTRUMENTDATE", "BANKINSTRUMENTDATE"],
            },
        )
        if normalized_opening_cheque_details is not None:
            normalized["DEFAULTOPENINGCHEQUEDETAILS.LIST"] = normalized_opening_cheque_details
        if cheque_printing_enabled is None:
            normalized["ISCHEQUEPRINTINGENABLED"] = "Yes"

    voucher_cheque_details = _first_present_value(
        normalized,
        "DEFAULTVCHCHEQUEDETAILS.LIST",
        "VOUCHERCHEQUEDETAILS.LIST",
        "DEFAULTVOUCHERCHEQUEDETAILS.LIST",
    )
    if voucher_cheque_details is not None:
        normalized_voucher_cheque_details = _normalize_config_entries(
            voucher_cheque_details,
            {
                "DELIVERYMODE": ["MODEOFDELIVERY", "CHEQUEDELIVERYMODE"],
                "DELIVERYTO": ["DELIVERTO", "CHEQUEDELIVERYTO"],
                "PRINTLOCATION": ["PRINTAT", "CHEQUEPRINTLOCATION"],
                "PAYABLELOCATION": ["PAYABLEAT", "CHEQUEPAYABLELOCATION"],
                "BANKLOCATION": ["BANKBRANCHLOCATION", "CHEQUEBANKLOCATION"],
                "CITY": ["PRINTCITY", "CHEQUECITY"],
                "CHEQUECROSSCOMMENT": ["CROSSCOMMENT", "CHEQUECROSSINGCOMMENT"],
            },
        )
        if normalized_voucher_cheque_details is not None:
            normalized["DEFAULTVCHCHEQUEDETAILS.LIST"] = normalized_voucher_cheque_details
        if cheque_printing_enabled is None:
            normalized["ISCHEQUEPRINTINGENABLED"] = "Yes"

    for target_key, source_keys in [
        ("ECHEQUEPRINTLOCATION.LIST", ("ECHEQUEPRINTLOCATION.LIST", "ECHEQUEPRINTLOCATIONS.LIST")),
        ("ECHEQUEPAYABLELOCATION.LIST", ("ECHEQUEPAYABLELOCATION.LIST", "ECHEQUEPAYABLELOCATIONS.LIST")),
        ("EDDPRINTLOCATION.LIST", ("EDDPRINTLOCATION.LIST", "EDDPRINTLOCATIONS.LIST")),
        ("EDDPAYABLELOCATION.LIST", ("EDDPAYABLELOCATION.LIST", "EDDPAYABLELOCATIONS.LIST")),
    ]:
        location_value = _first_present_value(normalized, *source_keys)
        normalized_locations = _normalize_location_block(location_value)
        if normalized_locations is not None:
            normalized[target_key] = normalized_locations
            normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")

    if "ECHEQUEPRINTLOCATION.LIST" in normalized or "ECHEQUEPAYABLELOCATION.LIST" in normalized:
        normalized.setdefault("ISECHEQUESUPPORTED", "Yes")
    if "EDDPRINTLOCATION.LIST" in normalized or "EDDPAYABLELOCATION.LIST" in normalized:
        normalized.setdefault("ISEDDSUPPORTED", "Yes")

    epayment_scalar_aliases = [
        ("PYMTINSTOUTPUTNAME", ("PYMTINSTOUTPUTNAME", "PAYMENTINSTRUCTIONOUTPUTNAME", "PAYMENTOUTPUTNAME", "OUTPUTFILENAME")),
        ("PAYMENTGATEWAY", ("PAYMENTGATEWAY", "GATEWAYNAME")),
        ("BANKCONFIGSHORTCODE", ("BANKCONFIGSHORTCODE", "SHORTCODE", "BANKSHORTCODE")),
        ("BANKMICR", ("BANKMICR", "MICRCODE", "BANKMICRNUMBER")),
        ("BANKCONFIGMICR", ("BANKCONFIGMICR", "BANKMICR", "MICRCODE", "BANKMICRNUMBER")),
        ("BANKNEWSTATEMENTS", ("BANKNEWSTATEMENTS", "NEWSTATEMENTSPATH", "BANKNEWSTATEMENTSPATH")),
        ("BANKIMPORTEDSTATEMENTS", ("BANKIMPORTEDSTATEMENTS", "IMPORTEDSTATEMENTS_PATH", "BANKIMPORTEDSTATEMENTSPATH")),
        ("PAYINSBATCHNAME", ("PAYINSBATCHNAME", "PAYMENTBATCHNAME")),
        ("LASTUSEDBATCHNAME", ("LASTUSEDBATCHNAME", "LASTPAYMENTBATCHNAME")),
        ("PAYINSFILENUMPERIOD", ("PAYINSFILENUMPERIOD", "PAYMENTFILENUMBERPERIOD")),
        ("PRODUCTCODETYPE", ("PRODUCTCODETYPE", "PAYMENTPRODUCTCODETYPE")),
        ("SALARYPYMTPRODUCTCODE", ("SALARYPYMTPRODUCTCODE", "SALARYPRODUCTCODE")),
        ("OTHERPYMTPRODUCTCODE", ("OTHERPYMTPRODUCTCODE", "OTHERPRODUCTCODE")),
        ("PAYMENTINSTLOCATION", ("PAYMENTINSTLOCATION", "PAYMENTINSTRUCTIONLOCATION")),
        ("ENCRPTIONLOCATION", ("ENCRPTIONLOCATION", "ENCRYPTIONLOCATION")),
        ("NEWIMFLOCATION", ("NEWIMFLOCATION", "NEWIMPORTFILELOCATION")),
        ("IMPORTEDIMFLOCATION", ("IMPORTEDIMFLOCATION", "IMPORTEDIMPORTFILELOCATION")),
        ("CORPORATEUSERNOECS", ("CORPORATEUSERNOECS", "CORPORATEUSERNUMBERECS")),
        ("CORPORATEUSERNOACH", ("CORPORATEUSERNOACH", "CORPORATEUSERNUMBERACH")),
        ("CORPORATEUSERNAME", ("CORPORATEUSERNAME", "CORPORATEUSER")),
        ("IMFNAME", ("IMFNAME", "IMPORTFORMATNAME")),
    ]
    epayment_scalar_present = False
    for target_key, source_keys in epayment_scalar_aliases:
        source_value = _first_non_empty_string(
            *[_first_present_value(normalized, key) for key in source_keys],
            *[_first_present_value(epayment_config_container, key) for key in source_keys],
        )
        if source_value:
            normalized[target_key] = source_value
            epayment_scalar_present = True

    epayment_config = _first_present_value(
        normalized,
        "LEDPAYINSCONFIGS.LIST",
        "EPAYMENTSCONFIGURATION.LIST",
        "EPAYMENTSCONFIGURATION",
        "EPAYMENTCONFIGURATION.LIST",
        "EPAYMENTCONFIGURATION",
        "EPAYMENTSCONFIG.LIST",
        "EPAYMENTSCONFIG",
    )
    if epayment_config is not None:
        normalized_epayment_config = _normalize_config_entries(
            epayment_config,
            {
                "FROMAMOUNT": ["FROM", "MINAMOUNT", "AMOUNTFROM"],
                "TOAMOUNT": ["TO", "MAXAMOUNT", "AMOUNTTO"],
                "TRANSFERMODE": ["MODE", "TRANSACTIONTYPE"],
                "PREFERREDMODE": ["DEFAULTMODE", "PREFERREDTRANSFERMODE"],
                "SETASDEFAULT": ["DEFAULT", "ISDEFAULT"],
            },
            yes_no_fields=["SETASDEFAULT"],
        )
        if normalized_epayment_config is not None:
            normalized["LEDPAYINSCONFIGS.LIST"] = normalized_epayment_config
            epayment_scalar_present = True

    if epayments_enabled == "Yes" or epayment_scalar_present:
        normalized.setdefault("ISPAYUPLOAD", "Yes")
        normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")

    if auto_reconciliation_enabled == "Yes":
        normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")
    auto_brs_config = _first_present_value(
        normalized,
        "AUTOBRSCONFIGS.LIST",
        "AUTORECONCILIATIONCONFIGURATION.LIST",
        "AUTORECONCILIATIONCONFIGURATION",
        "AUTORECONCILIATIONCONFIG.LIST",
        "AUTORECONCILIATIONCONFIG",
        "AUTORECONCILIATIONSETTINGS.LIST",
        "AUTORECONCILIATIONSETTINGS",
    )
    if auto_brs_config is not None:
        normalized_auto_brs_config = _normalize_config_entries(
            auto_brs_config,
            {
                "DEPOSITS": ["DEPOSITVOUCHERTYPE", "DEPOSITVOUCHER"],
                "WITHDRAWALS": ["WITHDRAWALVOUCHERTYPE", "WITHDRAWALVOUCHER"],
                "VOUCHERNUMBERINGSERIES": ["NUMBERINGSERIES", "VOUCHERSERIES"],
                "USEDIFFVCHTYPEFORINTERBANKCASHTRANSACTIONS": [
                    "USEDIFFVCHTYPEFORINTERBANKTRANSACTIONS",
                    "USEDIFFERENTVOUCHERTYPEFORINTERBANKCASHTRANSACTIONS",
                ],
                "INTERBANKCASHTRANSACTIONS": ["INTERBANKTRANSACTIONS", "INTERBANKCASHTRANSACTIONTYPE"],
                "IDENTIFYEXACTMATCHESWHILEIMPORTINGSTATEMENT": [
                    "IDENTIFYEXACTMATCHESONIMPORT",
                    "IDENTIFYEXACTMATCHESIMPORT",
                ],
                "IDENTIFYEXACTMATCHESWHILEIMPORTINGSTATEMENTASWELLASSAVINGVOUCHER": [
                    "IDENTIFYEXACTMATCHESONSAVE",
                    "IDENTIFYEXACTMATCHESIMPORTANDSAVE",
                ],
                "AUTOMATICALLYRECONCILEEXACTMATCHESFOUND": [
                    "AUTORECONCILEEXACTMATCHES",
                    "AUTORECONCILEEXACTMATCHESFOUND",
                ],
            },
            yes_no_fields=[
                "USEDIFFVCHTYPEFORINTERBANKCASHTRANSACTIONS",
                "IDENTIFYEXACTMATCHESWHILEIMPORTINGSTATEMENT",
                "IDENTIFYEXACTMATCHESWHILEIMPORTINGSTATEMENTASWELLASSAVINGVOUCHER",
                "AUTOMATICALLYRECONCILEEXACTMATCHESFOUND",
            ],
        )
        if normalized_auto_brs_config is not None:
            normalized["AUTOBRSCONFIGS.LIST"] = normalized_auto_brs_config
            normalized.setdefault("ISBANKSTATUSAPP", "Yes")
            normalized.setdefault("ISEBANKINGSUPPORTED", "Yes")

    bank_unreconciled_entries = _first_present_value(
        normalized,
        "BANKURENTRIES.LIST",
        "BANKUNRECONCILEDENTRIES.LIST",
    )
    if bank_unreconciled_entries is not None:
        normalized["BANKURENTRIES.LIST"] = copy.deepcopy(bank_unreconciled_entries)
        normalized.setdefault("ISBANKSTATUSAPP", "Yes")

    service_tax_details = _first_present_value(
        normalized,
        "SERVICETAXDETAILS.LIST",
        "SERVICETAXDETAILS",
        "SERVICETAXCONFIGURATION",
    )
    if service_tax_details is not None:
        normalized_service_tax_details = _normalize_config_entries(
            service_tax_details,
            {
                "SERVICECATEGORY": ["CATEGORY", "SERVICECATEGORYNAME"],
                "REGISTRATIONNUMBER": ["SERVICETAXREGISTRATIONNUMBER", "STREGISTRATIONNUMBER"],
                "STXDUTYHEAD": ["DUTYHEAD", "SERVICETAXDUTYHEAD"],
                "STXCLASSIFICATION": ["CLASSIFICATION", "SERVICETAXCLASSIFICATION"],
                "NOTIFICATIONSLNO": ["NOTIFICATIONNUMBER", "SLNO"],
                "ABATEMENTNOTIFICATIONNO": ["ABATEMENTNOTIFICATIONNUMBER"],
                "ISABATEMENTAPPLICABLE": ["ABATEMENTAPPLICABLE"],
                "ISSTXPARTY": ["STXPARTY"],
                "ISSTXNONREALIZEDTYPE": ["STXNONREALIZEDTYPE", "NONREALIZEDTYPE"],
            },
            yes_no_fields=["ISABATEMENTAPPLICABLE", "ISSTXPARTY", "ISSTXNONREALIZEDTYPE"],
        )
        if normalized_service_tax_details is not None:
            normalized["SERVICETAXDETAILS.LIST"] = normalized_service_tax_details
            first_service_tax_detail = (
                normalized_service_tax_details[0]
                if isinstance(normalized_service_tax_details, list)
                else normalized_service_tax_details
            )
            if isinstance(first_service_tax_detail, dict):
                for target_key in [
                    "SERVICECATEGORY",
                    "REGISTRATIONNUMBER",
                    "STXDUTYHEAD",
                    "STXCLASSIFICATION",
                    "NOTIFICATIONSLNO",
                    "ABATEMENTNOTIFICATIONNO",
                    "ISABATEMENTAPPLICABLE",
                    "ISSTXPARTY",
                    "ISSTXNONREALIZEDTYPE",
                ]:
                    if target_key in first_service_tax_detail and first_service_tax_detail[target_key] not in (None, ""):
                        normalized[target_key] = copy.deepcopy(first_service_tax_detail[target_key])
        normalized.setdefault("FORSERVICETAX", "Yes")

    for service_tax_key in [
        "SERVICECATEGORY",
        "REGISTRATIONNUMBER",
        "STXDUTYHEAD",
        "STXCLASSIFICATION",
        "NOTIFICATIONSLNO",
        "ABATEMENTNOTIFICATIONNO",
        "ISABATEMENTAPPLICABLE",
        "ISSTXPARTY",
        "ISSTXNONREALIZEDTYPE",
    ]:
        if service_tax_key not in normalized and service_tax_key in service_tax_config_container:
            normalized[service_tax_key] = copy.deepcopy(service_tax_config_container[service_tax_key])

    if any(
        normalized.get(key) not in (None, "")
        for key in [
            "SERVICECATEGORY",
            "REGISTRATIONNUMBER",
            "STXDUTYHEAD",
            "STXCLASSIFICATION",
            "NOTIFICATIONSLNO",
            "ABATEMENTNOTIFICATIONNO",
        ]
    ):
        normalized.setdefault("FORSERVICETAX", "Yes")

    if "FORSERVICETAX" in normalized and normalized["FORSERVICETAX"] == "Yes":
        normalized.setdefault("SERVICETAXAPPLICABLE", "Yes")

    for alias_key in [
        "PROVIDEBANKDETAILS",
        "BANKDETAILS.LIST",
        "ENABLECHEQUEPRINTING",
        "CHEQUEPRINTINGENABLED",
        "ENABLE_CHEQUE_PRINTING",
        "ENABLEAUTORECONCILIATION",
        "AUTORECONCILIATIONENABLED",
        "ENABLE_AUTO_RECONCILIATION",
        "ENABLEEPAYMENTS",
        "EPAYMENTSENABLED",
        "ENABLE_EPAYMENTS",
        "SETALTEREPAYMENTSCONFIGURATION",
        "SETALTEREPAYMENTSCONFIG",
        "SETALTERSERVICETAXDETAILS",
        "ENABLESERVICETAX",
        "ENABLE_SERVICE_TAX",
        "EXPORTUPLOADPAYMENTINSTRUCTIONSONVOUCHERCREATION",
        "EXPORTONVOUCHERCREATION",
        "EXPORTUPLOADONVOUCHERCREATION",
        "ALLOWEXPORTOFTRANSACTIONSWITHMISMATCHINBANKDETAILS",
        "ALLOWEXPORTWITHMISMATCHINBANKDETAILS",
        "ALLOWEXPORTWITHMISMATCHBANKDETAILS",
        "ENABLEBENEFICIARYCODE",
        "BENEFICIARYCODEON",
        "BULKPAYMENTFACILITY",
        "ISBATCHAPPLICABLE",
        "PAYMENTBATCHAPPLICABLE",
        "FILE_NUMBER_APPLICABLE",
        "FILENUMBERAPPLICABLE",
        "PAYMENTFILENUMBERAPPLICABLE",
        "PRODUCTCODEBASED",
        "ENABLEPRODUCTCODE",
        "CLIENTCODEENABLED",
        "ENABLECLIENTCODE",
        "FILENAMEFORMATSUPPORTED",
        "ENABLEFILENAMEFORMAT",
        "ASNOTIONALBANK",
        "ISNOTIONALBANK",
        "RECONCILIATIONBEGINNINGDATE",
        "RECONCILIATION_START_DATE",
        "BANKRECONCILIATIONFROM",
        "LOCATIONOFNEWBANKSTATEMENTS",
        "NEWBANKSTATEMENTSPATH",
        "LOCATIONOFIMPORTEDBANKSTATEMENTS",
        "IMPORTEDBANKSTATEMENTSPATH",
        "LOCATIONOFNEWINTERMEDIATEFILES",
        "NEWINTERMEDIATEFILELOCATION",
        "LOCATIONOFIMPORTEDINTERMEDIATEFILES",
        "IMPORTEDINTERMEDIATEFILELOCATION",
        "BSRCODE",
        "BRSCODE",
        "BANKCLIENTCODE",
        "SWIFT",
        "CHEQUEBOOKS.LIST",
        "CHEQUEBOOKRANGE.LIST",
        "CHEQUEBOOKRANGES.LIST",
        "CHEQUEPRINTINGCONFIGURATION.LIST",
        "CHEQUEPRINTINGCONFIGURATION",
        "CHEQUECONFIGURATION.LIST",
        "CHEQUECONFIGURATION",
        "OPENINGCHEQUEDETAILS.LIST",
        "OPENINGCHEQUECONFIGURATION.LIST",
        "VOUCHERCHEQUEDETAILS.LIST",
        "DEFAULTVOUCHERCHEQUEDETAILS.LIST",
        "ECHEQUEPRINTLOCATIONS.LIST",
        "ECHEQUEPAYABLELOCATIONS.LIST",
        "EDDPRINTLOCATIONS.LIST",
        "EDDPAYABLELOCATIONS.LIST",
        "PAYMENTINSTRUCTIONOUTPUTNAME",
        "PAYMENTOUTPUTNAME",
        "OUTPUTFILENAME",
        "GATEWAYNAME",
        "SHORTCODE",
        "BANKSHORTCODE",
        "MICRCODE",
        "BANKMICRNUMBER",
        "NEWSTATEMENTSPATH",
        "BANKNEWSTATEMENTSPATH",
        "IMPORTEDSTATEMENTS_PATH",
        "BANKIMPORTEDSTATEMENTSPATH",
        "PAYMENTBATCHNAME",
        "LASTPAYMENTBATCHNAME",
        "PAYMENTFILENUMBERPERIOD",
        "PAYMENTPRODUCTCODETYPE",
        "SALARYPRODUCTCODE",
        "OTHERPRODUCTCODE",
        "PAYMENTINSTRUCTIONLOCATION",
        "ENCRYPTIONLOCATION",
        "NEWIMPORTFILELOCATION",
        "IMPORTEDIMPORTFILELOCATION",
        "CORPORATEUSERNUMBERECS",
        "CORPORATEUSERNUMBERACH",
        "CORPORATEUSER",
        "IMPORTFORMATNAME",
        "EPAYMENTSCONFIGURATION.LIST",
        "EPAYMENTSCONFIGURATION",
        "EPAYMENTCONFIGURATION.LIST",
        "EPAYMENTCONFIGURATION",
        "EPAYMENTSCONFIG.LIST",
        "EPAYMENTSCONFIG",
        "AUTORECONCILIATIONCONFIGURATION.LIST",
        "AUTORECONCILIATIONCONFIGURATION",
        "AUTORECONCILIATIONCONFIG.LIST",
        "AUTORECONCILIATIONCONFIG",
        "AUTORECONCILIATIONSETTINGS.LIST",
        "AUTORECONCILIATIONSETTINGS",
        "BANKUNRECONCILEDENTRIES.LIST",
        "SERVICETAXDETAILS",
        "SERVICETAXCONFIGURATION",
        "MODEOFDELIVERY",
        "CHEQUEDELIVERYMODE",
        "DELIVERTO",
        "CHEQUEDELIVERYTO",
        "PRINTAT",
        "CHEQUEPRINTLOCATION",
        "PAYABLEAT",
        "CHEQUEPAYABLELOCATION",
        "BANKBRANCHLOCATION",
        "CHEQUEBANKLOCATION",
        "PRINTCITY",
        "CHEQUECITY",
        "CROSSCOMMENT",
        "CHEQUECROSSINGCOMMENT",
        "BANKDATE",
        "CHEQUEDATE",
        "ENTRYDATE",
        "TRANSACTIONNATURE",
        "NATURE",
        "BANKTRANSACTIONTYPE",
        "REMARK",
        "DEPOSIT",
        "WITHDRAWAL",
        "DEPOSITWITHDRAWAL",
        "INSTRUMENTNO",
        "CHEQUENO",
        "CHEQUENUMBER",
        "CHEQUEINSTRUMENTDATE",
        "BANKINSTRUMENTDATE",
        "FROM",
        "MINAMOUNT",
        "AMOUNTFROM",
        "TO",
        "MAXAMOUNT",
        "AMOUNTTO",
        "MODE",
        "PREFERREDTRANSFERMODE",
        "DEFAULTMODE",
        "DEFAULT",
        "ISDEFAULT",
        "DEPOSITVOUCHERTYPE",
        "DEPOSITVOUCHER",
        "WITHDRAWALVOUCHERTYPE",
        "WITHDRAWALVOUCHER",
        "NUMBERINGSERIES",
        "VOUCHERSERIES",
        "USEDIFFVCHTYPEFORINTERBANKTRANSACTIONS",
        "USEDIFFERENTVOUCHERTYPEFORINTERBANKCASHTRANSACTIONS",
        "INTERBANKTRANSACTIONS",
        "INTERBANKCASHTRANSACTIONTYPE",
        "IDENTIFYEXACTMATCHESONIMPORT",
        "IDENTIFYEXACTMATCHESIMPORT",
        "IDENTIFYEXACTMATCHESONSAVE",
        "IDENTIFYEXACTMATCHESIMPORTANDSAVE",
        "AUTORECONCILEEXACTMATCHES",
        "AUTORECONCILEEXACTMATCHESFOUND",
        "CATEGORY",
        "SERVICECATEGORYNAME",
        "SERVICETAXREGISTRATIONNUMBER",
        "STREGISTRATIONNUMBER",
        "DUTYHEAD",
        "SERVICETAXDUTYHEAD",
        "CLASSIFICATION",
        "SERVICETAXCLASSIFICATION",
        "NOTIFICATIONNUMBER",
        "SLNO",
        "ABATEMENTNOTIFICATIONNUMBER",
        "ABATEMENTAPPLICABLE",
        "STXPARTY",
        "STXNONREALIZEDTYPE",
        "NONREALIZEDTYPE",
    ]:
        normalized.pop(alias_key, None)

    return normalized


def _normalize_pay_head_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = _normalize_ledger_payload(payload)

    for key in ("COMPANY", "company"):
        normalized.pop(key, None)

    parent_name = _first_non_empty_string(
        normalized.get("PARENT"),
        payload.get("PARENT") if isinstance(payload, dict) else None,
    )
    if parent_name:
        normalized["PARENT"] = parent_name
        normalized.setdefault("RESERVENAME", parent_name)

    # Live Tally verification shows pay-head writes that reflect back through
    # the stable 'Payroll Ledgers' collection persist FORPAYROLL=Yes.
    normalized["FORPAYROLL"] = "Yes"

    return normalized


def _normalize_employee_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = copy.deepcopy(payload)

    for key in ("COMPANY", "company"):
        normalized.pop(key, None)

    normalized.setdefault("CATEGORY", "Primary Cost Category")
    normalized.setdefault("PARENT", "Primary")
    normalized["USEASEMPLOYEE"] = "Yes"
    normalized["FORPAYROLL"] = "Yes"

    alias_map = {
        "DISPLAYNAME": "EMPDISPLAYNAME",
        "EMPDISPLAYNAME": "EMPDISPLAYNAME",
        "DATEOFJOINING": "DATEOFJOIN",
        "DATEOFJOIN": "DATEOFJOIN",
        "EMAIL": "EMAILID",
        "EMAILID": "EMAILID",
        "AADHAARNUMBER": "AADHARNUMBER",
        "AADHARNUMBER": "AADHARNUMBER",
        "UAN": "UANNUMBER",
        "UANNUMBER": "UANNUMBER",
        "PASSPORTNUMBER": "PASSPORTDETAILS",
        "PASSPORTDETAILS": "PASSPORTDETAILS",
        "CONTRACTENDDATE": "CONTRACTEXPIRYDATE",
        "CONTRACTEXPIRYDATE": "CONTRACTEXPIRYDATE",
        "MOBILE": "MOBILENUMBER",
        "MOBILENUMBER": "MOBILENUMBER",
        "PHONE": "CONTACTNUMBERS",
        "CONTACTNUMBERS": "CONTACTNUMBERS",
    }

    for source_key, target_key in alias_map.items():
        value = normalized.get(source_key)
        if value not in (None, ""):
            normalized[target_key] = value

    for alias_key in (
        "DISPLAYNAME",
        "DATEOFJOINING",
        "EMAIL",
        "AADHAARNUMBER",
        "UAN",
        "PASSPORTNUMBER",
        "CONTRACTENDDATE",
        "MOBILE",
        "PHONE",
    ):
        normalized.pop(alias_key, None)

    return normalized


def _normalize_employee_group_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = copy.deepcopy(payload)

    for key in ("COMPANY", "company"):
        normalized.pop(key, None)

    normalized.setdefault("CATEGORY", "Primary Cost Category")
    normalized.setdefault("PARENT", "Primary")
    normalized["FORPAYROLL"] = "Yes"
    normalized["ISEMPLOYEEGROUP"] = "Yes"
    normalized.setdefault("GRPATTENDANCE", "No")
    normalized.setdefault("GRPPAYHEAD", "No")
    normalized.setdefault("USEFORPAYROLL", "Yes")

    parent_value = normalized.get("PARENT")
    if isinstance(parent_value, str) and parent_value.strip().lower() == "primary":
        normalized["PARENT"] = "\u0004 Primary"

    # Employee groups are cost centres in payroll mode, not employee masters.
    normalized.pop("USEASEMPLOYEE", None)

    if "LANGUAGENAME.LIST" not in normalized and isinstance(normalized.get("NAME"), str) and normalized["NAME"].strip():
        normalized["LANGUAGENAME.LIST"] = {
            "NAME.LIST": [
                {
                    "NAME": normalized["NAME"].strip(),
                }
            ]
        }

    return normalized


def _normalize_attendance_type_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = copy.deepcopy(payload)

    for key in ("COMPANY", "company"):
        normalized.pop(key, None)

    return normalized


COMPANY_FEATURE_FETCH = [
    "NAME",
    "MAILINGNAME",
    "BOOKSFROM",
    "FINANCIALYEARFROM",
    "COUNTRYNAME",
    "STATENAME",
    "PINCODE",
    "EMAIL",
    "WEBSITE",
    "ISINVENTORYON",
    "ISGSTON",
    "ISEINVOICEON",
    "ISEWAYBILLON",
]

GST_REGISTRATION_FETCH = [
    "NAME",
    "COUNTRYNAME",
    "STATENAME",
    "PINCODE",
    "GSTREGISTRATIONTYPE",
    "PARTYGSTIN",
    "GSTIN",
    "ISGSTON",
]

COMPANY_CURRENCY_FETCH = [
    "NAME",
    "BOOKSFROM",
    "FINANCIALYEARFROM",
    "BASECURRENCYNAME",
    "CURRSYMBOL",
    "CURRENCYSYMBOL",
    "FORMALNAME",
    "ISOCURRENCYCODE",
    "CURRENCYNAME",
    "DECIMALSYMBOL",
    "THOUSANDSYMBOL",
]

NUMBERING_RULE_FETCH = [
    "NAME",
    "NUMBERINGMETHOD",
    "PREFIX",
    "SUFFIX",
    "STARTINGNUMBER",
    "ROUNDINGMETHOD",
    "ROUNDLIMIT",
]

LEDGER_TAX_FETCH = [
    "NAME",
    "PARENT",
    "GSTREGISTRATIONTYPE",
    "PARTYGSTIN",
    "GSTAPPLICABLE",
    "TAXTYPE",
    "RATEOFTAXCALCULATION",
]

STOCK_TAX_FETCH = [
    "NAME",
    "PARENT",
    "HSNCODE",
    "GSTTYPEOFSUPPLY",
    "GSTAPPLICABLE",
    "TAXABILITY",
    "BASICTARIFF",
]

PRICE_STRUCTURE_FETCH = [
    "NAME",
    "PARENT",
    "BASEUNITS",
    "FULLPRICELIST.*",
    "STANDARDPRICELIST.*",
    "PRICELEVELLIST.*",
]

PRICE_LEVEL_FETCH = [
    "NAME",
]

PRICE_LIST_DETAIL_FIELDS = [
    "FULLPRICELIST.*",
    "STANDARDPRICELIST.*",
    "PRICELEVELLIST.*",
]

PRICE_LIST_REPORT_NAMES = [
    "Price Lists",
    "Price Lists (Stock Group)",
    "Price Lists (Stock Category)",
    "Price List",
    "Price List (Stock Group)",
]

PRICE_LEVEL_REPORT_NAMES = [
    "Price Levels",
    "Price Level",
]

BOM_FETCH = [
    "NAME",
    "PARENT",
    "BASEUNITS",
    "COMPONENTLIST.*",
    "MULTICOMPONENTLIST.*",
]

STOCK_CONTROL_FETCH = [
    "NAME",
    "PARENT",
    "BASEUNITS",
    "REORDERLEVEL",
    "MINORDERQTY",
    "OPENINGBALANCE",
]

SECURITY_ROLE_FETCH = [
    "NAME",
]

UQC_FETCH = [
    "NAME",
    "ORIGINALNAME",
    "ISSIMPLEUNIT",
    "BASEUNITS",
    "ADDITIONALUNITS",
    "DECIMALPLACES",
]

EINVOICE_FETCH = [
    "NAME",
    "ISEINVOICEON",
    "GSTREGISTRATIONTYPE",
    "PARTYGSTIN",
]

EWAYBILL_FETCH = [
    "NAME",
    "ISEWAYBILLON",
    "GSTREGISTRATIONTYPE",
    "PARTYGSTIN",
]

SALES_EINVOICE_STATUS_FETCH = [
    "DATE",
    "VOUCHERNUMBER",
    "REFERENCE",
    "PARTYLEDGERNAME",
    "PARTYNAME",
    "BASICBUYERNAME",
    "PARTYGSTIN",
    "PLACEOFSUPPLY",
    "IRN",
    "IRNACKNO",
    "IRNACKDATE",
    "IRNQRCODE",
    "IRNCANCELLED",
    "IRNCANCELDATE",
    "IRNCANCELREASON",
    "IRNCANCELREMARKS",
    "IRNERRORLIST.LIST",
]

SALES_EWAYBILL_STATUS_FETCH = [
    "DATE",
    "VOUCHERNUMBER",
    "REFERENCE",
    "PARTYLEDGERNAME",
    "PARTYNAME",
    "PARTYGSTIN",
    "PLACEOFSUPPLY",
    "TRANSPORTERNAME",
    "TRANSPORTMODE",
    "GOODSVEHICLENUMBER",
    "LORRYRECPTNO",
    "TEMPGSTEWAYBILLNUMBER",
    "TEMPGSTEWAYBILLDATE",
    "TEMPGSTEWAYSTATUS",
    "TEMPGSTEWAYISCANCELLED",
    "TEMPGSTEWAYTRANSPORTERNAME",
    "TEMPGSTEWAYTRANSPORTMODE",
    "TEMPGSTEWAYVEHICLENUMBER",
    "TEMPGSTEWAYVEHICLETYPE",
    "TEMPGSTEWAYTRANSPORTERDOCNO",
    "TEMPGSTEWAYTRANSPORTERDOCDATE",
    "TEMPGSTEWAYTRANSPORTERID",
    "TEMPGSTEWAYDISTANCE",
    "EWAYBILLDETAILS.LIST",
    "EWAYBILLERRORLIST.LIST",
]

COMMON_READY_VOUCHER_TYPE_ALIASES = {
    "sales": "Sales",
    "sales invoice": "Sales",
    "invoice": "Sales",
    "debit note": "Debit Note",
    "debitnote": "Debit Note",
    "credit note": "Credit Note",
    "creditnote": "Credit Note",
    "delivery note": "Delivery Note",
    "deliverynote": "Delivery Note",
    "receipt note": "Receipt Note",
    "receiptnote": "Receipt Note",
    "purchase": "Purchase",
    "journal": "Journal",
    "receipt": "Receipt",
    "pos": "POS",
    "sales pos": "POS",
}

COMMON_READY_SUPPORT_MATRIX: Dict[str, Dict[str, List[str]]] = {
    "einvoice": {
        "implemented": ["Sales"],
        "documented": ["Sales", "POS", "Debit Note", "Credit Note", "Receipt", "Journal"],
    },
    "ewaybill": {
        "implemented": ["Sales"],
        "documented": ["Sales", "Delivery Note", "POS", "Journal", "Purchase", "Receipt Note", "Credit Note", "Debit Note"],
    },
    "einvoice_ewaybill": {
        "implemented": ["Sales"],
        "documented": ["Sales"],
    },
}

COMMON_GENERATE_SUPPORT_MATRIX: Dict[str, Dict[str, List[str]]] = {
    "einvoice": {
        "implemented": ["Sales"],
        "documented": ["Sales", "POS", "Debit Note", "Credit Note", "Receipt", "Journal"],
    },
    "ewaybill": {
        "implemented": ["Sales"],
        "documented": ["Sales", "Delivery Note", "POS", "Journal", "Purchase", "Receipt Note", "Credit Note", "Debit Note"],
    },
    "einvoice_ewaybill": {
        "implemented": ["Sales"],
        "documented": ["Sales"],
    },
}


def _common_compliance_mode_label(compliance_mode: str) -> str:
    return compliance_mode.replace("_", " + ")


def _sales_specific_compliance_route(compliance_mode: str, operation: str) -> str:
    suffix = "generate" if operation == "generate" else "ready"
    mapping = {
        "einvoice": f"/vouchers/sales/einvoice-{suffix}" if suffix == "ready" else "/vouchers/sales/einvoice/generate",
        "ewaybill": f"/vouchers/sales/ewaybill-{suffix}" if suffix == "ready" else "/vouchers/sales/ewaybill/generate",
        "einvoice_ewaybill": f"/vouchers/sales/einvoice-ewaybill-{suffix}" if suffix == "ready" else "/vouchers/sales/einvoice-ewaybill/generate",
    }
    return mapping[compliance_mode]


def _common_compliance_unsupported_detail(
    *,
    compliance_mode: str,
    operation: str,
    voucher_type: str,
    implemented_types: List[str],
    documented_types: List[str],
) -> Dict[str, Any]:
    return {
        "supported": False,
        "resource": f"{compliance_mode} {operation}",
        "fallback_applied": False,
        "error_type": "unsupported",
        "voucher_type": voucher_type,
        "implemented_voucher_types": implemented_types,
        "documented_voucher_types": documented_types,
        "use_instead": _sales_specific_compliance_route(compliance_mode, operation),
        "reason": (
            f"The common {_common_compliance_mode_label(compliance_mode)} {operation} API accepts voucher_type, "
            f"but this connector build only implements the {operation} flow for {', '.join(implemented_types) or 'no voucher types yet'}."
        ),
    }

SALES_GENERATE_STATUS_FETCH = list(
    dict.fromkeys(
        SALES_EINVOICE_STATUS_FETCH
        + SALES_EWAYBILL_STATUS_FETCH
        + [
            "IRNJSONEXPORTED",
            "TEMPGSTEWAYCONSBILLNUMBER",
            "TEMPGSTEWAYCONSBILLDATE",
        ]
    )
)

GST_STATE_CODE_BY_NAME = {
    "jammu and kashmir": "01",
    "himachal pradesh": "02",
    "punjab": "03",
    "chandigarh": "04",
    "uttarakhand": "05",
    "haryana": "06",
    "delhi": "07",
    "rajasthan": "08",
    "uttar pradesh": "09",
    "bihar": "10",
    "sikkim": "11",
    "arunachal pradesh": "12",
    "nagaland": "13",
    "manipur": "14",
    "mizoram": "15",
    "tripura": "16",
    "meghalaya": "17",
    "assam": "18",
    "west bengal": "19",
    "jharkhand": "20",
    "odisha": "21",
    "chhattisgarh": "22",
    "madhya pradesh": "23",
    "gujarat": "24",
    "dadra and nagar haveli and daman and diu": "26",
    "maharashtra": "27",
    "andhra pradesh": "28",
    "karnataka": "29",
    "goa": "30",
    "lakshadweep": "31",
    "kerala": "32",
    "tamil nadu": "33",
    "puducherry": "34",
    "andaman and nicobar islands": "35",
    "telangana": "36",
    "andhra pradesh (new)": "37",
    "ladakh": "38",
    "other territory": "97",
    "centre jurisdiction": "99",
}

TRANSPORT_MODE_TO_PROVIDER = {
    "Road": "1",
    "Rail": "2",
    "Air": "3",
    "Ship": "4",
}

TRANSPORT_MODE_FROM_PROVIDER = {value: key for key, value in TRANSPORT_MODE_TO_PROVIDER.items()}

VEHICLE_TYPE_TO_PROVIDER = {
    "Regular": "R",
    "Over Dimensional Cargo": "O",
}

VEHICLE_TYPE_FROM_PROVIDER = {value: key for key, value in VEHICLE_TYPE_TO_PROVIDER.items()}

LIGHT_STOCK_ITEM_FETCH = [
    "NAME",
    "PARENT",
    "BASEUNITS",
    "CLOSINGBALANCE",
    "CLOSINGVALUE",
    "OPENINGBALANCE",
    "OPENINGVALUE",
    "OPENINGRATE",
]

LIGHT_STOCK_GROUP_FETCH = [
    "NAME",
    "PARENT",
]

LIGHT_LEDGER_FETCH = [
    "NAME",
    "PARENT",
]

LIGHT_COST_CENTRE_FETCH = [
    "NAME",
    "PARENT",
]

LIGHT_COST_CATEGORY_FETCH = [
    "NAME",
]

LIGHT_GODOWN_FETCH = [
    "NAME",
    "PARENT",
]

LIGHT_UNIT_FETCH = [
    "NAME",
    "ORIGINALNAME",
    "BASEUNITS",
]

SPECIAL_COLLECTION_EXPORTS: Dict[str, str] = {
    "employee": "Employees",
    "employee group": "Employee Groups",
    "pay head": "Payroll Ledgers",
    "attendance type": "List of Attendance Types",
}

PAYROLL_MASTERS = {"employee", "employee group", "pay head", "attendance type"}
GENERIC_PAYROLL_MASTER_WRITE_UNSUPPORTED = {"employee", "employee group"}
PAYROLL_ROUTE_MASTER_TYPES = {
    "/employees": "employee",
    "/employee-groups": "employee group",
    "/pay-heads": "pay head",
    "/attendance-types": "attendance type",
}

UNSUPPORTED_MASTER_EXPORTS: Dict[str, str] = {
    "security level": (
        "Tally does not expose security roles through the generic master "
        "collection interface in this build."
    ),
    "security role": (
        "Tally does not expose security roles through the generic master "
        "collection interface in this build."
    ),
}


def unsupported_response(resource: str, reason: str):
    raise HTTPException(
        status_code=501,
        detail={
            "supported": False,
            "resource": resource,
            "fallback_applied": False,
            "error_type": "unsupported",
            "reason": reason,
        },
    )


def experimental_route_disabled(resource: str, flag_name: str, reason: str):
    raise HTTPException(
        status_code=501,
        detail={
            "supported": False,
            "resource": resource,
            "fallback_applied": False,
            "error_type": "experimental_disabled",
            "reason": reason,
            "enable_with_env": flag_name,
            "warning": (
                "This path stays opt-in because live verification has shown that some Tally builds "
                "reject it or can destabilize the XML interface."
            ),
        },
    )


def _is_metadata_only_collection(payload: Any) -> bool:
    if not isinstance(payload, dict) or not payload:
        return False
    substantive_keys = [key for key in payload.keys() if not str(key).startswith("@")]
    if not substantive_keys:
        return True
    allowed_metadata = {"ISMSTDEPTYPE", "MSTDEPTYPE", "ISCMPDEPTYPE", "CMPDEPTYPE", "CMPLOCUS"}
    return all(str(key).upper() in allowed_metadata for key in substantive_keys)


def _payroll_collection_unavailable_detail(master_type: str) -> Dict[str, Any]:
    collection_export = SPECIAL_COLLECTION_EXPORTS.get(master_type)
    return {
        "supported": False,
        "resource": master_type,
        "fallback_applied": False,
        "error_type": "feature_unavailable",
        "required_tdl_feature": "payroll_exports",
        "collection_export": collection_export,
        "reason": (
            f"Tally returned only payroll collection metadata for '{master_type}' and no actual records. "
            "This usually means the payroll feature is disabled in the current company, or this Tally build "
            "does not expose that payroll master through the XML collection interface."
        ),
        "what_to_do": [
            "Enable payroll for the selected company in TallyPrime and retry.",
            "If the built-in export still returns metadata only, load a TDL package that exposes payroll exports for the local connector.",
            "Re-check GET /health/readiness and GET /capabilities after enabling payroll or TDL integration.",
        ],
    }


def _payroll_route_unavailable_detail(
    master_type: str,
    reason: str,
    *,
    response: Optional[Any] = None,
) -> Dict[str, Any]:
    detail = _payroll_collection_unavailable_detail(master_type)
    detail["reason"] = reason
    if response is not None:
        detail["tally_response"] = response
    return detail


def _attendance_voucher_feature_unavailable_detail() -> Dict[str, Any]:
    return {
        "supported": False,
        "resource": "attendance voucher",
        "fallback_applied": False,
        "error_type": "feature_unavailable",
        "required_tdl_feature": "payroll_exports",
        "collection_export": SPECIAL_COLLECTION_EXPORTS.get("attendance type"),
        "reason": (
            "Attendance voucher creation depends on attendance types being visible through Tally XML export. "
            "This company currently exposes only payroll metadata and no actual ATTENDANCETYPE records, so "
            "generic Attendance voucher imports will no-op in this Tally build."
        ),
        "what_to_do": [
            "Enable payroll and attendance types for the selected company in TallyPrime.",
            "Create at least one Attendance/Production Type in Tally and confirm it is visible in payroll exports.",
            "If attendance types still do not export, load a TDL package that exposes payroll attendance masters and vouchers.",
        ],
    }


def _payroll_write_no_effect_detail(master_type: str, response: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "supported": False,
        "resource": master_type,
        "fallback_applied": False,
        "error_type": "write_no_effect",
        "required_tdl_feature": "payroll_exports",
        "reason": (
            f"Tally acknowledged the {master_type} write request, but it did not create, alter, or delete anything. "
            "This usually means payroll is not enabled for the company or the payload is not valid for this Tally payroll master."
        ),
        "what_to_do": [
            "Verify that payroll is enabled for the selected company.",
            "Verify that the parent payroll master exists in Tally and is visible in the payroll UI.",
            "If writes still no-op, use a TDL-backed payroll import path for this Tally build.",
        ],
        "tally_response": response,
    }


def _generic_payroll_master_write_unsupported_detail(master_type: str) -> Dict[str, Any]:
    tag_name = {
        "employee": "EMPLOYEE",
        "employee group": "EMPLOYEEGROUP",
    }.get(master_type, master_type.upper())
    return {
        "supported": False,
        "resource": master_type,
        "fallback_applied": False,
        "error_type": "unsupported",
        "required_tdl_feature": "payroll_exports",
        "implementation_path": {
            "route_strategy": "generic master route",
            "router_builder": "register_master",
            "xml_builder": "build_master_upsert",
            "tally_reportname": "All Masters",
            "tally_object_tag": tag_name,
        },
        "reason": (
            f"This connector's generic payroll master write path for '{master_type}' is not Tally-compatible yet. "
            f"Direct XML imports using <{tag_name}> through the generic 'All Masters' request were verified to "
            "reach Tally but produce a no-effect acknowledgement even after payroll was enabled for the company. "
            "A dedicated payroll import/report path is still required for reliable employee master writes."
        ),
        "analysis_summary": [
            "Most master APIs in this connector share one generic import/export implementation instead of using a dedicated Tally path per resource.",
            f"'{master_type}' is currently one of those generic master routes.",
            "Payroll-specific writes do not behave like regular generic masters in this Tally build.",
            "This is why the employee API now returns explicit unsupported instead of a misleading no-effect acknowledgement.",
        ],
        "use_instead": "Create or alter this payroll master manually in TallyPrime until a dedicated path is implemented.",
    }


def _inventory_material_voucher_write_no_effect_detail(
    voucher_type: str,
    response: Dict[str, Any],
) -> Dict[str, Any]:
    return {
        "supported": False,
        "resource": f"{voucher_type.strip().lower()} voucher",
        "fallback_applied": False,
        "error_type": "write_no_effect",
        "reason": (
            f"Tally accepted the {voucher_type} voucher import request but did not create or modify it. "
            "In this Tally build, Material In/Out vouchers are not persisting through the generic "
            "Consumption Voucher XML path even when the inventory rows are normalized correctly."
        ),
        "what_to_do": [
            "Create one Material In or Material Out voucher manually in Tally and use that voucher structure as the import template.",
            "If the generic path still no-ops, enable a TDL-backed import path for Material In/Out in this company.",
            "Use Stock Journal when the business flow allows it, because Stock Journal writes are currently verified in this environment.",
        ],
        "tally_response": response,
    }


def _extract_tally_line_error(value: Any) -> Optional[str]:
    if isinstance(value, dict):
        line_error = value.get("LINEERROR")
        if isinstance(line_error, str) and line_error.strip():
            return line_error.strip()
        response_text = value.get("RESPONSE")
        if isinstance(response_text, str) and response_text.strip():
            return response_text.strip()
        for nested in value.values():
            found = _extract_tally_line_error(nested)
            if found:
                return found
    elif isinstance(value, list):
        for item in value:
            found = _extract_tally_line_error(item)
            if found:
                return found
    return None


def _classify_tally_error(line_error: str) -> tuple[int, Dict[str, Any]]:
    lowered = line_error.lower()

    unsupported_markers = (
        "could not find report",
        "incorrect object type",
        "unsupported",
        "unknown request",
    )
    validation_markers = (
        "required",
        "does not exist",
        "invalid",
        "not a valid",
        "cannot be",
        "mismatch",
        "already exists",
        "bad order number",
        "no entries in voucher",
    )

    if any(marker in lowered for marker in unsupported_markers):
        return (
            501,
            {
                "supported": False,
                "fallback_applied": False,
                "error_type": "unsupported",
                "reason": line_error,
            },
        )

    if any(marker in lowered for marker in validation_markers):
        return (
            422,
            {
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": line_error,
            },
        )

    return (
        502,
        {
            "supported": True,
            "fallback_applied": False,
            "error_type": "tally_error",
            "reason": line_error,
        },
    )


def _extract_tally_import_error_summary(parsed: Dict[str, Any]) -> Optional[str]:
    env = parsed.get("ENVELOPE") if isinstance(parsed, dict) else None
    if not isinstance(env, dict):
        return None
    body = env.get("BODY")
    if not isinstance(body, dict):
        return None
    data = body.get("DATA")
    if not isinstance(data, dict):
        return None
    errors_raw = data.get("ERRORS")
    if errors_raw in (None, "", "0", 0):
        return None
    try:
        errors = int(str(errors_raw))
    except ValueError:
        errors = 1
    if errors <= 0:
        return None
    line_error = _extract_tally_line_error(data)
    if line_error:
        return line_error
    return f"Tally rejected the request without LINEERROR (ERRORS={errors})"


IMPORT_RESULT_COUNTER_KEYS = (
    "CREATED",
    "ALTERED",
    "DELETED",
    "LASTVCHID",
    "LASTMID",
    "COMBINED",
    "IGNORED",
    "ERRORS",
    "CANCELLED",
    "EXCEPTIONS",
)


def _compact_import_result(line_error: str, response: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    normalized_response = dict(response or {})
    import_result = {
        key: str(normalized_response.get(key, "0"))
        for key in IMPORT_RESULT_COUNTER_KEYS
    }
    import_result["LINEERROR"] = line_error
    return {"IMPORTRESULT": import_result}


def _normalize_import_result(payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not isinstance(payload, dict):
        return None
    response = payload.get("IMPORTRESULT") or payload.get("RESPONSE")
    if not isinstance(response, dict):
        return None
    line_error = _extract_tally_line_error(response) or ""
    return _compact_import_result(line_error, response)


def _import_result_has_error(import_result: Dict[str, Any]) -> bool:
    if not isinstance(import_result, dict):
        return False
    payload = import_result.get("IMPORTRESULT")
    if not isinstance(payload, dict):
        return False
    for key in ("ERRORS", "EXCEPTIONS"):
        try:
            if int(str(payload.get(key, "0"))) > 0:
                return True
        except ValueError:
            return True
    return bool(_extract_tally_line_error(payload))


def _tally_http_error_detail(exc: httpx.HTTPError, *, compact: bool = False) -> Any:
    message = repr(exc)
    if isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout)) or "All connection attempts failed" in message:
        detail: Dict[str, Any] = {
            "supported": False,
            "fallback_applied": False,
            "error_type": "tally_connection_unavailable",
            "reason": (
                "TallyPrime is not accepting XML requests on http://127.0.0.1:9000 right now. "
                "Open the company in TallyPrime and make sure the local Tally listener is active."
            ),
            "what_to_do": [
                "Bring the TallyPrime window to the front.",
                "Open the target company inside TallyPrime.",
                "Keep TallyPrime running while retrying the API request.",
                "Confirm the connector is configured for TALLY_BASE_URL=http://127.0.0.1:9000.",
            ],
            "upstream_error": message,
        }
        return _compact_import_result(detail["reason"]) if compact else detail
    return _compact_import_result(f"Tally request failed: {message}") if compact else f"Tally request failed: {message}"


async def _post_xml(client: TallyClient, xml_req: str, view: str = "summary") -> Dict[str, Any]:
    try:
        xml_resp = await client.post_xml(xml_req)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=_tally_http_error_detail(exc)) from exc
    parsed = xml_to_json(xml_resp)
    line_error = _extract_tally_import_error_summary(parsed) or _extract_tally_line_error(parsed)
    if line_error:
        status_code, detail = _classify_tally_error(line_error)
        raise HTTPException(status_code=status_code, detail=detail)
    return _shape_xml_response(parsed, view)


async def _post_xml_import_result(client: TallyClient, xml_req: str) -> Dict[str, Any]:
    try:
        xml_resp = await client.post_xml(xml_req)
    except httpx.HTTPError as exc:
        raise HTTPException(
            status_code=502,
            detail=_tally_http_error_detail(exc, compact=True),
        ) from exc
    parsed = xml_to_json(xml_resp)
    import_result = _normalize_import_result(parsed)
    if import_result is not None:
        if _import_result_has_error(import_result):
            line_error = _extract_tally_line_error(import_result) or "Tally import failed"
            status_code, _ = _classify_tally_error(line_error)
            raise HTTPException(status_code=status_code, detail=import_result)
        return import_result
    line_error = _extract_tally_import_error_summary(parsed) or _extract_tally_line_error(parsed)
    if line_error:
        status_code, _ = _classify_tally_error(line_error)
        raise HTTPException(status_code=status_code, detail=_compact_import_result(line_error))
    return parsed


def _is_tally_noop_response(payload: Dict[str, Any]) -> bool:
    if not isinstance(payload, dict):
        return False
    response = payload.get("RESPONSE") or payload.get("IMPORTRESULT")
    if not isinstance(response, dict):
        return False
    counters = ("CREATED", "ALTERED", "DELETED", "IGNORED", "ERRORS", "CANCELLED", "COMBINED")
    return all(str(response.get(key, "0")) == "0" for key in counters)


def _tally_import_exceptions(payload: Dict[str, Any]) -> int:
    if not isinstance(payload, dict):
        return 0
    response = payload.get("RESPONSE") or payload.get("IMPORTRESULT")
    if not isinstance(response, dict):
        return 0
    raw_value = response.get("EXCEPTIONS", "0")
    try:
        return int(str(raw_value))
    except ValueError:
        return 0


async def _read_raw_xml_body(request: Request) -> str:
    body = await request.body()
    xml_text = body.decode("utf-8", errors="replace").strip()
    if not xml_text:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "Request body must contain a Tally XML ENVELOPE.",
            },
        )
    if not xml_text.lstrip().startswith("<"):
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "Raw voucher import expects XML, not JSON.",
            },
        )
    try:
        parsed = xml_to_json(xml_text)
    except HTTPException as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": str(exc.detail),
            },
        ) from exc
    envelope = parsed.get("ENVELOPE") if isinstance(parsed, dict) else None
    if not isinstance(envelope, dict):
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "XML must have an ENVELOPE root.",
            },
        )
    return xml_text


@router.post("/vouchers/import-xml")
async def vouchers_import_xml(
    request: Request,
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    xml_req = await _read_raw_xml_body(request)
    return await _post_xml_import_result(client, xml_req)


@router.post("/xml/execute")
async def xml_execute(
    request: Request,
    view: Optional[str] = Query(default="raw", description="Response view: summary, full, raw"),
    import_mode: Optional[bool] = Query(
        default=False,
        description="When true, return compact Tally import counters (CREATED/ALTERED/ERRORS).",
    ),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    xml_req = await _read_raw_xml_body(request)
    if import_mode:
        return await _post_xml_import_result(client, xml_req)
    return await _post_xml(client, xml_req, view or "raw")


@router.get("/xml/trace/status")
async def xml_trace_status(
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
):
    await ensure_auth(x_agent_key, settings)
    trace_dir = settings.resolved_tally_xml_trace_dir()
    return {
        "enabled": bool(settings.tally_xml_trace_enabled),
        "trace_dir": str(trace_dir),
        "trace_dir_exists": trace_dir.exists(),
    }


@router.get("/xml/trace/files")
async def xml_trace_files(
    limit: Optional[int] = Query(default=50, description="Maximum recent trace files to return"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
):
    await ensure_auth(x_agent_key, settings)
    trace_dir = settings.resolved_tally_xml_trace_dir()
    if not trace_dir.exists() or not trace_dir.is_dir():
        return {"trace_dir": str(trace_dir), "items": []}
    effective_limit = max(1, min(int(limit or 50), 500))
    candidates = [path for path in trace_dir.rglob("*") if path.is_file()]
    candidates.sort(key=lambda path: path.stat().st_mtime, reverse=True)
    items = []
    for path in candidates[:effective_limit]:
        items.append(
            {
                "relative_path": str(path.relative_to(trace_dir)),
                "absolute_path": str(path),
                "size_bytes": path.stat().st_size,
                "modified_epoch_sec": path.stat().st_mtime,
            }
        )
    return {"trace_dir": str(trace_dir), "items": items}


@router.get("/xml/trace/file")
async def xml_trace_file(
    path: str = Query(..., description="Relative path under the configured trace directory"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
):
    await ensure_auth(x_agent_key, settings)
    trace_dir = settings.resolved_tally_xml_trace_dir().resolve()
    requested_path = (trace_dir / path).resolve()
    try:
        requested_path.relative_to(trace_dir)
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "Trace file path must stay inside the configured trace directory.",
            },
        ) from exc
    if not requested_path.is_file():
        raise HTTPException(
            status_code=404,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "not_found",
                "reason": "Requested trace file does not exist.",
            },
        )
    return {
        "trace_dir": str(trace_dir),
        "relative_path": str(requested_path.relative_to(trace_dir)),
        "absolute_path": str(requested_path),
        "content": requested_path.read_text(encoding="utf-8", errors="replace"),
    }


def _tally_access_sample_xml() -> Dict[str, str]:
    return {
        "company_list": build_company_list(),
        "company_open": build_company_open("Software Development"),
        "companies_report": build_report_export("List of Companies"),
        "day_book": build_report_export(
            "Day Book",
            static_variables={"SVEXPORTFORMAT": "$$SysName:XML"},
        ),
    }


@router.get("/xml/access/steps")
async def xml_access_steps(
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
):
    await ensure_auth(x_agent_key, settings)
    trace_dir = settings.resolved_tally_xml_trace_dir()
    return {
        "tally_base_url": str(settings.tally_base_url),
        "trace_enabled": bool(settings.tally_xml_trace_enabled),
        "trace_dir": str(trace_dir),
        "steps": [
            {
                "step": 1,
                "name": "connector-health",
                "method": "GET",
                "path": "/health",
                "purpose": "Verify the local connector is running.",
            },
            {
                "step": 2,
                "name": "trace-status",
                "method": "GET",
                "path": "/xml/trace/status",
                "purpose": "Verify XML request/response tracing is enabled.",
            },
            {
                "step": 3,
                "name": "company-list",
                "method": "GET",
                "path": "/companies/list",
                "purpose": "Discover companies visible to local TallyPrime.",
            },
            {
                "step": 4,
                "name": "company-open",
                "method": "POST",
                "path": "/companies/open",
                "purpose": "Open the target company inside local TallyPrime.",
            },
            {
                "step": 5,
                "name": "raw-xml-execute",
                "method": "POST",
                "path": "/xml/execute",
                "purpose": "Send any raw Tally XML envelope directly.",
            },
            {
                "step": 6,
                "name": "trace-files",
                "method": "GET",
                "path": "/xml/trace/files",
                "purpose": "Inspect all saved XML request and response files.",
            },
            {
                "step": 7,
                "name": "trace-file-read",
                "method": "GET",
                "path": "/xml/trace/file",
                "purpose": "Read one captured XML trace file by relative path.",
            },
        ],
    }


@router.get("/xml/access/samples")
async def xml_access_samples(
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
):
    await ensure_auth(x_agent_key, settings)
    return {
        "tally_base_url": str(settings.tally_base_url),
        "samples": _tally_access_sample_xml(),
    }


@router.get("/xml/access/check")
async def xml_access_check(
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    result: Dict[str, Any] = {
        "status": "ok",
        "tally_base_url": str(settings.tally_base_url),
        "trace_enabled": bool(settings.tally_xml_trace_enabled),
        "trace_dir": str(settings.resolved_tally_xml_trace_dir()),
        "checks": {},
    }

    result["checks"]["connector_health"] = {"ok": True}
    result["checks"]["trace_status"] = {
        "ok": bool(settings.tally_xml_trace_enabled),
        "trace_dir_exists": settings.resolved_tally_xml_trace_dir().exists(),
    }

    try:
        companies_payload = await _post_xml(client, build_company_list(), "summary")
        result["checks"]["company_list"] = {
            "ok": True,
            "response": companies_payload,
        }
    except HTTPException as exc:
        result["status"] = "degraded"
        result["checks"]["company_list"] = {
            "ok": False,
            "status_code": exc.status_code,
            "detail": exc.detail,
        }

    try:
        export_payload = await _post_xml(
            client,
            build_report_export("List of Companies", static_variables={"SVEXPORTFORMAT": "$$SysName:XML"}),
            "raw",
        )
        result["checks"]["raw_xml_execute"] = {
            "ok": True,
            "response": export_payload,
        }
    except HTTPException as exc:
        result["status"] = "degraded"
        result["checks"]["raw_xml_execute"] = {
            "ok": False,
            "status_code": exc.status_code,
            "detail": exc.detail,
        }

    return result


async def _export_master(
    client: TallyClient,
    company_name: str,
    master_type: str,
    fetch: Optional[List[str]] = None,
    filter_expr: Optional[str] = None,
    limit: Optional[int] = None,
    view: str = "summary",
    collection_name: Optional[str] = None,
    settings: Optional[Settings] = None,
    route_path: Optional[str] = None,
) -> Dict[str, Any]:
    normalized_master_type = (master_type or "").strip().lower()
    normalized_view = _normalize_view(view)
    effective_collection_name = collection_name or _caller_collection_name(_master_collection_prefix(normalized_master_type))

    if normalized_master_type == "price list":
        effective_fetch = (
            _full_fetch(fetch, extra_fields=PRICE_LIST_DETAIL_FIELDS)
            if _needs_full_fetch(normalized_view)
            else list(fetch or PRICE_STRUCTURE_FETCH.copy())
        )
        try:
            xml_req = build_master_list(
                company_name,
                "stock item",
                effective_fetch,
                filter_expr,
                limit,
                collection_name=effective_collection_name,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        return {
            "source": "stock-item.price-lists",
            "items": await _post_xml(client, xml_req, normalized_view),
        }

    if normalized_master_type == "bom":
        effective_fetch = (
            _full_fetch(fetch, extra_fields=["COMPONENTLIST.*", "MULTICOMPONENTLIST.*"])
            if _needs_full_fetch(normalized_view)
            else list(fetch or BOM_FETCH.copy())
        )
        try:
            xml_req = build_master_list(
                company_name,
                "stock item",
                effective_fetch,
                filter_expr,
                limit,
                collection_name=effective_collection_name,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        if _needs_full_fetch(normalized_view):
            return {
                "source": "stock-item.bom",
                "items": await _post_xml(client, xml_req, normalized_view),
            }
        return await _post_xml(client, xml_req, normalized_view)

    if normalized_master_type == "pay head":
        if (
            settings is not None
            and settings.tdl_integration_enabled
            and _tdl_feature_is_ready(settings, "payroll_exports")
            and not fetch
            and not filter_expr
            and limit is None
            and normalized_view == "summary"
            and route_path == "/pay-heads"
        ):
            return await _export_tdl_payroll_route(
                client,
                settings,
                company_name,
                "/pay-heads",
                normalized_view,
            )
        if fetch or filter_expr or limit or _needs_full_fetch(normalized_view):
            extra_fields = [
                "PAYHEADTYPE",
                "FORPAYROLL",
                "CALCULATIONTYPE",
                "AFFECTSNETSALARY",
                "NAMEINPAYSLIP",
                "USEFORGRATUITY",
                "USEFORBONUS",
                "ROUNDINGMETHOD",
                "ROUNDOFFLIMIT",
                "ISCOSTCENTRESON",
            ]
            effective_fetch = (
                _full_fetch(fetch, extra_fields=extra_fields)
                if _needs_full_fetch(normalized_view)
                else list(dict.fromkeys(list(fetch or LIGHT_LEDGER_FETCH) + extra_fields))
            )
            try:
                xml_req = build_master_list(
                    company_name,
                    "ledger",
                    effective_fetch,
                    filter_expr,
                    limit,
                    collection_name=effective_collection_name,
                )
            except ValueError as exc:
                raise HTTPException(status_code=400, detail=str(exc))
            response = await _post_xml(client, xml_req, normalized_view)
        else:
            xml_req = build_collection_export("Payroll Ledgers", company_name)
            response = await _post_xml(client, xml_req, normalized_view)
        if _is_metadata_only_collection(response):
            raise HTTPException(status_code=501, detail=_payroll_collection_unavailable_detail("pay head"))
        return _filter_pay_head_exports(response)

    if normalized_master_type == "employee group":
        extra_fields = ["CATEGORY", "FORPAYROLL", "ISEMPLOYEEGROUP", "GRPATTENDANCE", "GRPPAYHEAD"]
        effective_fetch = (
            _full_fetch(fetch, extra_fields=extra_fields)
            if _needs_full_fetch(normalized_view)
            else list(dict.fromkeys(list(fetch or LIGHT_COST_CENTRE_FETCH) + extra_fields))
        )
        try:
            xml_req = build_master_list(
                company_name,
                "cost centre",
                effective_fetch,
                filter_expr,
                limit,
                collection_name=effective_collection_name,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        response = await _post_xml(client, xml_req, normalized_view)
        return _filter_employee_group_exports(response)

    special_collection_id = SPECIAL_COLLECTION_EXPORTS.get(normalized_master_type)
    if special_collection_id:
        if (
            settings is not None
            and settings.tdl_integration_enabled
            and _tdl_feature_is_ready(settings, "payroll_exports")
            and not fetch
            and not filter_expr
            and limit is None
            and normalized_view == "summary"
            and route_path == "/attendance-types"
        ):
            return await _export_tdl_payroll_route(
                client,
                settings,
                company_name,
                "/attendance-types",
                normalized_view,
            )
        if fetch or filter_expr or limit:
            raise HTTPException(
                status_code=400,
                detail={
                    "supported": True,
                    "fallback_applied": False,
                    "error_type": "validation",
                    "reason": (
                        f"{master_type} export uses the built-in Tally collection "
                        f"'{special_collection_id}', which does not support generic "
                        "fetch/filter/limit overrides in this connector path."
                    ),
                },
            )
        xml_req = build_collection_export(special_collection_id, company_name)
        response = await _post_xml(client, xml_req, normalized_view)
        if normalized_master_type in PAYROLL_MASTERS and _is_metadata_only_collection(response):
            raise HTTPException(status_code=501, detail=_payroll_collection_unavailable_detail(normalized_master_type))
        return response

    unsupported_reason = UNSUPPORTED_MASTER_EXPORTS.get(normalized_master_type)
    if unsupported_reason:
        unsupported_response(normalized_master_type, unsupported_reason)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else fetch
    try:
        xml_req = build_master_list(
            company_name,
            master_type,
            effective_fetch,
            filter_expr,
            limit,
            collection_name=effective_collection_name,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    response = await _post_xml(client, xml_req, normalized_view)
    if normalized_master_type == "currency" and normalized_view != "raw":
        return _normalize_currency_exports(response)
    return response


async def _upsert_master(
    client: TallyClient,
    company_name: str,
    master_type: str,
    payload: Dict[str, Any],
    action: str = "Alter",
) -> Dict[str, Any]:
    try:
        xml_req = build_master_upsert(
            await _preferred_write_company_context(client, company_name),
            master_type,
            action,
            payload,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return await _post_xml(client, xml_req)


async def _update_company_settings(
    client: TallyClient,
    company_name: str,
    payload: Dict[str, Any],
    action: str = "Alter",
) -> Dict[str, Any]:
    normalized = dict(payload)
    normalized.setdefault("NAME", company_name)
    try:
        xml_req = build_company_update(
            await _preferred_write_company_context(client, company_name),
            action,
            normalized,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return await _post_xml(client, xml_req)


def _normalize_tally_date(value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    text = value.strip()
    if len(text) == 10 and text[4] == "-" and text[7] == "-":
        return text.replace("-", "")
    return text


def _validate_company_create_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = _normalize_company_write_payload(payload)
    company_name = _first_non_empty_string(
        normalized.get("NAME"),
        payload.get("NAME") if isinstance(payload, dict) else None,
        payload.get("name") if isinstance(payload, dict) else None,
    )
    if not company_name:
        raise HTTPException(status_code=400, detail="NAME is required")
    normalized["NAME"] = company_name

    for field_name in ("FINANCIALYEARFROM", "BOOKSFROM", "STARTINGFROM"):
        raw_value = normalized.get(field_name)
        normalized_value = _normalize_tally_date(str(raw_value)) if raw_value is not None else None
        if not normalized_value or len(normalized_value) != 8 or not normalized_value.isdigit():
            raise HTTPException(
                status_code=422,
                detail={
                    "supported": True,
                    "fallback_applied": False,
                    "error_type": "validation",
                    "reason": (
                        f"{field_name} must be in YYYYMMDD or YYYY-MM-DD format for company creation."
                    ),
                },
            )
        normalized[field_name] = normalized_value

    return normalized


def _validate_pay_head_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    normalized = _normalize_pay_head_payload(payload)
    parent_name = _first_non_empty_string(
        normalized.get("PARENT"),
        payload.get("PARENT") if isinstance(payload, dict) else None,
    )
    if not parent_name:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "resource": "pay head",
                "fallback_applied": False,
                "error_type": "validation",
                "reason": (
                    "PARENT is required for pay-head writes. Use an existing Tally group such as "
                    "'Indirect Expenses', 'Direct Expenses', or another verified payroll ledger parent."
                ),
            },
        )
    if parent_name.strip().lower() == "primary":
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "resource": "pay head",
                "fallback_applied": False,
                "error_type": "validation",
                "reason": (
                    "Parent group 'Primary' is not valid for pay-head writes in this Tally build. "
                    "Use a real ledger group such as 'Indirect Expenses', 'Direct Expenses', or "
                    "another existing payroll-compatible parent."
                ),
                "what_to_do": [
                    "Use an existing ledger group such as 'Indirect Expenses' when appropriate.",
                    "Avoid sending the root placeholder group 'Primary' for pay-head imports.",
                ],
            },
        )
    return normalized


def _validate_employee_payload(payload: Dict[str, Any], action: str) -> Dict[str, Any]:
    normalized = _normalize_employee_payload(payload)
    normalized_action = (action or "Create").strip().lower()
    if normalized_action not in {"create", "alter"}:
        return normalized

    employee_name = _first_non_empty_string(
        normalized.get("NAME"),
        payload.get("NAME") if isinstance(payload, dict) else None,
    )
    if not employee_name:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "resource": "employee",
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "NAME is required for employee Create/Alter requests.",
            },
        )
    normalized["NAME"] = employee_name

    parent_name = _first_non_empty_string(
        normalized.get("PARENT"),
        payload.get("PARENT") if isinstance(payload, dict) else None,
    )
    if not parent_name or parent_name.strip().lower() == "primary":
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "resource": "employee",
                "fallback_applied": False,
                "error_type": "validation",
                "reason": (
                    "PARENT must be an existing Employee Group for employee Create/Alter requests. "
                    "The root placeholder group 'Primary' is not valid here."
                ),
                "what_to_do": [
                    "Create or choose a real Employee Group first.",
                    "Then send that Employee Group name in PARENT.",
                ],
            },
        )
    normalized["PARENT"] = parent_name
    return normalized


def _validate_employee_group_payload(payload: Dict[str, Any], action: str) -> Dict[str, Any]:
    normalized = _normalize_employee_group_payload(payload)
    normalized_action = (action or "Create").strip().lower()
    if normalized_action not in {"create", "alter"}:
        return normalized

    group_name = _first_non_empty_string(
        normalized.get("NAME"),
        payload.get("NAME") if isinstance(payload, dict) else None,
    )
    if not group_name:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "resource": "employee group",
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "NAME is required for employee-group Create/Alter requests.",
            },
        )
    normalized["NAME"] = group_name
    return normalized


def _validate_attendance_type_payload(payload: Dict[str, Any], action: str) -> Dict[str, Any]:
    normalized = _normalize_attendance_type_payload(payload)
    normalized_action = (action or "Create").strip().lower()
    if normalized_action not in {"create", "alter"}:
        return normalized

    attendance_type_name = _first_non_empty_string(
        normalized.get("NAME"),
        payload.get("NAME") if isinstance(payload, dict) else None,
    )
    if not attendance_type_name:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "resource": "attendance type",
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "NAME is required for attendance-type Create/Alter requests.",
            },
        )
    normalized["NAME"] = attendance_type_name
    return normalized


def _first_non_blank_string(*vals: Any) -> str:
    for value in vals:
        if isinstance(value, str):
            text = value.strip()
            if text:
                return text
    return ""


def _extract_company_name(payload: Any) -> Optional[str]:
    if isinstance(payload, str):
        return payload.strip() or None
    if isinstance(payload, list):
        for item in payload:
            discovered = _extract_company_name(item)
            if discovered:
                return discovered
        return None
    if not isinstance(payload, dict):
        return None

    if "COMPANY" in payload:
        discovered = _extract_company_name(payload.get("COMPANY"))
        if discovered:
            return discovered

    for key in ("NAME", "@NAME", "RESERVEDNAME"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, list):
            for item in value:
                if isinstance(item, str) and item.strip():
                    return item.strip()
    return None


def _company_names_match(left: Optional[str], right: Optional[str]) -> bool:
    if not isinstance(left, str) or not isinstance(right, str):
        return False
    return left.strip().lower() == right.strip().lower()


def _payload_contains_company_name(payload: Any, company_name: Optional[str]) -> bool:
    if not isinstance(company_name, str) or not company_name.strip():
        return False
    target = company_name.strip()
    if isinstance(payload, str):
        return _company_names_match(payload, target)
    if isinstance(payload, list):
        return any(_payload_contains_company_name(item, target) for item in payload)
    if not isinstance(payload, dict):
        return False

    discovered = _extract_company_name(payload)
    if _company_names_match(discovered, target):
        return True
    return any(_payload_contains_company_name(value, target) for value in payload.values())


def _payload_contains_text(payload: Any, text: str) -> bool:
    target = (text or "").strip().lower()
    if not target:
        return False
    if isinstance(payload, str):
        return target in payload.strip().lower()
    if isinstance(payload, list):
        return any(_payload_contains_text(item, target) for item in payload)
    if not isinstance(payload, dict):
        return False
    return any(_payload_contains_text(value, target) for value in payload.values())


def _is_tdl_scaffold_placeholder_response(payload: Any) -> bool:
    markers = (
        "this is a scaffold tdl package for the local connector",
        "implement the required tdl collections/reports before enabling tdl_integration_enabled",
        "add real collections, reports, or functions for the connector endpoints",
    )
    return any(_payload_contains_text(payload, marker) for marker in markers)


async def _current_company_name_hint(client: TallyClient) -> Optional[str]:
    try:
        payload = await asyncio.wait_for(
            _export_master(
                client,
                None,
                "company",
                fetch=["NAME", "RESERVEDNAME"],
                limit=1,
                view="summary",
            ),
            timeout=2.5,
        )
    except Exception:
        return None
    return _extract_company_name(payload)


async def _company_visible_in_list(client: TallyClient, company_name: Optional[str]) -> Optional[bool]:
    if not isinstance(company_name, str) or not company_name.strip():
        return None
    try:
        payload = await asyncio.wait_for(
            _post_xml(client, build_company_list(["NAME", "RESERVEDNAME"]), "summary"),
            timeout=3.0,
        )
    except Exception:
        return None

    if _is_null_envelope_response(payload):
        return False
    return _payload_contains_company_name(payload, company_name)


async def _preferred_write_company_context(
    client: TallyClient,
    company_name: Optional[str],
) -> Optional[str]:
    if not isinstance(company_name, str) or not company_name.strip():
        return None
    active_company_name = await _current_company_name_hint(client)
    if _company_names_match(active_company_name, company_name):
        return None
    return company_name


async def _ensure_active_company_for_builtin_report(
    client: TallyClient,
    report_name: str,
    company_name: Optional[str],
) -> None:
    if not isinstance(company_name, str) or not company_name.strip():
        return
    active_company_name = await _current_company_name_hint(client)
    if active_company_name is None or _company_names_match(active_company_name, company_name):
        return
    raise HTTPException(
        status_code=409,
        detail={
            "supported": False,
            "resource": report_name,
            "fallback_applied": False,
            "error_type": "active_company_mismatch",
            "reason": (
                f"Built-in report '{report_name}' can be exported only for the currently active "
                "Tally company in this connector/Tally setup."
            ),
            "requested_company": company_name,
            "active_company": active_company_name,
            "what_to_do": [
                f"Open '{company_name}' in TallyPrime and retry the report.",
                "Or switch the UI/backend selection to the currently active Tally company.",
            ],
        },
    )


def _import_result_counter(payload: Dict[str, Any], key: str) -> int:
    if not isinstance(payload, dict):
        return 0
    response = payload.get("RESPONSE") or payload.get("IMPORTRESULT")
    if not isinstance(response, dict):
        return 0
    raw_value = response.get(key, "0")
    try:
        return int(str(raw_value))
    except ValueError:
        return 0


def _price_level_unsupported_detail() -> Dict[str, Any]:
    return {
        "supported": False,
        "resource": "price level",
        "fallback_applied": False,
        "error_type": "unsupported",
        "reason": (
            "Standalone price levels are maintained from Tally's company inventory features screen, "
            "not through a verified standalone XML master-import path in this connector. "
            "The generic PRICELEVEL master upsert is ignored in the current Tally build, and a "
            "stable company-feature XML field path for creating or renaming price-level names has "
            "not been verified yet."
        ),
        "use_instead": "POST /price-lists",
        "required_fields": ["ITEMNAME", "PRICELEVEL"],
        "note": (
            "If you send ITEMNAME/STOCKITEMNAME together with PRICELEVEL (or NAME on the "
            "/price-levels route), the connector can apply that price level through the "
            "stock item's PRICELEVELLIST instead of the unsupported master path."
        ),
        "manual_steps": [
            "In Tally, open F11 > Inventory Features and enable multiple price levels.",
            "Create or rename the company price-level names in the Company Price Levels screen.",
            "Then use POST /price-lists to persist item-wise rates and discounts for that level.",
        ],
    }


def _price_levels_unavailable_detail(
    reason: str,
    *,
    action: Optional[str] = None,
    response: Optional[Any] = None,
) -> Dict[str, Any]:
    detail: Dict[str, Any] = {
        "supported": False,
        "resource": "/price-levels",
        "fallback_applied": False,
        "error_type": "unsupported",
        "required_tdl_feature": "price_levels",
        "requested_action": action,
        "reason": reason,
        "what_to_do": [
            "Implement a dedicated TDL collection/report for standalone company price levels and mark the feature ready in tdl/manifest.json.",
            "For item-linked rates and discount rows, use POST /price-lists with ITEMNAME and PRICELEVEL.",
            "Do not rely on scaffold TDL placeholders or generic PRICELEVEL master imports for this Tally build.",
        ],
    }
    if response is not None:
        detail["tally_response"] = response
    return detail


def _tds_outstandings_unavailable_detail(
    reason: str,
    *,
    response: Optional[Any] = None,
) -> Dict[str, Any]:
    detail: Dict[str, Any] = {
        "supported": False,
        "resource": "/reports/tds-outstandings",
        "fallback_applied": False,
        "error_type": "unsupported",
        "required_tdl_feature": "tds_outstandings",
        "reason": reason,
        "what_to_do": [
            "Implement a real TDL report for TDS Outstandings and mark the feature ready in tdl/manifest.json.",
            "Do not rely on scaffold TDL placeholders for statutory report support.",
            "If the native XML report remains unavailable in this Tally build, keep using the TDL-backed route.",
        ],
    }
    if response is not None:
        detail["tally_response"] = response
    return detail


def _security_roles_unavailable_detail(reason: str, *, action: Optional[str] = None, response: Optional[Any] = None) -> Dict[str, Any]:
    detail: Dict[str, Any] = {
        "supported": False,
        "resource": "/settings/security-roles",
        "fallback_applied": False,
        "error_type": "unsupported",
        "requested_action": action,
        "reason": reason,
        "what_to_do": [
            "Implement a dedicated TDL collection/report for security roles and mark the feature ready in tdl/manifest.json.",
            "Do not rely on generic SECURITYLEVEL master import/export paths for this Tally build.",
            "After wiring the TDL package, restart the connector and re-check /health/readiness and /capabilities.",
        ],
    }
    if response is not None:
        detail["tally_response"] = response
    return detail


def _price_structure_write_no_effect_detail(
    resource: str,
    item_name: str,
    price_level: str,
    response: Dict[str, Any],
    *,
    delete_requested: bool = False,
) -> Dict[str, Any]:
    if delete_requested:
        reason = (
            "Tally acknowledged the delete request, but the Price List export still "
            "shows a persisted row for this item/price level."
        )
    else:
        reason = (
            "Tally acknowledged the stock item update, but the Price List export still "
            "does not show any persisted price-list rows for this item/price level. "
            "This write shape is not producing a real readable price-list entry in the current Tally build."
        )
    return {
        "supported": False,
        "fallback_applied": True,
        "error_type": "write_no_effect",
        "resource": resource,
        "item_name": item_name,
        "price_level": price_level,
        "reason": reason,
        "tally_response": response,
        "use_instead": "Verify manually in Tally Price List screen before treating this as successful.",
    }

def _compose_price_rate(rate: Any, unit: Any) -> Any:
    if rate is None:
        return None
    if isinstance(rate, str):
        text = rate.strip()
        if not text:
            return None
        if "/" in text:
            return text
        if isinstance(unit, str) and unit.strip():
            return f"{text}/{unit.strip()}"
        return text
    if isinstance(unit, str) and unit.strip():
        return f"{rate}/{unit.strip()}"
    return rate


def _today_tally_date() -> str:
    return date.today().strftime("%Y%m%d")


def _string_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    return str(value).strip()


def _price_band_boundary(payload: Dict[str, Any], *names: str) -> str:
    for name in names:
        if name in payload:
            return _string_value(payload.get(name))
    return ""


def _normalize_price_list_request(
    payload: Dict[str, Any],
    *,
    allow_price_level_name_alias: bool = False,
) -> Dict[str, Any]:
    item_name = _first_non_blank_string(payload.get("ITEMNAME"), payload.get("STOCKITEMNAME"))
    price_level = _first_non_blank_string(
        payload.get("PRICELEVEL"),
        payload.get("LEVELNAME"),
        payload.get("NAME") if allow_price_level_name_alias else None,
    )
    if not item_name or not price_level:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "ITEMNAME and PRICELEVEL are required to update a price list.",
            },
        )

    normalized_date = payload.get("FROMDATE")
    if normalized_date is None:
        normalized_date = payload.get("DATE")
    normalized_date_text = _normalize_tally_date(_string_value(normalized_date)) if normalized_date not in (None, "") else ""

    price_rate = _compose_price_rate(payload.get("RATE"), payload.get("UNIT"))
    discount_present = "DISCOUNT" in payload
    discount_value = payload.get("DISCOUNT") if discount_present else None

    return {
        "item_name": item_name,
        "price_level": price_level,
        "effective_date": normalized_date_text,
        "band": {
            "STARTINGFROM": _price_band_boundary(payload, "STARTINGFROM", "FROMQTY", "FROM"),
            "ENDINGAT": _price_band_boundary(payload, "ENDINGAT", "TOQTY", "LESSTHAN", "TO"),
            "RATE": price_rate,
            "DISCOUNT": discount_value,
        },
        "rate_or_discount_supplied": price_rate is not None or discount_present,
    }


def _price_band_key(payload: Dict[str, Any]) -> tuple[str, str]:
    return (
        _string_value(payload.get("STARTINGFROM")),
        _string_value(payload.get("ENDINGAT")),
    )


def _price_list_entry_level(entry: Dict[str, Any]) -> str:
    return _string_value(_extract_tally_text(entry.get("PRICELEVEL")) or entry.get("PRICELEVEL"))


def _price_list_entry_date(entry: Dict[str, Any]) -> str:
    raw_date = _extract_tally_text(entry.get("DATE")) or entry.get("DATE")
    return _normalize_tally_date(_string_value(raw_date)) or ""


def _price_list_band_rows(entry: Dict[str, Any]) -> List[Dict[str, Any]]:
    rows = [copy.deepcopy(row) for row in _listify(entry.get("PRICELEVELLIST.LIST")) if isinstance(row, dict)]
    if rows:
        return rows

    direct_row_keys = {"STARTINGFROM", "ENDINGAT", "RATE", "DISCOUNT"}
    if any(key in entry for key in direct_row_keys):
        return [{
            key: copy.deepcopy(entry[key])
            for key in direct_row_keys
            if key in entry
        }]
    return []


def _price_band_key_from_row(row: Dict[str, Any]) -> tuple[str, str]:
    return (
        _string_value(_extract_tally_text(row.get("STARTINGFROM")) or row.get("STARTINGFROM")),
        _string_value(_extract_tally_text(row.get("ENDINGAT")) or row.get("ENDINGAT")),
    )


def _update_price_band_row(row: Dict[str, Any], desired_band: Dict[str, Any]) -> Dict[str, Any]:
    updated = copy.deepcopy(row)
    updated["STARTINGFROM"] = desired_band.get("STARTINGFROM", "")
    updated["ENDINGAT"] = desired_band.get("ENDINGAT", "")
    if desired_band.get("RATE") is not None:
        updated["RATE"] = desired_band["RATE"]
    if desired_band.get("DISCOUNT") is not None:
        updated["DISCOUNT"] = desired_band["DISCOUNT"]
    return updated


def _new_price_band_row(desired_band: Dict[str, Any]) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "STARTINGFROM": desired_band.get("STARTINGFROM", ""),
        "ENDINGAT": desired_band.get("ENDINGAT", ""),
    }
    if desired_band.get("RATE") is not None:
        row["RATE"] = desired_band["RATE"]
    if desired_band.get("DISCOUNT") is not None:
        row["DISCOUNT"] = desired_band["DISCOUNT"]
    return row


def _merge_price_list_entries(
    existing_entries: List[Dict[str, Any]],
    desired: Dict[str, Any],
    action: str,
) -> List[Dict[str, Any]]:
    normalized_action = (action or "Alter").strip().lower()
    merged_entries = [copy.deepcopy(entry) for entry in existing_entries if isinstance(entry, dict)]
    target_level = desired["price_level"].strip().lower()
    target_date = desired["effective_date"]
    target_band_key = _price_band_key(desired["band"])
    delete_specific_band = any(part for part in target_band_key)

    if normalized_action in {"create", "alter"} and not desired["rate_or_discount_supplied"]:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "RATE or DISCOUNT is required to create or alter a price list row.",
            },
        )

    effective_date = target_date or _today_tally_date()

    if normalized_action == "delete":
        removed_any = False
        filtered_entries: List[Dict[str, Any]] = []
        for entry in merged_entries:
            if _price_list_entry_level(entry).lower() != target_level:
                filtered_entries.append(entry)
                continue
            if target_date and _price_list_entry_date(entry) != target_date:
                filtered_entries.append(entry)
                continue

            if not delete_specific_band:
                removed_any = True
                continue

            rows = _price_list_band_rows(entry)
            kept_rows = [row for row in rows if _price_band_key_from_row(row) != target_band_key]
            if len(kept_rows) != len(rows):
                removed_any = True
            if kept_rows:
                updated_entry = copy.deepcopy(entry)
                updated_entry["PRICELEVELLIST.LIST"] = kept_rows
                filtered_entries.append(updated_entry)
        if not removed_any:
            raise HTTPException(
                status_code=404,
                detail={
                    "supported": True,
                    "fallback_applied": False,
                    "error_type": "not_found",
                    "resource": "price list",
                    "reason": "No persisted price-list row matched the supplied item, price level, and quantity band.",
                },
            )
        return filtered_entries

    target_entry: Optional[Dict[str, Any]] = None
    for entry in merged_entries:
        if _price_list_entry_level(entry).lower() != target_level:
            continue
        if _price_list_entry_date(entry) != effective_date:
            continue
        target_entry = entry
        break

    if target_entry is None:
        merged_entries.append(
            {
                "PRICELEVEL": desired["price_level"],
                "DATE": effective_date,
                "PRICELEVELLIST.LIST": [_new_price_band_row(desired["band"])],
            }
        )
        return merged_entries

    rows = _price_list_band_rows(target_entry)
    updated_rows: List[Dict[str, Any]] = []
    matched_band = False
    for row in rows:
        if _price_band_key_from_row(row) == target_band_key:
            updated_rows.append(_update_price_band_row(row, desired["band"]))
            matched_band = True
        else:
            updated_rows.append(row)
    if not matched_band:
        updated_rows.append(_new_price_band_row(desired["band"]))
    target_entry["PRICELEVEL"] = desired["price_level"]
    target_entry["DATE"] = effective_date
    target_entry["PRICELEVELLIST.LIST"] = updated_rows
    return merged_entries


def _tally_formula_literal(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


async def _export_stock_item_price_lists(
    client: TallyClient,
    company_name: str,
    item_name: str,
) -> Dict[str, Any]:
    return await _export_master(
        client,
        company_name,
        "stock item",
        PRICE_STRUCTURE_FETCH,
        filter_expr=f'$Name = "{_tally_formula_literal(item_name)}"',
        limit=1,
        view="full",
        collection_name=_collection_name("PYSTOCK", f"price_list_write_{item_name}"),
    )


async def _write_stock_item_price_lists(
    client: TallyClient,
    company_name: str,
    item_name: str,
    full_price_list_entries: List[Dict[str, Any]],
) -> Dict[str, Any]:
    try:
        xml_req = build_stock_item_price_list_upsert(
            company_name,
            item_name,
            full_price_list_entries,
            action="Alter",
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return await _post_xml(client, xml_req)


async def _upsert_price_list(
    client: TallyClient,
    company_name: str,
    payload: Dict[str, Any],
    action: str = "Alter",
    *,
    allow_price_level_name_alias: bool = False,
) -> Dict[str, Any]:
    desired = _normalize_price_list_request(
        payload,
        allow_price_level_name_alias=allow_price_level_name_alias,
    )
    item_name = desired["item_name"]
    price_level = desired["price_level"]
    export_payload = await _export_stock_item_price_lists(client, company_name, item_name)
    stock_items = _listify_master_entries(export_payload, "STOCKITEM")
    matching_stock_item = next(
        (stock_item for stock_item in stock_items if _stock_item_name_matches(stock_item, item_name)),
        None,
    )
    if matching_stock_item is None:
        raise HTTPException(
            status_code=404,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "not_found",
                "resource": "stock item",
                "reason": f"Stock item '{item_name}' was not found in the selected company.",
            },
        )

    existing_entries = [
        copy.deepcopy(entry)
        for entry in _listify(matching_stock_item.get("FULLPRICELIST.LIST"))
        if isinstance(entry, dict)
    ]
    merged_entries = _merge_price_list_entries(existing_entries, desired, action)
    response = await _write_stock_item_price_lists(client, company_name, item_name, merged_entries)
    if _is_tally_noop_response(response):
        raise HTTPException(
            status_code=501,
            detail=_price_structure_write_no_effect_detail(
                "price list",
                item_name,
                price_level,
                response,
            ),
        )
    normalized_action = (action or "Alter").strip().lower()
    if normalized_action in {"create", "alter"}:
        if not await _price_list_write_visible_in_export(
            client,
            company_name,
            item_name,
            price_level,
            effective_date=desired["effective_date"] or _today_tally_date(),
            band=desired["band"],
        ):
            raise HTTPException(
                status_code=501,
                detail=_price_structure_write_no_effect_detail(
                    "price list",
                    item_name,
                    price_level,
                    response,
                ),
            )
    if normalized_action == "delete":
        if await _price_list_write_visible_in_export(
            client,
            company_name,
            item_name,
            price_level,
            effective_date=desired["effective_date"],
            band=desired["band"] if any(_price_band_key(desired["band"])) else None,
        ):
            raise HTTPException(
                status_code=501,
                detail=_price_structure_write_no_effect_detail(
                    "price list",
                    item_name,
                    price_level,
                    response,
                    delete_requested=True,
                ),
            )
    return response


async def _upsert_price_level(
    client: TallyClient,
    company_name: str,
    payload: Dict[str, Any],
    action: str = "Alter",
    *,
    settings: Optional[Settings] = None,
) -> Dict[str, Any]:
    item_name = _first_non_blank_string(payload.get("ITEMNAME"), payload.get("STOCKITEMNAME"))
    price_level = _first_non_blank_string(payload.get("PRICELEVEL"), payload.get("LEVELNAME"), payload.get("NAME"))
    if item_name and price_level:
        return await _upsert_price_list(
            client,
            company_name,
            payload,
            action,
            allow_price_level_name_alias=True,
        )
    if settings and settings.tdl_integration_enabled:
        if not _tdl_feature_is_ready(settings, "price_levels"):
            raise HTTPException(
                status_code=501,
                detail=_tdl_feature_unavailable_detail(
                    settings,
                    "price_levels",
                    "/price-levels",
                    action=action or "Alter",
                ),
            )
        contract = _tdl_feature_contract(settings, "price_levels")
        report_name = (
            contract.get("write_report_name")
            or contract.get("report_name")
            or TDL_FEATURE_DEFAULTS["price_levels"]["write_report_name"]
        )
        xml_req = build_tdl_gateway_report(
            str(report_name),
            company=company_name,
            payload={**dict(payload or {}), "ACTION": action or "Alter"},
        )
        response = await _post_xml(client, xml_req, "full")
        if _is_tdl_scaffold_placeholder_response(response):
            raise HTTPException(
                status_code=501,
                detail=_price_levels_unavailable_detail(
                    "The configured TDL price-level write path is still returning the scaffold placeholder response, so live standalone price-level writes are not implemented yet.",
                    action=action or "Alter",
                    response=response,
                ),
            )
        return {
            "status": "submitted",
            "mode": "tdl-report",
            "feature": "price_levels",
            "response": response,
        }
    raise HTTPException(status_code=501, detail=_price_level_unsupported_detail())


def _parse_yyyymmdd(value: str) -> date:
    text = _normalize_tally_date(value) or ""
    if len(text) != 8 or not text.isdigit():
        raise ValueError(f"Expected date in YYYYMMDD or YYYY-MM-DD format, got: {value}")
    return date(int(text[0:4]), int(text[4:6]), int(text[6:8]))


def _tally_display_date(value: str) -> str:
    parsed = _parse_yyyymmdd(value)
    month = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[parsed.month - 1]
    return f"{parsed.day}-{month}-{parsed.strftime('%y')}"


def _tally_jd(value: str) -> str:
    parsed = _parse_yyyymmdd(value)
    return str((parsed - date(1900, 1, 1)).days + 1)


def _require_string(payload: Dict[str, Any], *names: str) -> str:
    for name in names:
        value = payload.get(name)
        if isinstance(value, str) and value.strip():
            return value.strip()
    raise ValueError(f"{names[0]} is required")


def _listify(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def _empty_none(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: _empty_none(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_empty_none(v) for v in value]
    if value is None:
        return ""
    return value





_INVENTORY_CONSUMPTION_VOUCHER_TYPES = {
    "Stock Journal",
    "Material In",
    "Material Out",
    "Rejections In",
    "Rejections Out",
}


async def _first_available_stock_item(
    client: TallyClient,
    company_name: Optional[str],
) -> Dict[str, str]:
    stock_export = await _export_master(
        client,
        company_name or "",
        "stock item",
        ["NAME", "BASEUNITS"],
        limit=1,
        view="summary",
        collection_name=_collection_name("PYSTOCK", "purchase_write_stock_items"),
    )
    stock_items = _listify_master_entries(stock_export, "STOCKITEM")
    if not stock_items:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "purchase voucher",
                "fallback_applied": False,
                "error_type": "missing_stock_item",
                "reason": (
                    "Purchase voucher creation in this local Tally build needs at least one stock item, "
                    "but the connector could not find any usable stock item master."
                ),
                "what_to_do": (
                    "Create one stock item in Tally first, or use the stock-items API before retrying the Purchase voucher."
                ),
            },
        )
    stock_item = stock_items[0]
    stock_name = _first_non_empty_string(
        stock_item.get("NAME"),
        stock_item.get("@NAME"),
        _extract_tally_text(stock_item.get("NAME")),
        _extract_tally_text(stock_item.get("@NAME")),
        _first_non_empty_string(_coerce_mapping(stock_item.get("LANGUAGENAME"), "LANGUAGENAME").get("NAME")),
    )
    if not stock_name:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "purchase voucher",
                "fallback_applied": False,
                "error_type": "missing_stock_item",
                "reason": "The first exported stock item did not contain a usable NAME field.",
            },
        )
    base_units = _first_non_empty_string(
        stock_item.get("BASEUNITS"),
        stock_item.get("@BASEUNITS"),
        _extract_tally_text(stock_item.get("BASEUNITS")),
        _extract_tally_text(stock_item.get("@BASEUNITS")),
        "Not Applicable",
    ) or "Not Applicable"
    base_units = re.sub(r"[\x00-\x1f]+", " ", base_units).strip() or "Not Applicable"
    return {"name": stock_name, "base_units": base_units}


def _coerce_purchase_entry_list(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    raw_entries = payload.get("ALLLEDGERENTRIES.LIST")
    if raw_entries is None:
        raw_entries = payload.get("LEDGERENTRIES.LIST")
    entries = [entry for entry in _listify(raw_entries) if isinstance(entry, dict)]
    return [copy.deepcopy(entry) for entry in entries]


def _resolve_invoice_party_and_offset_entries(
    payload: Dict[str, Any],
) -> tuple[Optional[str], Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    entry_list = _coerce_purchase_entry_list(payload)
    if not entry_list:
        return None, None, None

    party_ledger_name = _first_non_empty_string(
        payload.get("PARTYLEDGERNAME"),
        payload.get("PARTYNAME"),
    )
    if not party_ledger_name:
        party_entry = next(
            (
                entry
                for entry in entry_list
                if _coerce_tally_yes_no(entry.get("ISPARTYLEDGER")) == "Yes"
                and _first_non_empty_string(entry.get("LEDGERNAME"))
            ),
            None,
        )
        party_ledger_name = _first_non_empty_string((party_entry or {}).get("LEDGERNAME"))
    if not party_ledger_name:
        return None, None, None

    party_entry = next(
        (
            entry
            for entry in entry_list
            if _first_non_empty_string(entry.get("LEDGERNAME")) == party_ledger_name
            or _coerce_tally_yes_no(entry.get("ISPARTYLEDGER")) == "Yes"
        ),
        None,
    )
    offset_entry = next(
        (
            entry
            for entry in entry_list
            if _first_non_empty_string(entry.get("LEDGERNAME"))
            and _first_non_empty_string(entry.get("LEDGERNAME")) != party_ledger_name
        ),
        None,
    )
    return party_ledger_name, party_entry, offset_entry


def _is_cash_or_bank_ledger_name(ledger_name: Optional[str]) -> bool:
    normalized = (ledger_name or "").strip().lower()
    return normalized in {"cash", "bank", "bank a/c", "bank account", "cash-in-hand"}


def _looks_like_sales_charge_ledger(ledger_name: Optional[str]) -> bool:
    normalized = (ledger_name or "").strip().lower()
    if not normalized:
        return False
    charge_tokens = (
        "gst",
        "cgst",
        "sgst",
        "igst",
        "cess",
        "tax",
        "vat",
        "freight",
        "round",
        "discount",
        "packing",
        "shipping",
        "delivery",
        "handling",
        "charge",
    )
    return any(token in normalized for token in charge_tokens)


def _resolve_sales_ledger_name(payload: Dict[str, Any], sales_entry: Optional[Dict[str, Any]]) -> Optional[str]:
    explicit_candidates: List[Optional[str]] = [
        _first_non_empty_string(
            payload.get("ACCOUNTINGLEDGERNAME"),
            payload.get("SALESLEDGERNAME"),
            payload.get("OFFSETLEDGERNAME"),
        )
    ]
    explicit_candidates.extend(
        _first_non_empty_string(entry.get("ACCOUNTINGLEDGERNAME"), entry.get("LEDGERNAME"))
        for entry in _listify(payload.get("ALLINVENTORYENTRIES.LIST"))
        if isinstance(entry, dict)
    )
    explicit_candidates.append(_first_non_empty_string((sales_entry or {}).get("LEDGERNAME")))

    for candidate in explicit_candidates:
        if candidate and not _is_cash_or_bank_ledger_name(candidate) and not _looks_like_sales_charge_ledger(candidate):
            return candidate

    raw_entries = _coerce_purchase_entry_list(payload)
    for raw_entry in raw_entries:
        ledger_name = _first_non_empty_string(raw_entry.get("LEDGERNAME"))
        if not ledger_name:
            continue
        if _coerce_tally_yes_no(raw_entry.get("ISPARTYLEDGER")) == "Yes":
            continue
        if _is_cash_or_bank_ledger_name(ledger_name) or _looks_like_sales_charge_ledger(ledger_name):
            continue
        return ledger_name

    return "Sales A/C"


def _ledger_export_name(ledger: Dict[str, Any]) -> Optional[str]:
    return _first_non_empty_string(
        ledger.get("NAME"),
        ledger.get("@NAME"),
        _extract_tally_text(ledger.get("NAME")),
        _extract_tally_text(ledger.get("@NAME")),
    )


def _sales_charge_ledger_parent(ledger_name: str) -> str:
    normalized = ledger_name.strip().lower()
    if any(token in normalized for token in ["gst", "cgst", "sgst", "igst", "cess", "tax", "vat"]):
        return "Duties & Taxes"
    return "Indirect Incomes"


def _sales_revenue_ledger_parent() -> str:
    return "Sales Accounts"


def _purchase_expense_ledger_parent() -> str:
    return "Purchase Accounts"


async def _ensure_sales_support_ledgers_exist(
    client: Optional[TallyClient],
    company_name: Optional[str],
    sales_ledger_name: Optional[str],
    ledger_names: List[str],
) -> None:
    if client is None or not company_name:
        return
    exported = await _export_master(
        client,
        company_name,
        "ledger",
        fetch=["NAME", "PARENT"],
        limit=5000,
        view="full",
        collection_name=_collection_name("PYLEDGER", "sales_charge_ledger_validation"),
    )
    existing_names = {
        name.strip().lower()
        for name in (_ledger_export_name(item) for item in _listify_master_entries(exported, "LEDGER"))
        if isinstance(name, str) and name.strip()
    }
    if sales_ledger_name:
        normalized_sales_name = sales_ledger_name.strip().lower()
        if normalized_sales_name and normalized_sales_name not in existing_names:
            await _upsert_master(
                client,
                company_name,
                "ledger",
                {
                    "NAME": sales_ledger_name,
                    "PARENT": _sales_revenue_ledger_parent(),
                },
                action="Create",
            )
            existing_names.add(normalized_sales_name)
    for ledger_name in ledger_names:
        normalized_name = ledger_name.strip().lower()
        if not normalized_name or normalized_name in existing_names:
            continue
        await _upsert_master(
            client,
            company_name,
            "ledger",
            {
                "NAME": ledger_name,
                "PARENT": _sales_charge_ledger_parent(ledger_name),
            },
            action="Create",
        )
        existing_names.add(normalized_name)


def _resolve_purchase_ledger_name(payload: Dict[str, Any], expense_entry: Optional[Dict[str, Any]]) -> Optional[str]:
    explicit_candidates: List[Optional[str]] = [
        _first_non_empty_string(
            payload.get("ACCOUNTINGLEDGERNAME"),
            payload.get("PURCHASELEDGERNAME"),
            payload.get("OFFSETLEDGERNAME"),
        )
    ]
    explicit_candidates.extend(
        _first_non_empty_string(entry.get("ACCOUNTINGLEDGERNAME"), entry.get("LEDGERNAME"))
        for entry in _listify(payload.get("ALLINVENTORYENTRIES.LIST"))
        if isinstance(entry, dict)
    )
    explicit_candidates.append(_first_non_empty_string((expense_entry or {}).get("LEDGERNAME")))

    for candidate in explicit_candidates:
        if candidate and not _is_cash_or_bank_ledger_name(candidate) and not _looks_like_sales_charge_ledger(candidate):
            return candidate

    raw_entries = _coerce_purchase_entry_list(payload)
    for raw_entry in raw_entries:
        ledger_name = _first_non_empty_string(raw_entry.get("LEDGERNAME"))
        if not ledger_name:
            continue
        if _coerce_tally_yes_no(raw_entry.get("ISPARTYLEDGER")) == "Yes":
            continue
        if _is_cash_or_bank_ledger_name(ledger_name) or _looks_like_sales_charge_ledger(ledger_name):
            continue
        return ledger_name

    return "Purchase A/C"


async def _ensure_purchase_support_ledgers_exist(
    client: Optional[TallyClient],
    company_name: Optional[str],
    purchase_ledger_name: Optional[str],
) -> None:
    if client is None or not company_name or not purchase_ledger_name:
        return
    exported = await _export_master(
        client,
        company_name,
        "ledger",
        fetch=["NAME", "PARENT"],
        limit=5000,
        view="full",
        collection_name=_collection_name("PYLEDGER", "purchase_expense_ledger_validation"),
    )
    existing_names = {
        name.strip().lower()
        for name in (_ledger_export_name(item) for item in _listify_master_entries(exported, "LEDGER"))
        if isinstance(name, str) and name.strip()
    }
    normalized_name = purchase_ledger_name.strip().lower()
    if normalized_name and normalized_name not in existing_names:
        await _upsert_master(
            client,
            company_name,
            "ledger",
            {
                "NAME": purchase_ledger_name,
                "PARENT": _purchase_expense_ledger_parent(),
            },
            action="Create",
        )


async def _prepare_sales_create_payload(
    client: TallyClient,
    company_name: Optional[str],
    payload: Dict[str, Any],
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return payload

    normalized = copy.deepcopy(payload)
    raw_inventory_entries = [entry for entry in _listify(normalized.get("ALLINVENTORYENTRIES.LIST")) if isinstance(entry, dict)]
    if not raw_inventory_entries:
        return normalized

    party_ledger_name, party_entry, sales_entry = _resolve_invoice_party_and_offset_entries(normalized)
    if not party_ledger_name or not sales_entry:
        return normalized

    sales_ledger_name = _resolve_sales_ledger_name(normalized, sales_entry)
    if not sales_ledger_name:
        return normalized

    raw_ledger_entries = _coerce_purchase_entry_list(normalized)
    fallback_stock_item: Optional[Dict[str, str]] = None
    built_inventory_entries: List[Dict[str, Any]] = []
    built_charge_entries: List[Dict[str, Any]] = []
    total_amount = Decimal("0")
    for raw_entry in raw_inventory_entries:
        stock_item_name = _first_non_empty_string(
            raw_entry.get("STOCKITEMNAME"),
            raw_entry.get("stock_item_name"),
        )
        actual_qty = _first_non_empty_string(raw_entry.get("ACTUALQTY"), raw_entry.get("BILLEDQTY"))
        billed_qty = _first_non_empty_string(raw_entry.get("BILLEDQTY"), actual_qty)
        rate = _first_non_empty_string(raw_entry.get("RATE"), raw_entry.get("rate"))
        amount_value = abs(_decimal_value(_first_present_value(raw_entry, "AMOUNT", "amount"), "AMOUNT"))

        if not stock_item_name or not actual_qty or not billed_qty or not rate:
            if fallback_stock_item is None:
                fallback_stock_item = await _first_available_stock_item(client, company_name)
            stock_item_name = stock_item_name or fallback_stock_item["name"]
            base_units = fallback_stock_item["base_units"]
            actual_qty = actual_qty or f"1 {base_units}"
            billed_qty = billed_qty or actual_qty
            rate = rate or f"{amount_value:.2f}/{base_units}"

        accounting_alloc = _build_sales_ready_ledger_entry(
            sales_ledger_name,
            amount_value,
            is_party=False,
            extra_fields={},
        )
        built_inventory_entries.append(
            {
                "STOCKITEMNAME": stock_item_name,
                "RATE": rate,
                "ACTUALQTY": actual_qty,
                "BILLEDQTY": billed_qty,
                "AMOUNT": f"{amount_value:.2f}",
                "ISDEEMEDPOSITIVE": "No",
                "ISLASTDEEMEDPOSITIVE": "No",
                "ISAUTONEGATE": "No",
                "BATCHALLOCATIONS.LIST": {
                    "GODOWNNAME": _first_non_empty_string(raw_entry.get("GODOWNNAME"), "Main Location"),
                    "BATCHNAME": _first_non_empty_string(raw_entry.get("BATCHNAME"), "Primary Batch"),
                    "AMOUNT": f"{amount_value:.2f}",
                    "ACTUALQTY": actual_qty,
                    "BILLEDQTY": billed_qty,
                },
                "ACCOUNTINGALLOCATIONS.LIST": accounting_alloc,
            }
        )
        total_amount += amount_value

    if not built_inventory_entries:
        return normalized

    requested_party_amount: Optional[Decimal] = None
    if isinstance(party_entry, dict):
        party_amount_value = _first_present_value(party_entry, "AMOUNT")
        if party_amount_value not in (None, ""):
            requested_party_amount = abs(_decimal_value(party_amount_value, "AMOUNT"))

    charge_ledger_names: List[str] = []
    for raw_entry in raw_ledger_entries:
        ledger_name = _first_non_empty_string(raw_entry.get("LEDGERNAME"))
        if not ledger_name:
            continue
        if ledger_name == party_ledger_name or ledger_name == sales_ledger_name:
            continue
        if _is_cash_or_bank_ledger_name(ledger_name):
            continue

        raw_amount = _first_present_value(raw_entry, "AMOUNT")
        if raw_amount in (None, ""):
            continue

        charge_amount = -_decimal_value(raw_amount, "AMOUNT")
        if charge_amount == 0:
            continue

        extra_fields = copy.deepcopy(raw_entry)
        extra_fields.pop("LEDGERNAME", None)
        extra_fields.pop("AMOUNT", None)
        extra_fields.pop("ISDEEMEDPOSITIVE", None)
        extra_fields.pop("ISLASTDEEMEDPOSITIVE", None)
        extra_fields.pop("ISPARTYLEDGER", None)
        built_charge_entries.append(
            _build_sales_ready_ledger_entry(
                ledger_name,
                charge_amount,
                is_party=False,
                extra_fields=extra_fields,
            )
        )
        charge_ledger_names.append(ledger_name)

    await _ensure_sales_support_ledgers_exist(client, company_name, sales_ledger_name, charge_ledger_names)

    reference_name = _first_non_empty_string(
        normalized.get("VOUCHERNUMBER"),
        normalized.get("REFERENCE"),
        normalized.get("DATE"),
        "SALES",
    ) or "SALES"

    if requested_party_amount is None:
        requested_party_amount = total_amount + sum(
            (amount for amount in (_decimal_value(entry.get("AMOUNT"), "AMOUNT") for entry in built_charge_entries)),
            Decimal("0"),
        )

    built_party_entry = _build_sales_ready_ledger_entry(
        party_ledger_name,
        -requested_party_amount,
        is_party=True,
        extra_fields={},
    )
    built_party_entry["BILLALLOCATIONS.LIST"] = {
        "NAME": reference_name,
        "BILLTYPE": "New Ref",
        "AMOUNT": f"{-requested_party_amount:.2f}",
    }

    normalized["PERSISTEDVIEW"] = "Invoice Voucher View"
    normalized["ISINVOICE"] = "Yes"
    normalized["PARTYLEDGERNAME"] = party_ledger_name
    normalized["ALLINVENTORYENTRIES.LIST"] = (
        built_inventory_entries if len(built_inventory_entries) > 1 else built_inventory_entries[0]
    )
    ledger_entries: List[Dict[str, Any]] = [built_party_entry, *built_charge_entries]
    normalized["LEDGERENTRIES.LIST"] = ledger_entries if len(ledger_entries) > 1 else ledger_entries[0]
    normalized.pop("ALLLEDGERENTRIES.LIST", None)
    return normalized


async def _prepare_purchase_create_payload(
    client: TallyClient,
    company_name: Optional[str],
    payload: Dict[str, Any],
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return payload

    normalized = copy.deepcopy(payload)
    party_ledger_name, _, expense_entry = _resolve_invoice_party_and_offset_entries(normalized)
    if not party_ledger_name or not expense_entry:
        return normalized

    expense_ledger_name = _resolve_purchase_ledger_name(normalized, expense_entry)
    if not expense_ledger_name:
        return normalized

    await _ensure_purchase_support_ledgers_exist(client, company_name, expense_ledger_name)

    raw_inventory_entries = [entry for entry in _listify(normalized.get("ALLINVENTORYENTRIES.LIST")) if isinstance(entry, dict)]
    fallback_stock_item: Optional[Dict[str, str]] = None
    built_inventory_entries: List[Dict[str, Any]] = []
    total_amount = Decimal("0")

    if raw_inventory_entries:
        for raw_entry in raw_inventory_entries:
            stock_item_name = _first_non_empty_string(
                raw_entry.get("STOCKITEMNAME"),
                raw_entry.get("stock_item_name"),
            )
            quantity = _first_non_empty_string(raw_entry.get("ACTUALQTY"), raw_entry.get("BILLEDQTY"))
            billed_quantity = _first_non_empty_string(raw_entry.get("BILLEDQTY"), quantity)
            rate = _first_non_empty_string(raw_entry.get("RATE"), raw_entry.get("rate"))
            amount_value = abs(_decimal_value(_first_present_value(raw_entry, "AMOUNT", "amount"), "AMOUNT"))

            if not stock_item_name or not quantity or not billed_quantity or not rate:
                if fallback_stock_item is None:
                    fallback_stock_item = await _first_available_stock_item(client, company_name)
                stock_item_name = stock_item_name or fallback_stock_item["name"]
                base_units = fallback_stock_item["base_units"]
                quantity = quantity or f"1 {base_units}"
                billed_quantity = billed_quantity or quantity
                rate = rate or f"{amount_value:.2f}/{base_units}"

            accounting_alloc = _build_sales_ready_ledger_entry(
                expense_ledger_name,
                -amount_value,
                is_party=False,
                extra_fields={},
            )
            built_inventory_entries.append(
                {
                    "STOCKITEMNAME": stock_item_name,
                    "RATE": rate,
                    "ACTUALQTY": quantity,
                    "BILLEDQTY": billed_quantity,
                    "AMOUNT": f"{-amount_value:.2f}",
                    "ISDEEMEDPOSITIVE": "Yes",
                    "ISLASTDEEMEDPOSITIVE": "Yes",
                    "ISAUTONEGATE": "No",
                    "BATCHALLOCATIONS.LIST": {
                        "GODOWNNAME": _first_non_empty_string(raw_entry.get("GODOWNNAME"), "Main Location"),
                        "BATCHNAME": _first_non_empty_string(raw_entry.get("BATCHNAME"), "Primary Batch"),
                        "AMOUNT": f"{-amount_value:.2f}",
                        "ACTUALQTY": quantity,
                        "BILLEDQTY": billed_quantity,
                    },
                    "ACCOUNTINGALLOCATIONS.LIST": accounting_alloc,
                }
            )
            total_amount += amount_value
    else:
        amount_source = _first_present_value(expense_entry, "AMOUNT")
        amount_value = abs(_decimal_value(amount_source, "AMOUNT"))
        stock_item = await _first_available_stock_item(client, company_name)
        base_units = stock_item["base_units"]
        quantity = _first_non_empty_string(normalized.get("ACTUALQTY"), normalized.get("BILLEDQTY"), f"1 {base_units}") or f"1 {base_units}"
        billed_quantity = _first_non_empty_string(normalized.get("BILLEDQTY"), quantity) or quantity
        rate = _first_non_empty_string(normalized.get("RATE"), f"{amount_value:.2f}/{base_units}") or f"{amount_value:.2f}/{base_units}"
        accounting_alloc = _build_sales_ready_ledger_entry(
            expense_ledger_name,
            -amount_value,
            is_party=False,
            extra_fields={},
        )
        built_inventory_entries.append(
            {
                "STOCKITEMNAME": stock_item["name"],
                "RATE": rate,
                "ACTUALQTY": quantity,
                "BILLEDQTY": billed_quantity,
                "AMOUNT": f"{-amount_value:.2f}",
                "ISDEEMEDPOSITIVE": "Yes",
                "ISLASTDEEMEDPOSITIVE": "Yes",
                "ISAUTONEGATE": "No",
                "ACCOUNTINGALLOCATIONS.LIST": accounting_alloc,
            }
        )
        total_amount = amount_value

    reference_name = _first_non_empty_string(
        normalized.get("VOUCHERNUMBER"),
        normalized.get("REFERENCE"),
        normalized.get("DATE"),
        "PURCHASE",
    ) or "PURCHASE"

    built_party_entry = _build_sales_ready_ledger_entry(
        party_ledger_name,
        total_amount,
        is_party=True,
        extra_fields={},
    )
    built_party_entry["BILLALLOCATIONS.LIST"] = {
        "NAME": reference_name,
        "BILLTYPE": "New Ref",
        "AMOUNT": f"{total_amount:.2f}",
    }

    normalized["PERSISTEDVIEW"] = "Invoice Voucher View"
    normalized["ISINVOICE"] = "Yes"
    normalized["PARTYLEDGERNAME"] = party_ledger_name
    normalized["ALLINVENTORYENTRIES.LIST"] = (
        built_inventory_entries if len(built_inventory_entries) > 1 else built_inventory_entries[0]
    )
    normalized["LEDGERENTRIES.LIST"] = built_party_entry
    normalized.pop("ALLLEDGERENTRIES.LIST", None)
    return normalized


def _prepare_order_create_payload(
    payload: Dict[str, Any],
    voucher_type: str,
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return payload
    if voucher_type.strip().lower() not in {"sales order", "purchase order"}:
        return payload

    normalized = copy.deepcopy(payload)
    party_ledger_name, party_entry, offset_entry = _resolve_invoice_party_and_offset_entries(normalized)
    if not party_ledger_name or not offset_entry:
        return normalized

    offset_ledger_name = _first_non_empty_string(offset_entry.get("LEDGERNAME"))
    if not offset_ledger_name:
        return normalized

    amount_candidates = [
        _first_present_value((party_entry or {}), "AMOUNT"),
        _first_present_value(offset_entry, "AMOUNT"),
    ]
    raw_inventory_entries = [entry for entry in _listify(normalized.get("ALLINVENTORYENTRIES.LIST")) if isinstance(entry, dict)]
    amount_candidates.extend(_first_present_value(entry, "AMOUNT", "amount") for entry in raw_inventory_entries)

    amount_value = None
    for candidate in amount_candidates:
        if candidate in (None, ""):
            continue
        amount_value = abs(_decimal_value(candidate, "AMOUNT"))
        if amount_value > 0:
            break
    if amount_value is None or amount_value <= 0:
        return normalized

    normalized_voucher_type = voucher_type.strip().lower()
    normalized["PARTYLEDGERNAME"] = party_ledger_name
    normalized["PERSISTEDVIEW"] = "Invoice Voucher View"
    normalized["ISINVOICE"] = "No"
    normalized["OBJVIEW"] = "Invoice Voucher View"
    if normalized_voucher_type == "purchase order":
        normalized["ALLLEDGERENTRIES.LIST"] = [
            {
                "LEDGERNAME": party_ledger_name,
                "AMOUNT": f"{amount_value:.2f}",
                "ISPARTYLEDGER": "Yes",
                "ISDEEMEDPOSITIVE": "No",
            },
            {
                "LEDGERNAME": offset_ledger_name,
                "AMOUNT": f"{amount_value:.2f}",
                "ISDEEMEDPOSITIVE": "Yes",
            },
        ]
    else:
        normalized["ALLLEDGERENTRIES.LIST"] = [
            {
                "LEDGERNAME": party_ledger_name,
                "AMOUNT": f"{amount_value:.2f}",
                "ISPARTYLEDGER": "Yes",
                "ISDEEMEDPOSITIVE": "Yes",
            },
            {
                "LEDGERNAME": offset_ledger_name,
                "AMOUNT": f"{-amount_value:.2f}",
                "ISDEEMEDPOSITIVE": "Yes",
            },
        ]

    normalized.pop("ALLINVENTORYENTRIES.LIST", None)
    normalized.pop("LEDGERENTRIES.LIST", None)
    normalized.pop("REFERENCE", None)
    return normalized


def _select_order_template_voucher(
    vouchers: List[Dict[str, Any]],
    voucher_type: str,
) -> Dict[str, Any]:
    candidates = [
        voucher
        for voucher in vouchers
        if isinstance(voucher, dict)
        and _first_non_empty_string(voucher.get("VOUCHERTYPENAME"), voucher.get("@VCHTYPE")) == voucher_type
        and _first_non_empty_string(voucher.get("PERSISTEDVIEW"), voucher.get("@OBJVIEW")) == "Invoice Voucher View"
    ]
    if not candidates:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": f"{voucher_type.lower()} voucher",
                "fallback_applied": False,
                "reason": (
                    f"No existing {voucher_type} voucher is available in Tally to derive the verified import shape. "
                    f"Create one {voucher_type} manually in Tally first, then retry."
                ),
            },
        )
    return candidates[-1]


def _prepare_order_template_payload(
    template_voucher: Dict[str, Any],
    ready_payload: Dict[str, Any],
) -> Dict[str, Any]:
    voucher = {
        key: _blank_template_values(copy.deepcopy(value))
        for key, value in template_voucher.items()
        if not key.startswith("@")
    }
    voucher["GUID"] = ""

    for key, value in ready_payload.items():
        if key in {"ALLLEDGERENTRIES.LIST", "LEDGERENTRIES.LIST", "ALLINVENTORYENTRIES.LIST"}:
            continue
        voucher[key] = copy.deepcopy(value)

    for key in [
        "MASTERID",
        "ALTERID",
        "VOUCHERKEY",
        "VOUCHERRETAINKEY",
        "REMOTEID",
        "GUID",
        "OLDAUDITENTRYIDS.LIST",
        "AUDITENTRIES.LIST",
        "ACCOUNTAUDITENTRIES.LIST",
        "OLDAUDITENTRIES.LIST",
        "ALLINVENTORYENTRIES.LIST",
        "INVOICEORDERLIST.LIST",
        "INVOICEDELNOTES.LIST",
        "INVOICEINDENTLIST.LIST",
        "ORIGINVOICEDETAILS.LIST",
        "INVOICEEXPORTLIST.LIST",
    ]:
        voucher.pop(key, None)

    template_ledger_items = [
        item
        for item in _listify(template_voucher.get("LEDGERENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    if not template_ledger_items:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": f"{_first_non_empty_string(ready_payload.get('VOUCHERTYPENAME'), 'order').lower()} voucher",
                "fallback_applied": False,
                "reason": "The selected Tally order template does not contain usable ledger rows.",
            },
        )

    party_template = next(
        (item for item in template_ledger_items if _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) == "Yes"),
        template_ledger_items[0],
    )
    offset_template = next(
        (item for item in template_ledger_items if _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) != "Yes"),
        template_ledger_items[-1],
    )
    ready_ledger_items = [
        item
        for item in _listify(ready_payload.get("ALLLEDGERENTRIES.LIST") or ready_payload.get("LEDGERENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    built_ledger_items: List[Dict[str, Any]] = []
    for ready_entry in ready_ledger_items:
        is_party_entry = _coerce_tally_yes_no(ready_entry.get("ISPARTYLEDGER")) == "Yes"
        ledger_item = _blank_template_values(copy.deepcopy(party_template if is_party_entry else offset_template))
        for key, value in ready_entry.items():
            ledger_item[key] = copy.deepcopy(value)
        built_ledger_items.append(ledger_item)
    voucher["LEDGERENTRIES.LIST"] = built_ledger_items if len(built_ledger_items) > 1 else built_ledger_items[0]

    pruned_voucher = _prune_empty_structure(voucher)
    return pruned_voucher if isinstance(pruned_voucher, dict) else voucher


async def _prepare_order_template_import_payload(
    client: TallyClient,
    company_name: Optional[str],
    ready_payload: Dict[str, Any],
    voucher_type: str,
    report_name: str,
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return ready_payload

    requested_date = _normalize_tally_date(ready_payload.get("DATE")) if isinstance(ready_payload.get("DATE"), str) else None
    export_attempts = []
    if requested_date:
        export_attempts.append({"from_date": requested_date, "to_date": requested_date})
    export_attempts.append({"from_date": None, "to_date": None})

    last_error: Optional[HTTPException] = None
    for export_window in export_attempts:
        report_export = await _export_report(
            client,
            report_name,
            company_name,
            from_date=export_window["from_date"],
            to_date=export_window["to_date"],
            view="full",
        )
        shaped = _shape_report_vouchers(report_export, report_name, voucher_type=voucher_type)
        voucher_items = [
            item for item in _listify(shaped.get("VOUCHER")) if isinstance(item, dict)
        ]
        try:
            template_voucher = _select_order_template_voucher(voucher_items, voucher_type)
            return _prepare_order_template_payload(template_voucher, ready_payload)
        except HTTPException as exc:
            last_error = exc

    if last_error is not None:
        raise last_error
    return ready_payload


def _normalize_inventory_consumption_payload(
    payload: Dict[str, Any],
    voucher_type: str,
) -> Dict[str, Any]:
    if voucher_type not in _INVENTORY_CONSUMPTION_VOUCHER_TYPES:
        return payload

    normalized = copy.deepcopy(payload)
    raw_entries = normalized.get("ALLINVENTORYENTRIES.LIST")
    if raw_entries is None:
        return normalized

    normalized_entries: List[Dict[str, Any]] = []
    inventory_in_entries: List[Dict[str, Any]] = []
    inventory_out_entries: List[Dict[str, Any]] = []

    for raw_entry in _listify(raw_entries):
        if not isinstance(raw_entry, dict):
            raise ValueError("ALLINVENTORYENTRIES.LIST entries must be objects")

        entry = copy.deepcopy(raw_entry)
        entry.pop("ACCOUNTINGALLOCATIONS.LIST", None)

        amount_value = _decimal_value(_first_present_value(entry, "AMOUNT"), "AMOUNT")
        amount_abs = abs(amount_value)
        qty_actual = _require_string(entry, "ACTUALQTY")
        qty_billed = _first_non_empty_string(entry.get("BILLEDQTY"), qty_actual) or qty_actual
        rate = _require_string(entry, "RATE")
        stock_item_name = _require_string(entry, "STOCKITEMNAME")

        raw_flag = _first_non_empty_string(entry.get("ISDEEMEDPOSITIVE"), entry.get("ISLASTDEEMEDPOSITIVE"))
        if raw_flag is not None:
            raw_flag = _coerce_tally_yes_no(raw_flag)

        if amount_value > 0:
            is_inward = True
        elif amount_value < 0:
            is_inward = False
        elif raw_flag is not None:
            is_inward = raw_flag == "Yes"
        else:
            raise ValueError("Stock journal entry needs a non-zero AMOUNT or explicit ISDEEMEDPOSITIVE")

        direction_flag = "Yes" if is_inward else "No"
        signed_amount = f"{amount_abs:.2f}" if is_inward else f"{-amount_abs:.2f}"

        entry["STOCKITEMNAME"] = stock_item_name
        entry["RATE"] = rate
        entry["ACTUALQTY"] = qty_actual.replace("-", "").strip()
        entry["BILLEDQTY"] = qty_billed.replace("-", "").strip()
        entry["AMOUNT"] = signed_amount
        entry["ISDEEMEDPOSITIVE"] = direction_flag
        entry["ISLASTDEEMEDPOSITIVE"] = direction_flag
        entry.setdefault("ISAUTONEGATE", "No")
        entry.setdefault("ISSCRAP", "No")
        entry.setdefault("DISCOUNT", "0")
        entry.setdefault("VATTAXRATE", "0")

        batch_value = entry.get("BATCHALLOCATIONS.LIST") or entry.get("BATCHALLOCATIONS") or {}
        if batch_value and not isinstance(batch_value, dict):
            raise ValueError("BATCHALLOCATIONS.LIST must be an object")
        batch = copy.deepcopy(batch_value) if isinstance(batch_value, dict) else {}
        batch["GODOWNNAME"] = _first_non_empty_string(
            batch.get("GODOWNNAME"),
            entry.get("GODOWNNAME"),
            "Main Location",
        )
        batch["BATCHNAME"] = _first_non_empty_string(
            batch.get("BATCHNAME"),
            entry.get("BATCHNAME"),
            "Primary Batch",
        )
        batch["INDENTNO"] = _first_non_empty_string(batch.get("INDENTNO"), "Not Applicable")
        batch["ORDERNO"] = _first_non_empty_string(batch.get("ORDERNO"), "Not Applicable")
        batch["TRACKINGNUMBER"] = _first_non_empty_string(batch.get("TRACKINGNUMBER"), "Not Applicable")
        batch["AMOUNT"] = signed_amount
        batch["ACTUALQTY"] = entry["ACTUALQTY"]
        batch["BILLEDQTY"] = entry["BILLEDQTY"]
        batch["BATCHRATE"] = _first_non_empty_string(batch.get("BATCHRATE"), rate) or rate
        batch.setdefault("BATCHDISCOUNT", "0")
        entry.pop("BATCHALLOCATIONS", None)
        entry["BATCHALLOCATIONS.LIST"] = batch

        normalized_entries.append(entry)
        if is_inward:
            inventory_in_entries.append(copy.deepcopy(entry))
        else:
            inventory_out_entries.append(copy.deepcopy(entry))

    normalized["ALLINVENTORYENTRIES.LIST"] = (
        normalized_entries if len(normalized_entries) > 1 else normalized_entries[0]
    )
    if inventory_in_entries:
        normalized["INVENTORYENTRIESIN.LIST"] = (
            inventory_in_entries if len(inventory_in_entries) > 1 else inventory_in_entries[0]
        )
    if inventory_out_entries:
        normalized["INVENTORYENTRIESOUT.LIST"] = (
            inventory_out_entries if len(inventory_out_entries) > 1 else inventory_out_entries[0]
        )

    return normalized






def _coerce_mapping(value: Any, field_name: str) -> Dict[str, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    raise ValueError(f"{field_name} must be an object")


def _decimal_value(value: Any, field_name: str) -> Decimal:
    if isinstance(value, Decimal):
        return value
    if isinstance(value, str):
        text = value.strip()
    else:
        text = str(value)
    try:
        return Decimal(text)
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"Invalid {field_name} value: {value}") from exc


def _format_decimal_amount(value: Any, field_name: str = "amount", absolute: bool = False) -> str:
    amount = _decimal_value(value, field_name)
    if absolute:
        amount = abs(amount)
    return f"{amount:.2f}"


def _deemed_positive_flag(amount: Decimal) -> str:
    return "Yes" if amount < 0 else "No"


def _string_list_block(item_name: str, value: Any) -> Optional[Dict[str, Any]]:
    items = [item.strip() for item in _listify(value) if isinstance(item, str) and item.strip()]
    if not items:
        return None
    return {
        "@TYPE": "String",
        item_name: items if len(items) > 1 else items[0],
    }


def _normalized_pin(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        return text or None
    text = str(value).strip()
    return text or None


def _coerce_rate_string(value: Any, quantity_text: Optional[str]) -> str:
    if isinstance(value, str) and value.strip():
        return value.strip()
    amount = _decimal_value(value, "rate")
    unit = ""
    if isinstance(quantity_text, str) and " " in quantity_text.strip():
        unit = quantity_text.strip().split(" ", 1)[1].strip()
    return f"{amount:.2f}/{unit}" if unit else f"{amount:.2f}"


def _normalize_transport_mode(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    normalized = text.lower()
    return {
        "1": "Road",
        "road": "Road",
        "2": "Rail",
        "rail": "Rail",
        "3": "Air",
        "air": "Air",
        "4": "Ship",
        "ship": "Ship",
    }.get(normalized, text)


def _normalize_vehicle_type(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    normalized = text.lower()
    return {
        "r": "Regular",
        "regular": "Regular",
        "o": "Over Dimensional Cargo",
        "odc": "Over Dimensional Cargo",
        "over dimensional cargo": "Over Dimensional Cargo",
    }.get(normalized, text)


def _join_address_lines(value: Any) -> Optional[str]:
    lines = [line.strip() for line in _listify(value) if isinstance(line, str) and line.strip()]
    if not lines:
        return None
    return ", ".join(lines)


def _blank_template_values(value: Any) -> Any:
    if isinstance(value, dict):
        blanked: Dict[str, Any] = {}
        for key, nested in value.items():
            if key.startswith("@"):
                blanked[key] = copy.deepcopy(nested)
            else:
                blanked[key] = _blank_template_values(nested)
        return blanked
    if isinstance(value, list):
        return [_blank_template_values(item) for item in value]
    return ""


def _prune_empty_structure(value: Any) -> Any:
    if isinstance(value, dict):
        attrs = {key: copy.deepcopy(nested) for key, nested in value.items() if key.startswith("@")}
        children: Dict[str, Any] = {}
        for key, nested in value.items():
            if key.startswith("@"):
                continue
            pruned = _prune_empty_structure(nested)
            if pruned is not None:
                children[key] = pruned
        if not children:
            return None
        return {**attrs, **children}
    if isinstance(value, list):
        items = [item for item in (_prune_empty_structure(item) for item in value) if item is not None]
        return items or None
    if value in ("", None):
        return None
    return value


def _build_sales_ready_ledger_entry(
    ledger_name: str,
    amount: Decimal,
    is_party: bool = False,
    extra_fields: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    entry = copy.deepcopy(extra_fields or {})
    entry["LEDGERNAME"] = ledger_name
    entry["AMOUNT"] = f"{amount:.2f}"
    deemed_positive = _deemed_positive_flag(amount)
    entry.setdefault("ISDEEMEDPOSITIVE", deemed_positive)
    entry.setdefault("ISLASTDEEMEDPOSITIVE", deemed_positive)
    entry.setdefault("LEDGERFROMITEM", "No")
    entry.setdefault("REMOVEZEROENTRIES", "No")
    entry.setdefault("ISPARTYLEDGER", "Yes" if is_party else "No")
    return entry


def _build_sales_ready_inventory_entry(
    item: Dict[str, Any],
    voucher_reference: Optional[str],
) -> tuple[Dict[str, Any], Decimal]:
    stock_item_name = _require_string(item, "stock_item_name", "STOCKITEMNAME")
    actual_qty = _require_string(item, "quantity", "ACTUALQTY")
    billed_qty = _first_non_empty_string(item.get("billed_quantity"), item.get("BILLEDQTY"), actual_qty) or actual_qty
    rate = _coerce_rate_string(_first_present_value(item, "rate", "RATE"), actual_qty)
    amount_value = _decimal_value(_first_present_value(item, "amount", "AMOUNT"), "item amount")
    amount = abs(amount_value)
    sales_ledger = _require_string(item, "sales_ledger", "ACCOUNTINGLEDGERNAME", "LEDGERNAME")
    godown_name = _first_non_empty_string(item.get("godown"), item.get("GODOWNNAME"), "Main Location")
    batch_name = _first_non_empty_string(item.get("batch"), item.get("BATCHNAME"), "Primary Batch")
    description = _first_non_empty_string(item.get("description"), item.get("DESCRIPTION"))
    order_number = _first_non_empty_string(item.get("order_no"), item.get("ORDERNO"), voucher_reference)
    order_due_date = _first_non_empty_string(item.get("order_due_date"), item.get("ORDERDUEDATE"))
    hsn_or_sac = _first_non_empty_string(
        item.get("hsn_code"),
        item.get("GSTHSNSACCODE"),
        item.get("HSNCODE"),
        item.get("sac_code"),
        item.get("SACCODE"),
    )
    gst_tax_rate = _first_non_empty_string(item.get("gst_rate"), item.get("GSTTAXRATE"))
    assessable_value = _first_present_value(item, "taxable_amount", "assessable_value", "GSTASSESSABLEVALUE", "GSTASSBLVALUE")

    batch_alloc: Dict[str, Any] = {
        "GODOWNNAME": godown_name,
        "BATCHNAME": batch_name,
        "AMOUNT": f"{amount:.2f}",
        "ACTUALQTY": actual_qty,
        "BILLEDQTY": billed_qty,
    }
    if order_number:
        batch_alloc["ORDERNO"] = order_number
    if order_due_date:
        normalized_due_date = _normalize_tally_date(order_due_date)
        batch_alloc["ORDERDUEDATE"] = {
            "@JD": _tally_jd(normalized_due_date),
            "@P": _tally_display_date(normalized_due_date),
            "#text": _tally_display_date(normalized_due_date),
        }

    accounting_alloc = _build_sales_ready_ledger_entry(
        sales_ledger,
        amount,
        is_party=False,
        extra_fields={},
    )
    if hsn_or_sac:
        accounting_alloc["GSTHSNSACCODE"] = hsn_or_sac
    if gst_tax_rate:
        accounting_alloc["GSTTAXRATE"] = gst_tax_rate
    if assessable_value is not None:
        formatted_assessable = _format_decimal_amount(assessable_value, "assessable value", absolute=True)
        accounting_alloc["GSTASSESSABLEVALUE"] = formatted_assessable
        accounting_alloc["GSTASSBLVALUE"] = formatted_assessable

    inventory_entry: Dict[str, Any] = {
        "STOCKITEMNAME": stock_item_name,
        "RATE": rate,
        "ACTUALQTY": actual_qty,
        "BILLEDQTY": billed_qty,
        "AMOUNT": f"{amount:.2f}",
        "ISDEEMEDPOSITIVE": "No",
        "ISLASTDEEMEDPOSITIVE": "No",
        "ISAUTONEGATE": "No",
        "BATCHALLOCATIONS.LIST": batch_alloc,
        "ACCOUNTINGALLOCATIONS.LIST": accounting_alloc,
    }
    if description:
        inventory_entry["DESCRIPTION"] = description
    return inventory_entry, amount


def _normalize_common_ready_voucher_type(value: Optional[str]) -> Optional[str]:
    if not isinstance(value, str) or not value.strip():
        return None
    normalized_key = re.sub(r"\s+", " ", value.replace("-", " ").strip().lower())
    return COMMON_READY_VOUCHER_TYPE_ALIASES.get(normalized_key)


def _resolve_common_ready_voucher_type(
    payload: Dict[str, Any],
    voucher_type_override: Optional[str] = None,
) -> str:
    voucher_data = _coerce_mapping(payload.get("voucher"), "voucher")
    raw_voucher_type = _first_non_empty_string(
        voucher_type_override,
        payload.get("voucher_type"),
        payload.get("VOUCHERTYPENAME"),
        voucher_data.get("voucher_type"),
        voucher_data.get("voucherType"),
        voucher_data.get("VOUCHERTYPENAME"),
    )
    if raw_voucher_type is None:
        return "Sales"

    canonical_voucher_type = _normalize_common_ready_voucher_type(raw_voucher_type)
    if canonical_voucher_type:
        return canonical_voucher_type

    supported_types = sorted(
        {
            documented_type
            for mode_matrix in COMMON_READY_SUPPORT_MATRIX.values()
            for documented_type in mode_matrix.get("documented", [])
        }
    )
    raise HTTPException(
        status_code=422,
        detail={
            "supported": True,
            "fallback_applied": False,
            "error_type": "validation",
            "reason": f"Unsupported voucher_type '{raw_voucher_type}'.",
            "supported_voucher_types": supported_types,
        },
    )


def _validate_common_ready_voucher_type(
    voucher_type: str,
    compliance_mode: str,
) -> None:
    support_details = COMMON_READY_SUPPORT_MATRIX.get(compliance_mode, {})
    implemented_types = support_details.get("implemented", [])
    documented_types = support_details.get("documented", [])
    if voucher_type in implemented_types:
        return

    raise HTTPException(
        status_code=501,
        detail=_common_compliance_unsupported_detail(
            compliance_mode=compliance_mode,
            operation="ready",
            voucher_type=voucher_type,
            implemented_types=implemented_types,
            documented_types=documented_types,
        ),
    )


def _prepare_sales_ready_payload(
    payload: Dict[str, Any],
    include_einvoice: bool,
    include_ewaybill: bool,
) -> Dict[str, Any]:
    wrapper_keys = {
        "voucher_type",
        "voucher",
        "seller",
        "buyer",
        "transaction_details",
        "document_details",
        "seller_details",
        "buyer_details",
        "dispatch_from",
        "dispatch_details",
        "ship_to",
        "ship_details",
        "items",
        "taxes",
        "ewaybill",
        "ewaybill_details",
        "export",
        "export_details",
        "payment",
        "payment_details",
        "value_details",
        "reference_details",
        "additional_document_details",
        "provider_payload",
        "provider_ewaybill_payload",
        "tally_fields",
    }
    normalized = {
        key: copy.deepcopy(value)
        for key, value in payload.items()
        if key not in wrapper_keys
    }

    tally_fields = _coerce_mapping(payload.get("tally_fields"), "tally_fields")
    normalized.update(copy.deepcopy(tally_fields))

    voucher_data = _coerce_mapping(payload.get("voucher"), "voucher")
    buyer_data = _coerce_mapping(payload.get("buyer"), "buyer")
    dispatch_data = _coerce_mapping(payload.get("dispatch_from"), "dispatch_from")
    ship_to_data = _coerce_mapping(payload.get("ship_to"), "ship_to")
    ewaybill_data = _coerce_mapping(payload.get("ewaybill"), "ewaybill")
    export_data = _coerce_mapping(payload.get("export"), "export")

    voucher_date = _first_non_empty_string(
        voucher_data.get("DATE"),
        voucher_data.get("date"),
        normalized.get("DATE"),
    )
    if not voucher_date:
        raise ValueError("voucher.DATE is required")
    normalized["DATE"] = _normalize_tally_date(voucher_date)
    normalized.setdefault("EFFECTIVEDATE", normalized["DATE"])
    normalized["VOUCHERTYPENAME"] = "Sales"
    normalized["PERSISTEDVIEW"] = "Invoice Voucher View"
    normalized["ISINVOICE"] = "Yes"

    voucher_number = _first_non_empty_string(
        voucher_data.get("VOUCHERNUMBER"),
        voucher_data.get("voucher_number"),
        normalized.get("VOUCHERNUMBER"),
    )
    if voucher_number:
        normalized["VOUCHERNUMBER"] = voucher_number

    reference = _first_non_empty_string(
        voucher_data.get("REFERENCE"),
        voucher_data.get("reference"),
        normalized.get("REFERENCE"),
    )
    if reference:
        normalized["REFERENCE"] = reference

    narration = _first_non_empty_string(
        voucher_data.get("NARRATION"),
        voucher_data.get("narration"),
        normalized.get("NARRATION"),
    )
    if narration:
        normalized["NARRATION"] = narration

    party_ledger_name = _first_non_empty_string(
        voucher_data.get("PARTYLEDGERNAME"),
        voucher_data.get("party_ledger_name"),
        voucher_data.get("PARTYNAME"),
        voucher_data.get("party_name"),
        normalized.get("PARTYLEDGERNAME"),
        normalized.get("PARTYNAME"),
    )
    if not party_ledger_name:
        raise ValueError("voucher.PARTYLEDGERNAME is required")
    normalized["PARTYLEDGERNAME"] = party_ledger_name
    normalized.setdefault("PARTYNAME", party_ledger_name)
    normalized.setdefault("BASICBASEPARTYNAME", party_ledger_name)

    party_mailing_name = _first_non_empty_string(
        voucher_data.get("PARTYMAILINGNAME"),
        voucher_data.get("party_mailing_name"),
        buyer_data.get("mailing_name"),
        buyer_data.get("legal_name"),
        normalized.get("PARTYMAILINGNAME"),
        party_ledger_name,
    )
    if party_mailing_name:
        normalized["PARTYMAILINGNAME"] = party_mailing_name
        normalized.setdefault("CONSIGNEEMAILINGNAME", party_mailing_name)

    if include_einvoice or include_ewaybill:
        party_gstin = _first_non_empty_string(
            buyer_data.get("gstin"),
            buyer_data.get("PARTYGSTIN"),
            normalized.get("PARTYGSTIN"),
        )
        if party_gstin:
            normalized["PARTYGSTIN"] = party_gstin

        buyer_name = _first_non_empty_string(
            buyer_data.get("legal_name"),
            buyer_data.get("mailing_name"),
            normalized.get("BASICBUYERNAME"),
            party_mailing_name,
            party_ledger_name,
        )
        if buyer_name:
            normalized["BASICBUYERNAME"] = buyer_name

        buyer_address_block = _string_list_block(
            "BASICBUYERADDRESS",
            _first_present_value(
                buyer_data,
                "address_lines",
                "BASICBUYERADDRESS",
                "BASICBUYERADDRESS.LIST",
            ),
        )
        if buyer_address_block is not None:
            normalized["BASICBUYERADDRESS.LIST"] = buyer_address_block

        place_of_supply = _first_non_empty_string(
            buyer_data.get("place_of_supply_state"),
            buyer_data.get("state"),
            ship_to_data.get("state"),
            normalized.get("PLACEOFSUPPLY"),
        )
        if place_of_supply:
            normalized["PLACEOFSUPPLY"] = place_of_supply

        bill_to_place = _first_non_empty_string(
            buyer_data.get("bill_to_place"),
            buyer_data.get("place"),
            ship_to_data.get("place"),
            normalized.get("BILLTOPLACE"),
        )
        if bill_to_place:
            normalized["BILLTOPLACE"] = bill_to_place

        party_pincode = _normalized_pin(
            _first_present_value(
                buyer_data,
                "pincode",
                "PARTYPINCODE",
            )
            or normalized.get("PARTYPINCODE")
        )
        if party_pincode:
            normalized["PARTYPINCODE"] = party_pincode

        buyer_gst_registration_type = _first_non_empty_string(
            buyer_data.get("gst_registration_type"),
            buyer_data.get("GSTREGISTRATIONTYPE"),
            normalized.get("GSTREGISTRATIONTYPE"),
        )
        if buyer_gst_registration_type:
            normalized["GSTREGISTRATIONTYPE"] = buyer_gst_registration_type

        dispatch_name = _first_non_empty_string(
            dispatch_data.get("name"),
            dispatch_data.get("DISPATCHFROMNAME"),
            normalized.get("DISPATCHFROMNAME"),
        )
        if dispatch_name:
            normalized["DISPATCHFROMNAME"] = dispatch_name

        dispatch_place = _first_non_empty_string(
            dispatch_data.get("place"),
            dispatch_data.get("DISPATCHFROMPLACE"),
            normalized.get("DISPATCHFROMPLACE"),
        )
        if dispatch_place:
            normalized["DISPATCHFROMPLACE"] = dispatch_place

        dispatch_state = _first_non_empty_string(
            dispatch_data.get("state"),
            dispatch_data.get("DISPATCHFROMSTATENAME"),
            normalized.get("DISPATCHFROMSTATENAME"),
        )
        if dispatch_state:
            normalized["DISPATCHFROMSTATENAME"] = dispatch_state

        dispatch_pincode = _normalized_pin(
            _first_present_value(dispatch_data, "pincode", "DISPATCHFROMPINCODE")
            or normalized.get("DISPATCHFROMPINCODE")
        )
        if dispatch_pincode:
            normalized["DISPATCHFROMPINCODE"] = dispatch_pincode

        ship_to_name = _first_non_empty_string(
            ship_to_data.get("mailing_name"),
            ship_to_data.get("legal_name"),
            ship_to_data.get("CONSIGNEEMAILINGNAME"),
            normalized.get("CONSIGNEEMAILINGNAME"),
            party_mailing_name,
            party_ledger_name,
        )
        if ship_to_name:
            normalized["CONSIGNEEMAILINGNAME"] = ship_to_name

        ship_to_gstin = _first_non_empty_string(
            ship_to_data.get("gstin"),
            ship_to_data.get("CONSIGNEEGSTIN"),
            normalized.get("CONSIGNEEGSTIN"),
            party_gstin,
        )
        if ship_to_gstin:
            normalized["CONSIGNEEGSTIN"] = ship_to_gstin

        ship_to_place = _first_non_empty_string(
            ship_to_data.get("place"),
            ship_to_data.get("SHIPTOPLACE"),
            normalized.get("SHIPTOPLACE"),
            bill_to_place,
        )
        if ship_to_place:
            normalized["SHIPTOPLACE"] = ship_to_place

        ship_to_state = _first_non_empty_string(
            ship_to_data.get("state"),
            ship_to_data.get("CONSIGNEESTATENAME"),
            normalized.get("CONSIGNEESTATENAME"),
            place_of_supply,
        )
        if ship_to_state:
            normalized["CONSIGNEESTATENAME"] = ship_to_state

        ship_to_pincode = _normalized_pin(
            _first_present_value(ship_to_data, "pincode", "CONSIGNEEPINCODE")
            or normalized.get("CONSIGNEEPINCODE")
            or party_pincode
        )
        if ship_to_pincode:
            normalized["CONSIGNEEPINCODE"] = ship_to_pincode

        destination_country = _first_non_empty_string(
            export_data.get("destination_country"),
            ship_to_data.get("country"),
            buyer_data.get("country"),
            normalized.get("BASICDESTINATIONCOUNTRY"),
            "India",
        )
        if destination_country:
            normalized["BASICDESTINATIONCOUNTRY"] = destination_country
            normalized.setdefault("CONSIGNEECOUNTRYNAME", destination_country)

        nature_of_sales = _first_non_empty_string(
            export_data.get("nature_of_sales"),
            voucher_data.get("NATUREOFSALES"),
            normalized.get("NATUREOFSALES"),
            "Export" if destination_country.lower() != "india" else "Domestic",
        )
        if nature_of_sales:
            normalized["NATUREOFSALES"] = nature_of_sales

        shipping_bill_no = _first_non_empty_string(
            export_data.get("shipping_bill_no"),
            export_data.get("SHIPPINGBILLNO"),
            normalized.get("SHIPPINGBILLNO"),
        )
        if shipping_bill_no:
            normalized["SHIPPINGBILLNO"] = shipping_bill_no

        port_code = _first_non_empty_string(
            export_data.get("port_code"),
            export_data.get("PORTCODE"),
            normalized.get("PORTCODE"),
        )
        if port_code:
            normalized["PORTCODE"] = port_code

    item_entries: List[Dict[str, Any]] = []
    item_total = Decimal("0")
    for item_value in _listify(payload.get("items")):
        if not isinstance(item_value, dict):
            raise ValueError("items must contain objects")
        item_entry, item_amount = _build_sales_ready_inventory_entry(item_value, reference)
        item_entries.append(item_entry)
        item_total += item_amount
    if not item_entries:
        raise ValueError("items is required and must contain at least one object")
    normalized["ALLINVENTORYENTRIES.LIST"] = item_entries if len(item_entries) > 1 else item_entries[0]

    tax_entries: List[Dict[str, Any]] = []
    tax_total = Decimal("0")
    for tax_value in _listify(payload.get("taxes")):
        if not isinstance(tax_value, dict):
            raise ValueError("taxes must contain objects")
        ledger_name = _require_string(tax_value, "ledger_name", "LEDGERNAME")
        tax_amount = _decimal_value(_first_present_value(tax_value, "amount", "AMOUNT"), "tax amount")
        tax_entry = _build_sales_ready_ledger_entry(ledger_name, tax_amount, is_party=False, extra_fields={})
        gst_tax_rate = _first_non_empty_string(tax_value.get("gst_rate"), tax_value.get("GSTTAXRATE"))
        if gst_tax_rate:
            tax_entry["GSTTAXRATE"] = gst_tax_rate
        tax_entries.append(tax_entry)
        tax_total += tax_amount

    party_entry = _build_sales_ready_ledger_entry(
        party_ledger_name,
        -(item_total + tax_total),
        is_party=True,
        extra_fields={},
    )
    ledger_entries: List[Dict[str, Any]] = [party_entry, *tax_entries]
    normalized["LEDGERENTRIES.LIST"] = ledger_entries if len(ledger_entries) > 1 else ledger_entries[0]

    if include_ewaybill:
        transport_mode = _normalize_transport_mode(
            _first_present_value(
                ewaybill_data,
                "transport_mode",
                "TRANSPORTMODE",
            )
        )
        transporter_name = _first_non_empty_string(
            ewaybill_data.get("transporter_name"),
            ewaybill_data.get("TRANSPORTERNAME"),
            normalized.get("TRANSPORTERNAME"),
        )
        transporter_id = _first_non_empty_string(
            ewaybill_data.get("transporter_id"),
            ewaybill_data.get("TRANSPORTERID"),
            normalized.get("TEMPGSTEWAYTRANSPORTERID"),
        )
        vehicle_number = _first_non_empty_string(
            ewaybill_data.get("vehicle_number"),
            ewaybill_data.get("GOODSVEHICLENUMBER"),
            normalized.get("GOODSVEHICLENUMBER"),
        )
        vehicle_type = _normalize_vehicle_type(
            _first_present_value(
                ewaybill_data,
                "vehicle_type",
                "TEMPGSTEWAYVEHICLETYPE",
            )
        )
        transport_doc_no = _first_non_empty_string(
            ewaybill_data.get("transport_document_no"),
            ewaybill_data.get("document_number"),
            ewaybill_data.get("LORRYRECPTNO"),
            ewaybill_data.get("AIRWAYBILLNO"),
            ewaybill_data.get("BILLOFLADINGNO"),
        )
        transport_doc_date = _first_non_empty_string(
            ewaybill_data.get("transport_document_date"),
            ewaybill_data.get("document_date"),
            ewaybill_data.get("TEMPGSTEWAYTRANSPORTERDOCDATE"),
        )
        distance_value = _first_present_value(
            ewaybill_data,
            "distance_km",
            "TEMPGSTEWAYDISTANCE",
            "DISTANCE",
        )

        if transporter_name:
            normalized["TRANSPORTERNAME"] = transporter_name
            normalized["TEMPGSTEWAYTRANSPORTERNAME"] = transporter_name
            normalized.setdefault("BASICSHIPPEDBY", transporter_name)
        if transport_mode:
            normalized["TRANSPORTMODE"] = transport_mode
            normalized["TEMPGSTEWAYTRANSPORTMODE"] = transport_mode
        if transporter_id:
            normalized["TEMPGSTEWAYTRANSPORTERID"] = transporter_id
        if vehicle_number:
            normalized["GOODSVEHICLENUMBER"] = vehicle_number
            normalized["TEMPGSTEWAYVEHICLENUMBER"] = vehicle_number
        if vehicle_type:
            normalized["TEMPGSTEWAYVEHICLETYPE"] = vehicle_type
        if transport_doc_no:
            if transport_mode == "Air":
                normalized["AIRWAYBILLNO"] = transport_doc_no
            elif transport_mode == "Ship":
                normalized["BILLOFLADINGNO"] = transport_doc_no
            else:
                normalized["LORRYRECPTNO"] = transport_doc_no
            normalized["TEMPGSTEWAYTRANSPORTERDOCNO"] = transport_doc_no
        if transport_doc_date:
            normalized["TEMPGSTEWAYTRANSPORTERDOCDATE"] = _normalize_tally_date(transport_doc_date)
        if distance_value is not None:
            normalized["TEMPGSTEWAYDISTANCE"] = str(distance_value).strip()

        temp_consignor = _first_non_empty_string(dispatch_data.get("name"), dispatch_name)
        temp_consignor_address = _join_address_lines(dispatch_data.get("address_lines"))
        temp_consignee = _first_non_empty_string(ship_to_name, party_mailing_name, party_ledger_name)
        temp_consignee_address = _join_address_lines(ship_to_data.get("address_lines"))
        if temp_consignor:
            normalized["TEMPGSTEWAYCONSIGNOR"] = temp_consignor
        if temp_consignor_address:
            normalized["TEMPGSTEWAYCONSIGNORADDRESS"] = temp_consignor_address
        if dispatch_place:
            normalized["TEMPGSTEWAYFROMPLACE"] = dispatch_place
        if dispatch_state:
            normalized["TEMPGSTEWAYCONSIGNORSTATE"] = dispatch_state
            normalized["TEMPGSTEWAYCONSSHIPFROMSTATE"] = dispatch_state
        if dispatch_pincode:
            normalized["TEMPGSTEWAYPINCODENUMBER"] = dispatch_pincode
            normalized["TEMPGSTEWAYPINCODE"] = dispatch_pincode
        if temp_consignee:
            normalized["TEMPGSTEWAYCONSIGNEE"] = temp_consignee
        if temp_consignee_address:
            normalized["TEMPGSTEWAYCONSADDRESS"] = temp_consignee_address
        if ship_to_place:
            normalized["TEMPGSTEWAYCONSFROMPLACE"] = ship_to_place
        if ship_to_state:
            normalized["TEMPGSTEWAYCONSSTATE"] = ship_to_state
            normalized["TEMPGSTEWAYCONSSHIPTOSTATE"] = ship_to_state
        if ship_to_gstin:
            normalized["TEMPGSTEWAYCONSTIN"] = ship_to_gstin
        if ship_to_pincode:
            normalized["TEMPGSTEWAYCONSPINCODENUMBER"] = ship_to_pincode
            normalized["TEMPGSTEWAYCONSPINCODE"] = ship_to_pincode
        if voucher_number:
            normalized.setdefault("TEMPGSTEWAYCONSBILLNUMBER", voucher_number)
        normalized.setdefault("TEMPGSTEWAYCONSBILLDATE", normalized["DATE"])

    return normalized


def _select_sales_template_voucher(
    vouchers: List[Dict[str, Any]],
    requested_date: Optional[str],
) -> Dict[str, Any]:
    candidates = [
        voucher
        for voucher in vouchers
        if isinstance(voucher, dict)
        and _first_non_empty_string(voucher.get("VOUCHERTYPENAME"), voucher.get("@VCHTYPE")) == "Sales"
        and _first_non_empty_string(voucher.get("PERSISTEDVIEW"), voucher.get("@OBJVIEW")) == "Invoice Voucher View"
    ]
    if not candidates:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "sales einvoice/ewaybill ready",
                "fallback_applied": False,
                "reason": (
                    "No existing Sales invoice voucher is available in Tally to derive the verified import shape. "
                    "Create one sales invoice manually in Tally first, then retry."
                ),
            },
        )

    if not requested_date:
        return candidates[-1]

    normalized_requested = _normalize_tally_date(requested_date)
    matching = [
        voucher
        for voucher in candidates
        if isinstance(voucher.get("DATE"), str) and _normalize_tally_date(voucher.get("DATE")) == normalized_requested
    ]
    if matching:
        return matching[-1]

    available_dates = sorted(
        {
            _normalize_tally_date(voucher.get("DATE"))
            for voucher in candidates
            if isinstance(voucher.get("DATE"), str) and voucher.get("DATE").strip()
        }
    )
    raise HTTPException(
        status_code=422,
        detail={
            "supported": True,
            "fallback_applied": False,
            "error_type": "validation",
            "reason": (
                "Sales e-invoice/e-way bill ready creation currently needs a real Tally Sales invoice template "
                "with the same DATE as the request."
            ),
            "requested_date": normalized_requested,
            "available_template_dates": available_dates,
        },
    )


def _prepare_sales_ready_template_payload(
    template_voucher: Dict[str, Any],
    ready_payload: Dict[str, Any],
) -> Dict[str, Any]:
    voucher = {
        key: _blank_template_values(copy.deepcopy(value))
        for key, value in template_voucher.items()
        if not key.startswith("@")
    }
    voucher["GUID"] = ""

    template_inventory_items = [
        item
        for item in _listify(voucher.get("ALLINVENTORYENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    template_ledger_items = [
        item
        for item in _listify(voucher.get("LEDGERENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    if not template_inventory_items or not template_ledger_items:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "sales einvoice/ewaybill ready",
                "fallback_applied": False,
                "reason": (
                    "The selected Sales voucher template does not contain usable inventory and ledger structures. "
                    "Create a regular inventory-style sales invoice manually in Tally first, then retry."
                ),
            },
        )

    party_ledger_template = next(
        (item for item in template_ledger_items if _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) == "Yes"),
        template_ledger_items[0],
    )
    non_party_ledger_template = next(
        (item for item in template_ledger_items if _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) != "Yes"),
        template_ledger_items[0],
    )
    original_party_ledger_template = next(
        (
            item
            for item in _listify(template_voucher.get("LEDGERENTRIES.LIST"))
            if isinstance(item, dict) and _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) == "Yes"
        ),
        None,
    )

    for key, value in ready_payload.items():
        if key in {"ALLINVENTORYENTRIES.LIST", "LEDGERENTRIES.LIST"}:
            continue
        voucher[key] = copy.deepcopy(value)

    for key in [
        "MASTERID",
        "ALTERID",
        "VOUCHERKEY",
        "REFERENCEDATE",
        "BASICDATETIMEOFINVOICE",
        "BASICDATETIMEOFREMOVAL",
        "INVOICEORDERLIST.LIST",
        "INVOICEDELNOTES.LIST",
        "INVOICEINDENTLIST.LIST",
        "ORIGINVOICEDETAILS.LIST",
        "INVOICEEXPORTLIST.LIST",
        "EWAYBILLDETAILS.LIST",
        "EWAYBILLERRORLIST.LIST",
        "IRNERRORLIST.LIST",
        "GSTEWAYCONSIGNORADDRESS.LIST",
        "GSTEWAYCONSIGNEEADDRESS.LIST",
        "TEMPGSTRATEDETAILS.LIST",
    ]:
        voucher.pop(key, None)

    ready_inventory_items = [
        item
        for item in _listify(ready_payload.get("ALLINVENTORYENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    built_inventory_items: List[Dict[str, Any]] = []
    for ready_item in ready_inventory_items:
        inventory_item = _blank_template_values(copy.deepcopy(template_inventory_items[0]))
        for key, value in ready_item.items():
            inventory_item[key] = copy.deepcopy(value)
        inventory_item.pop("ACCOUNTINGALLOCATIONS.LIST", None)
        inventory_item["ACCOUNTINGALLOCATIONS.LIST"] = copy.deepcopy(ready_item.get("ACCOUNTINGALLOCATIONS.LIST"))
        inventory_item.pop("BATCHALLOCATIONS.LIST", None)
        inventory_item["BATCHALLOCATIONS.LIST"] = copy.deepcopy(ready_item.get("BATCHALLOCATIONS.LIST"))
        built_inventory_items.append(inventory_item)
    voucher["ALLINVENTORYENTRIES.LIST"] = (
        built_inventory_items if len(built_inventory_items) > 1 else built_inventory_items[0]
    )

    ready_ledger_items = [
        item
        for item in _listify(ready_payload.get("LEDGERENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    built_ledger_items: List[Dict[str, Any]] = []
    for ready_entry in ready_ledger_items:
        is_party_entry = _coerce_tally_yes_no(ready_entry.get("ISPARTYLEDGER")) == "Yes"
        ledger_item = _blank_template_values(copy.deepcopy(party_ledger_template if is_party_entry else non_party_ledger_template))
        for key, value in ready_entry.items():
            ledger_item[key] = copy.deepcopy(value)
        if is_party_entry:
            original_bill_alloc = (
                (original_party_ledger_template or {}).get("BILLALLOCATIONS.LIST")
                if isinstance(original_party_ledger_template, dict)
                else None
            )
            if isinstance(original_bill_alloc, dict):
                bill_alloc = _blank_template_values(copy.deepcopy(original_bill_alloc))
                bill_alloc["NAME"] = _first_non_empty_string(
                    ready_payload.get("VOUCHERNUMBER"),
                    ready_payload.get("REFERENCE"),
                    ready_payload.get("PARTYINVNO"),
                    ready_payload.get("DATE"),
                ) or ready_payload.get("DATE")
                bill_alloc["BILLTYPE"] = _first_non_empty_string(original_bill_alloc.get("BILLTYPE"), "New Ref")
                bill_alloc["AMOUNT"] = ready_entry.get("AMOUNT")
                bill_credit_period = _first_non_empty_string(ready_payload.get("BILLCREDITPERIOD"))
                if bill_credit_period:
                    bill_alloc["BILLCREDITPERIOD"] = bill_credit_period
                ledger_item["BILLALLOCATIONS.LIST"] = bill_alloc
            else:
                ledger_item.pop("BILLALLOCATIONS.LIST", None)
        else:
            ledger_item.pop("BILLALLOCATIONS.LIST", None)
        built_ledger_items.append(ledger_item)
    voucher["LEDGERENTRIES.LIST"] = built_ledger_items if len(built_ledger_items) > 1 else built_ledger_items[0]

    if "VOUCHERNUMBER" not in ready_payload:
        voucher.pop("VOUCHERNUMBER", None)

    pruned_voucher = _prune_empty_structure(voucher)
    return pruned_voucher if isinstance(pruned_voucher, dict) else voucher


async def _prepare_sales_ready_import_payload(
    client: TallyClient,
    company_name: str,
    ready_payload: Dict[str, Any],
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return ready_payload

    requested_date = _normalize_tally_date(ready_payload.get("DATE")) if isinstance(ready_payload.get("DATE"), str) else None
    if requested_date:
        requested_year = int(requested_date[:4])
        requested_month = int(requested_date[4:6])
        fy_year = requested_year if requested_month >= 4 else requested_year - 1
    else:
        today = date.today()
        fy_year = today.year if today.month >= 4 else today.year - 1
    fy_start = f"{fy_year}0401"
    fy_end = f"{fy_year + 1}0331"

    template_export = await _export_report(
        client,
        "Sales Vouchers",
        company_name,
        fy_start,
        fy_end,
        view="raw",
    )
    data = (((template_export.get("ENVELOPE") or {}).get("BODY") or {}).get("DATA") if isinstance(template_export, dict) else None)
    if not isinstance(data, dict):
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "sales einvoice/ewaybill ready",
                "fallback_applied": False,
                "reason": (
                    "Could not export existing vouchers from Tally to derive a Sales invoice template. "
                    "Create one sales invoice manually in Tally first, then retry."
                ),
            },
        )

    voucher_items = [
        message.get("VOUCHER")
        for message in _listify(data.get("TALLYMESSAGE"))
        if isinstance(message, dict) and isinstance(message.get("VOUCHER"), dict)
    ]
    template_voucher = _select_sales_template_voucher(voucher_items, ready_payload.get("DATE"))
    return _prepare_sales_ready_template_payload(template_voucher, ready_payload)


def _select_inventory_invoice_template_voucher(
    vouchers: List[Dict[str, Any]],
    voucher_type: str,
    requested_date: Optional[str],
) -> Optional[Dict[str, Any]]:
    candidates = [
        voucher
        for voucher in vouchers
        if isinstance(voucher, dict)
        and _first_non_empty_string(voucher.get("VOUCHERTYPENAME"), voucher.get("@VCHTYPE")) == voucher_type
        and _first_non_empty_string(voucher.get("PERSISTEDVIEW"), voucher.get("@OBJVIEW")) == "Invoice Voucher View"
    ]
    if not candidates:
        return None

    if not requested_date:
        return candidates[-1]

    normalized_requested = _normalize_tally_date(requested_date)
    matching = [
        voucher
        for voucher in candidates
        if isinstance(voucher.get("DATE"), str) and _normalize_tally_date(voucher.get("DATE")) == normalized_requested
    ]
    if matching:
        return matching[-1]
    return candidates[-1]


def _prepare_inventory_invoice_template_payload(
    template_voucher: Dict[str, Any],
    ready_payload: Dict[str, Any],
) -> Dict[str, Any]:
    voucher = {
        key: _blank_template_values(copy.deepcopy(value))
        for key, value in template_voucher.items()
        if not key.startswith("@")
    }
    voucher["GUID"] = ""

    template_inventory_items = [
        item
        for item in _listify(voucher.get("ALLINVENTORYENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    template_ledger_items = [
        item
        for item in _listify(voucher.get("LEDGERENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    if not template_inventory_items or not template_ledger_items:
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": f"{_first_non_empty_string(ready_payload.get('VOUCHERTYPENAME'), 'inventory').lower()} voucher",
                "fallback_applied": False,
                "reason": (
                    "The selected Tally voucher template does not contain usable inventory and ledger structures. "
                    "Create a regular voucher manually in Tally first, then retry."
                ),
            },
        )

    party_ledger_template = next(
        (item for item in template_ledger_items if _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) == "Yes"),
        template_ledger_items[0],
    )
    non_party_ledger_template = next(
        (item for item in template_ledger_items if _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) != "Yes"),
        template_ledger_items[0],
    )
    original_party_ledger_template = next(
        (
            item
            for item in _listify(template_voucher.get("LEDGERENTRIES.LIST"))
            if isinstance(item, dict) and _coerce_tally_yes_no(item.get("ISPARTYLEDGER")) == "Yes"
        ),
        None,
    )

    for key, value in ready_payload.items():
        if key in {"ALLINVENTORYENTRIES.LIST", "LEDGERENTRIES.LIST", "ALLLEDGERENTRIES.LIST"}:
            continue
        voucher[key] = copy.deepcopy(value)

    for key in [
        "MASTERID",
        "ALTERID",
        "VOUCHERKEY",
        "VOUCHERRETAINKEY",
        "REMOTEID",
        "ALLLEDGERENTRIES.LIST",
        "OLDAUDITENTRYIDS.LIST",
        "OLDAUDITENTRIES.LIST",
        "ACCOUNTAUDITENTRIES.LIST",
        "AUDITENTRIES.LIST",
        "EWAYBILLDETAILS.LIST",
        "EWAYBILLERRORLIST.LIST",
        "IRNERRORLIST.LIST",
        "INVOICEDELNOTES.LIST",
        "INVOICEORDERLIST.LIST",
        "INVOICEINDENTLIST.LIST",
        "ORIGINVOICEDETAILS.LIST",
        "INVOICEEXPORTLIST.LIST",
    ]:
        voucher.pop(key, None)

    ready_inventory_items = [
        item
        for item in _listify(ready_payload.get("ALLINVENTORYENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    built_inventory_items: List[Dict[str, Any]] = []
    for ready_item in ready_inventory_items:
        inventory_item = _blank_template_values(copy.deepcopy(template_inventory_items[0]))
        for key, value in ready_item.items():
            inventory_item[key] = copy.deepcopy(value)
        if "ACCOUNTINGALLOCATIONS.LIST" in ready_item:
            inventory_item["ACCOUNTINGALLOCATIONS.LIST"] = copy.deepcopy(ready_item.get("ACCOUNTINGALLOCATIONS.LIST"))
        if "BATCHALLOCATIONS.LIST" in ready_item:
            inventory_item["BATCHALLOCATIONS.LIST"] = copy.deepcopy(ready_item.get("BATCHALLOCATIONS.LIST"))
        built_inventory_items.append(inventory_item)
    voucher["ALLINVENTORYENTRIES.LIST"] = (
        built_inventory_items if len(built_inventory_items) > 1 else built_inventory_items[0]
    )

    ready_ledger_items = [
        item
        for item in _listify(ready_payload.get("LEDGERENTRIES.LIST") or ready_payload.get("ALLLEDGERENTRIES.LIST"))
        if isinstance(item, dict)
    ]
    built_ledger_items: List[Dict[str, Any]] = []
    for ready_entry in ready_ledger_items:
        is_party_entry = _coerce_tally_yes_no(ready_entry.get("ISPARTYLEDGER")) == "Yes"
        ledger_item = _blank_template_values(copy.deepcopy(party_ledger_template if is_party_entry else non_party_ledger_template))
        for key, value in ready_entry.items():
            ledger_item[key] = copy.deepcopy(value)
        if is_party_entry:
            original_bill_alloc = (
                (original_party_ledger_template or {}).get("BILLALLOCATIONS.LIST")
                if isinstance(original_party_ledger_template, dict)
                else None
            )
            if isinstance(original_bill_alloc, dict):
                bill_alloc = _blank_template_values(copy.deepcopy(original_bill_alloc))
                bill_alloc["NAME"] = _first_non_empty_string(
                    ready_payload.get("REFERENCE"),
                    ready_payload.get("VOUCHERNUMBER"),
                    ready_payload.get("DATE"),
                ) or ready_payload.get("DATE")
                bill_alloc["BILLTYPE"] = _first_non_empty_string(original_bill_alloc.get("BILLTYPE"), "New Ref")
                bill_alloc["AMOUNT"] = ready_entry.get("AMOUNT")
                ledger_item["BILLALLOCATIONS.LIST"] = bill_alloc
            else:
                ledger_item.pop("BILLALLOCATIONS.LIST", None)
        else:
            ledger_item.pop("BILLALLOCATIONS.LIST", None)
        built_ledger_items.append(ledger_item)
    voucher["LEDGERENTRIES.LIST"] = built_ledger_items if len(built_ledger_items) > 1 else built_ledger_items[0]

    if "VOUCHERNUMBER" not in ready_payload:
        voucher.pop("VOUCHERNUMBER", None)

    pruned_voucher = _prune_empty_structure(voucher)
    return pruned_voucher if isinstance(pruned_voucher, dict) else voucher


def _prepare_inventory_note_ledger_fallback_payload(
    payload: Dict[str, Any],
    voucher_type: str,
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return payload

    normalized = copy.deepcopy(payload)
    party_ledger_name, party_entry, offset_entry = _resolve_invoice_party_and_offset_entries(normalized)
    if not party_ledger_name or not offset_entry:
        return normalized

    amount_candidates = [
        _first_present_value((party_entry or {}), "AMOUNT"),
        _first_present_value(offset_entry, "AMOUNT"),
    ]
    raw_inventory_entries = [
        entry for entry in _listify(normalized.get("ALLINVENTORYENTRIES.LIST")) if isinstance(entry, dict)
    ]
    amount_candidates.extend(_first_present_value(entry, "AMOUNT", "amount") for entry in raw_inventory_entries)

    amount_value = None
    for candidate in amount_candidates:
        if candidate in (None, ""):
            continue
        amount_value = abs(_decimal_value(candidate, "AMOUNT"))
        if amount_value > 0:
            break
    if amount_value is None or amount_value <= 0:
        return normalized

    offset_ledger_name = _first_non_empty_string(offset_entry.get("LEDGERNAME"))
    if not offset_ledger_name:
        return normalized

    normalized["PARTYLEDGERNAME"] = party_ledger_name
    if voucher_type.strip().lower() == "receipt note":
        normalized["ALLLEDGERENTRIES.LIST"] = [
            {
                "LEDGERNAME": offset_ledger_name,
                "AMOUNT": f"{amount_value:.2f}",
            },
            {
                "LEDGERNAME": party_ledger_name,
                "ISDEEMEDPOSITIVE": "Yes",
                "ISPARTYLEDGER": "Yes",
                "AMOUNT": f"{-amount_value:.2f}",
            },
        ]
    else:
        normalized["ALLLEDGERENTRIES.LIST"] = [
            {
                "LEDGERNAME": party_ledger_name,
                "ISPARTYLEDGER": "Yes",
                "AMOUNT": f"{amount_value:.2f}",
            },
            {
                "LEDGERNAME": offset_ledger_name,
                "ISDEEMEDPOSITIVE": "Yes",
                "AMOUNT": f"{-amount_value:.2f}",
            },
        ]

    normalized.pop("ALLINVENTORYENTRIES.LIST", None)
    normalized.pop("LEDGERENTRIES.LIST", None)
    normalized.pop("REFERENCE", None)
    normalized.pop("PERSISTEDVIEW", None)
    normalized.pop("ISINVOICE", None)
    normalized.pop("OBJVIEW", None)
    return normalized


async def _prepare_inventory_invoice_template_import_payload(
    client: TallyClient,
    company_name: Optional[str],
    ready_payload: Dict[str, Any],
    voucher_type: str,
    action: str,
) -> Dict[str, Any]:
    if (action or "Create").strip().lower() != "create":
        return ready_payload

    requested_date = _normalize_tally_date(ready_payload.get("DATE")) if isinstance(ready_payload.get("DATE"), str) else None
    if requested_date:
        requested_year = int(requested_date[:4])
        requested_month = int(requested_date[4:6])
        fy_year = requested_year if requested_month >= 4 else requested_year - 1
    else:
        today = date.today()
        fy_year = today.year if today.month >= 4 else today.year - 1
    fy_start = f"{fy_year}0401"
    fy_end = f"{fy_year + 1}0331"

    template_export = await _export_report(
        client,
        "Day Book",
        company_name,
        fy_start,
        fy_end,
        view="raw",
    )
    data = (((template_export.get("ENVELOPE") or {}).get("BODY") or {}).get("DATA") if isinstance(template_export, dict) else None)
    if not isinstance(data, dict):
        return _prepare_inventory_note_ledger_fallback_payload(ready_payload, voucher_type, action)

    voucher_items = [
        message.get("VOUCHER")
        for message in _listify(data.get("TALLYMESSAGE"))
        if isinstance(message, dict) and isinstance(message.get("VOUCHER"), dict)
    ]
    template_voucher = _select_inventory_invoice_template_voucher(voucher_items, voucher_type, ready_payload.get("DATE"))
    if template_voucher is None:
        return _prepare_inventory_note_ledger_fallback_payload(ready_payload, voucher_type, action)
    return _prepare_inventory_invoice_template_payload(template_voucher, ready_payload)


async def _export_report(
    client: TallyClient,
    report_name: str,
    company_name: Optional[str],
    from_date: Optional[str] = None,
    to_date: Optional[str] = None,
    extra_static_variables: Optional[Dict[str, Any]] = None,
    view: str = "summary",
) -> Dict[str, Any]:
    static_variables = dict(extra_static_variables or {})
    if from_date:
        static_variables["SVFROMDATE"] = _normalize_tally_date(from_date)
    if to_date:
        static_variables["SVTODATE"] = _normalize_tally_date(to_date)
    await _ensure_active_company_for_builtin_report(client, report_name, company_name)
    xml_req = build_report_export(report_name, company_name, static_variables)
    return await _post_xml(client, xml_req, view)


def _stock_summary_item_name(stock_item: Dict[str, Any]) -> Optional[str]:
    language_name = stock_item.get("LANGUAGENAME.LIST")
    nested_language_name = None
    if isinstance(language_name, dict):
        name_list = language_name.get("NAME.LIST")
        if isinstance(name_list, dict):
            nested_language_name = name_list.get("NAME")
    return _first_non_placeholder_text(
        stock_item.get("@NAME"),
        stock_item.get("NAME"),
        nested_language_name,
    )


def _stock_summary_decimal(value: Any) -> str:
    amount = _decimal_or_zero(_extract_tally_text(value) or value)
    return f"{amount:.2f}"


def _normalize_stock_summary_item(stock_item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    item_name = _stock_summary_item_name(stock_item)
    if not item_name:
        return None

    group_name = _first_non_placeholder_text(stock_item.get("PARENT"))
    base_units = _first_non_placeholder_text(stock_item.get("BASEUNITS"))
    quantity = _extract_tally_text(stock_item.get("OPENINGBALANCE")) or ""
    rate = _extract_tally_text(stock_item.get("OPENINGRATE")) or ""
    value = _stock_summary_decimal(stock_item.get("OPENINGVALUE"))

    return {
        "name": item_name,
        "stock_group": group_name or "",
        "base_units": base_units or "",
        "quantity": quantity,
        "rate": rate,
        "value": value,
    }


async def _build_full_stock_summary(
    client: TallyClient,
    company_name: Optional[str],
    from_date: Optional[str],
    to_date: Optional[str],
    view: str,
) -> Dict[str, Any]:
    normalized_view = _normalize_view(view)
    stock_items_export = await _export_master(
        client,
        company_name or "",
        "stock item",
        LIGHT_STOCK_ITEM_FETCH,
        view="summary",
        collection_name=_collection_name("PYREPORT", "stock_summary_all_items"),
    )
    stock_items = _listify_master_entries(stock_items_export, "STOCKITEM")

    normalized_items = []
    total_value = Decimal("0")
    for stock_item in stock_items:
        normalized_item = _normalize_stock_summary_item(stock_item)
        if normalized_item is None:
            continue
        normalized_items.append(normalized_item)
        total_value += _decimal_or_zero(normalized_item.get("value"))

    normalized_items.sort(key=lambda item: item.get("name", "").lower())

    response: Dict[str, Any] = {
        "report": "Stock Summary",
        "company": company_name or "",
        "from_date": _normalize_tally_date(from_date) or "",
        "to_date": _normalize_tally_date(to_date) or "",
        "source": "stock-item-master-export",
        "count": len(normalized_items),
        "items": normalized_items,
        "totals": {
            "items": len(normalized_items),
            "value": f"{total_value:.2f}",
        },
    }
    if normalized_view in {"full", "raw"}:
        response["raw"] = stock_items_export
    return response


def _parse_decimal_or_none(value: Any) -> Optional[str]:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    cleaned = raw.replace(",", "")
    try:
        return str(Decimal(cleaned))
    except InvalidOperation:
        return raw


def _decimal_or_zero(value: Any) -> Decimal:
    parsed = _parse_decimal_or_none(value)
    if parsed is None:
        return Decimal("0")
    try:
        return Decimal(str(parsed))
    except InvalidOperation:
        return Decimal("0")


def _sales_trend_month_key(value: Any) -> Optional[str]:
    raw = str(value or "").strip()
    if not raw:
        return None
    normalized = _normalize_tally_date(raw)
    if normalized and len(normalized) >= 6 and normalized[:6].isdigit():
        return normalized[:6]
    for fmt in ("%d-%m-%Y", "%d/%m/%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, fmt).strftime("%Y%m")
        except ValueError:
            continue
    return None


def _sales_trend_month_label(month_key: str) -> str:
    month_names = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    month_index = int(month_key[4:6])
    return f"{month_names[month_index - 1]}-{month_key[2:4]}"


def _sales_trend_default_range() -> tuple[str, str]:
    today = date.today()
    fy_start_year = today.year if today.month >= 4 else today.year - 1
    return f"{fy_start_year}0401", f"{fy_start_year + 1}0331"


def _sales_trend_month_keys(from_date: Optional[str], to_date: Optional[str], vouchers: List[Dict[str, Any]]) -> List[str]:
    normalized_from = _normalize_tally_date(from_date) if from_date else None
    normalized_to = _normalize_tally_date(to_date) if to_date else None
    if not normalized_from or not normalized_to:
        voucher_keys = [
            key
            for key in (_sales_trend_month_key(voucher.get("DATE")) for voucher in vouchers)
            if key
        ]
        if voucher_keys:
            normalized_from = normalized_from or f"{min(voucher_keys)}01"
            normalized_to = normalized_to or f"{max(voucher_keys)}01"
        else:
            default_from, default_to = _sales_trend_default_range()
            normalized_from = normalized_from or default_from
            normalized_to = normalized_to or default_to

    start_year = int(normalized_from[:4])
    start_month = int(normalized_from[4:6])
    end_year = int(normalized_to[:4])
    end_month = int(normalized_to[4:6])
    keys = []
    year, month = start_year, start_month
    while (year, month) <= (end_year, end_month):
        keys.append(f"{year:04d}{month:02d}")
        month += 1
        if month > 12:
            year += 1
            month = 1
    return keys


def _shape_sales_trend_response(parsed: Dict[str, Any], view: str) -> Dict[str, Any]:
    normalized_view = _normalize_view(view)
    if normalized_view == "raw":
        return parsed

    env = parsed.get("ENVELOPE") if isinstance(parsed, dict) else None
    if not isinstance(env, dict):
        return {
            "report": "sales-trend",
            "periods": [],
            "raw": parsed if normalized_view == "full" else None,
        }

    month_values = _listify(env.get("PERIODICTILEMONTHNAME"))
    amount_values = _listify(env.get("PERIODICTILENETTAMT"))
    periods = []
    for idx, month in enumerate(month_values):
        month_name = str(month or "").strip()
        if not month_name:
            continue
        amount = amount_values[idx] if idx < len(amount_values) else None
        periods.append({
            "month": month_name,
            "net_amount": _parse_decimal_or_none(amount),
        })

    response: Dict[str, Any] = {
        "report": "sales-trend",
        "periods": periods,
    }
    if normalized_view == "full":
        response["raw"] = parsed
    return response


def _shape_sales_trend_from_vouchers(
    voucher_response: Dict[str, Any],
    from_date: Optional[str],
    to_date: Optional[str],
    view: str,
) -> Dict[str, Any]:
    normalized_view = _normalize_view(view)
    if normalized_view == "raw":
        return voucher_response

    vouchers = [
        voucher
        for voucher in _listify(voucher_response.get("VOUCHER") if isinstance(voucher_response, dict) else None)
        if isinstance(voucher, dict)
    ]
    totals: Dict[str, Decimal] = {}
    for voucher in vouchers:
        month_key = _sales_trend_month_key(voucher.get("DATE"))
        if not month_key:
            continue
        totals[month_key] = totals.get(month_key, Decimal("0")) + _decimal_or_zero(
            voucher.get("NETAMOUNT") or voucher.get("AMOUNT")
        )

    periods = [
        {
            "month": _sales_trend_month_label(month_key),
            "net_amount": str(totals.get(month_key, Decimal("0"))),
        }
        for month_key in _sales_trend_month_keys(from_date, to_date, vouchers)
    ]
    response: Dict[str, Any] = {
        "report": "sales-trend",
        "source_report": "Day Book",
        "source_voucher_type": "Sales",
        "fallback_applied": True,
        "periods": periods,
    }
    if normalized_view == "full":
        response["raw"] = voucher_response
    return response


async def _export_sales_trend(
    client: TallyClient,
    company_name: Optional[str],
    from_date: Optional[str] = None,
    to_date: Optional[str] = None,
    view: str = "summary",
    report_name: str = "Sales Trend",
) -> Dict[str, Any]:
    static_variables: Dict[str, Any] = {}
    if from_date:
        static_variables["SVFROMDATE"] = _normalize_tally_date(from_date)
    if to_date:
        static_variables["SVTODATE"] = _normalize_tally_date(to_date)
    xml_req = build_report_export(report_name, company_name, static_variables)
    try:
        xml_resp = await client.post_xml(xml_req)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=_tally_http_error_detail(exc)) from exc
    parsed = xml_to_json(xml_resp)
    line_error = _extract_tally_import_error_summary(parsed) or _extract_tally_line_error(parsed)
    if line_error:
        if f"Could not find Report '{report_name}'!" == line_error:
            effective_from = from_date
            effective_to = to_date
            if not effective_from and not effective_to:
                effective_from, effective_to = _sales_trend_default_range()
            vouchers = await _export_daybook_vouchers(
                client,
                company_name,
                voucher_type="Sales",
                fetch=["DATE", "VOUCHERTYPENAME", "NETAMOUNT", "AMOUNT"],
                from_date=effective_from,
                to_date=effective_to,
                view="summary",
            )
            return _shape_sales_trend_from_vouchers(vouchers, effective_from, effective_to, view)
        status_code, detail = _classify_tally_error(line_error)
        raise HTTPException(status_code=status_code, detail=detail)
    return _shape_sales_trend_response(parsed, view)


async def _export_report_with_missing_report_hint(
    client: TallyClient,
    report_name: str,
    company_name: Optional[str],
    from_date: Optional[str] = None,
    to_date: Optional[str] = None,
    extra_static_variables: Optional[Dict[str, Any]] = None,
    view: str = "summary",
) -> Dict[str, Any]:
    try:
        return await _export_report(
            client,
            report_name,
            company_name,
            from_date,
            to_date,
            extra_static_variables,
            view,
        )
    except HTTPException as exc:
        detail = exc.detail if isinstance(exc.detail, dict) else None
        reason = str((detail or {}).get("reason") or "").strip()
        expected = f"Could not find Report '{report_name}'!"
        if exc.status_code == 501 and reason == expected:
            endpoint = STATUTORY_REPORT_ENDPOINTS.get(report_name, report_name)
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": endpoint,
                    "tally_reportname": report_name,
                    "fallback_applied": False,
                    "error_type": "unsupported",
                    "reason": (
                        f"Current Tally build/company does not expose report '{report_name}' "
                        "through the generic XML export path."
                    ),
                    "upstream_reason": reason,
                    "hint": (
                        "Verify the report exists in the opened company and in your Tally "
                        "edition/release. If the report is available only in the UI or through "
                        "a custom TDL path, this API cannot export it yet."
                    ),
                },
            ) from exc
        raise


async def _export_daybook_vouchers(
    client: TallyClient,
    company_name: Optional[str],
    voucher_type: Optional[str] = None,
    fetch: Optional[List[str]] = None,
    filter_expr: Optional[str] = None,
    limit: Optional[int] = None,
    from_date: Optional[str] = None,
    to_date: Optional[str] = None,
    view: str = "summary",
) -> Dict[str, Any]:
    normalized_view = _normalize_view(view)
    if normalized_view == "summary":
        effective_fetch = list(fetch or _report_summary_fetch(None, voucher_type) or [])
    else:
        effective_fetch = list(fetch or _default_voucher_fetch(voucher_type))

    xml_req = build_daybook_voucher_export(
        company_name,
        voucher_type=voucher_type,
        fetch=effective_fetch,
        filter_expr=filter_expr,
        from_date=_normalize_tally_date(from_date) if from_date else None,
        to_date=_normalize_tally_date(to_date) if to_date else None,
    )
    report_data = await _post_xml(client, xml_req, normalized_view)
    if normalized_view == "raw":
        return report_data
    summary_fetch = None if normalized_view == "full" else effective_fetch
    return _shape_report_vouchers(
        report_data,
        "Day Book",
        voucher_type,
        summary_fetch,
        limit,
    )


async def _export_price_lists(
    client: TallyClient,
    company_name: str,
    view: str = "summary",
    stock_group: Optional[str] = None,
    stock_category: Optional[str] = None,
    price_level: Optional[str] = None,
    price_level_date: Optional[str] = None,
    show_all_items: Optional[bool] = None,
) -> Dict[str, Any]:
    static_variables: Dict[str, Any] = {}
    if stock_group:
        static_variables["SVSTOCKGROUP"] = stock_group
    if stock_category:
        static_variables["SVSTOCKCATEGORY"] = stock_category
    if price_level:
        static_variables["SVPRICELEVEL"] = price_level
    if price_level_date:
        static_variables["SVPRICELEVELDATE"] = _normalize_tally_date(price_level_date)
    if show_all_items is not None:
        static_variables["SVSHOWALLITEMSFORPRICELEVEL"] = "Yes" if show_all_items else "No"

    # Tally's price list screen is a stock-item export with screen context, not a standalone master.
    normalized_view = _normalize_view(view)
    fallback_fetch = _full_fetch(None, extra_fields=PRICE_LIST_DETAIL_FIELDS) if _needs_full_fetch(normalized_view) else PRICE_STRUCTURE_FETCH
    try:
        xml_req = build_master_list(
            company_name,
            "stock item",
            fallback_fetch,
            collection_name=_collection_name("PYSTOCK", "price-lists"),
            extra_static_variables=static_variables,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {
        "source": "tally-price-list-screen",
        "items": await _post_xml(client, xml_req, normalized_view),
    }


async def _export_price_levels(
    client: TallyClient,
    company_name: str,
    fetch: Optional[List[str]],
    limit: Optional[int],
    view: str,
    *,
    settings: Optional[Settings] = None,
    route_path: str = "/price-levels",
) -> Dict[str, Any]:
    normalized_view = _normalize_view(view)
    if (
        settings
        and settings.tdl_integration_enabled
        and _tdl_feature_is_ready(settings, "price_levels")
        and not fetch
        and limit is None
        and normalized_view == "summary"
    ):
        contract = _tdl_feature_contract(settings, "price_levels")
        collection_name = _tdl_feature_route_collection(contract, route_path)
        if collection_name:
            response = await _post_xml(client, build_collection_export(collection_name, company_name), normalized_view)
        else:
            report_name = (
                contract.get("report_name")
                or TDL_FEATURE_DEFAULTS["price_levels"]["report_name"]
            )
            response = await _post_xml(
                client,
                build_tdl_gateway_report(
                    str(report_name),
                    company=company_name,
                    payload={"ACTION": "LIST"},
                ),
                normalized_view,
            )
        if _is_tdl_scaffold_placeholder_response(response):
            raise HTTPException(
                status_code=501,
                detail=_price_levels_unavailable_detail(
                    "The configured TDL price-levels feature is still serving the scaffold placeholder response, so live standalone price-level export is not implemented yet.",
                    action="List",
                    response=response,
                ),
            )
        if _is_metadata_only_collection(response):
            raise HTTPException(
                status_code=501,
                detail=_price_levels_unavailable_detail(
                    "The configured TDL price-level export returned only metadata and no actual price-level rows. A real collection/report implementation is still required.",
                    action="List",
                    response=response,
                ),
            )
        return response
    # Some Tally builds crash on Price Level master collections. Use report export only.
    last_exc: Optional[HTTPException] = None
    for report_name in PRICE_LEVEL_REPORT_NAMES:
        try:
            return await _export_report(client, report_name, company_name, view=normalized_view)
        except HTTPException as exc:
            last_exc = exc
            continue
    # Fallback: derive price levels from the native price list screen export.
    try:
        price_list_export = await _export_price_lists(client, company_name, view="full", show_all_items=True)
        price_levels = _extract_price_levels_from_price_list_export(price_list_export)
        return {
            "source": "price-list-screen",
            "items": {
                "PRICELEVEL": [{"NAME": name} for name in price_levels],
            },
        }
    except HTTPException:
        if last_exc is not None:
            raise last_exc
    return {"source": "price-level-report", "items": {}}


def _extract_price_levels_from_price_list_export(payload: Dict[str, Any]) -> List[str]:
    items = payload.get("items") if isinstance(payload, dict) else None
    if not isinstance(items, dict):
        return []
    stock_items = items.get("STOCKITEM") or items.get("STOCKITEM.LIST") or items.get("STOCKITEMS")
    if stock_items is None:
        return []
    names: List[str] = []
    for stock_item in _listify(stock_items):
        if not isinstance(stock_item, dict):
            continue
        names.extend(_collect_price_level_names(stock_item))
    # Unique + stable order.
    seen: set[str] = set()
    unique: List[str] = []
    for name in names:
        if name not in seen:
            seen.add(name)
            unique.append(name)
    return unique


def _collect_price_level_names(node: Any, parent_key: Optional[str] = None) -> List[str]:
    names: List[str] = []
    normalized_parent = (parent_key or "").upper()

    if isinstance(node, dict):
        context_is_price_level = any(
            marker in normalized_parent
            for marker in ("PRICELEVEL", "FULLPRICELIST", "STANDARDPRICELIST", "PRICELIST")
        )
        if context_is_price_level:
            for candidate_key in ("PRICELEVEL", "PRICELEVELNAME", "LEVELNAME", "NAME", "#text"):
                candidate = node.get(candidate_key)
                if isinstance(candidate, str) and candidate.strip():
                    names.append(candidate.strip())

        for key, value in node.items():
            upper_key = str(key).upper()
            if upper_key == "PRICELEVEL" and isinstance(value, str) and value.strip():
                names.append(value.strip())
            names.extend(_collect_price_level_names(value, str(key)))
        return names

    if isinstance(node, list):
        for item in node:
            names.extend(_collect_price_level_names(item, parent_key))
        return names

    if isinstance(node, str) and node.strip():
        if any(marker in normalized_parent for marker in ("PRICELEVEL", "FULLPRICELIST", "STANDARDPRICELIST", "PRICELIST")):
            names.append(node.strip())
    return names


def _extract_tally_text(value: Any) -> Optional[str]:
    if isinstance(value, str):
        text = value.strip()
        return text or None
    if isinstance(value, dict):
        for key in ("#text", "NAME"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate.strip():
                return candidate.strip()
    return None


def _is_placeholder_text(value: Optional[str]) -> bool:
    if not isinstance(value, str):
        return True
    text = value.strip()
    return text in {"", "?", "??", "null", "None"}


def _first_non_placeholder_text(*values: Any) -> Optional[str]:
    for value in values:
        text = _extract_tally_text(value)
        if not _is_placeholder_text(text):
            return text.strip()
    return None


def _normalize_currency_exports(payload: Dict[str, Any]) -> Dict[str, Any]:
    collection = payload
    if isinstance(payload, dict) and "COLLECTION" in payload and isinstance(payload.get("COLLECTION"), dict):
        collection = payload["COLLECTION"]

    raw_items = []
    if isinstance(collection, dict):
        value = collection.get("CURRENCY")
        if isinstance(value, dict):
            raw_items = [value]
        elif isinstance(value, list):
            raw_items = [item for item in value if isinstance(item, dict)]

    normalized_items: List[Dict[str, Any]] = []
    for item in raw_items:
        name = _first_non_placeholder_text(
            item.get("NAME"),
            item.get("ORIGINALNAME"),
            item.get("MAILINGNAME"),
            item.get("EXPANDEDSYMBOL"),
            item.get("ISOCURRENCYCODE"),
            item.get("@NAME"),
            item.get("@RESERVEDNAME"),
        )
        if not name:
            continue

        original_name = _first_non_placeholder_text(
            item.get("ORIGINALNAME"),
            item.get("NAME"),
            item.get("MAILINGNAME"),
            item.get("EXPANDEDSYMBOL"),
            item.get("ISOCURRENCYCODE"),
        ) or name
        symbol = _first_non_placeholder_text(
            item.get("CURRENCYSYMBOL"),
            item.get("CURRSYMBOL"),
            item.get("EXPANDEDSYMBOL"),
            item.get("MAILINGNAME"),
        )
        iso_currency_code = _first_non_placeholder_text(item.get("ISOCURRENCYCODE"))
        formal_name = _first_non_placeholder_text(item.get("FORMALNAME"), item.get("CURRENCYNAME"))
        decimal_symbol = _first_non_placeholder_text(item.get("DECIMALSYMBOL"))
        decimal_places = _first_non_placeholder_text(item.get("DECIMALPLACES"))

        normalized: Dict[str, Any] = {
            "name": name,
            "originalName": original_name,
        }
        if symbol:
            normalized["symbol"] = symbol
        if iso_currency_code:
            normalized["isoCurrencyCode"] = iso_currency_code
        if formal_name:
            normalized["formalName"] = formal_name
        if decimal_symbol:
            normalized["decimalSymbol"] = decimal_symbol
        if decimal_places:
            normalized["decimalPlaces"] = decimal_places

        guid = _first_non_placeholder_text(item.get("GUID"))
        if guid:
            normalized["guid"] = guid

        placeholder_fields = []
        for field_name in ("NAME", "ORIGINALNAME", "ISOCURRENCYCODE", "CURRENCYNAME"):
            if _is_placeholder_text(_extract_tally_text(item.get(field_name))):
                placeholder_fields.append(field_name)
        if placeholder_fields:
            normalized["warning"] = {
                "type": "tally_placeholder_fields",
                "fields": placeholder_fields,
                "note": "Tally returned placeholder values for some native currency fields; the connector used fallback fields where possible.",
            }

        normalized_items.append(normalized)

    response: Dict[str, Any] = {
        "items": normalized_items,
        "count": len(normalized_items),
        "source": "normalized-generic-currency-export",
    }
    if raw_items and not normalized_items:
        response["warning"] = (
            "Tally returned only placeholder currency rows for the generic currency export in this build."
        )
    return response


def _stock_item_name_matches(stock_item: Dict[str, Any], expected_name: str) -> bool:
    expected = expected_name.strip().lower()
    candidates = [
        stock_item.get("@NAME"),
        stock_item.get("NAME"),
        _extract_tally_text(stock_item.get("LANGUAGENAME.LIST")),
    ]
    language_name = stock_item.get("LANGUAGENAME.LIST")
    if isinstance(language_name, dict):
        name_list = language_name.get("NAME.LIST")
        if isinstance(name_list, dict):
            nested_name = name_list.get("NAME")
            if isinstance(nested_name, str):
                candidates.append(nested_name)
    for candidate in candidates:
        if isinstance(candidate, str) and candidate.strip().lower() == expected:
            return True
    return False


def _stock_item_has_price_rows(stock_item: Dict[str, Any], price_level: str) -> bool:
    price_lists = []
    for key in ("FULLPRICELIST.LIST", "STANDARDPRICELIST.LIST", "PRICELEVELLIST.LIST"):
        price_lists.extend(_listify(stock_item.get(key)))
    if not price_lists:
        return False

    normalized_price_level = price_level.strip().lower()
    if not normalized_price_level:
        return True

    discovered_levels = [name.strip().lower() for name in _collect_price_level_names(stock_item) if isinstance(name, str) and name.strip()]
    if normalized_price_level in discovered_levels:
        return True

    # Some price-list exports are already filtered by SVPRICELEVEL and return only matching rows
    # without echoing the level name inside each row. In that case, the presence of any row is enough.
    return any(entry not in (None, {}, [], ()) for entry in price_lists)


def _stock_item_has_specific_price_row(
    stock_item: Dict[str, Any],
    price_level: str,
    effective_date: str,
    band: Dict[str, Any],
) -> bool:
    normalized_level = price_level.strip().lower()
    normalized_date = _normalize_tally_date(effective_date) or ""
    target_band_key = _price_band_key(band)

    for entry in _listify(stock_item.get("FULLPRICELIST.LIST")):
        if not isinstance(entry, dict):
            continue
        if _price_list_entry_level(entry).lower() != normalized_level:
            continue
        entry_date = _price_list_entry_date(entry)
        if normalized_date and entry_date != normalized_date:
            continue

        rows = _price_list_band_rows(entry)
        if not rows:
            return True
        for row in rows:
            if _price_band_key_from_row(row) == target_band_key:
                return True
    return False


def _listify_master_entries(value: Any, key: str) -> List[Dict[str, Any]]:
    if not isinstance(value, dict):
        return []
    entries = value.get(key)
    if isinstance(entries, dict):
        return [entries]
    if isinstance(entries, list):
        return [item for item in entries if isinstance(item, dict)]
    return []


def _filter_pay_head_exports(payload: Dict[str, Any]) -> Dict[str, Any]:
    ledgers = _listify_master_entries(payload, "LEDGER")
    if not ledgers:
        return payload
    filtered = []
    for ledger in ledgers:
        pay_head_type = _extract_tally_text(ledger.get("PAYHEADTYPE"))
        for_payroll = (_extract_tally_text(ledger.get("FORPAYROLL")) or "").strip().lower()
        if (pay_head_type and pay_head_type.strip()) or for_payroll == "yes":
            filtered.append(ledger)
    filtered_payload = dict(payload)
    filtered_payload["LEDGER"] = filtered
    return filtered_payload


def _filter_employee_group_exports(payload: Dict[str, Any]) -> Dict[str, Any]:
    cost_centres = _listify_master_entries(payload, "COSTCENTRE")
    if not cost_centres:
        return payload
    filtered = []
    for cost_centre in cost_centres:
        is_employee_group = (_extract_tally_text(cost_centre.get("ISEMPLOYEEGROUP")) or "").strip().lower()
        for_payroll = (_extract_tally_text(cost_centre.get("FORPAYROLL")) or "").strip().lower()
        if is_employee_group == "yes" and for_payroll == "yes":
            filtered.append(cost_centre)
    filtered_payload = dict(payload)
    filtered_payload["COSTCENTRE"] = filtered
    return filtered_payload


def _filter_employee_exports(payload: Dict[str, Any]) -> Dict[str, Any]:
    cost_centres = _listify_master_entries(payload, "COSTCENTRE")
    if not cost_centres:
        return payload
    filtered = []
    for cost_centre in cost_centres:
        use_as_employee = (_extract_tally_text(cost_centre.get("USEASEMPLOYEE")) or "").strip().lower()
        for_payroll = (_extract_tally_text(cost_centre.get("FORPAYROLL")) or "").strip().lower()
        if use_as_employee == "yes" and for_payroll == "yes":
            filtered.append(cost_centre)
    filtered_payload = dict(payload)
    filtered_payload["COSTCENTRE"] = filtered
    return filtered_payload


async def _price_list_write_visible_in_export(
    client: TallyClient,
    company_name: str,
    item_name: str,
    price_level: str,
    *,
    effective_date: str = "",
    band: Optional[Dict[str, Any]] = None,
) -> bool:
    export_payload = await _export_stock_item_price_lists(client, company_name, item_name)
    if not isinstance(export_payload, dict):
        return False
    stock_items = _listify_master_entries(export_payload, "STOCKITEM")
    for stock_item in stock_items:
        if not isinstance(stock_item, dict):
            continue
        if not _stock_item_name_matches(stock_item, item_name):
            continue
        if band is not None:
            if _stock_item_has_specific_price_row(stock_item, price_level, effective_date, band):
                return True
            continue
        if _stock_item_has_price_rows(stock_item, price_level):
            return True
    return False


async def _pay_head_write_visible_in_export(
    client: TallyClient,
    company_name: str,
    pay_head_name: str,
) -> bool:
    xml_req = build_collection_export("Payroll Ledgers", company_name)
    export_payload = await _post_xml(client, xml_req, "full")
    ledgers = _listify_master_entries(export_payload, "LEDGER")
    for ledger in ledgers:
        if not _stock_item_name_matches(ledger, pay_head_name):
            continue
        for_payroll = (_extract_tally_text(ledger.get("FORPAYROLL")) or "").strip().lower()
        if for_payroll == "yes":
            return True
    return False


async def _attendance_type_write_visible_in_export(
    client: TallyClient,
    company_name: str,
    attendance_type_name: str,
) -> bool:
    export_payload = await _export_master(
        client,
        company_name,
        "attendance type",
        fetch=["NAME"],
        limit=5000,
        view="full",
        collection_name=_collection_name("PYLEDGER", "attendance_type_write_visible"),
    )
    if _is_metadata_only_collection(export_payload):
        return False
    for attendance_type in _listify_master_entries(export_payload, "ATTENDANCETYPE"):
        if _stock_item_name_matches(attendance_type, attendance_type_name):
            return True
    return False


async def _attendance_types_export_available(
    client: TallyClient,
    company_name: str,
) -> bool:
    export_payload = await _export_master(
        client,
        company_name,
        "attendance type",
        fetch=["NAME"],
        limit=1,
        view="full",
        collection_name=_collection_name("PYLEDGER", "attendance_type_voucher_available"),
    )
    if _is_metadata_only_collection(export_payload):
        return False
    return bool(_listify_master_entries(export_payload, "ATTENDANCETYPE"))


async def _export_tdl_payroll_route(
    client: TallyClient,
    settings: Settings,
    company_name: Optional[str],
    route_path: str,
    view: str,
) -> Dict[str, Any]:
    master_type = PAYROLL_ROUTE_MASTER_TYPES[route_path]
    contract = _tdl_feature_contract(settings, "payroll_exports")
    collection_name = _tdl_feature_route_collection(contract, route_path)
    if collection_name:
        response = await _post_xml(client, build_collection_export(collection_name, company_name), view)
    else:
        report_name = (
            contract.get("report_name")
            or TDL_FEATURE_DEFAULTS["payroll_exports"]["report_name"]
        )
        response = await _post_xml(
            client,
            build_tdl_gateway_report(
                str(report_name),
                company=company_name,
                payload={"ROUTE": route_path, "ACTION": "LIST"},
            ),
            view,
        )
    if _is_tdl_scaffold_placeholder_response(response):
        raise HTTPException(
            status_code=501,
            detail=_payroll_route_unavailable_detail(
                master_type,
                (
                    f"The configured TDL payroll export for '{master_type}' is still serving the scaffold placeholder response, "
                    "so live payroll export is not implemented yet."
                ),
                response=response,
            ),
        )
    if _is_metadata_only_collection(response):
        raise HTTPException(
            status_code=501,
            detail=_payroll_route_unavailable_detail(
                master_type,
                (
                    f"The configured TDL payroll export for '{master_type}' returned only metadata and no actual records. "
                    "A real payroll collection/report implementation is still required."
                ),
                response=response,
            ),
        )
    return response


async def _employee_group_write_visible_in_export(
    client: TallyClient,
    company_name: str,
    group_name: str,
) -> bool:
    export_payload = await _export_master(
        client,
        company_name,
        "cost centre",
        fetch=["NAME", "ISEMPLOYEEGROUP", "FORPAYROLL"],
        limit=5000,
        view="full",
        collection_name=_collection_name("PYLEDGER", "employee_group_write_visible"),
    )
    cost_centres = _listify_master_entries(export_payload, "COSTCENTRE")
    filtered = _filter_employee_group_exports({"COSTCENTRE": cost_centres})
    for cost_centre in _listify_master_entries(filtered, "COSTCENTRE"):
        if _stock_item_name_matches(cost_centre, group_name):
            return True
    return False


async def _employee_write_visible_in_export(
    client: TallyClient,
    company_name: str,
    employee_name: str,
) -> bool:
    export_payload = await _export_master(
        client,
        company_name,
        "cost centre",
        fetch=["NAME", "PARENT", "CATEGORY", "USEASEMPLOYEE", "FORPAYROLL"],
        limit=5000,
        view="full",
        collection_name=_collection_name("PYLEDGER", "employee_write_visible"),
    )
    cost_centres = _listify_master_entries(export_payload, "COSTCENTRE")
    filtered = _filter_employee_exports({"COSTCENTRE": cost_centres})
    for cost_centre in _listify_master_entries(filtered, "COSTCENTRE"):
        if _stock_item_name_matches(cost_centre, employee_name):
            return True
    return False


async def _cost_centre_is_employee_group(
    client: TallyClient,
    company_name: Optional[str],
    name: str,
) -> bool:
    normalized_name = (name or "").strip()
    if not normalized_name:
        return False
    exported = await _export_master(
        client,
        company_name,
        "cost centre",
        fetch=["NAME", "ISEMPLOYEEGROUP", "FORPAYROLL"],
        limit=5000,
        view="full",
        collection_name=_collection_name("PYLEDGER", "employee_parent_validation"),
    )
    cost_centres = []
    if isinstance(exported, dict):
        value = exported.get("COSTCENTRE")
        if isinstance(value, dict):
            cost_centres = [value]
        elif isinstance(value, list):
            cost_centres = [item for item in value if isinstance(item, dict)]
    for cost_centre in cost_centres:
        if not _stock_item_name_matches(cost_centre, normalized_name):
            continue
        is_employee_group = _extract_tally_text(cost_centre.get("ISEMPLOYEEGROUP"))
        for_payroll = _extract_tally_text(cost_centre.get("FORPAYROLL"))
        if (is_employee_group or "").strip().lower() == "yes" and (for_payroll or "").strip().lower() == "yes":
            return True
    return False


def register_master(path: str, master_type: str):
    async def upsert_master(
        payload: Dict[str, Any],
        settings: Settings = Depends(get_settings),
        client: TallyClient = Depends(get_client),
        x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
        company: Optional[str] = Query(default=None, description="Optional company override"),
        x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
        action: Optional[str] = Query(default="Alter", description="Tally ACTION for masters (Create/Alter/Delete)"),
    ):
        await ensure_auth(x_agent_key, settings)
        effective_payload = payload
        normalized_master_type = (master_type or "").strip().lower()
        if normalized_master_type == "ledger":
            effective_payload = _normalize_ledger_payload(payload)
        if normalized_master_type == "pay head":
            effective_payload = _validate_pay_head_payload(payload)
        if normalized_master_type == "attendance type":
            effective_payload = _validate_attendance_type_payload(payload, action or "Alter")
        company_name = resolve_company(settings.company, effective_payload, company, x_company)
        normalized_action = (action or "Alter").strip().lower()
        if normalized_action not in {"create", "alter", "delete"}:
            raise HTTPException(
                status_code=400,
                detail={
                    "supported": True,
                    "fallback_applied": False,
                    "error_type": "validation",
                    "reason": (
                        f"Master route '{path}' supports only Create, Alter, and Delete. "
                        f"Action '{action}' is not valid for Tally master operations."
                    ),
                },
            )
        if normalized_master_type == "company" and normalized_action == "create":
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "company create",
                    "fallback_applied": False,
                    "reason": (
                        "POST /companies?action=Create uses the generic master-import path, and that "
                        "request is not stable for company creation in this Tally build. It can crash "
                        "the Tally XML interface, so the connector blocks it explicitly."
                    ),
                    "use_instead": "/companies/create",
                    "note": (
                        "/companies/create is also kept explicit right now until a real Tally-compatible "
                        "company-creation request is verified. Do not use the generic /companies create "
                        "action for company creation."
                    ),
                },
            )
        if normalized_master_type == "currency" and normalized_action == "create":
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "currency create",
                    "fallback_applied": False,
                    "error_type": "unsupported",
                    "implementation_path": {
                        "route_strategy": "generic master route",
                        "router_builder": "register_master",
                        "xml_builder": "build_master_upsert",
                        "tally_reportname": "All Masters",
                        "tally_object_tag": "CURRENCY",
                    },
                    "reason": (
                        "Live verification against this Tally build shows generic CURRENCY master creation is not a stable XML path. "
                        "Even valid-looking payloads return Tally's own validation error about Formal Name, and some variants can "
                        "destabilize the XML interface."
                    ),
                    "what_works": (
                        "Use GET/POST /settings/company-currency to manage the active company currency settings, "
                        "or create additional currencies manually in TallyPrime until a verified currency-master import path is found."
                    ),
                },
            )
        if normalized_master_type == "price level":
            return await _upsert_price_level(
                client,
                company_name,
                effective_payload,
                action or "Alter",
                settings=settings,
            )
        if normalized_master_type in GENERIC_PAYROLL_MASTER_WRITE_UNSUPPORTED:
            raise HTTPException(
                status_code=501,
                detail=_generic_payroll_master_write_unsupported_detail(normalized_master_type),
            )
        try:
            write_master_type = "ledger" if normalized_master_type == "pay head" else master_type
            xml_req = build_master_upsert(company_name, write_master_type, action or "Alter", effective_payload)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        response = await _post_xml(client, xml_req)
        if normalized_master_type in PAYROLL_MASTERS and _is_tally_noop_response(response):
            raise HTTPException(status_code=501, detail=_payroll_write_no_effect_detail(normalized_master_type, response))
        if normalized_master_type == "pay head":
            pay_head_name = _first_non_empty_string(
                effective_payload.get("NAME") if isinstance(effective_payload, dict) else None,
                payload.get("NAME") if isinstance(payload, dict) else None,
            )
            if pay_head_name:
                is_visible = await _pay_head_write_visible_in_export(client, company_name, pay_head_name)
                if normalized_action in {"create", "alter"} and not is_visible:
                    raise HTTPException(
                        status_code=501,
                        detail={
                            "supported": False,
                            "resource": "pay head",
                            "fallback_applied": False,
                            "error_type": "write_not_visible",
                            "implementation_path": {
                                "route_strategy": "pay-head via ledger upsert",
                                "xml_builder": "build_master_upsert",
                                "tally_reportname": "All Masters",
                                "tally_object_tag": "LEDGER",
                            },
                            "reason": (
                                "Tally acknowledged the pay-head write request, but the created/altered master is not visible "
                                "as a real Pay Head in Tally export. Live verification shows this Tally build is still persisting "
                                "these requests as ordinary ledgers without PAYHEADTYPE."
                            ),
                            "tally_response": response,
                        },
                    )
                if normalized_action == "delete" and is_visible:
                    raise HTTPException(
                        status_code=501,
                        detail={
                            "supported": False,
                            "resource": "pay head",
                            "fallback_applied": False,
                            "error_type": "delete_no_effect",
                            "reason": (
                                "Tally acknowledged the pay-head delete request, but the pay head is still visible in payroll export."
                            ),
                            "tally_response": response,
                        },
                    )
        if normalized_master_type == "attendance type":
            attendance_type_name = _first_non_empty_string(
                effective_payload.get("NAME") if isinstance(effective_payload, dict) else None,
                payload.get("NAME") if isinstance(payload, dict) else None,
            )
            if attendance_type_name:
                is_visible = await _attendance_type_write_visible_in_export(
                    client,
                    company_name,
                    attendance_type_name,
                )
                if normalized_action in {"create", "alter"} and not is_visible:
                    raise HTTPException(
                        status_code=501,
                        detail={
                            "supported": False,
                            "resource": "attendance type",
                            "fallback_applied": False,
                            "error_type": "write_not_visible",
                            "implementation_path": {
                                "route_strategy": "generic attendance-type route with export verification",
                                "xml_builder": "build_master_upsert",
                                "tally_reportname": "All Masters",
                                "tally_object_tag": "ATTENDANCETYPE",
                                "visibility_check": "full ATTENDANCETYPE export by NAME",
                            },
                            "reason": (
                                "Tally acknowledged the attendance-type write request, but the created or altered master is not "
                                "visible in attendance-type export. This Tally build may still require a dedicated payroll import "
                                "path for attendance masters."
                            ),
                            "tally_response": response,
                        },
                    )
                if normalized_action == "delete" and is_visible:
                    raise HTTPException(
                        status_code=501,
                        detail={
                            "supported": False,
                            "resource": "attendance type",
                            "fallback_applied": False,
                            "error_type": "delete_no_effect",
                            "reason": (
                                "Tally acknowledged the attendance-type delete request, but the attendance type is still visible in export."
                            ),
                            "tally_response": response,
                        },
                    )
        if normalized_master_type == "price level" and _is_tally_noop_response(response):
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "price level",
                    "fallback_applied": False,
                    "error_type": "unsupported",
                    "reason": (
                        "This Tally build ignores Price Level master upserts. "
                        "Create/maintain price levels via the Price List screen, "
                        "or use POST /price-lists to apply a level to a stock item."
                    ),
                },
            )
        return response

    async def list_masters(
        fetch: Optional[List[str]] = Query(default=None, description="Optional list of Tally fields to fetch"),
        filter_expr: Optional[str] = Query(default=None, alias="filter", description="Tally formula filter, e.g. $PARENT=\"Sundry Debtors\""),
        limit: Optional[int] = Query(default=None, description="Max records"),
        view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
        company: Optional[str] = Query(default=None, description="Optional company override"),
        x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
        settings: Settings = Depends(get_settings),
        client: TallyClient = Depends(get_client),
        x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    ):
        await ensure_auth(x_agent_key, settings)
        try:
            company_name = resolve_company(settings.company, None, company, x_company)
            collection_name = _collection_name(_master_collection_prefix(master_type), path)
            if (master_type or "").strip().lower() == "price level":
                return await _export_price_levels(
                    client,
                    company_name,
                    fetch,
                    limit,
                    view or "summary",
                    settings=settings,
                    route_path=path,
                )
            return await _export_master(
                client,
                company_name,
                master_type,
                fetch,
                filter_expr,
                limit,
                view=view or "summary",
                collection_name=collection_name,
                settings=settings,
                route_path=path,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))

    router.add_api_route(path, upsert_master, methods=["POST"])
    router.add_api_route(path, list_masters, methods=["GET"])


@router.post("/price-lists")
async def price_lists_upsert(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _upsert_price_list(client, company_name, payload, action or "Alter")


@router.get("/price-lists")
async def price_lists(
    stock_group: Optional[str] = Query(default=None, description="Optional stock group selection used by the native Tally Price List screen"),
    stock_category: Optional[str] = Query(default=None, description="Optional stock category selection used by the native Tally Price List screen"),
    price_level: Optional[str] = Query(default=None, description="Optional price level selection used by the native Tally Price List screen"),
    price_level_date: Optional[str] = Query(default=None, description="Optional applicable date for the native Tally Price List screen"),
    show_all_items: Optional[bool] = Query(default=None, description="Show all items for the selected price level"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    return await _export_price_lists(
        client,
        company_name,
        view=normalized_view,
        stock_group=stock_group,
        stock_category=stock_category,
        price_level=price_level,
        price_level_date=price_level_date,
        show_all_items=show_all_items,
    )


def register_voucher_route(path: str, voucher_type: str, list_report_name: Optional[str] = None):
    async def upsert_voucher(
        payload: Any = Body(...),
        action: Optional[str] = Query(default="Create", description="Tally ACTION for vouchers (Create/Alter/Cancel/Delete)"),
        company: Optional[str] = Query(default=None, description="Company override"),
        x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
        x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
        settings: Settings = Depends(get_settings),
        client: TallyClient = Depends(get_client),
    ):
        await ensure_auth(x_agent_key, settings)
        payload = _unwrap_single_voucher_payload(payload)
        company_name = resolve_company(settings.company, payload, company, x_company)
        try:
            normalized_action = (action or "Create").strip().lower()
            if normalized_action not in {"create", "alter", "cancel", "delete"}:
                raise HTTPException(
                    status_code=400,
                    detail={
                        "supported": True,
                        "fallback_applied": False,
                        "error_type": "validation",
                        "reason": (
                            f"Voucher route '{path}' supports Create, Alter, Cancel, and Delete. "
                            f"Action '{action}' is not valid."
                        ),
                    },
                )
            effective_payload = payload
            normalized_voucher_type = voucher_type.strip().lower()
            if normalized_action in {"create", "alter"}:
                effective_payload = _normalize_inventory_consumption_payload(effective_payload, voucher_type)
            if normalized_action == "create" and normalized_voucher_type == "sales":
                effective_payload = await _prepare_sales_create_payload(
                    client,
                    company_name,
                    effective_payload,
                    action or "Create",
                )
            if normalized_action == "create" and normalized_voucher_type in {"sales order", "purchase order"}:
                effective_payload = _prepare_order_create_payload(
                    effective_payload,
                    voucher_type,
                    action or "Create",
                )
                effective_payload = await _prepare_order_template_import_payload(
                    client,
                    company_name,
                    effective_payload,
                    voucher_type,
                    list_report_name or f"{voucher_type} Vouchers",
                    action or "Create",
                )
            if normalized_action == "create" and normalized_voucher_type == "purchase":
                effective_payload = await _prepare_purchase_create_payload(
                    client,
                    company_name,
                    effective_payload,
                    action or "Create",
                )
            if normalized_action in {"create", "alter"} and normalized_voucher_type == "attendance":
                if not await _attendance_types_export_available(client, company_name or ""):
                    raise HTTPException(
                        status_code=501,
                        detail=_attendance_voucher_feature_unavailable_detail(),
                    )
            if normalized_action == "create" and normalized_voucher_type in {"delivery note", "receipt note"}:
                effective_payload = await _prepare_inventory_invoice_template_import_payload(
                    client,
                    company_name,
                    effective_payload,
                    voucher_type,
                    action or "Create",
                )
            xml_req = build_voucher_upsert(
                await _preferred_write_company_context(client, company_name),
                action,
                effective_payload,
                voucher_type,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        response = await _post_xml(client, xml_req)
        await _verify_voucher_write_safety(client, company_name, response, action, effective_payload, voucher_type)
        return response

    async def list_voucher(
        fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
        filter_expr: Optional[str] = Query(default=None, alias="filter", description="Tally formula filter"),
        limit: Optional[int] = Query(default=None, description="Max records"),
        from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
        to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
        view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
        company: Optional[str] = Query(default=None, description="Company override"),
        x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
        x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
        settings: Settings = Depends(get_settings),
        client: TallyClient = Depends(get_client),
    ):
        await ensure_auth(x_agent_key, settings)
        try:
            company_name = resolve_company(settings.company, None, company, x_company)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        normalized_view = _normalize_view(view)
        if list_report_name:
            if filter_expr:
                raise HTTPException(
                    status_code=400,
                    detail={
                        "supported": True,
                        "fallback_applied": False,
                        "error_type": "validation",
                        "reason": (
                            f"{voucher_type} export uses the built-in Tally report "
                            f"'{list_report_name}', which does not support the generic "
                            "collection filter expression in this connector path."
                        ),
                    },
                )
            report_data = await _export_report(client, list_report_name, company_name, from_date, to_date, view=normalized_view)
            if normalized_view == "raw":
                return report_data
            effective_fetch = None
            if normalized_view != "full":
                effective_fetch = _report_summary_fetch(fetch, voucher_type)
            return _shape_report_vouchers(
                report_data,
                list_report_name,
                voucher_type,
                effective_fetch,
                limit,
            )
        return await _export_daybook_vouchers(
            client,
            company_name,
            voucher_type=voucher_type,
            fetch=fetch,
            filter_expr=filter_expr,
            limit=limit,
            from_date=from_date,
            to_date=to_date,
            view=normalized_view,
        )

    router.add_api_route(path, upsert_voucher, methods=["POST"])
    router.add_api_route(path, list_voucher, methods=["GET"])


# Core master routes
register_master("/companies", "company")
register_master("/currencies", "currency")

# Ledger + groups
register_master("/groups", "group")
register_master("/ledger-groups", "group")
register_master("/ledgers", "ledger")

# Costing / projects
register_master("/cost-categories", "cost category")
register_master("/cost-centres", "cost centre")
register_master("/projects", "cost centre")

# Inventory masters
register_master("/uoms", "unit")
register_master("/godowns", "godown")
register_master("/stock-groups", "stock group")
register_master("/stock-categories", "stock category")
register_master("/stock-items", "stock item")
register_master("/boms", "bom")

# Pricing / voucher config
register_master("/price-levels", "price level")
register_master("/voucher-types", "voucher type")
register_master("/budgets", "budget")

# Payroll masters
register_master("/pay-heads", "pay head")
register_master("/attendance-types", "attendance type")


@router.post("/employee-groups")
async def employee_groups_upsert(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION for employee groups (Create/Alter/Delete)"),
    company: Optional[str] = Query(default=None, description="Optional company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_action = (action or "Create").strip().lower()
    if normalized_action not in {"create", "alter", "delete"}:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": (
                    "Employee group route supports only Create, Alter, and Delete. "
                    f"Action '{action}' is not valid for employee-group master operations."
                ),
            },
        )

    normalized_payload = _validate_employee_group_payload(payload, action or "Create")
    company_name = resolve_company(settings.company, payload, company, x_company)
    try:
        xml_req = build_master_upsert(company_name, "cost centre", action or "Create", normalized_payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    response = await _post_xml(client, xml_req)
    group_name = _first_non_empty_string(
        normalized_payload.get("NAME") if isinstance(normalized_payload, dict) else None,
        payload.get("NAME") if isinstance(payload, dict) else None,
    )
    if _is_tally_noop_response(response):
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "employee group",
                "fallback_applied": False,
                "error_type": "write_no_effect",
                "implementation_path": {
                    "route_strategy": "dedicated employee-group route",
                    "xml_builder": "build_master_upsert",
                    "tally_reportname": "All Masters",
                    "tally_object_tag": "COSTCENTRE",
                    "employee_group_model": "cost centre with FORPAYROLL=Yes and ISEMPLOYEEGROUP=Yes",
                },
                "reason": (
                    "Tally acknowledged the dedicated employee-group write request, but it did not create, alter, or delete anything. "
                    "The connector now writes employee groups through COSTCENTRE in payroll mode, which matches the live Tally export "
                    "shape better than the old generic EMPLOYEEGROUP import."
                ),
                "tally_response": response,
            },
        )
    if group_name:
        if normalized_action in {"create", "alter"} and not await _employee_group_write_visible_in_export(client, company_name, group_name):
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "employee group",
                    "fallback_applied": False,
                    "error_type": "write_not_visible",
                    "implementation_path": {
                        "route_strategy": "dedicated employee-group route",
                        "xml_builder": "build_master_upsert",
                        "tally_object_tag": "COSTCENTRE",
                        "visibility_check": "filtered COSTCENTRE export with ISEMPLOYEEGROUP=Yes and FORPAYROLL=Yes",
                    },
                    "reason": (
                        "Tally acknowledged the employee-group write request, but the resulting cost centre is not visible "
                        "as a real payroll employee group in export. This usually means Tally persisted only a generic cost centre shell."
                    ),
                    "tally_response": response,
                },
            )
        if normalized_action == "delete" and await _employee_group_write_visible_in_export(client, company_name, group_name):
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "employee group",
                    "fallback_applied": False,
                    "error_type": "delete_no_effect",
                    "reason": (
                        "Tally acknowledged the employee-group delete request, but the employee group is still visible in payroll export."
                    ),
                    "tally_response": response,
                },
            )
    return response


@router.get("/employee-groups")
async def employee_groups_list(
    fetch: Optional[List[str]] = Query(default=None, description="Optional list of employee-group fields to fetch"),
    filter_expr: Optional[str] = Query(default=None, alias="filter", description="Tally formula filter"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Optional company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    if (
        settings.tdl_integration_enabled
        and _tdl_feature_is_ready(settings, "payroll_exports")
        and not fetch
        and not filter_expr
        and limit is None
        and normalized_view == "summary"
    ):
        return await _export_tdl_payroll_route(
            client,
            settings,
            company_name,
            "/employee-groups",
            normalized_view,
        )
    default_fetch = [
        "NAME",
        "PARENT",
        "CATEGORY",
        "FORPAYROLL",
        "ISEMPLOYEEGROUP",
        "GRPATTENDANCE",
        "GRPPAYHEAD",
    ]
    response = await _export_master(
        client,
        company_name,
        "employee group",
        fetch or default_fetch,
        filter_expr,
        limit,
        view=normalized_view,
        collection_name=_collection_name("PYLEDGER", "employee_groups"),
    )
    if _is_metadata_only_collection(response):
        raise HTTPException(status_code=501, detail=_payroll_collection_unavailable_detail("employee group"))
    return _filter_employee_group_exports(response)


@router.post("/employees")
async def employees_upsert(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION for employees (Create/Alter/Delete)"),
    company: Optional[str] = Query(default=None, description="Optional company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_action = (action or "Create").strip().lower()
    if normalized_action not in {"create", "alter", "delete"}:
        raise HTTPException(
            status_code=400,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": (
                    "Employee route supports only Create, Alter, and Delete. "
                    f"Action '{action}' is not valid for employee master operations."
                ),
            },
        )

    normalized_payload = _validate_employee_payload(payload, action or "Create")
    company_name = resolve_company(settings.company, payload, company, x_company)
    parent_name = _first_non_empty_string(
        normalized_payload.get("PARENT"),
        payload.get("PARENT") if isinstance(payload.get("PARENT"), str) else None,
    )
    if normalized_action in {"create", "alter"}:
        if not await _cost_centre_is_employee_group(client, company_name, parent_name):
            raise HTTPException(
                status_code=400,
                detail={
                    "supported": True,
                    "resource": "employee",
                    "fallback_applied": False,
                    "error_type": "validation",
                    "reason": (
                        f"Parent '{parent_name or 'Primary'}' is not a valid Employee Group in Tally. "
                        "A true Tally employee must be created under an Employee Group; otherwise the write may "
                        "succeed as a cost centre shell but not appear in Tally's List of Employees."
                    ),
                    "what_to_do": (
                        "Create the Employee Group manually in Tally first, or wait until the connector gets a dedicated "
                        "employee-group implementation."
                    ),
                },
            )
    try:
        xml_req = build_master_upsert(company_name, "cost centre", action or "Create", normalized_payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    response = await _post_xml(client, xml_req)
    employee_name = _first_non_empty_string(
        normalized_payload.get("NAME") if isinstance(normalized_payload, dict) else None,
        payload.get("NAME") if isinstance(payload, dict) else None,
    )
    if _is_tally_noop_response(response):
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": "employee",
                "fallback_applied": False,
                "error_type": "write_no_effect",
                "implementation_path": {
                    "route_strategy": "dedicated employee route",
                    "xml_builder": "build_master_upsert",
                    "tally_reportname": "All Masters",
                    "tally_object_tag": "COSTCENTRE",
                    "employee_model": "cost centre with USEASEMPLOYEE=Yes",
                },
                "reason": (
                    "Tally acknowledged the dedicated employee write request, but it did not create, alter, or delete anything. "
                    "The connector now writes employees through COSTCENTRE with USEASEMPLOYEE=Yes, which matches the verified "
                    "Tally payroll master path better than the old generic EMPLOYEE import."
                ),
                "tally_response": response,
            },
        )
    if employee_name:
        if normalized_action in {"create", "alter"} and not await _employee_write_visible_in_export(client, company_name, employee_name):
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "employee",
                    "fallback_applied": False,
                    "error_type": "write_not_visible",
                    "implementation_path": {
                        "route_strategy": "dedicated employee route",
                        "xml_builder": "build_master_upsert",
                        "tally_object_tag": "COSTCENTRE",
                        "visibility_check": "filtered COSTCENTRE export with USEASEMPLOYEE=Yes and FORPAYROLL=Yes",
                    },
                    "reason": (
                        "Tally acknowledged the employee write request, but the resulting cost centre is not visible "
                        "as a real payroll employee in export. This usually means Tally persisted only a generic cost centre shell."
                    ),
                    "tally_response": response,
                },
            )
        if normalized_action == "delete" and await _employee_write_visible_in_export(client, company_name, employee_name):
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "employee",
                    "fallback_applied": False,
                    "error_type": "delete_no_effect",
                    "reason": (
                        "Tally acknowledged the employee delete request, but the employee is still visible in payroll export."
                    ),
                    "tally_response": response,
                },
            )
    return response


@router.get("/employees")
async def employees_list(
    fetch: Optional[List[str]] = Query(default=None, description="Optional list of employee fields to fetch"),
    filter_expr: Optional[str] = Query(default=None, alias="filter", description="Tally formula filter"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Optional company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    if (
        settings.tdl_integration_enabled
        and _tdl_feature_is_ready(settings, "payroll_exports")
        and not fetch
        and not filter_expr
        and limit is None
        and normalized_view == "summary"
    ):
        return await _export_tdl_payroll_route(
            client,
            settings,
            company_name,
            "/employees",
            normalized_view,
        )
    default_fetch = [
        "NAME",
        "PARENT",
        "CATEGORY",
        "EMPDISPLAYNAME",
        "DATEOFJOIN",
        "MOBILENUMBER",
        "EMAILID",
        "DESIGNATION",
        "PANNUMBER",
        "AADHARNUMBER",
        "UANNUMBER",
        "PFACCOUNTNUMBER",
        "ESINUMBER",
        "BANKACCOUNTNUMBER",
        "BANKBRANCH",
        "IFSCODE",
    ]
    response = await _export_master(
        client,
        company_name,
        "cost centre",
        fetch or default_fetch,
        filter_expr,
        limit,
        view=normalized_view,
        collection_name=_collection_name("PYLEDGER", "employees"),
    )
    if _is_metadata_only_collection(response):
        raise HTTPException(status_code=501, detail=_payroll_collection_unavailable_detail("employee"))
    return _filter_employee_exports(response)

# Voucher routes (generic)

async def _voucher_write_visible_in_export(
    client: TallyClient,
    company_name: Optional[str],
    voucher_type: str,
    voucher_date: str,
    voucher_number: Optional[str],
) -> bool:
    if not voucher_date:
        return True
    report_data = await _export_daybook_vouchers(
        client,
        company_name,
        voucher_type=voucher_type,
        fetch=["VOUCHERNUMBER", "DATE", "VOUCHERTYPENAME"],
        from_date=voucher_date,
        to_date=voucher_date,
        view="summary"
    )
    shaped = _shape_report_vouchers(report_data, "Daybook Vouchers")
    vouchers = shaped.get("VOUCHER") if isinstance(shaped, dict) else None
    if not isinstance(vouchers, list):
        return False
    if not vouchers and not voucher_number:
        return False
    for v in vouchers:
        if voucher_number and _first_non_empty_string(v.get("VOUCHERNUMBER")) != voucher_number:
            continue
        return True
    return False


async def _verify_voucher_write_safety(
    client: TallyClient,
    company_name: Optional[str],
    response: Dict[str, Any],
    action: str,
    payload: Dict[str, Any],
    voucher_type: Optional[str],
) -> None:
    if _is_tally_noop_response(response) or _tally_import_exceptions(response) > 0:
        normalized_voucher_type = (voucher_type or "generic").strip().lower()
        if normalized_voucher_type in {"purchase", "purchase order"}:
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": f"{normalized_voucher_type} voucher",
                    "fallback_applied": False,
                    "error_type": "write_no_effect",
                    "reason": (
                        f"Tally accepted the {voucher_type} voucher import request but did not create or modify it. "
                        "In this local Tally build, Purchase-family vouchers are behaving like invoice-style inventory vouchers, "
                        "and the current generic accounting payload is not enough for Tally to persist them."
                    ),
                    "what_to_do": (
                        "Use a verified Purchase template from Tally or send inventory-style entries with stock-item setup. "
                        "Journal, Payment, and Sales are currently the stable create paths in this environment."
                    ),
                    "tally_response": response,
                },
            )
        if normalized_voucher_type in {"material in", "material out"}:
            raise HTTPException(
                status_code=501,
                detail=_inventory_material_voucher_write_no_effect_detail(
                    voucher_type or normalized_voucher_type,
                    response,
                ),
            )
        raise HTTPException(
            status_code=501,
            detail={
                "supported": False,
                "resource": f"{normalized_voucher_type} voucher",
                "fallback_applied": False,
                "error_type": "write_no_effect",
                "reason": (
                    f"Tally accepted the {voucher_type or 'generic'} voucher import request but did not create or modify it. "
                    "This happens when ledgers are missing, amounts don't match, or the generic XML path is not fully supported for this voucher type."
                ),
                "tally_response": response,
            },
        )

    normalized_action = (action or "Create").strip().lower()
    req_date = _first_non_empty_string(payload.get("DATE"), payload.get("EFFECTIVEDATE"))
    req_vch_no = _first_non_empty_string(payload.get("VOUCHERNUMBER"))

    if normalized_action in {"create", "alter"} and req_date and req_vch_no and voucher_type:
        is_visible = await _voucher_write_visible_in_export(
            client, company_name, voucher_type, req_date, req_vch_no
        )
        if not is_visible:
            # Live Tally import acknowledgements are often more trustworthy than the generic
            # Day Book export path, which can lag behind, filter incorrectly, or read a
            # different active company context. Preserve the write success and attach a warning
            # instead of turning a successful import into a hard API failure.
            if isinstance(response, dict):
                response.setdefault("_connector_warning", {})
                response["_connector_warning"] = {
                    "supported": True,
                    "resource": f"{voucher_type.lower()} voucher",
                    "fallback_applied": True,
                    "error_type": "write_visibility_unverified",
                    "reason": (
                        f"Tally acknowledged the {voucher_type} voucher write, but the connector could not immediately "
                        "verify it through Day Book export. The voucher may still be created correctly; confirm it in "
                        "Tally or re-query after the active company/report context refreshes."
                    ),
                    "verification_inputs": {
                        "company": company_name,
                        "voucher_type": voucher_type,
                        "date": req_date,
                        "voucher_number": req_vch_no,
                    },
                }


@router.post("/vouchers")
async def voucher_upsert(
    payload: Any = Body(...),
    action: Optional[str] = Query(default="Create", description="Tally ACTION for vouchers (Create/Alter/Cancel/Delete)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type name (if not in payload)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    payload = _unwrap_single_voucher_payload(payload)
    company_name = resolve_company(settings.company, payload, company, x_company)
    try:
        normalized_action = (action or "Create").strip().lower()
        if normalized_action not in {"create", "alter", "cancel", "delete"}:
            raise HTTPException(
                status_code=400,
                detail={
                    "supported": True,
                    "fallback_applied": False,
                    "error_type": "validation",
                    "reason": (
                        "Generic voucher route supports Create, Alter, Cancel, and Delete. "
                        f"Action '{action}' is not valid."
                    ),
                },
            )
        resolved_voucher_type = voucher_type or _first_non_empty_string(
            payload.get("VOUCHERTYPENAME"),
            payload.get("VOUCHERTYPE"),
        )
        effective_payload = payload
        if normalized_action in {"create", "alter"} and resolved_voucher_type:
            effective_payload = _normalize_inventory_consumption_payload(effective_payload, resolved_voucher_type)
        if normalized_action == "create" and (resolved_voucher_type or "").strip().lower() == "purchase":
            effective_payload = await _prepare_purchase_create_payload(
                client,
                company_name,
                effective_payload,
                action or "Create",
            )
        xml_req = build_voucher_upsert(
            await _preferred_write_company_context(client, company_name),
            action,
            effective_payload,
            voucher_type,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    response = await _post_xml(client, xml_req)
    await _verify_voucher_write_safety(client, company_name, response, action, effective_payload, resolved_voucher_type)
    return response


@router.get("/vouchers")
async def voucher_list(
    voucher_type: Optional[str] = Query(default=None, description="Filter by VOUCHERTYPENAME"),
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    filter_expr: Optional[str] = Query(default=None, alias="filter", description="Tally formula filter"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        company_name = resolve_company(settings.company, None, company, x_company)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return await _export_daybook_vouchers(
        client,
        company_name,
        voucher_type=voucher_type,
        fetch=fetch,
        filter_expr=filter_expr,
        limit=limit,
        view=view or "summary",
    )


# Voucher routes (type-specific wrappers)

register_voucher_route("/vouchers/sales", "Sales")
register_voucher_route("/vouchers/purchase", "Purchase")
register_voucher_route("/vouchers/sales-orders", "Sales Order", list_report_name="Sales Order Vouchers")
register_voucher_route("/vouchers/purchase-orders", "Purchase Order", list_report_name="Purchase Order Vouchers")
register_voucher_route("/vouchers/delivery-notes", "Delivery Note")
register_voucher_route("/vouchers/goods-receipts", "Receipt Note")
register_voucher_route("/vouchers/stock-journals", "Stock Journal")
register_voucher_route("/vouchers/material-in", "Material In")
register_voucher_route("/vouchers/material-out", "Material Out")
register_voucher_route("/vouchers/manufacturing", "Stock Journal")
register_voucher_route("/vouchers/receipt", "Receipt")
register_voucher_route("/vouchers/payment", "Payment")
register_voucher_route("/vouchers/contra", "Contra")
register_voucher_route("/vouchers/journal", "Journal")
register_voucher_route("/vouchers/credit-notes", "Credit Note")
register_voucher_route("/vouchers/debit-notes", "Debit Note")
register_voucher_route("/vouchers/rejections-in", "Rejections In")
register_voucher_route("/vouchers/rejections-out", "Rejections Out")
register_voucher_route("/vouchers/payroll", "Payroll")
register_voucher_route("/vouchers/physical-stock", "Physical Stock")
register_voucher_route("/vouchers/attendance", "Attendance")
register_voucher_route("/vouchers/job-work-in-orders", "Job Work In Order")
register_voucher_route("/vouchers/job-work-out-orders", "Job Work Out Order")
register_voucher_route("/vouchers/memorandum", "Memorandum")
register_voucher_route("/vouchers/reversing-journal", "Reversing Journal")

async def _prepare_sales_ready_submission(
    payload: Dict[str, Any],
    action: str,
    company: Optional[str],
    x_company: Optional[str],
    settings: Settings,
    client: TallyClient,
    include_einvoice: bool,
    include_ewaybill: bool,
) -> tuple[str, Dict[str, Any], Dict[str, Any]]:
    normalized_payload = _prepare_sales_ready_payload(payload, include_einvoice, include_ewaybill)
    company_name = resolve_company(settings.company, normalized_payload, company, x_company)
    import_payload = await _prepare_sales_ready_import_payload(client, company_name, normalized_payload, action)
    return company_name, normalized_payload, import_payload


async def _sales_ready_voucher_upsert(
    payload: Dict[str, Any],
    action: str,
    company: Optional[str],
    x_company: Optional[str],
    settings: Settings,
    client: TallyClient,
    include_einvoice: bool,
    include_ewaybill: bool,
) -> Dict[str, Any]:
    company_name, _, import_payload = await _prepare_sales_ready_submission(
        payload,
        action,
        company,
        x_company,
        settings,
        client,
        include_einvoice,
        include_ewaybill,
    )
    try:
        xml_req = build_voucher_upsert(
            await _preferred_write_company_context(client, company_name),
            action,
            import_payload,
            "Sales",
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    response = await _post_xml(client, xml_req)
    await _verify_voucher_write_safety(client, company_name, response, action, import_payload, "Sales")
    return response


async def _common_ready_voucher_upsert(
    payload: Dict[str, Any],
    action: str,
    voucher_type_override: Optional[str],
    company: Optional[str],
    x_company: Optional[str],
    settings: Settings,
    client: TallyClient,
    include_einvoice: bool,
    include_ewaybill: bool,
) -> Dict[str, Any]:
    compliance_mode = "einvoice_ewaybill" if include_einvoice and include_ewaybill else ("einvoice" if include_einvoice else "ewaybill")
    canonical_voucher_type = _resolve_common_ready_voucher_type(payload, voucher_type_override)
    _validate_common_ready_voucher_type(canonical_voucher_type, compliance_mode)

    normalized_payload = copy.deepcopy(payload)
    voucher_data = _coerce_mapping(normalized_payload.get("voucher"), "voucher")
    voucher_data.setdefault("voucher_type", canonical_voucher_type)
    normalized_payload["voucher"] = voucher_data
    normalized_payload["voucher_type"] = canonical_voucher_type

    if canonical_voucher_type == "Sales":
        return await _sales_ready_voucher_upsert(
            normalized_payload,
            action,
            company,
            x_company,
            settings,
            client,
            include_einvoice,
            include_ewaybill,
        )

    raise HTTPException(
        status_code=501,
        detail={
            "supported": False,
            "resource": f"{canonical_voucher_type.lower()} {compliance_mode} ready",
            "fallback_applied": False,
            "reason": f"{canonical_voucher_type} routing is not implemented in this connector build yet.",
        },
    )


def _filter_sales_status_vouchers(
    vouchers: List[Dict[str, Any]],
    voucher_number: Optional[str] = None,
    reference: Optional[str] = None,
    party_ledger_name: Optional[str] = None,
    keep_fields: Optional[List[str]] = None,
    limit: Optional[int] = None,
) -> List[Dict[str, Any]]:
    normalized_voucher_number = voucher_number.strip() if isinstance(voucher_number, str) and voucher_number.strip() else None
    normalized_reference = reference.strip() if isinstance(reference, str) and reference.strip() else None
    normalized_party = party_ledger_name.strip() if isinstance(party_ledger_name, str) and party_ledger_name.strip() else None
    allowed_fields = set(keep_fields or [])

    filtered: List[Dict[str, Any]] = []
    for voucher in vouchers:
        if not isinstance(voucher, dict):
            continue
        if normalized_voucher_number and _first_non_empty_string(voucher.get("VOUCHERNUMBER")) != normalized_voucher_number:
            continue
        if normalized_reference and _first_non_empty_string(voucher.get("REFERENCE")) != normalized_reference:
            continue
        if normalized_party:
            voucher_party = _first_non_empty_string(voucher.get("PARTYLEDGERNAME"), voucher.get("PARTYNAME"))
            if voucher_party != normalized_party:
                continue

        if allowed_fields:
            shaped = {k: v for k, v in voucher.items() if k.startswith("@") or k in allowed_fields}
        else:
            shaped = voucher
        filtered.append(shaped)

    if limit is not None:
        filtered = filtered[: max(limit, 0)]
    return filtered


async def _sales_status_response(
    status_type: str,
    status_fields: List[str],
    company: Optional[str],
    x_company: Optional[str],
    voucher_number: Optional[str],
    reference: Optional[str],
    party_ledger_name: Optional[str],
    from_date: Optional[str],
    to_date: Optional[str],
    limit: Optional[int],
    settings: Settings,
    client: TallyClient,
) -> Dict[str, Any]:
    company_name = resolve_company(settings.company, None, company, x_company)
    report_data = await _export_report(client, "Sales Vouchers", company_name, from_date, to_date, view="summary")
    shaped = _shape_report_vouchers(report_data, "Sales Vouchers")
    vouchers = shaped.get("VOUCHER") if isinstance(shaped, dict) else None
    filtered_vouchers = _filter_sales_status_vouchers(
        vouchers if isinstance(vouchers, list) else [],
        voucher_number=voucher_number,
        reference=reference,
        party_ledger_name=party_ledger_name,
        keep_fields=status_fields,
        limit=limit,
    )

    filters: Dict[str, Any] = {}
    if isinstance(voucher_number, str) and voucher_number.strip():
        filters["voucher_number"] = voucher_number.strip()
    if isinstance(reference, str) and reference.strip():
        filters["reference"] = reference.strip()
    if isinstance(party_ledger_name, str) and party_ledger_name.strip():
        filters["party_ledger_name"] = party_ledger_name.strip()
    normalized_from = _normalize_tally_date(from_date)
    normalized_to = _normalize_tally_date(to_date)
    if normalized_from:
        filters["from_date"] = normalized_from
    if normalized_to:
        filters["to_date"] = normalized_to

    return {
        "status_type": status_type,
        "source_report": "Sales Vouchers",
        "filters": filters,
        "VOUCHER": filtered_vouchers,
    }


def _validate_common_generate_voucher_type(
    voucher_type: str,
    compliance_mode: str,
) -> None:
    support_details = COMMON_GENERATE_SUPPORT_MATRIX.get(compliance_mode, {})
    implemented_types = support_details.get("implemented", [])
    documented_types = support_details.get("documented", [])
    if voucher_type in implemented_types:
        return

    raise HTTPException(
        status_code=501,
        detail=_common_compliance_unsupported_detail(
            compliance_mode=compliance_mode,
            operation="generate",
            voucher_type=voucher_type,
            implemented_types=implemented_types,
            documented_types=documented_types,
        ),
    )


def _provider_yes_no_flag(value: Any, default: Optional[str] = None) -> Optional[str]:
    normalized = _coerce_tally_yes_no(value)
    if normalized == "Yes":
        return "Y"
    if normalized == "No":
        return "N"
    return default


def _provider_transport_mode(value: Any) -> Optional[str]:
    normalized = _normalize_transport_mode(value)
    if not normalized:
        return None
    return TRANSPORT_MODE_TO_PROVIDER.get(normalized, normalized)


def _provider_vehicle_type(value: Any) -> Optional[str]:
    normalized = _normalize_vehicle_type(value)
    if not normalized:
        return None
    return VEHICLE_TYPE_TO_PROVIDER.get(normalized, normalized)


def _provider_to_tally_transport_mode(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    return TRANSPORT_MODE_FROM_PROVIDER.get(text, _normalize_transport_mode(text))


def _provider_to_tally_vehicle_type(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    return VEHICLE_TYPE_FROM_PROVIDER.get(text, _normalize_vehicle_type(text))


def _gst_state_code_from_gstin(gstin: Any) -> Optional[str]:
    text = _first_non_empty_string(gstin)
    if not text:
        return None
    code = text[:2]
    return code if code.isdigit() else None


def _resolve_gst_state_code(value: Any = None, gstin: Any = None) -> Optional[str]:
    from_gstin = _gst_state_code_from_gstin(gstin)
    if from_gstin:
        return from_gstin

    text = _first_non_empty_string(value)
    if not text:
        return None
    if len(text) >= 2 and text[:2].isdigit():
        return text[:2]

    normalized = re.sub(r"\s+", " ", text.replace("&", "and").strip().lower())
    return GST_STATE_CODE_BY_NAME.get(normalized)


def _provider_date(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    if re.fullmatch(r"\d{2}/\d{2}/\d{4}", text):
        return text
    return _parse_yyyymmdd(text).strftime("%d/%m/%Y")


def _provider_datetime_to_tally_date(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%d/%m/%Y %H:%M:%S", "%d/%m/%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(text, fmt).strftime("%Y%m%d")
        except ValueError:
            continue
    try:
        return _normalize_tally_date(text)
    except Exception:
        return None


def _provider_address_parts(data: Dict[str, Any]) -> tuple[str, str]:
    address_lines = [
        line.strip()
        for line in _listify(data.get("address_lines"))
        if isinstance(line, str) and line.strip()
    ]
    address1 = _first_non_empty_string(data.get("address1"), address_lines[0] if address_lines else None) or ""
    address2 = _first_non_empty_string(data.get("address2"), address_lines[1] if len(address_lines) > 1 else None) or ""
    return address1, address2


def _provider_phone_value(value: Any) -> Optional[str]:
    text = _first_non_empty_string(value)
    if not text:
        return None
    return text


def _stringify_non_empty(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        return text or None
    text = str(value).strip()
    return text or None


def _parse_quantity_unit(value: Any, fallback_unit: Optional[str] = None) -> tuple[Decimal, Optional[str]]:
    if isinstance(value, Decimal):
        return abs(value), fallback_unit
    if isinstance(value, (int, float)):
        return abs(Decimal(str(value))), fallback_unit

    text = _first_non_empty_string(value)
    if not text:
        raise ValueError("item quantity is required")
    parts = text.split()
    try:
        quantity = abs(Decimal(parts[0]))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"Invalid item quantity value: {value}") from exc
    unit = fallback_unit or (parts[1].strip() if len(parts) > 1 else None)
    return quantity, unit


def _parse_rate_number(value: Any, amount: Optional[Decimal] = None, quantity: Optional[Decimal] = None) -> Decimal:
    text = _first_non_empty_string(value)
    if text:
        numeric_text = text.split("/", 1)[0].strip()
        try:
            return abs(Decimal(numeric_text))
        except (InvalidOperation, ValueError) as exc:
            raise ValueError(f"Invalid item rate value: {value}") from exc
    if amount is not None and quantity is not None and quantity != 0:
        return abs(amount) / quantity
    raise ValueError("item rate is required")


def _json_number(value: Decimal) -> int | float:
    normalized = value.quantize(Decimal("0.01"))
    if normalized == normalized.to_integral():
        return int(normalized)
    return float(normalized)


def _mi_results_map(body: Any) -> Dict[str, Any]:
    if isinstance(body, dict):
        results = body.get("results")
        if isinstance(results, dict):
            return results
    return {}


def _mi_message_map(body: Any) -> Dict[str, Any]:
    results = _mi_results_map(body)
    message = results.get("message")
    return message if isinstance(message, dict) else {}


def _mi_status_text(body: Any) -> Optional[str]:
    status = _mi_results_map(body).get("status")
    return status.strip() if isinstance(status, str) and status.strip() else None


def _mi_reason(body: Any) -> Optional[str]:
    if isinstance(body, str) and body.strip():
        return body.strip()
    results = _mi_results_map(body)
    message = results.get("message")
    if isinstance(message, str) and message.strip():
        return message.strip()
    for key in ("InfoDtls", "infoDtls", "errorMessage", "error", "message"):
        value = results.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


async def _gst_service_post(
    settings: Settings,
    path: str,
    payload: Dict[str, Any],
    operation: str,
) -> Dict[str, Any]:
    base_url = str(settings.gst_service_base_url).rstrip("/")
    target_url = f"{base_url}{path}"
    last_exc: Optional[httpx.HTTPError] = None
    response: Optional[httpx.Response] = None
    attempts = max(int(settings.gst_request_max_retries), 0) + 1
    for attempt in range(1, attempts + 1):
        try:
            async with httpx.AsyncClient(timeout=settings.gst_service_timeout_sec) as http:
                response = await http.post(target_url, json=payload)
            break
        except httpx.HTTPError as exc:
            last_exc = exc
            if attempt >= attempts:
                break
            if settings.gst_request_backoff_sec > 0:
                await asyncio.sleep(settings.gst_request_backoff_sec * attempt)
    if response is None:
        raise HTTPException(
            status_code=502,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "provider_error",
                "provider": "gst_service",
                "operation": operation,
                "reason": f"GST provider request failed: {repr(last_exc)}",
            },
        ) from last_exc

    try:
        body: Any = response.json()
    except ValueError:
        body = response.text

    if response.status_code >= 400:
        status_code = 422 if response.status_code in {400, 404, 409, 422} else 502
        raise HTTPException(
            status_code=status_code,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation" if status_code == 422 else "provider_error",
                "provider": "gst_service",
                "operation": operation,
                "reason": _mi_reason(body) or f"GST provider returned HTTP {response.status_code}.",
                "provider_response": body,
            },
        )

    status_text = (_mi_status_text(body) or "").lower()
    message = _mi_message_map(body)
    success_marker = message.get("Irn") or message.get("EwbNo") or status_text == "success"
    if not success_marker:
        raise HTTPException(
            status_code=422,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "provider": "gst_service",
                "operation": operation,
                "reason": _mi_reason(body) or f"GST provider did not confirm {operation} success.",
                "provider_response": body,
            },
        )

    return body if isinstance(body, dict) else {"raw_response": body}


def _ensure_generate_voucher_identity(payload: Dict[str, Any], prefix: str, action: str) -> Dict[str, Any]:
    updated = copy.deepcopy(payload)
    voucher_data = _coerce_mapping(updated.get("voucher"), "voucher")
    voucher_number = _first_non_empty_string(
        voucher_data.get("VOUCHERNUMBER"),
        voucher_data.get("voucher_number"),
        updated.get("VOUCHERNUMBER"),
    )
    reference = _first_non_empty_string(
        voucher_data.get("REFERENCE"),
        voucher_data.get("reference"),
        updated.get("REFERENCE"),
    )
    normalized_action = (action or "Create").strip().lower()

    if reference or voucher_number:
        updated["voucher"] = voucher_data
        return updated

    if normalized_action == "create":
        compact_prefix = re.sub(r"[^A-Z0-9]", "", prefix.upper())[:4] or "GEN"
        voucher_data["REFERENCE"] = f"{compact_prefix}{datetime.now().strftime('%m%d%H%M%S%f')[:12]}"
        updated["voucher"] = voucher_data
        return updated

    raise ValueError("voucher.REFERENCE or voucher.VOUCHERNUMBER is required for generate action=Alter")


async def _fetch_single_sales_voucher(
    client: TallyClient,
    company_name: str,
    voucher_date: str,
    voucher_number: Optional[str],
    reference: Optional[str],
    party_ledger_name: Optional[str],
    fetch_fields: Optional[List[str]] = None,
) -> Dict[str, Any]:
    report_data = await _export_report(
        client,
        "Sales Vouchers",
        company_name,
        voucher_date,
        voucher_date,
        view="summary",
    )
    shaped = _shape_report_vouchers(report_data, "Sales Vouchers")
    vouchers = shaped.get("VOUCHER") if isinstance(shaped, dict) else None
    filtered_vouchers = _filter_sales_status_vouchers(
        vouchers if isinstance(vouchers, list) else [],
        voucher_number=voucher_number,
        reference=reference,
        party_ledger_name=party_ledger_name,
        keep_fields=fetch_fields,
        limit=2,
    )
    if not filtered_vouchers:
        raise HTTPException(
            status_code=404,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "Could not locate the generated Sales voucher in Tally.",
                "voucher_number": voucher_number,
                "reference": reference,
                "date": voucher_date,
            },
        )
    if len(filtered_vouchers) > 1:
        raise HTTPException(
            status_code=409,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "validation",
                "reason": "Multiple Sales vouchers matched the supplied identity. Use voucher_number or a unique reference.",
                "voucher_number": voucher_number,
                "reference": reference,
                "date": voucher_date,
            },
        )
    return filtered_vouchers[0]


async def _alter_sales_voucher(
    client: TallyClient,
    company_name: str,
    voucher_number: str,
    voucher_date: str,
    update_fields: Dict[str, Any],
) -> Dict[str, Any]:
    payload = {
        "DATE": voucher_date,
        "VOUCHERNUMBER": voucher_number,
        **update_fields,
    }
    try:
        xml_req = build_voucher_upsert(
            await _preferred_write_company_context(client, company_name),
            "Alter",
            payload,
            "Sales",
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return await _post_xml(client, xml_req)


def _provider_wrapper_mapping(payload: Dict[str, Any], primary_key: str, provider_key: str) -> Dict[str, Any]:
    primary = payload.get(primary_key)
    if primary is not None:
        return _coerce_mapping(primary, primary_key)
    provider = payload.get(provider_key)
    if provider is not None:
        return _coerce_mapping(provider, provider_key)
    return {}


def _build_mi_einvoice_payload(
    payload: Dict[str, Any],
    ready_payload: Dict[str, Any],
) -> Dict[str, Any]:
    override = _coerce_mapping(payload.get("provider_payload"), "provider_payload")
    seller_data = _provider_wrapper_mapping(payload, "seller", "seller_details")
    buyer_data = _provider_wrapper_mapping(payload, "buyer", "buyer_details")
    transaction_data = _coerce_mapping(payload.get("transaction_details"), "transaction_details")
    dispatch_data = _provider_wrapper_mapping(payload, "dispatch_from", "dispatch_details")
    ship_to_data = _provider_wrapper_mapping(payload, "ship_to", "ship_details")
    export_data = _provider_wrapper_mapping(payload, "export", "export_details")
    payment_data = _provider_wrapper_mapping(payload, "payment", "payment_details")
    reference_data = _coerce_mapping(payload.get("reference_details"), "reference_details")
    value_details_override = _coerce_mapping(payload.get("value_details"), "value_details")
    voucher_data = _coerce_mapping(payload.get("voucher"), "voucher")

    if override:
        provider_payload = copy.deepcopy(override)
        provider_payload.setdefault("data_source", "erp")
        seller_gstin = _first_non_empty_string(
            provider_payload.get("user_gstin"),
            seller_data.get("gstin"),
            seller_data.get("GSTIN"),
        )
        if seller_gstin:
            provider_payload.setdefault("user_gstin", seller_gstin)
        document_details = provider_payload.get("document_details")
        if not isinstance(document_details, dict):
            document_details = {}
            provider_payload["document_details"] = document_details
        document_details.setdefault("document_type", "INV")
        voucher_number = _first_non_empty_string(
            voucher_data.get("voucher_number"),
            voucher_data.get("VOUCHERNUMBER"),
            ready_payload.get("VOUCHERNUMBER"),
            ready_payload.get("REFERENCE"),
        )
        if voucher_number:
            document_details.setdefault("document_number", voucher_number)
        provider_document_date = _provider_date(ready_payload.get("DATE"))
        if provider_document_date:
            document_details.setdefault("document_date", provider_document_date)
        return provider_payload

    if not seller_data:
        raise ValueError("seller is required unless provider_payload is supplied")
    if not buyer_data:
        raise ValueError("buyer is required unless provider_payload is supplied")

    seller_gstin = _first_non_empty_string(seller_data.get("gstin"), seller_data.get("GSTIN"))
    if not seller_gstin:
        raise ValueError("seller.gstin is required unless provider_payload is supplied")
    seller_legal_name = _first_non_empty_string(seller_data.get("legal_name"), seller_data.get("name"), seller_data.get("trade_name"))
    if not seller_legal_name:
        raise ValueError("seller.legal_name is required unless provider_payload is supplied")
    seller_address1, seller_address2 = _provider_address_parts(seller_data)
    if not seller_address1:
        raise ValueError("seller.address1 or seller.address_lines is required unless provider_payload is supplied")
    seller_location = _first_non_empty_string(seller_data.get("location"), seller_data.get("place"), dispatch_data.get("location"), dispatch_data.get("place"))
    if not seller_location:
        raise ValueError("seller.location is required unless provider_payload is supplied")
    seller_pincode = _normalized_pin(seller_data.get("pincode"))
    if not seller_pincode:
        raise ValueError("seller.pincode is required unless provider_payload is supplied")
    seller_state_code = _resolve_gst_state_code(seller_data.get("state_code") or seller_data.get("state"), seller_gstin)
    if not seller_state_code:
        raise ValueError("seller.state_code or a GSTIN with a valid state code is required unless provider_payload is supplied")

    buyer_gstin = _first_non_empty_string(buyer_data.get("gstin"), buyer_data.get("GSTIN"), ready_payload.get("PARTYGSTIN"), "URP")
    buyer_legal_name = _first_non_empty_string(buyer_data.get("legal_name"), buyer_data.get("mailing_name"), ready_payload.get("BASICBUYERNAME"), ready_payload.get("PARTYLEDGERNAME"))
    if not buyer_legal_name:
        raise ValueError("buyer.legal_name or buyer.mailing_name is required unless provider_payload is supplied")
    buyer_address1, buyer_address2 = _provider_address_parts(buyer_data)
    if not buyer_address1:
        raise ValueError("buyer.address1 or buyer.address_lines is required unless provider_payload is supplied")
    buyer_location = _first_non_empty_string(buyer_data.get("location"), buyer_data.get("place"), ready_payload.get("BILLTOPLACE"))
    if not buyer_location:
        raise ValueError("buyer.location or buyer.place is required unless provider_payload is supplied")
    buyer_pincode = _normalized_pin(buyer_data.get("pincode") or ready_payload.get("PARTYPINCODE"))
    if not buyer_pincode:
        raise ValueError("buyer.pincode is required unless provider_payload is supplied")
    buyer_state_code = _resolve_gst_state_code(buyer_data.get("state_code") or buyer_data.get("state"), buyer_gstin)
    if not buyer_state_code:
        raise ValueError("buyer.state_code or a GSTIN with a valid state code is required unless provider_payload is supplied")
    place_of_supply = _resolve_gst_state_code(
        buyer_data.get("place_of_supply") or buyer_data.get("place_of_supply_code") or ready_payload.get("PLACEOFSUPPLY"),
        buyer_gstin,
    )
    if not place_of_supply:
        raise ValueError("buyer.place_of_supply_code/place_of_supply or a GSTIN with a valid state code is required unless provider_payload is supplied")

    document_number = _first_non_empty_string(
        voucher_data.get("voucher_number"),
        voucher_data.get("VOUCHERNUMBER"),
        ready_payload.get("VOUCHERNUMBER"),
        ready_payload.get("REFERENCE"),
    )
    if not document_number:
        raise ValueError("voucher.VOUCHERNUMBER or voucher.REFERENCE is required for real-time generation")
    document_date = _provider_date(ready_payload.get("DATE"))
    if not document_date:
        raise ValueError("voucher.DATE is required for real-time generation")

    transaction_details = {
        "supply_type": _first_non_empty_string(transaction_data.get("supply_type"), transaction_data.get("type"), "B2B"),
        "charge_type": _first_non_empty_string(transaction_data.get("charge_type"), _provider_yes_no_flag(transaction_data.get("reverse_charge"), "N"), "N"),
        "igst_on_intra": _first_non_empty_string(transaction_data.get("igst_on_intra"), _provider_yes_no_flag(transaction_data.get("igst_on_intra_supply"), "N"), "N"),
        "ecommerce_gstin": _first_non_empty_string(transaction_data.get("ecommerce_gstin"), "") or "",
    }

    seller_payload = {
        "gstin": seller_gstin,
        "legal_name": seller_legal_name,
        "trade_name": _first_non_empty_string(seller_data.get("trade_name"), seller_legal_name),
        "address1": seller_address1,
        "address2": seller_address2,
        "location": seller_location,
        "pincode": int(seller_pincode),
        "state_code": seller_state_code,
    }
    seller_phone = _provider_phone_value(seller_data.get("phone_number") or seller_data.get("phone") or seller_data.get("mobile"))
    if seller_phone:
        seller_payload["phone_number"] = seller_phone
    seller_email = _first_non_empty_string(seller_data.get("email"))
    if seller_email:
        seller_payload["email"] = seller_email

    buyer_payload = {
        "gstin": buyer_gstin,
        "legal_name": buyer_legal_name,
        "trade_name": _first_non_empty_string(buyer_data.get("trade_name"), buyer_data.get("mailing_name"), buyer_legal_name),
        "address1": buyer_address1,
        "address2": buyer_address2,
        "location": buyer_location,
        "pincode": int(buyer_pincode),
        "place_of_supply": place_of_supply,
        "state_code": buyer_state_code,
    }
    buyer_phone = _provider_phone_value(buyer_data.get("phone_number") or buyer_data.get("phone") or buyer_data.get("mobile"))
    if buyer_phone:
        buyer_payload["phone_number"] = buyer_phone
    buyer_email = _first_non_empty_string(buyer_data.get("email"))
    if buyer_email:
        buyer_payload["email"] = buyer_email

    item_payloads: List[Dict[str, Any]] = []
    assessable_total = Decimal("0")
    igst_total = Decimal("0")
    cgst_total = Decimal("0")
    sgst_total = Decimal("0")
    cess_total = Decimal("0")
    state_cess_total = Decimal("0")
    other_charge_total = Decimal("0")
    discount_total = Decimal("0")

    interstate_supply = seller_state_code != place_of_supply

    for index, item_value in enumerate(_listify(payload.get("items")), start=1):
        if not isinstance(item_value, dict):
            raise ValueError("items must contain objects")
        quantity_value, unit_code = _parse_quantity_unit(
            _first_present_value(item_value, "quantity", "ACTUALQTY"),
            _first_non_empty_string(item_value.get("unit"), item_value.get("uqc")),
        )
        billed_quantity_value, billed_unit = _parse_quantity_unit(
            _first_present_value(item_value, "billed_quantity", "BILLEDQTY", "quantity", "ACTUALQTY"),
            unit_code,
        )
        item_amount = abs(_decimal_value(_first_present_value(item_value, "amount", "AMOUNT"), "item amount"))
        assessable_value = abs(
            _decimal_value(
                _first_present_value(item_value, "taxable_amount", "assessable_value", "GSTASSESSABLEVALUE", "amount", "AMOUNT"),
                "item assessable value",
            )
        )
        unit_price = _parse_rate_number(_first_present_value(item_value, "rate", "RATE"), item_amount, quantity_value)
        gst_rate = abs(_decimal_value(_first_present_value(item_value, "gst_rate", "GSTTAXRATE", 0), "item gst rate"))
        discount_amount = abs(_decimal_value(_first_present_value(item_value, "discount", 0), "item discount"))
        other_charge_amount = abs(_decimal_value(_first_present_value(item_value, "other_charge", 0), "item other charge"))
        igst_amount = abs(_decimal_value(_first_present_value(item_value, "igst_amount", 0), "item igst amount"))
        cgst_amount = abs(_decimal_value(_first_present_value(item_value, "cgst_amount", 0), "item cgst amount"))
        sgst_amount = abs(_decimal_value(_first_present_value(item_value, "sgst_amount", 0), "item sgst amount"))
        cess_rate = abs(_decimal_value(_first_present_value(item_value, "cess_rate", 0), "item cess rate"))
        cess_amount = abs(_decimal_value(_first_present_value(item_value, "cess_amount", 0), "item cess amount"))
        cess_nonadvol_amount = abs(_decimal_value(_first_present_value(item_value, "cess_nonadvol_amount", 0), "item cess nonadvol amount"))
        state_cess_rate = abs(_decimal_value(_first_present_value(item_value, "state_cess_rate", 0), "item state cess rate"))
        state_cess_amount = abs(_decimal_value(_first_present_value(item_value, "state_cess_amount", 0), "item state cess amount"))
        state_cess_nonadvol_amount = abs(_decimal_value(_first_present_value(item_value, "state_cess_nonadvol_amount", 0), "item state cess nonadvol amount"))

        if gst_rate and igst_amount == 0 and cgst_amount == 0 and sgst_amount == 0:
            if interstate_supply:
                igst_amount = (assessable_value * gst_rate) / Decimal("100")
            else:
                cgst_amount = (assessable_value * gst_rate) / Decimal("200")
                sgst_amount = (assessable_value * gst_rate) / Decimal("200")

        total_item_value = (
            assessable_value
            + igst_amount
            + cgst_amount
            + sgst_amount
            + cess_amount
            + cess_nonadvol_amount
            + state_cess_amount
            + state_cess_nonadvol_amount
            + other_charge_amount
            - discount_amount
        )

        product_description = _first_non_empty_string(item_value.get("description"), item_value.get("stock_item_name"), item_value.get("STOCKITEMNAME"))
        if not product_description:
            raise ValueError("item stock_item_name or description is required unless provider_payload is supplied")
        hsn_code = _first_non_empty_string(
            item_value.get("hsn_code"),
            item_value.get("GSTHSNSACCODE"),
            item_value.get("HSNCODE"),
            item_value.get("sac_code"),
        )
        if not hsn_code:
            raise ValueError("item hsn_code or sac_code is required unless provider_payload is supplied")

        item_payload = {
            "item_serial_number": str(index),
            "product_description": product_description,
            "is_service": _first_non_empty_string(item_value.get("is_service"), "N"),
            "hsn_code": hsn_code,
            "bar_code": _first_non_empty_string(item_value.get("bar_code"), "") or "",
            "quantity": _json_number(quantity_value),
            "free_quantity": _json_number(abs(_decimal_value(_first_present_value(item_value, "free_quantity", 0), "item free quantity"))),
            "unit": _first_non_empty_string(item_value.get("unit"), item_value.get("uqc"), billed_unit, unit_code, "NOS"),
            "unit_price": _json_number(unit_price),
            "total_amount": _json_number(item_amount),
            "pre_tax_value": _json_number(abs(_decimal_value(_first_present_value(item_value, "pre_tax_value", 0), "item pre-tax value"))),
            "discount": _json_number(discount_amount),
            "other_charge": _json_number(other_charge_amount),
            "assessable_value": _json_number(assessable_value),
            "gst_rate": _json_number(gst_rate),
            "igst_amount": _json_number(igst_amount),
            "cgst_amount": _json_number(cgst_amount),
            "sgst_amount": _json_number(sgst_amount),
            "cess_rate": _json_number(cess_rate),
            "cess_amount": _json_number(cess_amount),
            "cess_nonadvol_amount": _json_number(cess_nonadvol_amount),
            "state_cess_rate": _json_number(state_cess_rate),
            "state_cess_amount": _json_number(state_cess_amount),
            "state_cess_nonadvol_amount": _json_number(state_cess_nonadvol_amount),
            "total_item_value": _json_number(total_item_value),
            "country_origin": _first_non_empty_string(item_value.get("country_origin"), "") or "",
            "order_line_reference": _first_non_empty_string(item_value.get("order_line_reference"), ready_payload.get("REFERENCE"), "") or "",
            "product_serial_number": _first_non_empty_string(item_value.get("product_serial_number"), "") or "",
        }

        batch_details = _coerce_mapping(item_value.get("batch_details"), "items[].batch_details")
        if batch_details:
            item_payload["batch_details"] = copy.deepcopy(batch_details)
        attribute_details = [entry for entry in _listify(item_value.get("attribute_details")) if isinstance(entry, dict)]
        if attribute_details:
            item_payload["attribute_details"] = copy.deepcopy(attribute_details)

        item_payloads.append(item_payload)
        assessable_total += assessable_value
        igst_total += igst_amount
        cgst_total += cgst_amount
        sgst_total += sgst_amount
        cess_total += cess_amount + cess_nonadvol_amount
        state_cess_total += state_cess_amount + state_cess_nonadvol_amount
        other_charge_total += other_charge_amount
        discount_total += discount_amount

    if not item_payloads:
        raise ValueError("items is required and must contain at least one object")

    tax_entries = [entry for entry in _listify(payload.get("taxes")) if isinstance(entry, dict)]
    invoice_igst_total = Decimal("0")
    invoice_cgst_total = Decimal("0")
    invoice_sgst_total = Decimal("0")
    invoice_cess_total = Decimal("0")
    invoice_other_charge_total = Decimal("0")
    for tax_entry in tax_entries:
        ledger_name = _first_non_empty_string(tax_entry.get("ledger_name"), tax_entry.get("LEDGERNAME")) or ""
        amount = abs(_decimal_value(_first_present_value(tax_entry, "amount", "AMOUNT", 0), "tax amount"))
        lowered = ledger_name.lower()
        if "igst" in lowered:
            invoice_igst_total += amount
        elif "cgst" in lowered:
            invoice_cgst_total += amount
        elif "sgst" in lowered or "utgst" in lowered:
            invoice_sgst_total += amount
        elif "cess" in lowered:
            invoice_cess_total += amount
        else:
            invoice_other_charge_total += amount

    igst_total = invoice_igst_total or igst_total
    cgst_total = invoice_cgst_total or cgst_total
    sgst_total = invoice_sgst_total or sgst_total
    cess_total = invoice_cess_total or cess_total
    other_charge_total = invoice_other_charge_total or other_charge_total

    total_invoice_value = assessable_total + igst_total + cgst_total + sgst_total + cess_total + state_cess_total + other_charge_total - discount_total

    value_details = {
        "total_assessable_value": _json_number(assessable_total),
        "total_cgst_value": _json_number(cgst_total),
        "total_sgst_value": _json_number(sgst_total),
        "total_igst_value": _json_number(igst_total),
        "total_cess_value": _json_number(cess_total),
        "total_cess_value_of_state": _json_number(state_cess_total),
        "total_discount": _json_number(discount_total),
        "total_other_charge": _json_number(other_charge_total),
        "total_invoice_value": _json_number(total_invoice_value),
        "round_off_amount": _json_number(abs(_decimal_value(_first_present_value(value_details_override, "round_off_amount", 0), "round off amount"))),
        "total_invoice_value_additional_currency": _json_number(abs(_decimal_value(_first_present_value(value_details_override, "total_invoice_value_additional_currency", 0), "invoice value additional currency"))),
    }
    if value_details_override:
        for key, value in value_details_override.items():
            if value is not None:
                value_details[key] = value

    payload_out: Dict[str, Any] = {
        "user_gstin": seller_gstin,
        "data_source": "erp",
        "transaction_details": transaction_details,
        "document_details": {
            "document_type": _first_non_empty_string(voucher_data.get("document_type"), "INV"),
            "document_number": document_number,
            "document_date": document_date,
        },
        "seller_details": seller_payload,
        "buyer_details": buyer_payload,
        "value_details": value_details,
        "item_list": item_payloads,
    }

    if dispatch_data:
        dispatch_address1, dispatch_address2 = _provider_address_parts(dispatch_data)
        dispatch_location = _first_non_empty_string(dispatch_data.get("location"), dispatch_data.get("place"))
        dispatch_pincode = _normalized_pin(dispatch_data.get("pincode"))
        dispatch_state_code = _resolve_gst_state_code(dispatch_data.get("state_code") or dispatch_data.get("state"))
        if dispatch_address1 and dispatch_location and dispatch_pincode and dispatch_state_code:
            payload_out["dispatch_details"] = {
                "company_name": _first_non_empty_string(dispatch_data.get("company_name"), dispatch_data.get("name"), seller_legal_name),
                "address1": dispatch_address1,
                "address2": dispatch_address2,
                "location": dispatch_location,
                "pincode": int(dispatch_pincode),
                "state_code": dispatch_state_code,
            }

    if ship_to_data:
        ship_address1, ship_address2 = _provider_address_parts(ship_to_data)
        ship_location = _first_non_empty_string(ship_to_data.get("location"), ship_to_data.get("place"))
        ship_pincode = _normalized_pin(ship_to_data.get("pincode") or ready_payload.get("CONSIGNEEPINCODE"))
        ship_state_code = _resolve_gst_state_code(ship_to_data.get("state_code") or ship_to_data.get("state"), ship_to_data.get("gstin"))
        if ship_address1 and ship_location and ship_pincode and ship_state_code:
            payload_out["ship_details"] = {
                "gstin": _first_non_empty_string(ship_to_data.get("gstin"), ready_payload.get("CONSIGNEEGSTIN"), buyer_gstin),
                "legal_name": _first_non_empty_string(ship_to_data.get("legal_name"), ship_to_data.get("mailing_name"), ready_payload.get("CONSIGNEEMAILINGNAME"), buyer_legal_name),
                "trade_name": _first_non_empty_string(ship_to_data.get("trade_name"), ship_to_data.get("mailing_name"), buyer_legal_name),
                "address1": ship_address1,
                "address2": ship_address2,
                "location": ship_location,
                "pincode": int(ship_pincode),
                "state_code": ship_state_code,
            }

    if export_data:
        export_details = {
            "ship_bill_number": _first_non_empty_string(export_data.get("shipping_bill_no"), export_data.get("ship_bill_number"), "") or "",
            "ship_bill_date": _provider_date(_first_present_value(export_data, "ship_bill_date", "shipping_bill_date")) or "",
            "country_code": _first_non_empty_string(export_data.get("country_code"), "IN") or "IN",
            "foreign_currency": _first_non_empty_string(export_data.get("foreign_currency"), "INR") or "INR",
            "refund_claim": _provider_yes_no_flag(export_data.get("refund_claim"), "N") or "N",
            "port_code": _first_non_empty_string(export_data.get("port_code"), export_data.get("PORTCODE"), "") or "",
            "export_duty": _json_number(abs(_decimal_value(_first_present_value(export_data, "export_duty", 0), "export duty"))),
        }
        if any(str(value).strip() for value in export_details.values() if value is not None):
            payload_out["export_details"] = export_details

    if payment_data:
        payment_details = copy.deepcopy(payment_data)
        if payment_details:
            payload_out["payment_details"] = payment_details

    if reference_data:
        reference_details = copy.deepcopy(reference_data)
        if reference_details:
            payload_out["reference_details"] = reference_details

    additional_docs = [entry for entry in _listify(payload.get("additional_document_details")) if isinstance(entry, dict)]
    if additional_docs:
        payload_out["additional_document_details"] = copy.deepcopy(additional_docs)

    return payload_out


def _build_mi_ewaybill_payload(
    payload: Dict[str, Any],
    ready_payload: Dict[str, Any],
    irn: str,
) -> Dict[str, Any]:
    top_level_override = _coerce_mapping(payload.get("provider_ewaybill_payload"), "provider_ewaybill_payload")
    ewaybill_data = _provider_wrapper_mapping(payload, "ewaybill", "ewaybill_details")
    override = top_level_override or _coerce_mapping(ewaybill_data.get("provider_payload"), "ewaybill.provider_payload")
    seller_data = _provider_wrapper_mapping(payload, "seller", "seller_details")
    dispatch_data = _provider_wrapper_mapping(payload, "dispatch_from", "dispatch_details")
    ship_to_data = _provider_wrapper_mapping(payload, "ship_to", "ship_details")

    if override:
        provider_payload = copy.deepcopy(override)
        provider_payload["irn"] = irn
        provider_payload.setdefault("data_source", "erp")
        seller_gstin = _first_non_empty_string(
            provider_payload.get("user_gstin"),
            seller_data.get("gstin"),
            seller_data.get("GSTIN"),
        )
        if seller_gstin:
            provider_payload.setdefault("user_gstin", seller_gstin)
        return provider_payload

    seller_gstin = _first_non_empty_string(seller_data.get("gstin"), seller_data.get("GSTIN"))
    if not seller_gstin:
        raise ValueError("seller.gstin or provider_ewaybill_payload.user_gstin is required for e-way bill generation")

    transportation_mode = _provider_transport_mode(
        _first_present_value(ewaybill_data, "transport_mode", "transportation_mode", "TRANSPORTMODE")
    )
    if not transportation_mode:
        raise ValueError("ewaybill.transport_mode is required unless provider_ewaybill_payload is supplied")

    distance_value = _first_present_value(ewaybill_data, "distance_km", "distance", "TEMPGSTEWAYDISTANCE")
    if distance_value is None or str(distance_value).strip() == "":
        raise ValueError("ewaybill.distance_km is required unless provider_ewaybill_payload is supplied")

    provider_payload = {
        "user_gstin": seller_gstin,
        "irn": irn,
        "data_source": "erp",
        "transportation_mode": transportation_mode,
        "distance": int(str(distance_value).strip()),
    }

    transporter_id = _first_non_empty_string(ewaybill_data.get("transporter_id"), ewaybill_data.get("TRANSPORTERID"))
    transporter_name = _first_non_empty_string(ewaybill_data.get("transporter_name"), ewaybill_data.get("TRANSPORTERNAME"))
    if transporter_id:
        provider_payload["transporter_id"] = transporter_id
    if transporter_name:
        provider_payload["transporter_name"] = transporter_name

    transport_doc_no = _first_non_empty_string(
        ewaybill_data.get("transport_document_no"),
        ewaybill_data.get("transporter_document_number"),
        ewaybill_data.get("LORRYRECPTNO"),
        ewaybill_data.get("AIRWAYBILLNO"),
        ewaybill_data.get("BILLOFLADINGNO"),
    )
    if transport_doc_no:
        provider_payload["transporter_document_number"] = transport_doc_no

    transport_doc_date = _provider_date(
        _first_present_value(
            ewaybill_data,
            "transport_document_date",
            "transporter_document_date",
            "TEMPGSTEWAYTRANSPORTERDOCDATE",
        )
    )
    if transport_doc_date:
        provider_payload["transporter_document_date"] = transport_doc_date

    vehicle_number = _first_non_empty_string(ewaybill_data.get("vehicle_number"), ewaybill_data.get("GOODSVEHICLENUMBER"))
    if vehicle_number:
        provider_payload["vehicle_number"] = vehicle_number

    vehicle_type = _provider_vehicle_type(_first_present_value(ewaybill_data, "vehicle_type", "TEMPGSTEWAYVEHICLETYPE"))
    if vehicle_type:
        provider_payload["vehicle_type"] = vehicle_type

    if dispatch_data:
        dispatch_address1, dispatch_address2 = _provider_address_parts(dispatch_data)
        dispatch_location = _first_non_empty_string(dispatch_data.get("location"), dispatch_data.get("place"))
        dispatch_pincode = _normalized_pin(dispatch_data.get("pincode"))
        dispatch_state_code = _resolve_gst_state_code(dispatch_data.get("state_code") or dispatch_data.get("state"))
        if dispatch_address1 and dispatch_location and dispatch_pincode and dispatch_state_code:
            provider_payload["dispatch_details"] = {
                "company_name": _first_non_empty_string(dispatch_data.get("company_name"), dispatch_data.get("name"), seller_data.get("legal_name"), seller_data.get("trade_name"), "Dispatch"),
                "address1": dispatch_address1,
                "address2": dispatch_address2,
                "location": dispatch_location,
                "pincode": int(dispatch_pincode),
                "state_code": dispatch_state_code,
            }

    if ship_to_data:
        ship_address1, ship_address2 = _provider_address_parts(ship_to_data)
        ship_location = _first_non_empty_string(ship_to_data.get("location"), ship_to_data.get("place"))
        ship_pincode = _normalized_pin(ship_to_data.get("pincode") or ready_payload.get("CONSIGNEEPINCODE"))
        ship_state_code = _resolve_gst_state_code(ship_to_data.get("state_code") or ship_to_data.get("state"), ship_to_data.get("gstin"))
        if ship_address1 and ship_location and ship_pincode and ship_state_code:
            provider_payload["ship_details"] = {
                "address1": ship_address1,
                "address2": ship_address2,
                "location": ship_location,
                "pincode": int(ship_pincode),
                "state_code": ship_state_code,
            }

    return provider_payload


def _extract_einvoice_writeback_fields(provider_response: Dict[str, Any]) -> Dict[str, Any]:
    message = _mi_message_map(provider_response)
    irn = _first_non_empty_string(message.get("Irn"), message.get("irn"))
    if not irn:
        raise HTTPException(
            status_code=502,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "provider_error",
                "provider": "gst_service",
                "operation": "einvoice",
                "reason": "GST provider did not return an IRN.",
                "provider_response": provider_response,
            },
        )

    writeback: Dict[str, Any] = {
        "IRN": irn,
        "IRNCANCELLED": "No",
        "IRNJSONEXPORTED": "Yes",
    }
    ack_no = _stringify_non_empty(message.get("AckNo") or message.get("ack_no"))
    if ack_no:
        writeback["IRNACKNO"] = ack_no
    ack_date = _provider_datetime_to_tally_date(message.get("AckDt") or message.get("ack_date"))
    if ack_date:
        writeback["IRNACKDATE"] = ack_date
    qr_code = _first_non_empty_string(
        message.get("SignedQRCode"),
        message.get("SignedQrCode"),
        message.get("QRCode"),
        message.get("signed_qr_code"),
    )
    if qr_code:
        writeback["IRNQRCODE"] = qr_code
    return writeback


def _extract_ewaybill_writeback_fields(
    provider_response: Dict[str, Any],
    provider_payload: Dict[str, Any],
) -> Dict[str, Any]:
    message = _mi_message_map(provider_response)
    ewaybill_no = _stringify_non_empty(message.get("EwbNo") or message.get("ewaybill_number"))
    if not ewaybill_no:
        raise HTTPException(
            status_code=502,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "provider_error",
                "provider": "gst_service",
                "operation": "ewaybill",
                "reason": "GST provider did not return an e-Way Bill number.",
                "provider_response": provider_response,
            },
        )

    transport_mode = _provider_to_tally_transport_mode(provider_payload.get("transportation_mode"))
    vehicle_type = _provider_to_tally_vehicle_type(provider_payload.get("vehicle_type"))
    transport_doc_no = _first_non_empty_string(provider_payload.get("transporter_document_number"))
    transporter_name = _first_non_empty_string(provider_payload.get("transporter_name"))

    writeback: Dict[str, Any] = {
        "TEMPGSTEWAYBILLNUMBER": ewaybill_no,
        "TEMPGSTEWAYSTATUS": "Generated",
        "TEMPGSTEWAYISCANCELLED": "No",
    }
    ewaybill_date = _provider_datetime_to_tally_date(message.get("EwbDt") or message.get("ewaybill_date"))
    if ewaybill_date:
        writeback["TEMPGSTEWAYBILLDATE"] = ewaybill_date
    transporter_id = _first_non_empty_string(provider_payload.get("transporter_id"))
    if transporter_id:
        writeback["TEMPGSTEWAYTRANSPORTERID"] = transporter_id
    if transporter_name:
        writeback["TEMPGSTEWAYTRANSPORTERNAME"] = transporter_name
        writeback["TRANSPORTERNAME"] = transporter_name
        writeback.setdefault("BASICSHIPPEDBY", transporter_name)
    if transport_mode:
        writeback["TEMPGSTEWAYTRANSPORTMODE"] = transport_mode
        writeback["TRANSPORTMODE"] = transport_mode
    distance_value = provider_payload.get("distance")
    if distance_value is not None and str(distance_value).strip():
        writeback["TEMPGSTEWAYDISTANCE"] = str(distance_value).strip()
    vehicle_number = _first_non_empty_string(provider_payload.get("vehicle_number"))
    if vehicle_number:
        writeback["TEMPGSTEWAYVEHICLENUMBER"] = vehicle_number
        writeback["GOODSVEHICLENUMBER"] = vehicle_number
    if vehicle_type:
        writeback["TEMPGSTEWAYVEHICLETYPE"] = vehicle_type
    if transport_doc_no:
        writeback["TEMPGSTEWAYTRANSPORTERDOCNO"] = transport_doc_no
        if transport_mode == "Air":
            writeback["AIRWAYBILLNO"] = transport_doc_no
        elif transport_mode == "Ship":
            writeback["BILLOFLADINGNO"] = transport_doc_no
        else:
            writeback["LORRYRECPTNO"] = transport_doc_no
    transport_doc_date = _provider_datetime_to_tally_date(provider_payload.get("transporter_document_date"))
    if transport_doc_date:
        writeback["TEMPGSTEWAYTRANSPORTERDOCDATE"] = transport_doc_date
    return writeback


async def _sales_generate_voucher(
    payload: Dict[str, Any],
    action: str,
    company: Optional[str],
    x_company: Optional[str],
    settings: Settings,
    client: TallyClient,
    include_einvoice: bool,
    include_ewaybill: bool,
) -> Dict[str, Any]:
    normalized_action = (action or "Create").strip().title()
    if normalized_action not in {"Create", "Alter"}:
        raise HTTPException(status_code=400, detail="Only Create and Alter are supported for generate endpoints")

    identity_prefix = "EIEWBGEN" if include_einvoice and include_ewaybill else ("EINVGEN" if include_einvoice else "EWBGEN")
    identity_payload = _ensure_generate_voucher_identity(payload, identity_prefix, normalized_action)
    company_name, normalized_ready_payload, import_payload = await _prepare_sales_ready_submission(
        identity_payload,
        normalized_action,
        company,
        x_company,
        settings,
        client,
        include_einvoice,
        include_ewaybill,
    )
    try:
        xml_req = build_voucher_upsert(
            await _preferred_write_company_context(client, company_name),
            normalized_action,
            import_payload,
            "Sales",
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    voucher_write_result = await _post_xml(client, xml_req)

    ready_reference = _first_non_empty_string(normalized_ready_payload.get("REFERENCE"), import_payload.get("REFERENCE"))
    ready_voucher_number = _first_non_empty_string(normalized_ready_payload.get("VOUCHERNUMBER"), import_payload.get("VOUCHERNUMBER"))
    ready_party = _first_non_empty_string(normalized_ready_payload.get("PARTYLEDGERNAME"), normalized_ready_payload.get("PARTYNAME"))
    ready_date = _require_string(normalized_ready_payload, "DATE")

    voucher_snapshot = await _fetch_single_sales_voucher(
        client,
        company_name,
        ready_date,
        ready_voucher_number,
        ready_reference,
        ready_party,
        fetch_fields=SALES_GENERATE_STATUS_FETCH,
    )

    resolved_voucher_number = _first_non_empty_string(voucher_snapshot.get("VOUCHERNUMBER"))
    if not resolved_voucher_number:
        raise HTTPException(
            status_code=502,
            detail={
                "supported": True,
                "fallback_applied": False,
                "error_type": "tally_error",
                "reason": "Created Sales voucher could not be resolved to a Tally voucher number for writeback.",
                "reference": ready_reference,
                "date": ready_date,
            },
        )

    generation_result: Dict[str, Any] = {
        "voucher_write_result": voucher_write_result,
        "voucher_identity": {
            "voucher_type": "Sales",
            "date": ready_date,
            "voucher_number": resolved_voucher_number,
            "reference": _first_non_empty_string(voucher_snapshot.get("REFERENCE"), ready_reference),
            "party_ledger_name": _first_non_empty_string(voucher_snapshot.get("PARTYLEDGERNAME"), voucher_snapshot.get("PARTYNAME"), ready_party),
        },
    }

    if include_einvoice:
        einvoice_provider_payload = _build_mi_einvoice_payload(identity_payload, normalized_ready_payload)
        einvoice_provider_response = await _gst_service_post(settings, "/einvoice", einvoice_provider_payload, "einvoice")
        einvoice_writeback_fields = _extract_einvoice_writeback_fields(einvoice_provider_response)
        einvoice_writeback_result = await _alter_sales_voucher(
            client,
            company_name,
            resolved_voucher_number,
            ready_date,
            einvoice_writeback_fields,
        )
        voucher_snapshot = await _fetch_single_sales_voucher(
            client,
            company_name,
            ready_date,
            resolved_voucher_number,
            generation_result["voucher_identity"]["reference"],
            generation_result["voucher_identity"]["party_ledger_name"],
            fetch_fields=SALES_GENERATE_STATUS_FETCH,
        )
        generation_result["einvoice"] = {
            "provider_payload": einvoice_provider_payload,
            "provider_response": einvoice_provider_response,
            "tally_writeback_result": einvoice_writeback_result,
        }

    if include_ewaybill:
        irn_value = _first_non_empty_string(
            voucher_snapshot.get("IRN"),
            _mi_message_map(generation_result.get("einvoice", {}).get("provider_response", {})).get("Irn") if isinstance(generation_result.get("einvoice"), dict) else None,
        )
        if not irn_value:
            raise HTTPException(
                status_code=422,
                detail={
                    "supported": True,
                    "fallback_applied": False,
                    "error_type": "validation",
                    "reason": "IRN is required before e-Way Bill generation. Generate e-Invoice first or use the combined endpoint.",
                    "voucher_number": resolved_voucher_number,
                    "date": ready_date,
                },
            )
        ewaybill_provider_payload = _build_mi_ewaybill_payload(identity_payload, normalized_ready_payload, irn_value)
        ewaybill_provider_response = await _gst_service_post(settings, "/ewaybill/byirn", ewaybill_provider_payload, "ewaybill")
        ewaybill_writeback_fields = _extract_ewaybill_writeback_fields(ewaybill_provider_response, ewaybill_provider_payload)
        ewaybill_writeback_result = await _alter_sales_voucher(
            client,
            company_name,
            resolved_voucher_number,
            ready_date,
            ewaybill_writeback_fields,
        )
        voucher_snapshot = await _fetch_single_sales_voucher(
            client,
            company_name,
            ready_date,
            resolved_voucher_number,
            generation_result["voucher_identity"]["reference"],
            generation_result["voucher_identity"]["party_ledger_name"],
            fetch_fields=SALES_GENERATE_STATUS_FETCH,
        )
        generation_result["ewaybill"] = {
            "provider_payload": ewaybill_provider_payload,
            "provider_response": ewaybill_provider_response,
            "tally_writeback_result": ewaybill_writeback_result,
        }

    generation_result["tally_status"] = voucher_snapshot
    writeback_warnings: List[str] = []
    if include_einvoice and isinstance(generation_result.get("einvoice"), dict):
        einvoice_message = _mi_message_map(generation_result["einvoice"].get("provider_response"))
        provider_ack_no = _stringify_non_empty(einvoice_message.get("AckNo"))
        provider_ack_date = _provider_datetime_to_tally_date(einvoice_message.get("AckDt"))
        if provider_ack_no and _stringify_non_empty(voucher_snapshot.get("IRNACKNO")) != provider_ack_no:
            writeback_warnings.append("IRN ack number was returned by the GST provider but did not persist back into Tally.")
        if provider_ack_date and _stringify_non_empty(voucher_snapshot.get("IRNACKDATE")) != provider_ack_date:
            writeback_warnings.append("IRN ack date was returned by the GST provider but did not persist back into Tally through the generic voucher alter path.")
    if include_ewaybill and isinstance(generation_result.get("ewaybill"), dict):
        ewaybill_message = _mi_message_map(generation_result["ewaybill"].get("provider_response"))
        provider_ewaybill_no = _stringify_non_empty(ewaybill_message.get("EwbNo"))
        provider_ewaybill_date = _provider_datetime_to_tally_date(ewaybill_message.get("EwbDt"))
        if provider_ewaybill_no and _stringify_non_empty(voucher_snapshot.get("TEMPGSTEWAYBILLNUMBER")) != provider_ewaybill_no:
            writeback_warnings.append("e-Way Bill number was returned by the GST provider but did not persist back into Tally through the generic voucher alter path.")
        if provider_ewaybill_date and _stringify_non_empty(voucher_snapshot.get("TEMPGSTEWAYBILLDATE")) != provider_ewaybill_date:
            writeback_warnings.append("e-Way Bill date was returned by the GST provider but did not persist back into Tally through the generic voucher alter path.")
    if writeback_warnings:
        generation_result["writeback_warnings"] = writeback_warnings
    return generation_result


async def _common_generate_voucher_upsert(
    payload: Dict[str, Any],
    action: str,
    voucher_type_override: Optional[str],
    company: Optional[str],
    x_company: Optional[str],
    settings: Settings,
    client: TallyClient,
    include_einvoice: bool,
    include_ewaybill: bool,
) -> Dict[str, Any]:
    compliance_mode = "einvoice_ewaybill" if include_einvoice and include_ewaybill else ("einvoice" if include_einvoice else "ewaybill")
    canonical_voucher_type = _resolve_common_ready_voucher_type(payload, voucher_type_override)
    _validate_common_generate_voucher_type(canonical_voucher_type, compliance_mode)

    normalized_payload = copy.deepcopy(payload)
    voucher_data = _coerce_mapping(normalized_payload.get("voucher"), "voucher")
    voucher_data.setdefault("voucher_type", canonical_voucher_type)
    normalized_payload["voucher"] = voucher_data
    normalized_payload["voucher_type"] = canonical_voucher_type

    if canonical_voucher_type == "Sales":
        return await _sales_generate_voucher(
            normalized_payload,
            action,
            company,
            x_company,
            settings,
            client,
            include_einvoice,
            include_ewaybill,
        )

    raise HTTPException(
        status_code=501,
        detail={
            "supported": False,
            "resource": f"{canonical_voucher_type.lower()} {compliance_mode} generate",
            "fallback_applied": False,
            "reason": f"{canonical_voucher_type} routing is not implemented in this connector build yet.",
        },
    )


@router.post("/vouchers/einvoice/generate")
async def voucher_einvoice_generate(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type override; defaults to Sales"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_generate_voucher_upsert(
            payload,
            action or "Create",
            voucher_type,
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=False,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/sales/einvoice/generate")
async def sales_einvoice_generate(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_generate_voucher_upsert(
            payload,
            action or "Create",
            "Sales",
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=False,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/ewaybill/generate")
async def voucher_ewaybill_generate(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type override; defaults to Sales"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_generate_voucher_upsert(
            payload,
            action or "Create",
            voucher_type,
            company,
            x_company,
            settings,
            client,
            include_einvoice=False,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/sales/ewaybill/generate")
async def sales_ewaybill_generate(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_generate_voucher_upsert(
            payload,
            action or "Create",
            "Sales",
            company,
            x_company,
            settings,
            client,
            include_einvoice=False,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/einvoice-ewaybill/generate")
async def voucher_einvoice_ewaybill_generate(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type override; defaults to Sales"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_generate_voucher_upsert(
            payload,
            action or "Create",
            voucher_type,
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/sales/einvoice-ewaybill/generate")
async def sales_einvoice_ewaybill_generate(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_generate_voucher_upsert(
            payload,
            action or "Create",
            "Sales",
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/einvoice-ready")
async def voucher_einvoice_ready(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter/Delete)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type override; defaults to Sales"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_ready_voucher_upsert(
            payload,
            action or "Create",
            voucher_type,
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=False,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/sales/einvoice-ready")
async def sales_einvoice_ready(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_ready_voucher_upsert(
            payload,
            action or "Create",
            "Sales",
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=False,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.get("/vouchers/sales/einvoice-status")
async def sales_einvoice_status(
    voucher_number: Optional[str] = Query(default=None, description="Exact Tally voucher number"),
    reference: Optional[str] = Query(default=None, description="Exact voucher reference"),
    party_ledger_name: Optional[str] = Query(default=None, description="Exact party ledger name"),
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    limit: Optional[int] = Query(default=20, description="Max matching sales vouchers"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    return await _sales_status_response(
        "einvoice",
        SALES_EINVOICE_STATUS_FETCH,
        company,
        x_company,
        voucher_number,
        reference,
        party_ledger_name,
        from_date,
        to_date,
        limit,
        settings,
        client,
    )


@router.post("/vouchers/ewaybill-ready")
async def voucher_ewaybill_ready(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter/Delete)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type override; defaults to Sales"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_ready_voucher_upsert(
            payload,
            action or "Create",
            voucher_type,
            company,
            x_company,
            settings,
            client,
            include_einvoice=False,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/sales/ewaybill-ready")
async def sales_ewaybill_ready(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_ready_voucher_upsert(
            payload,
            action or "Create",
            "Sales",
            company,
            x_company,
            settings,
            client,
            include_einvoice=False,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.get("/vouchers/sales/ewaybill-status")
async def sales_ewaybill_status(
    voucher_number: Optional[str] = Query(default=None, description="Exact Tally voucher number"),
    reference: Optional[str] = Query(default=None, description="Exact voucher reference"),
    party_ledger_name: Optional[str] = Query(default=None, description="Exact party ledger name"),
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    limit: Optional[int] = Query(default=20, description="Max matching sales vouchers"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    return await _sales_status_response(
        "ewaybill",
        SALES_EWAYBILL_STATUS_FETCH,
        company,
        x_company,
        voucher_number,
        reference,
        party_ledger_name,
        from_date,
        to_date,
        limit,
        settings,
        client,
    )


@router.post("/vouchers/einvoice-ewaybill-ready")
async def voucher_einvoice_ewaybill_ready(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter/Delete)"),
    voucher_type: Optional[str] = Query(default=None, description="Voucher type override; defaults to Sales"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_ready_voucher_upsert(
            payload,
            action or "Create",
            voucher_type,
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/vouchers/sales/einvoice-ewaybill-ready")
async def sales_einvoice_ewaybill_ready(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Create", description="Tally ACTION (Create/Alter/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    try:
        return await _common_ready_voucher_upsert(
            payload,
            action or "Create",
            "Sales",
            company,
            x_company,
            settings,
            client,
            include_einvoice=True,
            include_ewaybill=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


def _connector_capability_matrix(settings: Settings) -> Dict[str, Any]:
    tdl_mode = "tdl-backed" if settings.tdl_integration_enabled else "not-configured"
    return {
        "native_xml": [
            "/health",
            "/auth/token",
            "/companies",
            "/ledgers",
            "/groups",
            "/stock-items",
            "/price-lists",
            "/reports/companies",
            "/reports/price-lists",
        ],
        "provider_backed": [
            "/vouchers/einvoice/generate",
            "/vouchers/ewaybill/generate",
            "/vouchers/einvoice-ewaybill/generate",
            "/vouchers/einvoice-ready",
            "/vouchers/ewaybill-ready",
            "/vouchers/einvoice-ewaybill-ready",
        ],
        "tdl_backed_or_required": {
            "/companies/open": {
                "configured_mode": (
                    "tdl-ready"
                    if _tdl_feature_is_ready(settings, "company_open")
                    else tdl_mode if not settings.experimental_tally_company_open_enabled else "experimental-native-xml"
                ),
                "enabled": settings.tdl_integration_enabled or settings.experimental_tally_company_open_enabled,
                "tdl_feature": _tdl_feature_contract(settings, "company_open"),
            },
            "/companies/create": {
                "configured_mode": (
                    "tdl-ready"
                    if _tdl_feature_is_ready(settings, "company_create")
                    else tdl_mode if not settings.experimental_tally_company_create_enabled else "experimental-native-xml"
                ),
                "enabled": settings.tdl_integration_enabled or settings.experimental_tally_company_create_enabled,
                "tdl_feature": _tdl_feature_contract(settings, "company_create"),
            },
            "/settings/security-roles": {
                "configured_mode": (
                    "tdl-ready"
                    if _tdl_feature_is_ready(settings, "security_roles")
                    else tdl_mode if not settings.experimental_tally_security_roles_enabled else "experimental-native-xml"
                ),
                "enabled": settings.tdl_integration_enabled or settings.experimental_tally_security_roles_enabled,
                "tdl_feature": _tdl_feature_contract(settings, "security_roles"),
            },
            "/price-levels": {
                "configured_mode": (
                    "tdl-ready"
                    if _tdl_feature_is_ready(settings, "price_levels")
                    else "item-price-list-fallback" if not settings.tdl_integration_enabled else "tdl-backed"
                ),
                "enabled": True,
                "note": "Standalone company-level price level names require TDL or verified native support; item-linked updates already work through /price-lists.",
                "tdl_feature": _tdl_feature_contract(settings, "price_levels"),
            },
            "/employees": {
                "configured_mode": "tdl-ready" if _tdl_feature_is_ready(settings, "payroll_exports") else tdl_mode if settings.tdl_integration_enabled else "native-payroll-export-with-tdl-fallback",
                "enabled": True,
                "note": "Employee writes use COSTCENTRE payroll flags; exports may still require payroll to be enabled or a TDL-backed payroll export path.",
                "tdl_feature": _tdl_feature_contract(settings, "payroll_exports"),
            },
            "/employee-groups": {
                "configured_mode": "tdl-ready" if _tdl_feature_is_ready(settings, "payroll_exports") else tdl_mode if settings.tdl_integration_enabled else "native-payroll-export-with-tdl-fallback",
                "enabled": True,
                "note": "Employee group writes use COSTCENTRE payroll flags; exports may still require payroll to be enabled or a TDL-backed payroll export path.",
                "tdl_feature": _tdl_feature_contract(settings, "payroll_exports"),
            },
            "/pay-heads": {
                "configured_mode": "tdl-ready" if _tdl_feature_is_ready(settings, "payroll_exports") else tdl_mode if settings.tdl_integration_enabled else "native-payroll-export-with-tdl-fallback",
                "enabled": True,
                "note": "Pay head exports depend on the Payroll Ledgers collection or a TDL-backed payroll export path in this Tally build.",
                "tdl_feature": _tdl_feature_contract(settings, "payroll_exports"),
            },
            "/attendance-types": {
                "configured_mode": "tdl-ready" if _tdl_feature_is_ready(settings, "payroll_exports") else tdl_mode if settings.tdl_integration_enabled else "native-payroll-export-with-tdl-fallback",
                "enabled": True,
                "note": "Attendance type exports depend on the payroll feature being visible to XML or a TDL-backed payroll export path.",
                "tdl_feature": _tdl_feature_contract(settings, "payroll_exports"),
            },
            "/reports/tds-outstandings": {
                "configured_mode": "tdl-ready" if _tdl_feature_is_ready(settings, "tds_outstandings") else tdl_mode,
                "enabled": True,
                "note": "Falls back to the generic Tally XML report path when no verified TDL report is loaded.",
                "tdl_feature": _tdl_feature_contract(settings, "tds_outstandings"),
            },
        },
        "partial_documented_behavior": {
            "/vouchers/einvoice/generate": {
                "implemented_voucher_types": COMMON_GENERATE_SUPPORT_MATRIX["einvoice"]["implemented"],
                "documented_voucher_types": COMMON_GENERATE_SUPPORT_MATRIX["einvoice"]["documented"],
            },
            "/vouchers/ewaybill/generate": {
                "implemented_voucher_types": COMMON_GENERATE_SUPPORT_MATRIX["ewaybill"]["implemented"],
                "documented_voucher_types": COMMON_GENERATE_SUPPORT_MATRIX["ewaybill"]["documented"],
            },
            "/vouchers/einvoice-ewaybill/generate": {
                "implemented_voucher_types": COMMON_GENERATE_SUPPORT_MATRIX["einvoice_ewaybill"]["implemented"],
                "documented_voucher_types": COMMON_GENERATE_SUPPORT_MATRIX["einvoice_ewaybill"]["documented"],
            },
        },
    }


def _connector_capability_summary(matrix: Dict[str, Any]) -> Dict[str, int]:
    return {
        "native_xml_count": len(matrix.get("native_xml", [])),
        "provider_backed_count": len(matrix.get("provider_backed", [])),
        "tdl_backed_or_required_count": len(matrix.get("tdl_backed_or_required", {})),
        "partial_documented_behavior_count": len(matrix.get("partial_documented_behavior", {})),
    }


def _route_status_detail(path: str, methods: List[str], settings: Settings) -> Dict[str, Any]:
    method_set = {method.upper() for method in methods}

    if path in {"/companies/open", "/companies/create", "/settings/security-roles"}:
        feature_name = {
            "/companies/open": "company_open",
            "/companies/create": "company_create",
            "/settings/security-roles": "security_roles",
        }[path]
        detail = {
            "status": "tdl_or_experimental",
            "reason": "Stable built-in XML behavior is not verified for this feature in the current connector design.",
            "tdl_feature": _tdl_feature_contract(settings, feature_name),
        }
        if path == "/companies/open":
            detail["experimental_flag"] = "EXPERIMENTAL_TALLY_COMPANY_OPEN_ENABLED"
        elif path == "/companies/create":
            detail["experimental_flag"] = "EXPERIMENTAL_TALLY_COMPANY_CREATE_ENABLED"
        else:
            detail["experimental_flag"] = "EXPERIMENTAL_TALLY_SECURITY_ROLES_ENABLED"
        return detail

    if path == "/price-levels" or path == "/settings/price-structures":
        return {
            "status": "partial",
            "reason": (
                "Item-linked price-list writes work, but standalone company-level price-level names still need "
                "a verified native XML path or a TDL-backed implementation."
            ),
            "tdl_feature": _tdl_feature_contract(settings, "price_levels"),
            "use_instead": "/price-lists",
        }

    if path in {"/employees", "/employee-groups", "/pay-heads", "/attendance-types"}:
        return {
            "status": "partial",
            "reason": (
                "Payroll routes are live, but successful behavior still depends on payroll being enabled in Tally "
                "or on a TDL-backed payroll export/import path."
            ),
            "tdl_feature": _tdl_feature_contract(settings, "payroll_exports"),
        }

    if path in STATUTORY_REPORT_ROUTES:
        if path == "/reports/tds-outstandings":
            return {
                "status": "partial",
                "reason": (
                    "The generic XML report path is build-dependent; a TDL-backed report can be used as a fallback "
                    "when the local TDL feature is loaded and marked ready."
                ),
                "tdl_feature": _tdl_feature_contract(settings, "tds_outstandings"),
            }
        return {
            "status": "tally_build_dependent",
            "reason": (
                "The route is implemented, but support depends on whether the current Tally build exposes the "
                "underlying report through XML export."
            ),
        }

    if path in {
        "/vouchers/einvoice/generate",
        "/vouchers/ewaybill/generate",
        "/vouchers/einvoice-ewaybill/generate",
        "/vouchers/einvoice-ready",
        "/vouchers/ewaybill-ready",
        "/vouchers/einvoice-ewaybill-ready",
    }:
        compliance_mode = (
            "einvoice_ewaybill"
            if "einvoice-ewaybill" in path
            else "einvoice" if "einvoice" in path
            else "ewaybill"
        )
        support_matrix = (
            COMMON_READY_SUPPORT_MATRIX
            if path.endswith("-ready")
            else COMMON_GENERATE_SUPPORT_MATRIX
        )
        return {
            "status": "partial",
            "reason": "Only a subset of documented voucher types is implemented for the common compliance flow.",
            "implemented_voucher_types": support_matrix[compliance_mode]["implemented"],
            "documented_voucher_types": support_matrix[compliance_mode]["documented"],
        }

    if path in {"/vouchers/sales/einvoice/generate", "/vouchers/sales/ewaybill/generate", "/vouchers/sales/einvoice-ewaybill/generate",
                "/vouchers/sales/einvoice-ready", "/vouchers/sales/ewaybill-ready", "/vouchers/sales/einvoice-ewaybill-ready",
                "/vouchers/sales/einvoice-status", "/vouchers/sales/ewaybill-status"}:
        return {
            "status": "implemented",
            "reason": "Sales-specific compliance routing is implemented in the connector.",
        }

    if path == "/currencies" and "POST" in method_set:
        return {
            "status": "partial",
            "reason": (
                "Currency reads and some updates are supported, but generic create is intentionally blocked because "
                "the native XML path is not stable in the verified Tally build."
            ),
        }

    if path == "/companies" and "POST" in method_set:
        return {
            "status": "partial",
            "reason": (
                "Generic company writes work for supported alter flows, but generic create is explicitly blocked; "
                "use /companies/create instead."
            ),
            "use_instead": "/companies/create",
        }

    return {
        "status": "implemented",
        "reason": "Route is implemented through native XML, explicit collection mapping, or verified connector logic.",
    }


def _connector_route_inventory(settings: Settings) -> List[Dict[str, Any]]:
    inventory: List[Dict[str, Any]] = []
    for route in router.routes:
        if not isinstance(route, APIRoute):
            continue
        methods = sorted(
            method for method in route.methods
            if method not in {"HEAD", "OPTIONS"}
        )
        detail = _route_status_detail(route.path, methods, settings)
        inventory.append(
            {
                "path": route.path,
                "methods": methods,
                **detail,
            }
        )
    inventory.sort(key=lambda item: item["path"])
    return inventory


def _connector_route_inventory_summary(inventory: List[Dict[str, Any]]) -> Dict[str, int]:
    summary: Dict[str, int] = {}
    for item in inventory:
        status = str(item.get("status") or "unknown")
        summary[status] = summary.get(status, 0) + 1
    return dict(sorted(summary.items()))


def _tdl_runtime_status(settings: Settings) -> Dict[str, Any]:
    manifest_path = settings.resolved_tdl_manifest_path()
    package_path = settings.resolved_tdl_package_path()
    manifest = _load_tdl_manifest(settings)
    required_features = settings.parsed_tdl_required_features()
    feature_statuses = {
        feature_name: _tdl_feature_contract(settings, feature_name)
        for feature_name in required_features
    }
    ready_features = [
        feature_name for feature_name in required_features if _tdl_feature_is_ready(settings, feature_name)
    ]
    return {
        "enabled": settings.tdl_integration_enabled,
        "profile_name": settings.tdl_profile_name.strip() or None,
        "package_path": str(package_path),
        "package_exists": package_path.is_file(),
        "manifest_path": str(manifest_path),
        "manifest_exists": manifest_path.is_file(),
        "manifest_profile_name": manifest.get("profile_name"),
        "required_features": required_features,
        "ready_features": ready_features,
        "missing_or_scaffold_features": [
            feature_name for feature_name in required_features if feature_name not in ready_features
        ],
        "feature_statuses": feature_statuses,
    }


async def _probe_tally_reachability(client: TallyClient) -> Dict[str, Any]:
    try:
        xml_req = build_company_list(["NAME"])
        await _post_xml(client, xml_req, "summary")
        return {"reachable": True, "status": "ok"}
    except HTTPException as exc:
        return {
            "reachable": False,
            "status": "error",
            "detail": exc.detail,
        }
    except Exception as exc:
        return {
            "reachable": False,
            "status": "error",
            "detail": repr(exc),
        }


async def _probe_http_endpoint(base_url: str, timeout_sec: float) -> Dict[str, Any]:
    normalized = str(base_url).rstrip("/")
    try:
        async with httpx.AsyncClient(timeout=timeout_sec) as http:
            response = await http.get(normalized)
        return {
            "reachable": True,
            "status": "ok",
            "status_code": response.status_code,
            "url": normalized,
        }
    except httpx.HTTPError as exc:
        return {
            "reachable": False,
            "status": "error",
            "detail": repr(exc),
            "url": normalized,
        }


@router.get("/health")
async def health(settings: Settings = Depends(get_settings)):
    return {
        "status": "ok",
        "version": "0.1.0",
        "environment": settings.normalized_app_env(),
        "warnings": settings.insecure_runtime_warnings(),
    }


@router.get("/health/readiness")
async def health_readiness(
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
):
    await ensure_auth(x_agent_key, settings)
    capability_matrix = _connector_capability_matrix(settings)
    tally_status = await _probe_tally_reachability(client)
    gst_status = await _probe_http_endpoint(str(settings.gst_service_base_url), settings.gst_service_timeout_sec)
    ready = tally_status.get("reachable") and gst_status.get("reachable")
    return {
        "status": "ok" if ready else "degraded",
        "ready": bool(ready),
        "environment": settings.normalized_app_env(),
        "upstreams": {
            "tally": tally_status,
            "gst_service": gst_status,
        },
        "tdl": _tdl_runtime_status(settings),
        "warnings": settings.insecure_runtime_warnings(),
        "capability_summary": _connector_capability_summary(capability_matrix),
    }


@router.get("/capabilities")
async def capabilities(
    settings: Settings = Depends(get_settings),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
):
    await ensure_auth(x_agent_key, settings)
    capability_matrix = _connector_capability_matrix(settings)
    return {
        "status": "ok",
        "environment": settings.normalized_app_env(),
        "tdl": _tdl_runtime_status(settings),
        "experimental_routes": {
            "companies_open_enabled": settings.experimental_tally_company_open_enabled,
            "companies_create_enabled": settings.experimental_tally_company_create_enabled,
            "security_roles_enabled": settings.experimental_tally_security_roles_enabled,
        },
        "capability_summary": _connector_capability_summary(capability_matrix),
        "capabilities": capability_matrix,
    }


@router.get("/implementation-matrix")
async def implementation_matrix(
    settings: Settings = Depends(get_settings),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
):
    await ensure_auth(x_agent_key, settings)
    route_inventory = _connector_route_inventory(settings)
    return {
        "status": "ok",
        "environment": settings.normalized_app_env(),
        "route_count": len(route_inventory),
        "status_summary": _connector_route_inventory_summary(route_inventory),
        "routes": route_inventory,
        "how_to_use": [
            "status=implemented means the connector has a verified native or explicit implementation path.",
            "status=partial means the route exists but some actions, voucher types, or Tally behaviors are still limited.",
            "status=tdl_or_experimental means safe support depends on TDL integration or an explicit experimental flag.",
            "status=tally_build_dependent means the route is implemented but the current Tally build may not expose the underlying report/screen through XML.",
        ],
    }


@router.post("/companies/open")
async def company_open(
    payload: Dict[str, Any],
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    company: Optional[str] = Query(default=None, description="Optional company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
):
    await ensure_auth(x_agent_key, settings)
    company_payload = dict(payload or {})
    if "COMPANY" not in company_payload and "company" not in company_payload:
        requested_name = _first_non_empty_string(
            payload.get("COMPANY"),
            payload.get("company"),
            payload.get("NAME"),
            payload.get("name"),
        )
        if requested_name:
            company_payload["COMPANY"] = requested_name
    company_name = resolve_company(settings.company, company_payload, company, x_company)
    if not company_name:
        raise HTTPException(status_code=400, detail="Company name is required")
    active_company_name = await _current_company_name_hint(client)
    if _company_names_match(active_company_name, company_name):
        return {
            "status": "ok",
            "mode": "already-open",
            "company": active_company_name,
            "switched": False,
            "message": "Requested company is already active in Tally.",
        }
    if settings.tdl_integration_enabled and _tdl_feature_is_ready(settings, "company_open"):
        contract = _tdl_feature_contract(settings, "company_open")
        report_name = (
            contract.get("write_report_name")
            or contract.get("report_name")
            or TDL_FEATURE_DEFAULTS["company_open"]["write_report_name"]
        )
        response = await _post_xml(
            client,
            build_tdl_gateway_report(
                str(report_name),
                payload={
                    "COMPANY": company_name,
                    "NAME": company_name,
                    "ACTION": "OPEN",
                },
            ),
            "full",
        )
        active_company_name = await _current_company_name_hint(client)
        if _company_names_match(active_company_name, company_name):
            return {
                "status": "ok",
                "mode": "tdl-report",
                "feature": "company_open",
                "company": company_name,
                "switched": True,
                "verified_via": "current_company_hint",
                "response": response,
            }
        return {
            "status": "submitted",
            "mode": "tdl-report",
            "feature": "company_open",
            "company": company_name,
            "switched": False,
            "verification": {
                "verified": False,
                "method": "current_company_hint",
                "active_company": active_company_name,
                "reason": "Tally accepted the request, but the connector could not verify that the active company changed yet.",
            },
            "response": response,
        }
    if settings.experimental_tally_company_open_enabled:
        try:
            xml_req = build_company_open(company_name)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        try:
            response = await _post_xml(client, xml_req, "full")
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=502, detail=_tally_http_error_detail(exc)) from exc
        active_company_name = await _current_company_name_hint(client)
        if _company_names_match(active_company_name, company_name):
            return {
                "status": "ok",
                "mode": "experimental-native-xml",
                "company": company_name,
                "switched": True,
                "verified_via": "current_company_hint",
                "warning": (
                    "This company-open flow is experimental. The connector verified the active company after the request, "
                    "but you should still confirm it in Tally when enabling new builds."
                ),
                "response": response,
            }
        raise HTTPException(
            status_code=502,
            detail={
                "supported": False,
                "resource": "company open",
                "fallback_applied": False,
                "error_type": "verification_failed",
                "reason": (
                    "Tally accepted the experimental company-open request, but the connector could not verify that the "
                    "active company switched to the requested company."
                ),
                "requested_company": company_name,
                "active_company": active_company_name,
                "tally_response": response,
            },
        )
    if settings.tdl_integration_enabled:
        raise HTTPException(
            status_code=501,
            detail=_tdl_feature_unavailable_detail(
                settings,
                "company_open",
                "/companies/open",
                action="Open",
            ),
        )
    if not settings.experimental_tally_company_open_enabled:
        experimental_route_disabled(
            "company open",
            "EXPERIMENTAL_TALLY_COMPANY_OPEN_ENABLED",
            (
                "A stable built-in Tally XML company-switch request has not been verified in this "
                "Tally build. The previous connector call only set SVCURRENTCOMPANY inside an "
                "Export envelope, which Tally can reject with 'Unknown Request'."
            ),
        )
    raise HTTPException(status_code=500, detail="unreachable route state")


@router.get("/companies/list")
async def company_list(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch (default NAME, GUID, RESERVEDNAME)"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else fetch
    xml_req = build_company_list(effective_fetch)
    response = await _post_xml(client, xml_req, normalized_view)
    if normalized_view == "raw":
        return response
    if _is_null_envelope_response(response):
        return _empty_company_list_response(
            "Tally returned ENVELOPE=null for 'List of Companies'. This usually means no company is currently "
            "open in TallyPrime or the session is still on the gateway screen."
        )
    if isinstance(response, dict) and response.get("COLLECTION") is None:
        return _empty_company_list_response(
            "Tally returned no company rows for 'List of Companies'. This usually means no company is currently "
            "open in TallyPrime or the session is still on the gateway screen."
        )
    return response


@router.post("/companies/update")
async def company_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company to apply update to"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_payload = _normalize_company_write_payload(payload)
    company_name = resolve_company(settings.company, normalized_payload, company, x_company)
    try:
        xml_req = build_company_update(company_name, action or "Alter", normalized_payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    try:
        xml_resp = await client.post_xml(xml_req)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=_tally_http_error_detail(exc)) from exc
    return prune_export_response(xml_to_json(xml_resp))


@router.post("/companies/create")
async def company_create(
    payload: Dict[str, Any],
    company: Optional[str] = Query(default=None, description="Context company; defaults to current/open company"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    """
    Create a new company in Tally. Provide at least NAME plus other fields (ADDRESS, STATENAME, COUNTRYNAME, PINCODE, EMAIL, etc).
    """
    await ensure_auth(x_agent_key, settings)
    normalized_payload = _validate_company_create_payload(payload)
    if settings.tdl_integration_enabled and _tdl_feature_is_ready(settings, "company_create"):
        contract = _tdl_feature_contract(settings, "company_create")
        report_name = (
            contract.get("write_report_name")
            or contract.get("report_name")
            or TDL_FEATURE_DEFAULTS["company_create"]["write_report_name"]
        )
        response = await _post_xml(
            client,
            build_tdl_gateway_report(
                str(report_name),
                payload={**normalized_payload, "ACTION": "CREATE"},
            ),
            "full",
        )
        is_visible = await _company_visible_in_list(client, normalized_payload.get("NAME"))
        if is_visible:
            return {
                "status": "ok",
                "mode": "tdl-report",
                "feature": "company_create",
                "company": normalized_payload.get("NAME"),
                "created": True,
                "verified_via": "company_list",
                "response": response,
            }
        return {
            "status": "submitted",
            "mode": "tdl-report",
            "feature": "company_create",
            "company": normalized_payload.get("NAME"),
            "created": False,
            "verification": {
                "verified": False,
                "method": "company_list",
                "company_visible": is_visible,
                "reason": "Tally accepted the request, but the connector could not verify the new company in the exported company list yet.",
            },
            "response": response,
        }
    if settings.experimental_tally_company_create_enabled:
        try:
            xml_req = build_company_create(normalized_payload)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        try:
            response = await _post_xml_import_result(client, xml_req)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=502, detail=_tally_http_error_detail(exc, compact=True)) from exc
        company_name = normalized_payload.get("NAME")
        if _is_tally_noop_response(response) or _import_result_counter(response, "CREATED") <= 0:
            raise HTTPException(
                status_code=501,
                detail={
                    "supported": False,
                    "resource": "company create",
                    "fallback_applied": False,
                    "error_type": "write_no_effect",
                    "reason": (
                        "Tally acknowledged the experimental company-create request, but it did not report any created company."
                    ),
                    "requested_company": company_name,
                    "tally_response": response,
                },
            )
        is_visible = await _company_visible_in_list(client, company_name)
        if is_visible:
            return {
                "status": "ok",
                "mode": "experimental-native-xml",
                "company": company_name,
                "created": True,
                "verified_via": "company_list",
                "warning": (
                    "This company-create flow is experimental. The connector verified the company in the exported company list, "
                    "but you should still confirm it in Tally when validating a new build."
                ),
                "response": response,
            }
        return {
            "status": "submitted",
            "mode": "experimental-native-xml",
            "company": company_name,
            "created": False,
            "warning": (
                "This company-create flow is experimental. Tally reported a create acknowledgement, but the connector could not "
                "verify the new company in the exported company list yet."
            ),
            "verification": {
                "verified": False,
                "method": "company_list",
                "company_visible": is_visible,
            },
            "response": response,
        }
    if settings.tdl_integration_enabled:
        raise HTTPException(
            status_code=501,
            detail=_tdl_feature_unavailable_detail(
                settings,
                "company_create",
                "/companies/create",
                action="Create",
            ),
        )
    if not settings.experimental_tally_company_create_enabled:
        experimental_route_disabled(
            "company create",
            "EXPERIMENTAL_TALLY_COMPANY_CREATE_ENABLED",
            (
                "A stable built-in Tally XML path for creating a new company has not been verified "
                "in this Tally build. The previous company-create envelope can disconnect the Tally "
                "HTTP interface instead of returning a safe import acknowledgement."
            ),
        )
    raise HTTPException(status_code=500, detail="unreachable route state")


@router.post("/companies/alter")
async def company_alter(
    payload: Dict[str, Any],
    company: Optional[str] = Query(default=None, description="Company to alter"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    """
    Alter an existing company in Tally. Provide NAME and fields to update.
    """
    await ensure_auth(x_agent_key, settings)
    normalized_payload = _normalize_company_write_payload(payload)
    target_company = resolve_company(settings.company, normalized_payload, company, x_company)
    try:
        return await _apply_company_alter_chunks(client, target_company, normalized_payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.get("/settings/company-features")
async def company_features(
    fetch: Optional[List[str]] = Query(default=None, description="Optional company feature fields to fetch"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or COMPANY_FEATURE_FETCH)
    return await _export_master(client, company_name, "company", effective_fetch, limit=1, view=normalized_view)


@router.post("/settings/company-features")
async def company_features_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _update_company_settings(client, company_name, payload, action or "Alter")


@router.get("/settings/gst-registration")
async def gst_registration(
    fetch: Optional[List[str]] = Query(default=None, description="Optional GST registration fields to fetch"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or GST_REGISTRATION_FETCH)
    return await _export_master(client, company_name, "company", effective_fetch, limit=1, view=normalized_view)


@router.post("/settings/gst-registration")
async def gst_registration_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _update_company_settings(client, company_name, payload, action or "Alter")


@router.get("/settings/company-currency")
async def company_currency(
    fetch: Optional[List[str]] = Query(default=None, description="Optional company currency fields to fetch"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or COMPANY_CURRENCY_FETCH)
    return await _export_master(client, company_name, "company", effective_fetch, limit=1, view=normalized_view)


@router.post("/settings/company-currency")
async def company_currency_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_payload = _normalize_company_write_payload(payload)
    company_name = resolve_company(settings.company, normalized_payload, company, x_company)
    currency_payload = _slice_payload(normalized_payload, ["NAME"] + sorted(COMPANY_ALTER_CURRENCY_FIELDS | COMPANY_ALTER_DATE_FIELDS))
    currency_payload.setdefault("NAME", company_name)
    return await _update_company_settings(client, company_name, currency_payload, action or "Alter")


@router.get("/settings/numbering-rules")
async def numbering_rules(
    fetch: Optional[List[str]] = Query(default=None, description="Optional voucher type fields to fetch"),
    filter_expr: Optional[str] = Query(default=None, alias="filter", description="Optional voucher type filter"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or NUMBERING_RULE_FETCH)
    return await _export_master(
        client,
        company_name,
        "voucher type",
        effective_fetch,
        filter_expr=filter_expr,
        limit=limit,
        view=normalized_view,
    )


@router.post("/settings/numbering-rules")
async def numbering_rules_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _upsert_master(client, company_name, "voucher type", payload, action or "Alter")


@router.get("/settings/tax-rate-tables")
async def tax_rate_tables(
    stock_fetch: Optional[List[str]] = Query(default=None, description="Stock item fields to fetch"),
    ledger_fetch: Optional[List[str]] = Query(default=None, description="Ledger fields to fetch"),
    stock_limit: Optional[int] = Query(default=None, description="Max stock items"),
    ledger_limit: Optional[int] = Query(default=None, description="Max ledgers"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_stock_fetch = _full_fetch(stock_fetch) if _needs_full_fetch(normalized_view) else (stock_fetch or STOCK_TAX_FETCH)
    effective_ledger_fetch = _full_fetch(ledger_fetch) if _needs_full_fetch(normalized_view) else (ledger_fetch or LEDGER_TAX_FETCH)
    stock_collection_name = _collection_name(_master_collection_prefix("stock item"), f"{__name__}.report_tax_rates.stock_items")
    ledger_collection_name = _collection_name(_master_collection_prefix("ledger"), f"{__name__}.report_tax_rates.ledgers")
    return {
        "stock_items": await _export_master(
            client,
            company_name,
            "stock item",
            effective_stock_fetch,
            limit=stock_limit,
            view=normalized_view,
            collection_name=stock_collection_name,
        ),
        "ledgers": await _export_master(
            client,
            company_name,
            "ledger",
            effective_ledger_fetch,
            limit=ledger_limit,
            view=normalized_view,
            collection_name=ledger_collection_name,
        ),
    }


@router.get("/settings/price-structures")
async def price_structures(
    fetch: Optional[List[str]] = Query(default=None, description="Price level fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_price_levels(
        client,
        company_name,
        fetch,
        limit,
        view or "summary",
        settings=settings,
        route_path="/settings/price-structures",
    )


@router.post("/settings/price-structures")
async def price_structures_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _upsert_price_level(
        client,
        company_name,
        payload,
        action or "Alter",
        settings=settings,
    )


@router.get("/settings/stock-controls")
async def stock_controls(
    stock_fetch: Optional[List[str]] = Query(default=None, description="Stock item fields to fetch"),
    godown_fetch: Optional[List[str]] = Query(default=None, description="Godown fields to fetch"),
    stock_limit: Optional[int] = Query(default=None, description="Max stock items"),
    godown_limit: Optional[int] = Query(default=None, description="Max godowns"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_stock_fetch = _full_fetch(stock_fetch) if _needs_full_fetch(normalized_view) else (stock_fetch or STOCK_CONTROL_FETCH)
    effective_godown_fetch = _full_fetch(godown_fetch) if _needs_full_fetch(normalized_view) else (godown_fetch or ["NAME"])
    stock_collection_name = _collection_name(_master_collection_prefix("stock item"), f"{__name__}.stock_controls.stock_items")
    godown_collection_name = _collection_name(_master_collection_prefix("godown"), f"{__name__}.stock_controls.godowns")
    return {
        "stock_items": await _export_master(
            client,
            company_name,
            "stock item",
            effective_stock_fetch,
            limit=stock_limit,
            view=normalized_view,
            collection_name=stock_collection_name,
        ),
        "godowns": await _export_master(
            client,
            company_name,
            "godown",
            effective_godown_fetch,
            limit=godown_limit,
            view=normalized_view,
            collection_name=godown_collection_name,
        ),
    }


@router.post("/settings/stock-controls")
async def stock_controls_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _upsert_master(client, company_name, "stock item", payload, action or "Alter")


@router.get("/settings/security-roles")
async def security_roles(
    fetch: Optional[List[str]] = Query(default=None, description="Security level fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_view = _normalize_view(view)
    company_name = resolve_company(settings.company, None, company, x_company)
    if settings.tdl_integration_enabled and _tdl_feature_is_ready(settings, "security_roles"):
        contract = _tdl_feature_contract(settings, "security_roles")
        collection_name = _tdl_feature_route_collection(contract, "/settings/security-roles")
        if collection_name:
            response = await _post_xml(client, build_collection_export(collection_name, company_name), normalized_view)
        else:
            report_name = (
                contract.get("report_name")
                or TDL_FEATURE_DEFAULTS["security_roles"]["report_name"]
            )
            response = await _post_xml(
                client,
                build_tdl_gateway_report(
                    str(report_name),
                    company=company_name,
                    payload={"ACTION": "LIST"},
                ),
                normalized_view,
            )
        if _is_tdl_scaffold_placeholder_response(response):
            raise HTTPException(
                status_code=501,
                detail=_security_roles_unavailable_detail(
                    "The configured TDL security-roles feature is still serving the scaffold placeholder response, so live security-role export is not implemented yet.",
                    action="List",
                    response=response,
                ),
            )
        if _is_metadata_only_collection(response):
            raise HTTPException(
                status_code=501,
                detail=_security_roles_unavailable_detail(
                    "The TDL security-role export returned only metadata and no role records. A real collection/report implementation is still required.",
                    action="List",
                    response=response,
                ),
            )
        return response
    if settings.tdl_integration_enabled and not settings.experimental_tally_security_roles_enabled:
        raise HTTPException(
            status_code=501,
            detail=_tdl_feature_unavailable_detail(
                settings,
                "security_roles",
                "/settings/security-roles",
                action="List",
            ),
        )
    if not settings.experimental_tally_security_roles_enabled:
        experimental_route_disabled(
            "security role",
            "EXPERIMENTAL_TALLY_SECURITY_ROLES_ENABLED",
            (
                "Security roles are not exposed through a stable built-in Tally XML "
                "export path in this Tally build. Direct collection probes such as "
                "'Security Levels' and 'Users and Passwords' can disconnect the XML request."
            ),
        )
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or ["NAME"])
    collection_name = _collection_name("PYMASTER", "settings_security_roles")
    try:
        xml_req = build_master_list(
            company_name,
            "security level",
            effective_fetch,
            limit=limit,
            collection_name=collection_name,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    response = await _post_xml(client, xml_req, normalized_view)
    if _is_metadata_only_collection(response):
        raise HTTPException(
            status_code=501,
            detail=_security_roles_unavailable_detail(
                "The experimental Security Level export returned only metadata and no records. This Tally build still needs a dedicated TDL/report-based integration.",
                action="List",
                response=response,
            ),
        )
    return response


@router.post("/settings/security-roles")
async def security_roles_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    if settings.tdl_integration_enabled and _tdl_feature_is_ready(settings, "security_roles"):
        company_name = resolve_company(settings.company, payload, company, x_company)
        contract = _tdl_feature_contract(settings, "security_roles")
        report_name = (
            contract.get("write_report_name")
            or contract.get("report_name")
            or TDL_FEATURE_DEFAULTS["security_roles"]["write_report_name"]
        )
        response = await _post_xml(
            client,
            build_tdl_gateway_report(
                str(report_name),
                company=company_name,
                payload={**dict(payload or {}), "ACTION": action or "Alter"},
            ),
            "full",
        )
        if _is_tdl_scaffold_placeholder_response(response):
            raise HTTPException(
                status_code=501,
                detail=_security_roles_unavailable_detail(
                    "The configured TDL security-role write path is still returning the scaffold placeholder response, so live writes are not implemented yet.",
                    action=action or "Alter",
                    response=response,
                ),
            )
        return {
            "status": "submitted",
            "mode": "tdl-report",
            "feature": "security_roles",
            "response": response,
        }
    if settings.tdl_integration_enabled and not settings.experimental_tally_security_roles_enabled:
        raise HTTPException(
            status_code=501,
            detail=_tdl_feature_unavailable_detail(
                settings,
                "security_roles",
                "/settings/security-roles",
                action=action or "Alter",
            ),
        )
    if not settings.experimental_tally_security_roles_enabled:
        experimental_route_disabled(
            "security role",
            "EXPERIMENTAL_TALLY_SECURITY_ROLES_ENABLED",
            (
                "Security role create/update is not supported through a stable built-in "
                "Tally XML path in this Tally build. A dedicated TDL/report-based "
                "integration is usually required."
            ),
        )
    raise HTTPException(
        status_code=501,
        detail=_security_roles_unavailable_detail(
            "Experimental generic SECURITYLEVEL writes are intentionally blocked because this Tally build does not have a verified stable native XML write path for security roles.",
            action=action or "Alter",
        ),
    )


@router.get("/settings/uqc-mappings")
async def uqc_mappings(
    fetch: Optional[List[str]] = Query(default=None, description="Unit/UQC fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or UQC_FETCH)
    return await _export_master(client, company_name, "unit", effective_fetch, limit=limit, view=normalized_view)


@router.get("/settings/einvoice")
async def einvoice_settings(
    fetch: Optional[List[str]] = Query(default=None, description="Optional e-invoice fields to fetch"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or EINVOICE_FETCH)
    return await _export_master(client, company_name, "company", effective_fetch, limit=1, view=normalized_view)


@router.post("/settings/einvoice")
async def einvoice_settings_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _update_company_settings(client, company_name, payload, action or "Alter")


@router.get("/settings/ewaybill")
async def ewaybill_settings(
    fetch: Optional[List[str]] = Query(default=None, description="Optional e-way bill fields to fetch"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or EWAYBILL_FETCH)
    return await _export_master(client, company_name, "company", effective_fetch, limit=1, view=normalized_view)


@router.post("/settings/ewaybill")
async def ewaybill_settings_update(
    payload: Dict[str, Any],
    action: Optional[str] = Query(default="Alter", description="Tally ACTION (Alter/Create/Delete)"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, payload, company, x_company)
    return await _update_company_settings(client, company_name, payload, action or "Alter")


@router.get("/reports/day-book")
async def report_day_book(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Day Book", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/stock-items")
async def report_stock_items(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_STOCK_ITEM_FETCH)
    return await _export_master(client, company_name, "stock item", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/stock-groups")
async def report_stock_groups(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_STOCK_GROUP_FETCH)
    return await _export_master(client, company_name, "stock group", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/ledgers")
async def report_ledgers(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_LEDGER_FETCH)
    return await _export_master(client, company_name, "ledger", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/cost-centres")
async def report_cost_centres(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_COST_CENTRE_FETCH)
    return await _export_master(client, company_name, "cost centre", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/cost-categories")
async def report_cost_categories(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_COST_CATEGORY_FETCH)
    return await _export_master(client, company_name, "cost category", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/godowns")
async def report_godowns(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_GODOWN_FETCH)
    return await _export_master(client, company_name, "godown", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/uoms")
async def report_uoms(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    limit: Optional[int] = Query(default=None, description="Max records"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else (fetch or LIGHT_UNIT_FETCH)
    return await _export_master(client, company_name, "unit", effective_fetch, limit=limit, view=normalized_view)


@router.get("/reports/tax-rates")
async def report_tax_rates(
    stock_fetch: Optional[List[str]] = Query(default=None, description="Stock item fields to fetch"),
    ledger_fetch: Optional[List[str]] = Query(default=None, description="Ledger fields to fetch"),
    stock_limit: Optional[int] = Query(default=None, description="Max stock items"),
    ledger_limit: Optional[int] = Query(default=None, description="Max ledgers"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    effective_stock_fetch = _full_fetch(stock_fetch) if _needs_full_fetch(normalized_view) else (stock_fetch or STOCK_TAX_FETCH)
    effective_ledger_fetch = _full_fetch(ledger_fetch) if _needs_full_fetch(normalized_view) else (ledger_fetch or LEDGER_TAX_FETCH)
    stock_collection_name = _collection_name(_master_collection_prefix("stock item"), f"{__name__}.report_tax_rates.stock_items")
    ledger_collection_name = _collection_name(_master_collection_prefix("ledger"), f"{__name__}.report_tax_rates.ledgers")
    return {
        "stock_items": await _export_master(
            client,
            company_name,
            "stock item",
            effective_stock_fetch,
            limit=stock_limit,
            view=normalized_view,
            collection_name=stock_collection_name,
        ),
        "ledgers": await _export_master(
            client,
            company_name,
            "ledger",
            effective_ledger_fetch,
            limit=ledger_limit,
            view=normalized_view,
            collection_name=ledger_collection_name,
        ),
    }


@router.get("/reports/outstanding-receivables")
async def report_outstanding_receivables(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Bills Receivable", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/outstanding-payables")
async def report_outstanding_payables(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Bills Payable", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/ledger-vouchers")
async def report_ledger_vouchers(
    ledger_name: str = Query(..., description="Ledger name"),
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(
        client,
        "Ledger Vouchers",
        company_name,
        from_date,
        to_date,
        extra_static_variables={
            "SVLEDGERNAME": ledger_name,
            "LEDGERNAME": ledger_name,
        },
        view=view or "summary",
    )


@router.get("/reports/stock-summary")
async def report_stock_summary(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _build_full_stock_summary(client, company_name, from_date, to_date, view or "summary")


@router.get("/reports/batch-availability")
async def report_batch_availability(
    stock_item: Optional[str] = Query(default=None, description="Optional stock item name"),
    godown: Optional[str] = Query(default=None, description="Optional godown name"),
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    extra_static_variables = {
        "SVSTOCKITEM": stock_item,
        "SVGODOWN": godown,
    }
    normalized_view = _normalize_view(view)
    report_data = await _export_report(
        client,
        "Item Batch Summary",
        company_name,
        from_date,
        to_date,
        extra_static_variables,
        view=normalized_view,
    )
    if normalized_view != "raw" and _is_null_envelope_response(report_data):
        return _empty_or_ambiguous_report_response(
            "Item Batch Summary",
            _report_filter_summary(
                stock_item=stock_item,
                godown=godown,
                from_date=from_date,
                to_date=to_date,
            ),
        )
    return report_data


@router.get("/reports/price-lists")
async def report_price_lists(
    stock_group: Optional[str] = Query(default=None, description="Optional stock group selection used by the native Tally Price List screen"),
    stock_category: Optional[str] = Query(default=None, description="Optional stock category selection used by the native Tally Price List screen"),
    price_level: Optional[str] = Query(default=None, description="Optional price level selection used by the native Tally Price List screen"),
    price_level_date: Optional[str] = Query(default=None, description="Optional applicable date for the native Tally Price List screen"),
    show_all_items: Optional[bool] = Query(default=None, description="Show all items for the selected price level"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    return await _export_price_lists(
        client,
        company_name,
        view=normalized_view,
        stock_group=stock_group,
        stock_category=stock_category,
        price_level=price_level,
        price_level_date=price_level_date,
        show_all_items=show_all_items,
    )


@router.get("/reports/companies")
async def report_companies(
    fetch: Optional[List[str]] = Query(default=None, description="Fields to fetch"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    normalized_view = _normalize_view(view)
    effective_fetch = _full_fetch(fetch) if _needs_full_fetch(normalized_view) else fetch
    xml_req = build_company_list(effective_fetch)
    return await _post_xml(client, xml_req, normalized_view)


@router.get("/reports/bank-reco-status")
async def report_bank_reco_status(
    ledger_name: Optional[str] = Query(default=None, description="Optional bank ledger"),
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    normalized_view = _normalize_view(view)
    report_data = await _export_report(
        client,
        "Bank Recon",
        company_name,
        from_date,
        to_date,
        extra_static_variables={
            "SVLEDGERNAME": ledger_name,
            "LEDGERNAME": ledger_name,
        },
        view=normalized_view,
    )
    if normalized_view != "raw" and _is_null_envelope_response(report_data):
        return _empty_or_ambiguous_report_response(
            "Bank Recon",
            _report_filter_summary(
                ledger_name=ledger_name,
                from_date=from_date,
                to_date=to_date,
            ),
        )
    return report_data


@router.get("/reports/trial-balance")
async def report_trial_balance(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Trial Balance", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/balance-sheet")
async def report_balance_sheet(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Balance Sheet", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/profit-loss")
async def report_profit_loss(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Profit and Loss", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/cash-book")
async def report_cash_book(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Cash Book", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/bank-book")
async def report_bank_book(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Bank Book", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/cash-flow")
async def report_cash_flow(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Cash Flow", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/funds-flow")
async def report_funds_flow(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Funds Flow", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/sales-register")
async def report_sales_register(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Sales Register", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/sales-trend")
async def report_sales_trend(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    report_name: Optional[str] = Query(default="Sales Trend", description="Tally report name to export"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_sales_trend(
        client,
        company_name,
        from_date,
        to_date,
        view or "summary",
        report_name or "Sales Trend",
    )


@router.get("/reports/purchase-register")
async def report_purchase_register(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Purchase Register", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/journal-register")
async def report_journal_register(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    xml_req = build_daybook_voucher_export(company_name, "Journal", None, None, from_date, to_date)
    return await _post_xml(client, xml_req, view or "summary")

@router.get("/reports/receipt-register")
async def report_receipt_register(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    xml_req = build_daybook_voucher_export(company_name, "Receipt", None, None, from_date, to_date)
    return await _post_xml(client, xml_req, view or "summary")

@router.get("/reports/payment-register")
async def report_payment_register(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    xml_req = build_daybook_voucher_export(company_name, "Payment", None, None, from_date, to_date)
    return await _post_xml(client, xml_req, view or "summary")


@router.get("/reports/gstr-1")
async def report_gstr_1(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "GSTR1", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/gstr-2")
async def report_gstr_2(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "GSTR2", company_name, from_date, to_date, view=view or "summary")


@router.get("/reports/gstr-3b")
async def report_gstr_3b(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "GSTR3B", company_name, from_date, to_date, view=view or "summary")


# Advanced Inventory Reports

@router.get("/reports/stock-ageing-analysis")
async def report_stock_ageing_analysis(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Stock Ageing Analysis", company_name, from_date, to_date, view=view or "summary")

@router.get("/reports/movement-analysis")
async def report_movement_analysis(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Movement Analysis", company_name, from_date, to_date, view=view or "summary")

@router.get("/reports/reorder-status")
async def report_reorder_status(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Reorder Status", company_name, from_date, to_date, view=view or "summary")


# Statutory TDS/TCS Reports

@router.get("/reports/form-26q")
async def report_form_26q(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report_with_missing_report_hint(
        client,
        "Form 26Q",
        company_name,
        from_date,
        to_date,
        view=view or "summary",
    )

@router.get("/reports/form-24q")
async def report_form_24q(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report_with_missing_report_hint(
        client,
        "Form 24Q",
        company_name,
        from_date,
        to_date,
        view=view or "summary",
    )

@router.get("/reports/form-27eq")
async def report_form_27eq(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report_with_missing_report_hint(
        client,
        "Form 27EQ",
        company_name,
        from_date,
        to_date,
        view=view or "summary",
    )

@router.get("/reports/tds-outstandings")
async def report_tds_outstandings(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    if settings.tdl_integration_enabled and _tdl_feature_is_ready(settings, "tds_outstandings"):
        contract = _tdl_feature_contract(settings, "tds_outstandings")
        report_name = contract.get("report_name") or TDL_FEATURE_DEFAULTS["tds_outstandings"]["report_name"]
        payload: Dict[str, Any] = {
            "RESOURCE": "tds-outstandings",
            "SOURCE_REPORT": "TDS Outstandings",
            "VIEW": view or "summary",
        }
        if from_date:
            payload["FROM_DATE"] = _normalize_tally_date(from_date)
        if to_date:
            payload["TO_DATE"] = _normalize_tally_date(to_date)
        response = await _post_xml(
            client,
            build_tdl_gateway_report(
                str(report_name),
                company=company_name,
                payload=payload,
                static_variables={
                    "SVFROMDATE": payload.get("FROM_DATE"),
                    "SVTODATE": payload.get("TO_DATE"),
                },
            ),
            view or "summary",
        )
        if _is_tdl_scaffold_placeholder_response(response):
            raise HTTPException(
                status_code=501,
                detail=_tds_outstandings_unavailable_detail(
                    "The configured TDL TDS Outstandings feature is still serving the scaffold placeholder response, so live statutory report support is not implemented yet.",
                    response=response,
                ),
            )
        if _is_metadata_only_collection(response):
            raise HTTPException(
                status_code=501,
                detail=_tds_outstandings_unavailable_detail(
                    "The configured TDL TDS Outstandings report returned only metadata and no report rows. A real TDL report implementation is still required.",
                    response=response,
                ),
            )
        return {
            "status": "ok",
            "mode": "tdl-report",
            "feature": "tds_outstandings",
            "report_name": report_name,
            "company": company_name,
            "response": response,
        }
    return await _export_report_with_missing_report_hint(
        client,
        "TDS Outstandings",
        company_name,
        from_date,
        to_date,
        view=view or "summary",
    )


# Financial & Costing Reports

@router.get("/reports/cost-centre-breakup")
async def report_cost_centre_breakup(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Cost Centre Breakup", company_name, from_date, to_date, view=view or "summary")

@router.get("/reports/ratio-analysis")
async def report_ratio_analysis(
    from_date: Optional[str] = Query(default=None, description="From date in YYYYMMDD or YYYY-MM-DD"),
    to_date: Optional[str] = Query(default=None, description="To date in YYYYMMDD or YYYY-MM-DD"),
    view: Optional[str] = Query(default="summary", description="Response view: summary, full, raw"),
    company: Optional[str] = Query(default=None, description="Company override"),
    x_company: Optional[str] = Header(default=None, convert_underscores=True, description="Optional company override"),
    x_agent_key: Optional[str] = Header(default=None, convert_underscores=True),
    settings: Settings = Depends(get_settings),
    client: TallyClient = Depends(get_client),
):
    await ensure_auth(x_agent_key, settings)
    company_name = resolve_company(settings.company, None, company, x_company)
    return await _export_report(client, "Ratio Analysis", company_name, from_date, to_date, view=view or "summary")
