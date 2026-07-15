#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/output/dev-stack"
PID_DIR="$LOG_DIR/pids"

mkdir -p "$LOG_DIR" "$PID_DIR"

start_service() {
  local name="$1"
  local workdir="$2"
  local command="$3"
  local log_file="$LOG_DIR/${name}.log"
  local pid_file="$PID_DIR/${name}.pid"

  if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    echo "$name is already running with PID $(cat "$pid_file")"
    return
  fi

  (
    cd "$workdir"
    setsid bash -lc "$command" >"$log_file" 2>&1 < /dev/null &
    echo $! >"$pid_file"
  )

  echo "Started $name (PID $(cat "$pid_file"))"
  echo "  log: $log_file"
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local max_attempts="${3:-60}"
  local attempt=1

  until curl -fsS "$url" >/dev/null 2>&1; do
    if (( attempt >= max_attempts )); then
      echo "$name did not become ready at $url"
      return 1
    fi
    sleep 2
    ((attempt++))
  done

  echo "$name is ready at $url"
}

start_service \
  "connector" \
  "$ROOT_DIR/TallyConnectorPython" \
  "export PYTHONPATH=src; exec ./.venv/bin/python -m uvicorn tally_connector.main:app --host 127.0.0.1 --port 8082"

wait_for_http "Connector" "http://127.0.0.1:8082/health"

start_service \
  "backend" \
  "$ROOT_DIR/tally-backend" \
  "exec mvn spring-boot:run"

wait_for_http "Backend" "http://127.0.0.1:9090/v3/api-docs"

start_service \
  "ui" \
  "$ROOT_DIR/tally-ui" \
  "exec npm start -- --host 127.0.0.1"

wait_for_http "UI" "http://127.0.0.1:4200"

cat <<EOF

Dev stack is running:
  UI:        http://127.0.0.1:4200
  Backend:   http://127.0.0.1:9090
  Connector: http://127.0.0.1:8082

Use scripts/stop_dev_stack.sh to stop everything.
EOF
