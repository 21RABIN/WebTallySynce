#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found. Install Python 3.10+ on Ubuntu and try again."
  exit 1
fi

PYTHON_VERSION="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
PYTHON_OK="$(python3 -c 'import sys; print(int(sys.version_info >= (3, 10)))')"
if [[ "$PYTHON_OK" != "1" ]]; then
  echo "Python 3.10+ is required. Found: $PYTHON_VERSION"
  exit 1
fi

BUILD_VENV=".venv-build-linux"
DIST_DIR="dist-ubuntu"
PYTHON_CMD="python3"
PIP_INSTALL_TARGET=()

if [[ ! -d "$BUILD_VENV" ]]; then
  if python3 -m venv "$BUILD_VENV"; then
    :
  else
    echo "python3-venv is unavailable; falling back to a user-site build install."
  fi
fi

if [[ -x "$BUILD_VENV/bin/python" ]] && "$BUILD_VENV/bin/python" -m pip --version >/dev/null 2>&1; then
  PYTHON_CMD="$BUILD_VENV/bin/python"
else
  PIP_INSTALL_TARGET=(--user)
fi

"$PYTHON_CMD" -m pip install --upgrade pip "${PIP_INSTALL_TARGET[@]}"
"$PYTHON_CMD" -m pip install "${PIP_INSTALL_TARGET[@]}" -r requirements.txt -r requirements-build.txt

rm -rf build "$DIST_DIR"

"$PYTHON_CMD" -m PyInstaller \
  --noconfirm \
  --clean \
  --onefile \
  --name TallyConnectorPython \
  --distpath "$DIST_DIR" \
  --workpath build \
  --paths src \
  --collect-all uvicorn \
  --collect-all anyio \
  --collect-all starlette \
  --collect-all websockets \
  --collect-all httptools \
  --collect-all watchfiles \
  --collect-all pydantic \
  --collect-all pydantic_core \
  --collect-all pydantic_settings \
  --collect-all backports \
  --hidden-import backports \
  --hidden-import backports.tarfile \
  --hidden-import xmltodict \
  run_connector.py

if [[ -f ".env" ]]; then
  cp -f ".env" "$DIST_DIR/.env"
fi
cp -f ".env.example" "$DIST_DIR/.env.example"
cp -f "README.md" "$DIST_DIR/README.md"
cp -f "start_connector.sh" "$DIST_DIR/start_connector.sh"
chmod +x "$DIST_DIR/TallyConnectorPython" "$DIST_DIR/start_connector.sh"

echo
echo "Ubuntu build complete."
echo "Output folder: $SCRIPT_DIR/$DIST_DIR"
if [[ -f "$DIST_DIR/.env" ]]; then
  echo "Packaged runtime defaults were copied from the project .env file."
else
  echo "No project .env file was found, so the packaged app will bootstrap from .env.example/defaults."
fi
echo "Run ./start_connector.sh or ./TallyConnectorPython"
