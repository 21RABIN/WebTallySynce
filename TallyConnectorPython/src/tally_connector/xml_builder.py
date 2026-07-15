import copy
import html
import json
import re
from typing import Any, Dict, Iterable, List, Optional


MASTER_TAGS = {
    "stock item": "STOCKITEM",
    "item": "STOCKITEM",
    "stock": "STOCKITEM",
    "stock group": "STOCKGROUP",
    "stock category": "STOCKCATEGORY",
    "ledger": "LEDGER",
    "ledger group": "GROUP",
    "group": "GROUP",
    "cost category": "COSTCATEGORY",
    "cost centre": "COSTCENTRE",
    "cost center": "COSTCENTRE",
    "project": "COSTCENTRE",
    "godown": "GODOWN",
    "warehouse": "GODOWN",
    "unit": "UNIT",
    "uom": "UNIT",
    "currency": "CURRENCY",
    "company": "COMPANY",
    "price level": "PRICELEVEL",
    "price list": "PRICELEVEL",
    "voucher type": "VOUCHERTYPE",
    "budget": "BUDGET",
    "bom": "BOM",
    "bill of material": "BOM",
    "employee": "EMPLOYEE",
    "employee group": "EMPLOYEEGROUP",
    "pay head": "PAYHEAD",
    "attendance type": "ATTENDANCETYPE",
    "attendance": "ATTENDANCETYPE",
    "security level": "SECURITYLEVEL",
    "security role": "SECURITYLEVEL",
}

NON_ACCOUNTING_INVOICE_VOUCHERS = {
    "delivery note",
    "receipt note",
}

DEFAULT_MASTER_FETCHES = {
    "company": ["NAME"],
    "currency": [
        "NAME",
        "ORIGINALNAME",
        "MAILINGNAME",
        "EXPANDEDSYMBOL",
        "ISOCURRENCYCODE",
        "DECIMALSYMBOL",
        "DECIMALPLACES",
    ],
    "group": ["NAME", "PARENT"],
    "ledger": ["NAME", "PARENT", "GSTREGISTRATIONTYPE", "PARTYGSTIN", "GSTAPPLICABLE"],
    "cost category": ["NAME", "PARENT"],
    "cost centre": ["NAME", "PARENT"],
    "project": ["NAME", "PARENT"],
    "godown": ["NAME", "PARENT"],
    "unit": ["NAME", "ORIGINALNAME", "BASEUNITS", "DECIMALPLACES"],
    "stock group": ["NAME", "PARENT"],
    "stock category": ["NAME", "PARENT"],
    "stock item": [
        "NAME",
        "PARENT",
        "BASEUNITS",
        "CLOSINGBALANCE",
        "CLOSINGVALUE",
        "OPENINGBALANCE",
        "OPENINGVALUE",
        "OPENINGRATE",
        "HSNCODE",
        "GSTAPPLICABLE",
    ],
    "bom": ["NAME", "PARENT", "BASEUNITS", "COMPONENTLIST.*", "MULTICOMPONENTLIST.*"],
    "price level": ["NAME", "PARENT", "BASEUNITS"],
    "price list": ["NAME", "PARENT", "BASEUNITS"],
    "voucher type": [
        "NAME",
        "NUMBERINGMETHOD",
        "PREFIX",
        "SUFFIX",
        "STARTINGNUMBER",
        "ROUNDINGMETHOD",
        "ROUNDLIMIT",
    ],
    "budget": ["NAME", "PARENT"],
    "employee": ["NAME", "PARENT"],
    "employee group": ["NAME", "PARENT"],
    "pay head": ["NAME", "PARENT"],
    "attendance type": ["NAME"],
    "security level": ["NAME"],
}

DEFAULT_VOUCHER_FETCHES = {
    "sales": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "BASICBUYERNAME",
        "NETAMOUNT",
        "NARRATION",
        "PLACEOFSUPPLY",
        "PARTYGSTIN",
    ],
    "purchase": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "NETAMOUNT",
        "NARRATION",
        "PLACEOFSUPPLY",
        "PARTYGSTIN",
    ],
    "sales order": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
        "ALLINVENTORYENTRIES.LIST",
    ],
    "purchase order": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
        "ALLINVENTORYENTRIES.LIST",
    ],
    "delivery note": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
    ],
    "receipt note": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
    ],
    "stock journal": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
        "LEDGERENTRIES.LIST",
    ],
    "material in": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
        "LEDGERENTRIES.LIST",
    ],
    "material out": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
        "LEDGERENTRIES.LIST",
    ],
    "manufacturing": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
        "LEDGERENTRIES.LIST",
    ],
    "receipt": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
    ],
    "payment": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
    ],
    "contra": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
    ],
    "journal": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
    ],
    "credit note": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "NARRATION",
        "NETAMOUNT",
        "LEDGERENTRIES.LIST",
    ],
    "debit note": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "NARRATION",
        "NETAMOUNT",
        "LEDGERENTRIES.LIST",
    ],
    "rejections in": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
    ],
    "rejections out": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "ALLINVENTORYENTRIES.LIST",
    ],
    "payroll": [
        "DATE",
        "VOUCHERNUMBER",
        "VOUCHERTYPENAME",
        "NARRATION",
        "LEDGERENTRIES.LIST",
    ],
}

