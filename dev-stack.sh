#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$ROOT_DIR/output/dev-stack"
mkdir -p "$RUNTIME_DIR"
LOCAL_DB_ENV_FILE="$ROOT_DIR/output/mariadb/backend-mysql.env"

CONNECTOR_DIR="$ROOT_DIR/TallyConnectorPython"
BACKEND_DIR="$ROOT_DIR/tally-backend"
UI_DIR="$ROOT_DIR/tally-ui"

CONNECTOR_PID_FILE="$RUNTIME_DIR/connector.pid"
BACKEND_PID_FILE="$RUNTIME_DIR/backend.pid"
UI_PID_FILE="$RUNTIME_DIR/ui.pid"

CONNECTOR_LOG="$RUNTIME_DIR/connector.log"
BACKEND_LOG="$RUNTIME_DIR/backend.log"
UI_LOG="$RUNTIME_DIR/ui.log"

command_name="${1:-start}"

is_pid_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

read_pid() {
  local file="$1"
  if [[ -f "$file" ]]; then
    tr -d '[:space:]' < "$file"
  fi
}

write_pid() {
  local file="$1"
  local pid="$2"
  printf '%s\n' "$pid" > "$file"
}

remove_pid_file() {
  local file="$1"
  rm -f "$file"
}

port_is_listening() {
  local port="$1"
  ss -ltn "( sport = :$port )" | grep -q ":$port"
}

start_connector() {
  local pid
  pid="$(read_pid "$CONNECTOR_PID_FILE" || true)"
  if is_pid_running "$pid"; then
    echo "Connector already running with PID $pid"
    return
  fi
  if port_is_listening 8082; then
    echo "Connector port 8082 is already in use. Reusing existing process."
    return
  fi
  echo "Starting connector on 8082..."
  (
    cd "$CONNECTOR_DIR"
    nohup env PYTHONPATH=src ./.venv/bin/uvicorn tally_connector.main:app --host 0.0.0.0 --port 8082 >>"$CONNECTOR_LOG" 2>&1 &
    write_pid "$CONNECTOR_PID_FILE" "$!"
  )
}

start_backend() {
  local pid
  pid="$(read_pid "$BACKEND_PID_FILE" || true)"
  if is_pid_running "$pid"; then
    echo "Backend already running with PID $pid"
    return
  fi
  if port_is_listening 9090; then
    echo "Backend port 9090 is already in use. Reusing existing process."
    return
  fi
  echo "Starting backend on 9090..."
  (
    cd "$BACKEND_DIR"
    if [[ -f "$LOCAL_DB_ENV_FILE" ]]; then
      # Reuse an explicit datasource config when present.
      # shellcheck disable=SC1090
      source "$LOCAL_DB_ENV_FILE"
    elif [[ -x "$ROOT_DIR/scripts/mariadb-local.sh" ]]; then
      "$ROOT_DIR/scripts/mariadb-local.sh" start >/dev/null
      if [[ -f "$LOCAL_DB_ENV_FILE" ]]; then
        # shellcheck disable=SC1090
        source "$LOCAL_DB_ENV_FILE"
      fi
    fi
    nohup mvn spring-boot:run >>"$BACKEND_LOG" 2>&1 &
    write_pid "$BACKEND_PID_FILE" "$!"
  )
}

start_ui() {
  local pid
  pid="$(read_pid "$UI_PID_FILE" || true)"
  if is_pid_running "$pid"; then
    echo "UI already running with PID $pid"
    return
  fi
  if port_is_listening 4200; then
    echo "UI port 4200 is already in use. Reusing existing process."
    return
  fi
  echo "Starting UI on 4200..."
  (
    cd "$UI_DIR"
    nohup npm start -- --host 0.0.0.0 --port 4200 >>"$UI_LOG" 2>&1 &
    write_pid "$UI_PID_FILE" "$!"
  )
}

stop_one() {
  local name="$1"
  local file="$2"
  local pid
  pid="$(read_pid "$file" || true)"
  if ! is_pid_running "$pid"; then
    remove_pid_file "$file"
    echo "$name is not running"
    return
  fi
  echo "Stopping $name (PID $pid)..."
  kill "$pid" 2>/dev/null || true
  sleep 1
  if is_pid_running "$pid"; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  remove_pid_file "$file"
}

status_one() {
  local name="$1"
  local port="$2"
  local file="$3"
  local pid
  pid="$(read_pid "$file" || true)"
  if is_pid_running "$pid"; then
    echo "$name: running (pid $pid, port $port)"
    return
  fi
  if port_is_listening "$port"; then
    echo "$name: port $port is in use by another process"
    return
  fi
  echo "$name: stopped"
}

wait_for_health() {
  local name="$1"
  local url="$2"
  local max_attempts="${3:-30}"
  local attempt=1
  while (( attempt <= max_attempts )); do
    if curl --silent --max-time 2 "$url" >/dev/null 2>&1; then
      echo "$name is ready"
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  echo "$name did not become ready in time"
  return 1
}

start_all() {
  start_connector
  wait_for_health "Connector" "http://127.0.0.1:8082/health" 20 || true
  start_backend
  wait_for_health "Backend" "http://127.0.0.1:9090/api/cache/status" 30 || true
  start_ui
  echo
  status_all
  echo
  echo "Logs:"
  echo "  Connector: $CONNECTOR_LOG"
  echo "  Backend:   $BACKEND_LOG"
  echo "  UI:        $UI_LOG"
}

stop_all() {
  stop_one "UI" "$UI_PID_FILE"
  stop_one "Backend" "$BACKEND_PID_FILE"
  stop_one "Connector" "$CONNECTOR_PID_FILE"
}

status_all() {
  status_one "Connector" 8082 "$CONNECTOR_PID_FILE"
  status_one "Backend" 9090 "$BACKEND_PID_FILE"
  status_one "UI" 4200 "$UI_PID_FILE"
}

case "$command_name" in
  start)
    start_all
    ;;
  stop)
    stop_all
    ;;
  restart)
    stop_all
    start_all
    ;;
  status)
    status_all
    ;;
  *)
    echo "Usage: ./dev-stack.sh {start|stop|restart|status}"
    exit 1
    ;;
esac
