from pathlib import Path
import sys


PROJECT_ROOT = Path(__file__).resolve().parent
SRC_DIR = PROJECT_ROOT / "src"

if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from tally_connector.main import app  # noqa: E402


def main() -> None:
    rows = []
    ignored_paths = {"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"}
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None)
        if not path or not methods or path in ignored_paths:
            continue
        filtered_methods = sorted(m for m in methods if m in {"GET", "POST", "PUT", "PATCH", "DELETE"})
        if not filtered_methods:
            continue
        rows.append((", ".join(filtered_methods), path, getattr(route, "name", "")))

    rows.sort(key=lambda item: (item[1], item[0]))

    print("Implemented connector APIs")
    print("==========================")
    for idx, (methods, path, name) in enumerate(rows, start=1):
        print(f"{idx:03d}. {methods:<11} {path}    {name}")
    print(f"\nTotal APIs: {len(rows)}")


if __name__ == "__main__":
    main()
