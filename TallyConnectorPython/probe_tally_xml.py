#!/usr/bin/env python3
import argparse
import asyncio
import json
import sys
from pathlib import Path
from typing import Dict, List

import xmltodict


PROJECT_ROOT = Path(__file__).resolve().parent
SRC_ROOT = PROJECT_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))


from tally_connector.config import Settings  # noqa: E402
from tally_connector.tally_client import TallyClient, trace_slug, write_trace_snapshot  # noqa: E402
from tally_connector.xml_builder import build_collection_export, build_report_export  # noqa: E402


PRESET_PROBES: Dict[str, Dict[str, List[str]]] = {
    "form-24q": {"kind": "report", "candidates": ["Form 24Q", "24Q", "E-24Q", "e-TDS Form 24Q"]},
    "form-26q": {"kind": "report", "candidates": ["Form 26Q", "26Q", "E-26Q", "e-TDS Form 26Q"]},
    "form-27eq": {"kind": "report", "candidates": ["Form 27EQ", "27EQ", "E-27EQ", "e-TCS Form 27EQ"]},
    "tds-outstandings": {
        "kind": "report",
        "candidates": ["TDS Outstandings", "TDS Outstanding", "TDS Outstanding Report"],
    },
    "security-roles": {
        "kind": "collection",
        "candidates": ["Security Levels", "Users and Passwords", "Security Level"],
    },
    "employees": {"kind": "collection", "candidates": ["Employees", "Payroll Ledgers"]},
    "employee-groups": {"kind": "collection", "candidates": ["Employee Groups"]},
    "pay-heads": {"kind": "collection", "candidates": ["Payroll Ledgers", "Pay Heads"]},
    "attendance-types": {"kind": "collection", "candidates": ["Attendance Types"]},
    "price-levels": {"kind": "collection", "candidates": ["Price Levels", "Price List"]},
}


def _response_summary(text: str) -> Dict[str, object]:
    summary: Dict[str, object] = {
        "success": True,
        "error": None,
    }
    if "Could not find Report" in text:
        summary["success"] = False
        summary["error"] = "report_not_found"
    elif "Unknown Request" in text:
        summary["success"] = False
        summary["error"] = "unknown_request"
    elif "LINEERROR" in text:
        summary["success"] = False
        summary["error"] = "line_error"
    try:
        parsed = xmltodict.parse(text)
        header = parsed.get("ENVELOPE", {}).get("HEADER", {}) if isinstance(parsed, dict) else {}
        body = parsed.get("ENVELOPE", {}).get("BODY", {}) if isinstance(parsed, dict) else {}
        summary["header"] = header if isinstance(header, dict) else {}
        summary["body_keys"] = sorted(body.keys()) if isinstance(body, dict) else []
    except Exception as exc:  # pragma: no cover - defensive only
        summary["parse_error"] = repr(exc)
    return summary


def _build_request(kind: str, candidate: str, company: str, from_date: str, to_date: str) -> str:
    if kind == "collection":
        return build_collection_export(candidate, company or None)
    static_variables = {}
    if from_date:
        static_variables["SVFROMDATE"] = from_date
    if to_date:
        static_variables["SVTODATE"] = to_date
    return build_report_export(candidate, company=company or None, static_variables=static_variables or None)


async def _run_probe(args: argparse.Namespace) -> int:
    settings = Settings()
    base_url = args.base_url or str(settings.tally_base_url)
    company = args.company or settings.company
    output_dir = Path(args.output_dir or (PROJECT_ROOT / "xml-discovery-output")).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if args.preset:
        preset = PRESET_PROBES[args.preset]
        kind = preset["kind"]
        candidates = list(preset["candidates"])
    else:
        kind = args.kind
        candidates = list(args.candidate or [])

    client = TallyClient(base_url, agent_key=args.agent_key or settings.agent_key)
    summary_rows = []
    try:
        for candidate in candidates:
            request_xml = _build_request(kind, candidate, company, args.from_date, args.to_date)
            try:
                response = await client.post_xml(request_xml)
                probe_dir = write_trace_snapshot(
                    output_dir / trace_slug(args.preset or kind),
                    request_xml,
                    response_text=response,
                    status_code=200,
                )
                result = _response_summary(response)
            except Exception as exc:
                probe_dir = write_trace_snapshot(
                    output_dir / trace_slug(args.preset or kind),
                    request_xml,
                    error_text=repr(exc),
                )
                result = {"success": False, "error": repr(exc)}
            row = {
                "candidate": candidate,
                "kind": kind,
                "company": company,
                "output_dir": str(probe_dir),
                **result,
            }
            summary_rows.append(row)
            print(json.dumps(row, ensure_ascii=True))
    finally:
        await client.close()

    summary_path = output_dir / f"{trace_slug(args.preset or kind)}_summary.json"
    summary_path.write_text(json.dumps(summary_rows, indent=2), encoding="utf-8")
    print(f"summary_file={summary_path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe live Tally XML report or collection candidates.")
    parser.add_argument("--base-url", default="", help="Override Tally base URL. Defaults to TALLY_BASE_URL.")
    parser.add_argument("--company", default="", help="Optional SVCURRENTCOMPANY value.")
    parser.add_argument("--agent-key", default="", help="Optional agent key if probing through a protected gateway.")
    parser.add_argument("--output-dir", default="", help="Directory to store probe snapshots.")
    parser.add_argument("--from-date", default="", help="Optional report from date in YYYYMMDD or YYYY-MM-DD.")
    parser.add_argument("--to-date", default="", help="Optional report to date in YYYYMMDD or YYYY-MM-DD.")
    parser.add_argument("--preset", choices=sorted(PRESET_PROBES.keys()), help="Run a built-in unresolved API probe preset.")
    parser.add_argument("--kind", choices=["report", "collection"], default="report", help="Probe kind when not using --preset.")
    parser.add_argument("--candidate", action="append", help="Candidate report or collection name to probe. Repeatable.")
    args = parser.parse_args()

    if not args.preset and not args.candidate:
        parser.error("Provide either --preset or at least one --candidate.")

    return asyncio.run(_run_probe(args))


if __name__ == "__main__":
    raise SystemExit(main())
