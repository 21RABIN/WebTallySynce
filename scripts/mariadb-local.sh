#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/output/mariadb"
DATA_DIR="$RUNTIME_DIR/data"
TMP_DIR="$RUNTIME_DIR/tmp"
LOG_FILE="$RUNTIME_DIR/mariadb.log"
PID_FILE="$RUNTIME_DIR/mariadb.pid"
SOCKET_FILE="$RUNTIME_DIR/mariadb.sock"
ENV_FILE="$RUNTIME_DIR/backend-mysql.env"
PORT="${MARIADB_LOCAL_PORT:-3306}"
DB_NAME="${MARIADB_LOCAL_DB:-tallyconnector}"
DB_USER="${MARIADB_LOCAL_USER:-tallyapp}"
DB_PASSWORD="${MARIADB_LOCAL_PASSWORD:-tallyapp123}"
SOCKET_FILE="${MARIADB_LOCAL_SOCKET:-/tmp/tallyconnector-mariadb-${UID}.sock}"

command_name="${1:-start}"

mkdir -p "$RUNTIME_DIR" "$TMP_DIR"

is_running() {
  local pid=""
  if [[ -f "$PID_FILE" ]]; then
    pid="$(tr -d '[:space:]' < "$PID_FILE")"
  fi
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

wait_for_socket() {
  local attempts="${1:-30}"
  local count=1
  while (( count <= attempts )); do
    if mariadb-admin --protocol=socket --socket="$SOCKET_FILE" ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    count=$((count + 1))
  done
  return 1
}

write_backend_env() {
  cat >"$ENV_FILE" <<EOF
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:${PORT}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME='${DB_USER}'
export SPRING_DATASOURCE_PASSWORD='${DB_PASSWORD}'
export SPRING_JPA_DATABASE='mysql'
export SPRING_DATASOURCE_DRIVER_CLASS_NAME='com.mysql.cj.jdbc.Driver'
EOF
}

initialize_db() {
  if [[ -d "$DATA_DIR/mysql" ]]; then
    return 0
  fi

  rm -rf "$DATA_DIR"
  mkdir -p "$DATA_DIR"

  mariadb-install-db \
    --datadir="$DATA_DIR" \
    --auth-root-authentication-method=normal \
    --skip-test-db \
    >/dev/null
}

start_db() {
  if is_running; then
    echo "Local MariaDB already running on port $PORT"
    return 0
  fi

  initialize_db

  rm -f "$SOCKET_FILE"
  nohup mariadbd \
    --datadir="$DATA_DIR" \
    --socket="$SOCKET_FILE" \
    --port="$PORT" \
    --pid-file="$PID_FILE" \
    --bind-address=127.0.0.1 \
    --skip-networking=0 \
    --tmpdir="$TMP_DIR" \
    --log-error="$LOG_FILE" \
    >/dev/null 2>&1 &

  if ! wait_for_socket 45; then
    echo "Local MariaDB did not become ready. See $LOG_FILE"
    exit 1
  fi

  mariadb --protocol=socket --socket="$SOCKET_FILE" -uroot <<EOF
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`;
CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF

  write_backend_env
  echo "Local MariaDB is ready on 127.0.0.1:$PORT"
  echo "Backend env file: $ENV_FILE"
}

stop_db() {
  if ! is_running; then
    rm -f "$PID_FILE"
    echo "Local MariaDB is not running"
    return 0
  fi

  mariadb-admin --protocol=socket --socket="$SOCKET_FILE" -uroot shutdown >/dev/null 2>&1 || true
  sleep 1

  if is_running; then
    local pid
    pid="$(tr -d '[:space:]' < "$PID_FILE")"
    kill "$pid" 2>/dev/null || true
  fi

  rm -f "$PID_FILE" "$SOCKET_FILE"
  echo "Local MariaDB stopped"
}

status_db() {
  if is_running; then
    echo "Local MariaDB: running on 127.0.0.1:$PORT"
    echo "Socket: $SOCKET_FILE"
    echo "Database: $DB_NAME"
    echo "User: $DB_USER"
    echo "Backend env: $ENV_FILE"
  else
    echo "Local MariaDB: stopped"
  fi
}

case "$command_name" in
  start)
    start_db
    ;;
  stop)
    stop_db
    ;;
  restart)
    stop_db
    start_db
    ;;
  status)
    status_db
    ;;
  env)
    write_backend_env
    cat "$ENV_FILE"
    ;;
  *)
    echo "Usage: scripts/mariadb-local.sh {start|stop|restart|status|env}"
    exit 1
    ;;
esac
