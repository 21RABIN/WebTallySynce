#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_DIR="$ROOT_DIR/output/migration"
mkdir -p "$MIGRATION_DIR"

H2_JAR="${H2_JAR:-$HOME/.m2/repository/com/h2database/h2/2.1.214/h2-2.1.214.jar}"
H2_DB_PATH="${H2_DB_PATH:-$ROOT_DIR/tally-backend/data/auth_user}"
H2_URL="jdbc:h2:file:${H2_DB_PATH};ACCESS_MODE_DATA=r;FILE_LOCK=NO"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DB="${MYSQL_DB:-tallyconnector}"
MYSQL_USER="${MYSQL_USER:-tallyapp}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-tallyapp123}"

DUMP_FILE="$MIGRATION_DIR/h2_full_dump.sql"
IMPORT_FILE="$MIGRATION_DIR/mariadb_import.sql"
BACKUP_FILE="$MIGRATION_DIR/mariadb_backup_before_h2_import_$(date +%Y%m%dT%H%M%S).sql"

TABLES=(
  auth_users
  payment_reminders
  tally_balance_sheet_snapshot_rows
  tally_companies_snapshot_rows
  tally_company_currency_snapshot_rows
  tally_company_features_snapshot_rows
  tally_currencies_snapshot_rows
  tally_dataset_snapshots
  tally_day_book_snapshot_rows
  tally_generic_snapshot_rows
  tally_groups_snapshot_rows
  tally_ledger_mappings
  tally_ledger_vouchers_snapshot_rows
  tally_ledgers_snapshot_rows
  tally_profit_loss_snapshot_rows
  tally_stock_groups_snapshot_rows
  tally_stock_items_snapshot_rows
  tally_sync_logs
  tally_sync_runs
  tally_uoms_snapshot_rows
  tally_voucher_write_queue
)

mysql_exec() {
  mariadb -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "-p$MYSQL_PASSWORD" "$@"
}

echo "Exporting H2 data from $H2_DB_PATH ..."
java -cp "$H2_JAR" org.h2.tools.Script \
  -url "$H2_URL" \
  -user sa \
  -password '' \
  -script "$DUMP_FILE" \
  -options NOSETTINGS

echo "Backing up current MariaDB database to $BACKUP_FILE ..."
mariadb-dump -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "-p$MYSQL_PASSWORD" "$MYSQL_DB" >"$BACKUP_FILE"

echo "Building MariaDB import script ..."
{
  echo "SET FOREIGN_KEY_CHECKS=0;"
  echo "SET UNIQUE_CHECKS=0;"
  echo "SET sql_notes=0;"
  for table in "${TABLES[@]}"; do
    echo "TRUNCATE TABLE \`$table\`;"
  done

  awk '
    /^INSERT INTO / { in_insert=1 }
    in_insert { print }
    in_insert && /;[[:space:]]*$/ { in_insert=0 }
  ' "$DUMP_FILE" | python3 -c '
import re
import sys

sql = sys.stdin.read()
sql = re.sub(r"INSERT INTO \"PUBLIC\"\.\"([A-Z0-9_]+)\"", lambda m: f"INSERT INTO `{m.group(1).lower()}`", sql)
sql = re.sub(r"\bTIMESTAMP\s+\x27([^\x27]*)\x27", lambda m: f"\x27{m.group(1)}\x27", sql)
sql = re.sub(r"\bDATE\s+\x27([^\x27]*)\x27", lambda m: f"\x27{m.group(1)}\x27", sql)
sys.stdout.write(sql)
'

  echo "SET sql_notes=1;"
  echo "SET UNIQUE_CHECKS=1;"
  echo "SET FOREIGN_KEY_CHECKS=1;"
} >"$IMPORT_FILE"

echo "Importing data into MariaDB $MYSQL_DB on $MYSQL_HOST:$MYSQL_PORT ..."
mysql_exec "$MYSQL_DB" <"$IMPORT_FILE"

echo
echo "Row counts after import:"
mysql_exec "$MYSQL_DB" -e "
SELECT 'auth_users' AS table_name, COUNT(*) AS row_count FROM auth_users
UNION ALL
SELECT 'tally_sync_runs', COUNT(*) FROM tally_sync_runs
UNION ALL
SELECT 'tally_dataset_snapshots', COUNT(*) FROM tally_dataset_snapshots
UNION ALL
SELECT 'tally_generic_snapshot_rows', COUNT(*) FROM tally_generic_snapshot_rows
UNION ALL
SELECT 'tally_voucher_write_queue', COUNT(*) FROM tally_voucher_write_queue;
"

echo
echo "Migration complete."
echo "H2 dump: $DUMP_FILE"
echo "MariaDB backup: $BACKUP_FILE"
echo "Import script: $IMPORT_FILE"
