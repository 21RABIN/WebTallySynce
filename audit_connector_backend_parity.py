#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Set, Tuple

from fastapi.routing import APIRoute


Route = Tuple[str, str]


CONNECTOR_EXCLUDE_PREFIXES = (
    "/auth/",
    "/health",
    "/capabilities",
    "/implementation-matrix",
)

BACKEND_EXCLUDE_PREFIXES = (
    "/api/auth",
    "/api/auth/users",
    "/api/connectors",
    "/api/routing",
    "/api/health",
    "/api/tally",
)


def _normalize_backend_path(connector_path: str) -> str:
    return f"/api{connector_path}"


def _extract_connector_routes(repo_root: Path) -> Set[Route]:
    src_root = repo_root / "TallyConnectorPython" / "src"
    if str(src_root) not in sys.path:
        sys.path.insert(0, str(src_root))
    from tally_connector.routes import router  # noqa: WPS433

    routes: Set[Route] = set()
    for route in router.routes:
        if not isinstance(route, APIRoute):
            continue
        path = route.path
        if path.startswith(CONNECTOR_EXCLUDE_PREFIXES):
            continue
        methods = sorted(method for method in (route.methods or set()) if method in {"GET", "POST", "PUT", "PATCH", "DELETE"})
        for method in methods:
            routes.add((method, _normalize_backend_path(path)))
    return routes


def _extract_backend_routes(controller_paths: Sequence[Path]) -> Set[Route]:
    routes: Set[Route] = set()
    mapping_pattern = re.compile(
        r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)(?:\((.*?)\))?',
        re.DOTALL,
    )
    class_mapping_pattern = re.compile(r'@RequestMapping\("([^"]+)"\)')
    quoted_string_pattern = re.compile(r'"([^"]*)"')
    value_attr_pattern = re.compile(r'value\s*=\s*"([^"]*)"')

    for controller_path in controller_paths:
        text = controller_path.read_text(encoding="utf-8")
        class_match = class_mapping_pattern.search(text)
        class_base = class_match.group(1) if class_match else ""
        for match in mapping_pattern.finditer(text):
            method = match.group(1).replace("Mapping", "").upper()
            args = match.group(2) or ""
            value_match = value_attr_pattern.search(args)
            if value_match:
                sub_path = value_match.group(1)
            else:
                quoted_match = quoted_string_pattern.search(args)
                sub_path = quoted_match.group(1) if quoted_match else ""
            routes.add((method, f"{class_base}{sub_path}"))
    return routes


def _filter_backend_connector_surface(routes: Iterable[Route]) -> Set[Route]:
    filtered = set()
    for method, path in routes:
        if path.startswith(BACKEND_EXCLUDE_PREFIXES):
            continue
        filtered.add((method, path))
    return filtered


def _group_by_path(routes: Iterable[Route]) -> Dict[str, List[str]]:
    grouped: Dict[str, List[str]] = {}
    for method, path in sorted(routes, key=lambda item: (item[1], item[0])):
        grouped.setdefault(path, []).append(method)
    return grouped


def _result_payload(connector_routes: Set[Route], backend_routes: Set[Route]) -> Dict[str, object]:
    backend_surface = _filter_backend_connector_surface(backend_routes)
    missing = sorted(connector_routes - backend_surface, key=lambda item: (item[1], item[0]))
    backend_only = sorted(backend_surface - connector_routes, key=lambda item: (item[1], item[0]))
    return {
        "connector_route_count": len(connector_routes),
        "backend_connector_surface_count": len(backend_surface),
        "missing_backend_proxies": [
            {"method": method, "path": path}
            for method, path in missing
        ],
        "backend_only_routes": [
            {"method": method, "path": path}
            for method, path in backend_only
        ],
        "connector_routes_by_path": _group_by_path(connector_routes),
        "backend_routes_by_path": _group_by_path(backend_surface),
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Audit parity between Python connector routes and Java backend proxy controllers."
    )
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parent),
        help="Path to the tallyconnector repo root.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print the result as JSON only.",
    )
    args = parser.parse_args()

    repo_root = Path(args.repo_root).expanduser().resolve()
    backend_controller_paths = sorted((repo_root / "tally-backend" / "src" / "main" / "java" / "com" / "tallybackend" / "web").glob("*.java"))

    connector_routes = _extract_connector_routes(repo_root)
    backend_routes = _extract_backend_routes(backend_controller_paths)
    payload = _result_payload(connector_routes, backend_routes)

    if args.json:
        print(json.dumps(payload, indent=2))
        return 0

    print(f"Connector routes audited: {payload['connector_route_count']}")
    print(f"Backend connector-surface routes audited: {payload['backend_connector_surface_count']}")
    missing = payload["missing_backend_proxies"]
    backend_only = payload["backend_only_routes"]
    print(f"Missing backend proxies: {len(missing)}")
    if missing:
        for entry in missing:
            print(f"  {entry['method']} {entry['path']}")
    print(f"Backend-only connector-surface routes: {len(backend_only)}")
    if backend_only:
        for entry in backend_only:
            print(f"  {entry['method']} {entry['path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