COLLECTION_TYPES = {
    "STOCKITEM": "Stock Item",
    "STOCKGROUP": "Stock Group",
    "STOCKCATEGORY": "Stock Category",
    "LEDGER": "Ledger",
    "GROUP": "Group",
    "COSTCATEGORY": "Cost Category",
    "COSTCENTRE": "Cost Centre",
    "GODOWN": "Godown",
    "UNIT": "Unit",
    "CURRENCY": "Currency",
    "COMPANY": "Company",
    "PRICELEVEL": "Price Levels",
    "VOUCHERTYPE": "Voucher Type",
    "BUDGET": "Budget",
    "BOM": "BOM",
    "EMPLOYEE": "Employee",
    "EMPLOYEEGROUP": "Employee Group",
    "PAYHEAD": "Pay Head",
    "ATTENDANCETYPE": "Attendance/Production Type",
    "SECURITYLEVEL": "Security Level",
}


def _svcurrentcompany_lines(company: Optional[str], level: int = 4) -> List[str]:
    if isinstance(company, str) and company.strip():
        indent = "  " * level
        return [f"{indent}<SVCURRENTCOMPANY>{_esc(company.strip())}</SVCURRENTCOMPANY>"]
    return []


def _default_master_fetch(master_type: str) -> List[str]:
    normalized = (master_type or "").strip().lower()
    return list(DEFAULT_MASTER_FETCHES.get(normalized, ["NAME"]))


def _default_voucher_fetch(voucher_type: Optional[str]) -> List[str]:
    normalized = (voucher_type or "").strip().lower()
    if not normalized:
        return [
            "DATE",
            "VOUCHERNUMBER",
            "VOUCHERTYPENAME",
            "PARTYNAME",
            "PARTYLEDGERNAME",
            "NETAMOUNT",
            "NARRATION",
        ]
    return list(
        DEFAULT_VOUCHER_FETCHES.get(
            normalized,
            [
                "DATE",
                "VOUCHERNUMBER",
                "VOUCHERTYPENAME",
                "PARTYNAME",
                "PARTYLEDGERNAME",
                "NETAMOUNT",
                "NARRATION",
            ],
        )
    )


def build_master_upsert(company: Optional[str], master_type: str, action: str, data: Dict[str, Any]) -> str:
    """
    Build Tally XML envelope for master upsert (Alter/Create).

    - master_type: human-friendly name, resolved via MASTER_TAGS
    - data: free-form payload. "NAME" (or "name") is mandatory and becomes the root NAME attribute/tag.
    - nested dicts/lists are rendered recursively so complex masters (BOM components, price lists, bank allocs, GST details) are supported.
    """
    tag_name = _resolve_tag(master_type)
    name = _first_string(data.get("NAME"), data.get("name"))
    if not name:
        raise ValueError("NAME is required")
    action = action or "Alter"

    # stable ordering for top-level fields for diff-friendly output
    keys = sorted(k for k in data.keys() if k.upper() != "NAME")
    body_lines: List[str] = [f"            <NAME>{_esc(name)}</NAME>"]
    for key in keys:
        body_lines.extend(_emit(_to_tag(key), data[key], level=6))

    inner = "\n".join(body_lines)
    static_lines = _svcurrentcompany_lines(company, level=5)
    static_block = "\n".join(static_lines)
    envelope = f"""<ENVELOPE>
  <HEADER>
    <TALLYREQUEST>Import Data</TALLYREQUEST>
  </HEADER>
  <BODY>
    <IMPORTDATA>
      <REQUESTDESC>
        <REPORTNAME>All Masters</REPORTNAME>
        <STATICVARIABLES>
{static_block}
        </STATICVARIABLES>
      </REQUESTDESC>
      <REQUESTDATA>
        <TALLYMESSAGE xmlns:UDF="TallyUDF">
          <{tag_name} NAME="{_esc(name)}" ACTION="{_esc(action)}">
{inner}
          </{tag_name}>
        </TALLYMESSAGE>
      </REQUESTDATA>
    </IMPORTDATA>
  </BODY>
</ENVELOPE>"""
    return envelope


