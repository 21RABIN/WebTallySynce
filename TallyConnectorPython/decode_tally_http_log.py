#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


DEFAULT_LOG_PATH = Path.home() / ".wine" / "drive_c" / "Program Files" / "TallyPrime" / "tallyhttp.log"


def _read_tally_log(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe") or b"\x00" in raw[:8]:
        return raw.decode("utf-16le", errors="replace")
    return raw.decode("utf-8", errors="replace")


def _extract_ids(text: str) -> list[str]:
    seen = set()
    ordered = []
    for match in re.finditer(r"<ID>\s*([^<]+?)\s*</ID>", text, re.IGNORECASE):
        value = (match.group(1) or "").strip()
        if value and value not in seen:
            seen.add(value)
            ordered.append(value)
    return ordered


def main() -> int:
    parser = argparse.ArgumentParser(description="Decode and inspect TallyPrime tallyhttp.log.")
    parser.add_argument("--log-path", default=str(DEFAULT_LOG_PATH), help="Path to tallyhttp.log.")
    parser.add_argument("--save-decoded", default="", help="Optional path to save decoded UTF-8 text.")
    parser.add_argument("--filter", default="", help="Optional case-insensitive substring filter.")
    parser.add_argument("--list-ids", action="store_true", help="List unique <ID> values found in the log.")
    args = parser.parse_args()

    log_path = Path(args.log_path).expanduser().resolve()
    if not log_path.is_file():
        raise SystemExit(f"log file not found: {log_path}")

    text = _read_tally_log(log_path)
    if args.save_decoded:
        Path(args.save_decoded).expanduser().resolve().write_text(text, encoding="utf-8")

    if args.list_ids:
        for value in _extract_ids(text):
            if args.filter and args.filter.lower() not in value.lower():
                continue
            print(value)
        return 0

    if args.filter:
        needle = args.filter.lower()
        for line in text.splitlines():
            if needle in line.lower():
                print(line)
        return 0

    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