def build_stock_item_price_list_upsert(
    company: Optional[str],
    item_name: str,
    full_price_list_entries: List[Dict[str, Any]],
    action: str = "Alter",
) -> str:
    normalized_item_name = _first_string(item_name)
    if not normalized_item_name:
        raise ValueError("NAME is required")

    body_lines: List[str] = [f"            <NAME>{_esc(normalized_item_name)}</NAME>"]
    for entry in full_price_list_entries:
        body_lines.extend(_emit_preserve("FULLPRICELIST.LIST", entry, level=6))

    inner = "\n".join(body_lines)
    static_lines = _svcurrentcompany_lines(company, level=5)
    static_block = "\n".join(static_lines)
    return f"""<ENVELOPE>
  <HEADER>
    <TALLYREQUEST>Import Data</TALLYREQUEST>
  </HEADER>
  <BODY>
    <IMPORTDATA>
      <REQUESTDESC>
        <REPORTNAME>All Masters</REPORTNAME>
        <STATICVARIABLES>
{static_block}
        </STATICVARIABLES>
      </REQUESTDESC>
      <REQUESTDATA>
        <TALLYMESSAGE xmlns:UDF="TallyUDF">
          <STOCKITEM NAME="{_esc(normalized_item_name)}" ACTION="{_esc(action or 'Alter')}">
{inner}
          </STOCKITEM>
        </TALLYMESSAGE>
      </REQUESTDATA>
    </IMPORTDATA>
  </BODY>
</ENVELOPE>"""


ACCOUNTING_VOUCHER_VIEWS = {
    "contra": "Accounting Voucher View",
    "credit note": "Accounting Voucher View",
    "debit note": "Accounting Voucher View",
    "journal": "Accounting Voucher View",
    "payment": "Accounting Voucher View",
    "receipt": "Accounting Voucher View",
}

INVENTORY_CONSUMPTION_VIEWS = {
    "stock journal": "Consumption Voucher View",
    "material in": "Consumption Voucher View",
    "material out": "Consumption Voucher View",
    "rejections in": "Consumption Voucher View",
    "rejections out": "Consumption Voucher View",
}

PREFERRED_XML_FIELD_ORDER = [
    "NAME",
    "LEDGERNAME",
    "STOCKITEMNAME",
    "RATE",
    "ACTUALQTY",
    "BILLEDQTY",
    "QTY",
    "ISPARTYLEDGER",
    "ISDEEMEDPOSITIVE",
    "ISLASTDEEMEDPOSITIVE",
    "ISAUTONEGATE",
    "LEDGERFROMITEM",
    "REMOVEZEROENTRIES",
    "GODOWNNAME",
    "BATCHNAME",
    "INDENTNO",
    "ORDERNO",
    "TRACKINGNUMBER",
    "AMOUNT",
]
PREFERRED_XML_FIELD_ORDER_INDEX = {
    name: index for index, name in enumerate(PREFERRED_XML_FIELD_ORDER)
}


def _first_present(data: Dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in data:
            return data[name]
    return None


def _prepare_voucher_payload(
    action: str,
    data: Dict[str, Any],
    voucher_type: str,
) -> tuple[Dict[str, Any], str, str]:
    payload = copy.deepcopy(data)
    payload.pop("COMPANY", None)
    payload.pop("company", None)
    normalized_vtype = voucher_type.strip()
    is_create = action.lower() == "create"

    persisted_view = _first_present(payload, "PERSISTEDVIEW", "persisted_view")
    if not isinstance(persisted_view, str) or not persisted_view.strip():
        has_inventory = "ALLINVENTORYENTRIES.LIST" in payload or "INVENTORYALLOCATIONS.LIST" in payload
        if normalized_vtype.lower() in INVENTORY_CONSUMPTION_VIEWS:
            persisted_view = INVENTORY_CONSUMPTION_VIEWS[normalized_vtype.lower()]
        else:
            persisted_view = "Invoice Voucher View" if has_inventory else ACCOUNTING_VOUCHER_VIEWS.get(normalized_vtype.lower(), "Accounting Voucher View")
    if persisted_view == "Invoice Voucher View" or not is_create:
        payload["PERSISTEDVIEW"] = persisted_view
    payload.setdefault("VOUCHERTYPENAME", normalized_vtype)

    is_invoice = _first_present(payload, "ISINVOICE", "is_invoice")
    if normalized_vtype.lower() in NON_ACCOUNTING_INVOICE_VOUCHERS:
        # Tally exports Delivery Note and Receipt Note in Invoice Voucher View,
        # but still persists them as non-invoice vouchers.
        is_invoice = "No"
    if not isinstance(is_invoice, str) or not is_invoice.strip():
        is_invoice = "Yes" if persisted_view == "Invoice Voucher View" else "No"
    if persisted_view == "Invoice Voucher View" or not is_create:
        payload["ISINVOICE"] = is_invoice

    obj_view = _first_present(payload, "OBJVIEW", "obj_view")
    if not isinstance(obj_view, str) or not obj_view.strip():
        obj_view = persisted_view

    # For create requests, preserve whichever ledger-entry shape the caller
    # supplied. Mirroring into both list names causes duplicate postings for
    # accounting vouchers on this Tally build.
    if not is_create:
        if "ALLLEDGERENTRIES.LIST" not in payload and "LEDGERENTRIES.LIST" in payload:
            if persisted_view == "Accounting Voucher View":
                payload["ALLLEDGERENTRIES.LIST"] = copy.deepcopy(payload["LEDGERENTRIES.LIST"])
        elif "ALLLEDGERENTRIES.LIST" in payload and "LEDGERENTRIES.LIST" not in payload:
            payload["LEDGERENTRIES.LIST"] = copy.deepcopy(payload["ALLLEDGERENTRIES.LIST"])
    elif persisted_view != "Accounting Voucher View" and "ALLLEDGERENTRIES.LIST" in payload and "LEDGERENTRIES.LIST" not in payload:
        payload["LEDGERENTRIES.LIST"] = copy.deepcopy(payload["ALLLEDGERENTRIES.LIST"])
        # Inventory-style voucher creates are more reliable when the ledger
        # rows are sent in the native LEDGERENTRIES.LIST shape only.
        payload.pop("ALLLEDGERENTRIES.LIST", None)

    if is_create:
        payload.setdefault("DATE", "")
    if payload.get("DATE") and "EFFECTIVEDATE" not in payload:
        payload["EFFECTIVEDATE"] = payload["DATE"]

    return payload, persisted_view, obj_view


def _emit_preserve(tag: str, value: Any, level: int) -> List[str]:
    indent = "  " * level
    if isinstance(value, list):
        lines: List[str] = []
        for item in value:
            lines.extend(_emit_preserve(tag, item, level))
        return lines

    if isinstance(value, dict):
        attrs: List[str] = []
        text_value: Any = None
        child_items: List[tuple[str, Any]] = []
        for k, v in value.items():
            if k == "#text":
                text_value = v
            elif k.startswith("@"):
                attrs.append(f' {k[1:]}="{_esc(v)}"')
            else:
                child_items.append((k, v))

        child_items.sort(
            key=lambda item: (
                PREFERRED_XML_FIELD_ORDER_INDEX.get(_to_tag(item[0]), len(PREFERRED_XML_FIELD_ORDER_INDEX)),
                _to_tag(item[0]),
            )
        )

        open_tag = f"{indent}<{tag}{''.join(attrs)}>"
        if not child_items and text_value is None:
            return [f"{indent}<{tag}{''.join(attrs)}/>"]
        if not child_items:
            if text_value == "":
                return [f"{indent}<{tag}{''.join(attrs)}/>"]
            return [f"{open_tag}{_esc(text_value)}</{tag}>"]

        lines = [open_tag]
        if text_value is not None:
            lines.append(f"{indent}  {_esc(text_value)}")
        for k, v in child_items:
            lines.extend(_emit_preserve(_to_tag(k), v, level + 1))
        lines.append(f"{indent}</{tag}>")
        return lines

    if value == "":
        return [f"{indent}<{tag}/>"]
    return [f"{indent}<{tag}>{_esc(value)}</{tag}>"]


# --------------------- VOUCHERS ---------------------

def build_voucher_upsert(company: Optional[str], action: str, data: Dict[str, Any], voucher_type: str | None = None) -> str:
    """
    Build Tally XML envelope for voucher create/alter.

    Requires VOUCHERTYPENAME (either in data or passed as voucher_type).
    """
    vtype = voucher_type or _first_string(data.get("VOUCHERTYPENAME"), data.get("VOUCHERTYPE"))
    if not vtype:
        raise ValueError("VOUCHERTYPENAME is required")
    action = (action or "Create").strip()
    normalized_action = action.title()
    payload, persisted_view, obj_view = _prepare_voucher_payload(normalized_action, data, vtype)

    if normalized_action in {"Alter", "Cancel", "Delete"}:
        master_id = _first_string(
            payload.get("MASTERID"),
            payload.get("MasterID"),
            payload.get("master_id"),
            payload.get("LASTVCHID"),
        )
        voucher_number = _first_string(payload.get("VOUCHERNUMBER"), payload.get("TAGVALUE"))
        voucher_date = _first_string(payload.get("DATE"), payload.get("EFFECTIVEDATE"))
        if not master_id and not voucher_number:
            raise ValueError("MASTERID or VOUCHERNUMBER is required for voucher Alter/Cancel/Delete")
        if not voucher_date:
            raise ValueError("DATE is required for voucher Alter/Cancel/Delete")
        if master_id:
            tag_name = "MASTER ID"
            tag_value = master_id
        else:
            tag_name = "Voucher Number"
            tag_value = voucher_number
        attrs = (
            f'DATE="{_esc(voucher_date)}" '
            f'TAGNAME="{_esc(tag_name)}" '
            f'TAGVALUE="{_esc(tag_value)}" '
            f'ACTION="{_esc(normalized_action)}" '
            f'VCHTYPE="{_esc(vtype)}" '
            f'OBJVIEW="{_esc(obj_view)}"'
        )
        skip_keys = {
            "DATE",
            "EFFECTIVEDATE",
            "VOUCHERNUMBER",
            "VOUCHERTYPENAME",
            "VOUCHERTYPE",
            "OBJVIEW",
            "MASTERID",
            "MasterID",
            "master_id",
            "LASTVCHID",
        }
    else:
        obj_view = _first_string(payload.get("OBJVIEW"), payload.get("PERSISTEDVIEW"))
        attrs = f'VCHTYPE="{_esc(vtype)}" ACTION="{_esc(normalized_action)}"'
        if obj_view:
            attrs += f' OBJVIEW="{_esc(obj_view)}"'
        skip_keys = {"OBJVIEW", "VOUCHERTYPE"}

    explicit_order = [
        "DATE",
        "EFFECTIVEDATE",
        "VOUCHERTYPENAME",
        "VOUCHERNUMBER",
        "PERSISTEDVIEW",
        "ISINVOICE",
        "OBJVIEW",
        "PARTYLEDGERNAME",
        "PARTYNAME",
        "NARRATION",
        "ALLLEDGERENTRIES.LIST",
        "LEDGERENTRIES.LIST",
    ]
    remaining_keys = [k for k in payload.keys() if k.upper() not in {name.upper() for name in skip_keys}]
    keys = [k for k in explicit_order if k in payload and k in remaining_keys]
    keys.extend(k for k in remaining_keys if k not in keys)
    body_lines: List[str] = []
    for key in keys:
        tag = _to_tag(key)
        body_lines.extend(_emit_preserve(tag, payload[key], level=4))

    inner = "\n".join(body_lines)
    static_lines = _svcurrentcompany_lines(company, level=4)
    static_block = "\n".join(static_lines)

    request_desc = """      <REQUESTDESC>
        <REPORTNAME>Vouchers</REPORTNAME>
      </REQUESTDESC>"""
    if static_block.strip():
        request_desc = f"""      <REQUESTDESC>
        <REPORTNAME>Vouchers</REPORTNAME>
        <STATICVARIABLES>
{static_block}
        </STATICVARIABLES>
      </REQUESTDESC>"""

    if normalized_action == "Create":
        return f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Import</TALLYREQUEST>
    <TYPE>Data</TYPE>
    <ID>Vouchers</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
{static_block}
      </STATICVARIABLES>
    </DESC>
    <DATA>
      <TALLYMESSAGE>
        <VOUCHER {attrs}>
{inner}
        </VOUCHER>
      </TALLYMESSAGE>
    </DATA>
  </BODY>
</ENVELOPE>"""

    return f"""<ENVELOPE>
  <HEADER>
    <TALLYREQUEST>Import Data</TALLYREQUEST>
  </HEADER>
  <BODY>
    <IMPORTDATA>
{request_desc}
      <REQUESTDATA>
        <TALLYMESSAGE xmlns:UDF="TallyUDF">
          <VOUCHER {attrs}>
{inner}
          </VOUCHER>
        </TALLYMESSAGE>
      </REQUESTDATA>
    </IMPORTDATA>
  </BODY>
</ENVELOPE>"""


def build_voucher_list(
    company: Optional[str],
    voucher_type: str | None = None,
    fetch: Iterable[str] | None = None,
    filter_expr: str | None = None,
    limit: int | None = None,
    collection_name: Optional[str] = None,
) -> str:
    fetch_fields = list(fetch) if fetch else _default_voucher_fetch(voucher_type)
    fetch_lines = "\n".join(f"              <FETCH>{_esc(f)}</FETCH>" for f in fetch_fields)
    limit_line = f"              <MAXRECORDS>{int(limit)}</MAXRECORDS>" if limit else ""
    collection_id = _normalize_collection_name(collection_name, "PYVOUCHERS")
    formula_name = f"{collection_id}_FILTER"

    filter_line = ""
    formula_block = ""
    type_filter_expr = None
    if voucher_type:
        escaped_vtype = _esc(voucher_type.strip())
        type_filter_expr = f"($VOUCHERTYPENAME = \"{escaped_vtype}\") OR ($VCHTYPE = \"{escaped_vtype}\")"
    if type_filter_expr and filter_expr:
        filter_expr = f"({type_filter_expr}) AND ({filter_expr})"
    elif type_filter_expr:
        filter_expr = type_filter_expr
    if filter_expr:
        filter_line = f"              <FILTER>{formula_name}</FILTER>"
        formula_block = (
            f"        <SYSTEM TYPE=\"Formulae\" NAME=\"{_esc(formula_name)}\">\n"
            f"          <![CDATA[{filter_expr}]]>\n"
            "        </SYSTEM>\n"
        )

    static_lines = _svcurrentcompany_lines(company, level=4)
    static_lines.append("        <SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>")
    static_block = "\n".join(static_lines)

    envelope = f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Export</TALLYREQUEST>
    <TYPE>Collection</TYPE>
    <ID>{_esc(collection_id)}</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
{static_block}
      </STATICVARIABLES>
      <TDL>
        <TDLMESSAGE>
          <COLLECTION NAME="{_esc(collection_id)}" ISINITIALIZE="Yes">
            <TYPE>Voucher</TYPE>
{fetch_lines}
{limit_line}
{filter_line}
          </COLLECTION>
{formula_block}        </TDLMESSAGE>
      </TDL>
    </DESC>
  </BODY>
</ENVELOPE>"""
    return envelope


def build_master_list(
    company: Optional[str],
    master_type: str,
    fetch: Iterable[str] | None = None,
    filter_expr: str | None = None,
    limit: int | None = None,
    collection_name: Optional[str] = None,
    extra_static_variables: Optional[Dict[str, Any]] = None,
) -> str:
    """Build Tally XML envelope to export/list masters using a custom collection."""
    tag_name = _resolve_tag(master_type)
    type_name = COLLECTION_TYPES.get(tag_name, tag_name.title())
    fetch_fields = list(fetch) if fetch else _default_master_fetch(master_type)
    collection_id = _normalize_collection_name(collection_name, "PYMASTER")
    formula_name = f"{collection_id}_FILTER"

    fetch_lines = "\n".join(f"              <FETCH>{_esc(f)}</FETCH>" for f in fetch_fields)
    limit_line = f"              <MAXRECORDS>{int(limit)}</MAXRECORDS>" if limit else ""

    filter_line = ""
    formula_block = ""
    if filter_expr:
        filter_line = f"              <FILTER>{formula_name}</FILTER>"
        formula_block = (
            f"        <SYSTEM TYPE=\"Formulae\" NAME=\"{_esc(formula_name)}\">\n"
            f"          <![CDATA[{filter_expr}]]>\n"
            "        </SYSTEM>\n"
        )

    static_variables = dict(extra_static_variables or {})
    static_lines = _svcurrentcompany_lines(company, level=4)
    for key, value in static_variables.items():
        if value is None or value == "":
            continue
        static_lines.append(f"        <{_to_tag(key)}>{_esc(value)}</{_to_tag(key)}>")
    static_lines.append("        <SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>")
    static_block = "\n".join(static_lines)

    envelope = f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Export</TALLYREQUEST>
    <TYPE>Collection</TYPE>
    <ID>{_esc(collection_id)}</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
{static_block}
      </STATICVARIABLES>
      <TDL>
        <TDLMESSAGE>
          <COLLECTION NAME="{_esc(collection_id)}" ISINITIALIZE="Yes">
            <TYPE>{_esc(type_name)}</TYPE>
{fetch_lines}
{limit_line}
{filter_line}
          </COLLECTION>
{formula_block}        </TDLMESSAGE>
      </TDL>
    </DESC>
  </BODY>
</ENVELOPE>"""
    return envelope


def build_company_open(company: str) -> str:
    company = (company or "").strip()
    if not company:
        raise ValueError("Company name is required")
    return f"""<ENVELOPE>
  <HEADER>
    <TALLYREQUEST>Export</TALLYREQUEST>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
        <SVCURRENTCOMPANY>{_esc(company)}</SVCURRENTCOMPANY>
      </STATICVARIABLES>
    </DESC>
  </BODY>
</ENVELOPE>"""


def build_company_list(fetch: Iterable[str] | None = None) -> str:
    fetch_fields = list(fetch) if fetch else ["NAME"]
    native_lines = "\n".join(f"              <NATIVEMETHOD>{_esc(f)}</NATIVEMETHOD>" for f in fetch_fields)

    envelope = f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Export</TALLYREQUEST>
    <TYPE>Collection</TYPE>
    <ID>List of Companies</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
        <SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>
        <SVISSIMPLECOMPANY>No</SVISSIMPLECOMPANY>
      </STATICVARIABLES>
      <TDL>
        <TDLMESSAGE>
          <COLLECTION ISMODIFY="No" ISFIXED="No" ISINITIALIZE="Yes" ISOPTION="No" ISINTERNAL="No" NAME="List of Companies">
            <TYPE>Company</TYPE>
{native_lines}
          </COLLECTION>
        </TDLMESSAGE>
      </TDL>
    </DESC>
  </BODY>
</ENVELOPE>"""
    return envelope


def build_collection_export(collection_id: str, company: str | None = None) -> str:
    static_lines = []
    static_lines.extend(_svcurrentcompany_lines(company, level=4))
    static_lines.append("        <SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>")

    static_block = "\n".join(static_lines)
    return f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Export</TALLYREQUEST>
    <TYPE>Collection</TYPE>
    <ID>{_esc(collection_id)}</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
{static_block}
      </STATICVARIABLES>
    </DESC>
  </BODY>
</ENVELOPE>"""


def build_company_update(company: Optional[str], action: str, data: Dict[str, Any]) -> str:
    # reuse master upsert with company master type
    return build_master_upsert(company, "company", action, data)


def build_company_create(data: Dict[str, Any]) -> str:
    """
    Build envelope for company creation without setting SVCURRENTCOMPANY (Tally requires a blank current company context for create).
    """
    tag_name = MASTER_TAGS.get("company")
    name = _first_string(data.get("NAME"), data.get("name"))
    if not name:
        raise ValueError("NAME is required")
    # stable ordering
    keys = sorted(k for k in data.keys() if k.upper() != "NAME")
    body_lines: List[str] = [f"            <NAME>{_esc(name)}</NAME>"]
    for key in keys:
        body_lines.extend(_emit(_to_tag(key), data[key], level=6))

    inner = "\n".join(body_lines)
    envelope = f"""<ENVELOPE>
  <HEADER>
    <TALLYREQUEST>Import Data</TALLYREQUEST>
  </HEADER>
  <BODY>
    <IMPORTDATA>
      <REQUESTDESC>
        <REPORTNAME>All Masters</REPORTNAME>
      </REQUESTDESC>
      <REQUESTDATA>
        <TALLYMESSAGE xmlns:UDF="TallyUDF">
          <{tag_name} NAME="{_esc(name)}" ACTION="Create">
{inner}
          </{tag_name}>
        </TALLYMESSAGE>
      </REQUESTDATA>
    </IMPORTDATA>
  </BODY>
</ENVELOPE>"""
    return envelope


def build_report_export(
    report_name: str,
    company: Optional[str] = None,
    static_variables: Optional[Dict[str, Any]] = None,
) -> str:
    """
    Build a generic Tally report export envelope.

    This is used for operational report pulls like Day Book, Trial Balance,
    Balance Sheet, Profit & Loss, Ledger Vouchers, and similar built-in reports.
    """
    static_variables = dict(static_variables or {})
    if company:
        static_variables.setdefault("SVCURRENTCOMPANY", company)
    static_variables.setdefault("SVEXPORTFORMAT", "$$SysName:XML")

    static_lines = "\n".join(
        f"        <{_to_tag(key)}>{_esc(value)}</{_to_tag(key)}>"
        for key, value in static_variables.items()
        if value is not None and value != ""
    )

    return f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Export</TALLYREQUEST>
    <TYPE>Data</TYPE>
    <ID>{_esc(report_name)}</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
{static_lines}
      </STATICVARIABLES>
    </DESC>
  </BODY>
</ENVELOPE>"""


def build_tdl_gateway_report(
    report_name: str,
    company: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
    static_variables: Optional[Dict[str, Any]] = None,
) -> str:
    gateway_variables = dict(static_variables or {})
    normalized_payload = dict(payload or {})
    if normalized_payload:
        gateway_variables.setdefault(
            "LCCALLPAYLOADJSON",
            json.dumps(normalized_payload, separators=(",", ":"), ensure_ascii=True, default=str),
        )
        for key in sorted(normalized_payload.keys()):
            value = normalized_payload[key]
            if isinstance(value, (str, int, float, bool)) or value is None:
                gateway_variables.setdefault(f"LCCALL_{_to_tag(key)}", value)
    return build_report_export(
        report_name,
        company=company,
        static_variables=gateway_variables,
    )


def build_daybook_voucher_export(
    company: Optional[str],
    voucher_type: Optional[str] = None,
    fetch: Iterable[str] | None = None,
    filter_expr: str | None = None,
    from_date: Optional[str] = None,
    to_date: Optional[str] = None,
) -> str:
    fetch_fields = list(fetch) if fetch else _default_voucher_fetch(voucher_type)

    combined_filter = filter_expr.strip() if isinstance(filter_expr, str) and filter_expr.strip() else ""
    if voucher_type:
        voucher_filter = f'($VoucherTypeName = "{_esc(voucher_type.strip())}")'
        combined_filter = f"({voucher_filter}) AND ({combined_filter})" if combined_filter else voucher_filter

    static_lines = []
    static_lines.extend(_svcurrentcompany_lines(company, level=4))
    if from_date:
        static_lines.append(f"        <SVFROMDATE>{_esc(from_date)}</SVFROMDATE>")
    if to_date:
        static_lines.append(f"        <SVTODATE>{_esc(to_date)}</SVTODATE>")
    static_lines.append("        <SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>")
    static_block = "\n".join(static_lines)

    local_lines = []
    if combined_filter:
        local_lines.append("          <LOCAL>Collection : Default : Add : Filter : PYVOUCHERFILTER</LOCAL>")
    for field in fetch_fields:
        local_lines.append(f"          <LOCAL>Collection : Default : Add : Fetch : {field}</LOCAL>")
    local_block = "\n".join(local_lines)

    formula_block = ""
    if combined_filter:
        formula_block = (
            "        <SYSTEM TYPE=\"Formulae\" NAME=\"PYVOUCHERFILTER\">\n"
            f"          <![CDATA[{combined_filter}]]>\n"
            "        </SYSTEM>\n"
        )

    return f"""<ENVELOPE>
  <HEADER>
    <VERSION>1</VERSION>
    <TALLYREQUEST>Export</TALLYREQUEST>
    <TYPE>Data</TYPE>
    <ID>Day Book</ID>
  </HEADER>
  <BODY>
    <DESC>
      <STATICVARIABLES>
{static_block}
      </STATICVARIABLES>
      <TDL>
        <TDLMESSAGE>
          <REPORT NAME="Day Book" ISMODIFY="Yes" ISFIXED="No" ISINITIALIZE="No" ISOPTION="No" ISINTERNAL="No">
{local_block}
          </REPORT>
{formula_block}        </TDLMESSAGE>
      </TDL>
    </DESC>
  </BODY>
</ENVELOPE>"""


def _normalize_collection_name(collection_name: Optional[str], default_name: str) -> str:
    raw_name = (collection_name or default_name or "").strip()
    normalized = re.sub(r"[^A-Za-z0-9]+", "_", raw_name).strip("_").upper()
    return normalized or default_name


def _esc(s: Any) -> str:
    return html.escape(str(s), quote=True)


def _to_tag(s: str) -> str:
    # Keep dots (e.g., LANGUAGENAME.LIST), strip spaces, uppercase everything else.
    return s.replace(" ", "").upper()


def _emit(tag: str, value: Any, level: int) -> List[str]:
    indent = "  " * level
    if isinstance(value, list):
        lines: List[str] = []
        for item in value:
            lines.extend(_emit(tag, item, level))
        return lines

    if isinstance(value, dict):
        lines = [f"{indent}<{tag}>"]
        for k in sorted(value.keys()):
            lines.extend(_emit(_to_tag(k), value[k], level + 1))
        lines.append(f"{indent}</{tag}>")
        return lines

    if value == "":
        return [f"{indent}<{tag}/>"]
    return [f"{indent}<{tag}>{_esc(value)}</{tag}>"]


def _resolve_tag(master_type: str) -> str:
    tag_name = MASTER_TAGS.get(master_type.strip().lower()) if master_type else None
    if not tag_name:
        raise ValueError(f"Unsupported master type: {master_type}")
    return tag_name


def _first_string(*vals):
    for v in vals:
        if isinstance(v, str) and v:
            return v
    return ""
