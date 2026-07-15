#!/usr/bin/env bash

set -u -o pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8082}"
AGENT_KEY="${AGENT_KEY:-local-dev-key}"
TALLY_URL="${TALLY_URL:-http://127.0.0.1:9000}"
MODE="${MODE:-get}"
OUTPUT_DIR="${OUTPUT_DIR:-}"

if [[ "$MODE" != "inventory" && "$MODE" != "seed" && "$MODE" != "get" && "$MODE" != "full" ]]; then
  echo "Unknown MODE='$MODE'. Use MODE=inventory, MODE=seed, MODE=get, or MODE=full."
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TOTAL=0
HTTP_OK=0
HTTP_ERROR=0
CURL_FAIL=0
SKIPPED=0

if [[ -n "$OUTPUT_DIR" ]]; then
  mkdir -p "$OUTPUT_DIR"
fi

log_section() {
  printf '\n========== %s ==========\n' "$1"
}

slugify() {
  echo "$1" | tr ' /:?=&' '_' | tr -cd '[:alnum:]_-'
}

preview_body() {
  local file="$1"
  python - "$file" <<'PY'
import json, sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(errors="replace").strip()
if not text:
    print("<empty>")
    raise SystemExit
try:
    obj = json.loads(text)
    compact = json.dumps(obj, ensure_ascii=False)
    print(compact[:320] + ("..." if len(compact) > 320 else ""))
except Exception:
    one_line = " ".join(text.split())
    print(one_line[:320] + ("..." if len(one_line) > 320 else ""))
PY
}

read_json_field() {
  local path="$1"
  local python_expr="$2"
  python - "$path" "$python_expr" <<'PY'
import json, sys
from pathlib import Path

path = Path(sys.argv[1])
expr = sys.argv[2]
obj = json.loads(path.read_text())
ns = {"obj": obj}
value = eval(expr, {"__builtins__": {}}, ns)
print("" if value is None else value)
PY
}

wait_for_tally() {
  local retries="${1:-20}"
  local delay="${2:-1}"
  local i
  for ((i = 1; i <= retries; i++)); do
    if curl -sS -o /dev/null "$TALLY_URL"; then
      return 0
    fi
    sleep "$delay"
  done
  return 1
}

ensure_tally_ready() {
  if ! wait_for_tally 20 1; then
    echo "Tally server is not reachable at $TALLY_URL. Start/open Tally and try again."
    exit 1
  fi
}

probe_tally_after_request() {
  local name="$1"
  if ! curl -sS -o /dev/null "$TALLY_URL"; then
    echo "      Tally server is unavailable after '$name'. Stopping run to avoid noisy failures."
    exit 1
  fi
}

skip_request() {
  local name="$1"
  local reason="$2"
  SKIPPED=$((SKIPPED + 1))
  printf '[SKIP] %-32s %s\n' "$name" "$reason"
}

refresh_seed_payloads() {
  local stock_groups_file="$TMP_DIR/seed_stock_groups.json"
  local uoms_file="$TMP_DIR/seed_uoms.json"

  curl -sS -H "Accept: application/json" -H "X-AGENT-KEY: $AGENT_KEY" \
    "$BASE_URL/stock-groups?fetch=NAME&limit=10" -o "$stock_groups_file"
  curl -sS -H "Accept: application/json" -H "X-AGENT-KEY: $AGENT_KEY" \
    "$BASE_URL/uoms?fetch=NAME&limit=10" -o "$uoms_file"

  local stock_group_parent
  local base_unit
  stock_group_parent="$(read_json_field "$stock_groups_file" "((obj.get('STOCKGROUP') or [{}])[0] if isinstance(obj.get('STOCKGROUP'), list) else (obj.get('STOCKGROUP') or {})).get('@NAME', '')")"
  base_unit="$(read_json_field "$uoms_file" "((obj.get('UNIT') or [{}])[0] if isinstance(obj.get('UNIT'), list) else (obj.get('UNIT') or {})).get('@NAME', '')")"

  if [[ -z "$stock_group_parent" ]]; then
    stock_group_parent="ElectronicsA"
  fi
  if [[ -z "$base_unit" ]]; then
    base_unit="NOS"
  fi

  STOCK_GROUP_BODY="{\"NAME\":\"Seed Stock Group\",\"PARENT\":\"$stock_group_parent\"}"
  STOCK_ITEM_BODY="{\"NAME\":\"Seed Item\",\"PARENT\":\"Seed Stock Group\",\"BASEUNITS\":\"$base_unit\"}"
}

run_inventory_phase() {
  log_section "API inventory"
  local py_bin=".venv/bin/python"
  if [[ ! -x "$py_bin" ]]; then
    py_bin="python"
  fi
  (cd "$(dirname "$0")" && PYTHONPATH=src "$py_bin" list_api_inventory.py)
}

run_request() {
  local name="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"

  TOTAL=$((TOTAL + 1))

  local body_file="$TMP_DIR/body_${TOTAL}.txt"
  local curl_args=(
    -sS
    -X "$method"
    -H "Accept: application/json"
    -H "X-AGENT-KEY: $AGENT_KEY"
    -o "$body_file"
    -w "%{http_code}"
  )

  if [[ "$method" == "POST" ]]; then
    curl_args+=(-H "Content-Type: application/json" --data "$body")
  fi

  local code
  if ! code="$(curl "${curl_args[@]}" "$BASE_URL$path" 2>"$TMP_DIR/curl_${TOTAL}.err")"; then
    CURL_FAIL=$((CURL_FAIL + 1))
    printf '[%03d] %-32s %-4s %s -> CURL FAILED\n' "$TOTAL" "$name" "$method" "$path"
    cat "$TMP_DIR/curl_${TOTAL}.err"
    probe_tally_after_request "$name"
    return
  fi

  if [[ "$code" =~ ^2 ]]; then
    HTTP_OK=$((HTTP_OK + 1))
  else
    HTTP_ERROR=$((HTTP_ERROR + 1))
  fi

  printf '[%03d] %-32s %-4s %s -> HTTP %s\n' "$TOTAL" "$name" "$method" "$path" "$code"
  printf '      %s\n' "$(preview_body "$body_file")"

  if [[ -n "$OUTPUT_DIR" ]]; then
    cp "$body_file" "$OUTPUT_DIR/$(printf '%03d' "$TOTAL")_$(slugify "$name").json"
  fi

  probe_tally_after_request "$name"
}

g() {
  run_request "$1" GET "$2"
}

p() {
  run_request "$1" POST "$2" "$3"
}

# Verified-safe seed bodies (checked locally against the current Tally build).
GROUP_BODY='{"NAME":"Seed SubGroup","PARENT":"Bank Accounts"}'
LEDGER_BODY='{"NAME":"Seed Ledger","PARENT":"Sundry Debtors"}'
COST_CATEGORY_BODY='{"NAME":"Seed Category"}'
COST_CENTRE_BODY='{"NAME":"Seed Centre","CATEGORY":"Primary Cost Category"}'
UNIT_BODY='{"NAME":"PCS","ORIGINALNAME":"Pieces","ISSIMPLEUNIT":"Yes","DECIMALPLACES":"0"}'
GODOWN_BODY='{"NAME":"Seed Godown","PARENT":"Main Location"}'
STOCK_GROUP_BODY='{"NAME":"Seed Stock Group","PARENT":"ElectronicsA"}'
STOCK_CATEGORY_BODY='{"NAME":"Seed Stock Category"}'
STOCK_ITEM_BODY='{"NAME":"Seed Item","PARENT":"Seed Stock Group","BASEUNITS":"NOS"}'
BUDGET_BODY='{"NAME":"Seed Budget"}'
COMPANY_BODY='{"NAME":"Ridsys"}'
VOUCHER_TYPE_BODY='{"NAME":"Sales"}'
PRICE_LEVEL_BODY='{"NAME":"Retail"}'
COMPANY_FEATURES_BODY='{"ISINVENTORYON":"Yes"}'
GST_REG_BODY='{"GSTREGISTRATIONTYPE":"Regular","PARTYGSTIN":"27ABCDE1234F1Z5","STATENAME":"Maharashtra"}'
NUMBERING_RULE_BODY='{"NAME":"Sales"}'
EINVOICE_BODY='{"ISEINVOICEON":"Yes"}'
EWAYBILL_BODY='{"ISEWAYBILLON":"Yes"}'

run_seed_phase() {
  log_section "SEED endpoints"
  refresh_seed_payloads
  p "groups-create" '/groups?action=Create' "$GROUP_BODY"
  p "ledgers-create" '/ledgers?action=Create' "$LEDGER_BODY"
  p "cost-categories-create" '/cost-categories?action=Create' "$COST_CATEGORY_BODY"
  p "cost-centres-create" '/cost-centres?action=Create' "$COST_CENTRE_BODY"
  p "uoms-create" '/uoms?action=Create' "$UNIT_BODY"
  p "godowns-create" '/godowns?action=Create' "$GODOWN_BODY"
  p "stock-groups-create" '/stock-groups?action=Create' "$STOCK_GROUP_BODY"
  p "stock-categories-create" '/stock-categories?action=Create' "$STOCK_CATEGORY_BODY"
  p "stock-items-create" '/stock-items?action=Create' "$STOCK_ITEM_BODY"
  p "budgets-create" '/budgets?action=Create' "$BUDGET_BODY"
}

run_get_phase() {
  log_section "GET endpoints"
  g "health" '/health'

  g "companies-list" '/companies?fetch=NAME&limit=10'
  g "currencies-list" '/currencies?fetch=NAME&limit=10'
  g "groups-list" '/groups?fetch=NAME&limit=10'
  g "ledger-groups-list" '/ledger-groups?fetch=NAME&limit=10'
  g "ledgers-list" '/ledgers?fetch=NAME&limit=10'
  g "cost-categories-list" '/cost-categories?fetch=NAME&limit=10'
  g "cost-centres-list" '/cost-centres?fetch=NAME&limit=10'
  g "projects-list" '/projects?fetch=NAME&limit=10'
  g "uoms-list" '/uoms?fetch=NAME&limit=10'
  g "godowns-list" '/godowns?fetch=NAME&limit=10'
  g "stock-groups-list" '/stock-groups?fetch=NAME&limit=10'
  g "stock-categories-list" '/stock-categories?fetch=NAME&limit=10'
  g "stock-items-list" '/stock-items?fetch=NAME&limit=10'
  g "boms-list" '/boms?fetch=NAME&limit=10'
  g "price-levels-list" '/price-levels?fetch=NAME&limit=10'
  g "price-lists-list" '/price-lists?fetch=NAME&limit=10'
  g "voucher-types-list" '/voucher-types?fetch=NAME&limit=10'
  g "budgets-list" '/budgets?fetch=NAME&limit=10'
  g "employees-list" '/employees'
  g "employee-groups-list" '/employee-groups'
  g "pay-heads-list" '/pay-heads'
  g "attendance-types-list" '/attendance-types'

  g "vouchers-generic-list" '/vouchers?voucher_type=Journal&limit=10'
  g "sales-vouchers-list" '/vouchers/sales?limit=10'
  g "purchase-vouchers-list" '/vouchers/purchase?limit=10'
  g "sales-orders-list" '/vouchers/sales-orders?from_date=20260401&to_date=20260430&limit=10'
  g "purchase-orders-list" '/vouchers/purchase-orders?from_date=20260401&to_date=20260430&limit=10'
  g "delivery-notes-list" '/vouchers/delivery-notes?limit=10'
  g "goods-receipts-list" '/vouchers/goods-receipts?limit=10'
  g "stock-journals-list" '/vouchers/stock-journals?limit=10'
  g "material-in-list" '/vouchers/material-in?limit=10'
  g "material-out-list" '/vouchers/material-out?limit=10'
  g "manufacturing-list" '/vouchers/manufacturing?limit=10'
  g "receipt-list" '/vouchers/receipt?limit=10'
  g "payment-list" '/vouchers/payment?limit=10'
  g "contra-list" '/vouchers/contra?limit=10'
  g "journal-list" '/vouchers/journal?limit=10'
  g "credit-notes-list" '/vouchers/credit-notes?limit=10'
  g "debit-notes-list" '/vouchers/debit-notes?limit=10'
  g "rejections-in-list" '/vouchers/rejections-in?limit=10'
  g "rejections-out-list" '/vouchers/rejections-out?limit=10'
  g "payroll-list" '/vouchers/payroll?limit=10'

  g "companies-available" '/companies/list?fetch=NAME&fetch=GUID'
  g "settings-company-features" '/settings/company-features'
  g "settings-gst-registration" '/settings/gst-registration'
  g "settings-numbering-rules" '/settings/numbering-rules'
  g "settings-tax-rate-tables" '/settings/tax-rate-tables'
  g "settings-price-structures" '/settings/price-structures'
  g "settings-stock-controls" '/settings/stock-controls'
  g "settings-security-roles" '/settings/security-roles'
  g "settings-uqc-mappings" '/settings/uqc-mappings'
  g "settings-einvoice" '/settings/einvoice'
  g "settings-ewaybill" '/settings/ewaybill'

  g "report-day-book" '/reports/day-book?from_date=20260401&to_date=20260430'
  g "report-stock-items" '/reports/stock-items?limit=100'
  g "report-stock-groups" '/reports/stock-groups?limit=100'
  g "report-ledgers" '/reports/ledgers?limit=100'
  g "report-cost-centres" '/reports/cost-centres?limit=100'
  g "report-cost-categories" '/reports/cost-categories?limit=100'
  g "report-godowns" '/reports/godowns?limit=100'
  g "report-uoms" '/reports/uoms?limit=100'
  g "report-tax-rates" '/reports/tax-rates?stock_limit=50&ledger_limit=50'
  g "report-receivables" '/reports/outstanding-receivables?from_date=20260401&to_date=20260430'
  g "report-payables" '/reports/outstanding-payables?from_date=20260401&to_date=20260430'
  g "report-ledger-vouchers" '/reports/ledger-vouchers?ledger_name=Cash&from_date=20260401&to_date=20260430'
  g "report-stock-summary" '/reports/stock-summary?from_date=20260401&to_date=20260430'
  g "report-batch-availability" '/reports/batch-availability?stock_item=Product1&godown=Main%20Location&from_date=20260401&to_date=20260430'
  g "report-price-lists" '/reports/price-lists?limit=100'
  g "report-companies" '/reports/companies?fetch=NAME&fetch=GUID'
  g "report-bank-reco" '/reports/bank-reco-status?ledger_name=Cash&from_date=20260401&to_date=20260430'
  g "report-trial-balance" '/reports/trial-balance?from_date=20260401&to_date=20270331'
  g "report-balance-sheet" '/reports/balance-sheet?from_date=20260401&to_date=20270331'
  g "report-profit-loss" '/reports/profit-loss?from_date=20260401&to_date=20270331'
}

run_full_post_phase() {
  log_section "POST smoke / cleanup"
  refresh_seed_payloads

  p "groups-alter" '/groups?action=Alter' "$GROUP_BODY"
  p "ledgers-alter" '/ledgers?action=Alter' "$LEDGER_BODY"
  p "cost-categories-alter" '/cost-categories?action=Alter' "$COST_CATEGORY_BODY"
  p "cost-centres-alter" '/cost-centres?action=Alter' "$COST_CENTRE_BODY"
  p "uoms-alter" '/uoms?action=Alter' "$UNIT_BODY"
  p "godowns-alter" '/godowns?action=Alter' "$GODOWN_BODY"
  p "stock-groups-alter" '/stock-groups?action=Alter' "$STOCK_GROUP_BODY"
  p "stock-categories-alter" '/stock-categories?action=Alter' "$STOCK_CATEGORY_BODY"
  p "stock-items-alter" '/stock-items?action=Alter' "$STOCK_ITEM_BODY"
  p "budgets-alter" '/budgets?action=Alter' "$BUDGET_BODY"
  p "companies-update" '/companies/update?action=Alter' "$COMPANY_BODY"
  p "companies-alter" '/companies/alter' "$COMPANY_BODY"
  p "voucher-types-alter" '/voucher-types?action=Alter' "$VOUCHER_TYPE_BODY"
  p "price-levels-alter" '/price-levels?action=Alter' "$PRICE_LEVEL_BODY"
  p "price-lists-alter" '/price-lists?action=Alter' "$PRICE_LEVEL_BODY"
  p "settings-numbering-rules-alter" '/settings/numbering-rules?action=Alter' "$NUMBERING_RULE_BODY"
  p "settings-company-features-alter" '/settings/company-features?action=Alter' "$COMPANY_FEATURES_BODY"
  p "settings-gst-registration-alter" '/settings/gst-registration?action=Alter' "$GST_REG_BODY"
  p "settings-price-structures-alter" '/settings/price-structures?action=Alter' "$PRICE_LEVEL_BODY"
  p "settings-stock-controls-alter" '/settings/stock-controls?action=Alter' '{"NAME":"Product1"}'
  p "settings-einvoice-alter" '/settings/einvoice?action=Alter' "$EINVOICE_BODY"
  p "settings-ewaybill-alter" '/settings/ewaybill?action=Alter' "$EWAYBILL_BODY"

  p "stock-items-delete" '/stock-items?action=Delete' '{"NAME":"Seed Item"}'
  p "stock-categories-delete" '/stock-categories?action=Delete' '{"NAME":"Seed Stock Category"}'
  p "stock-groups-delete" '/stock-groups?action=Delete' '{"NAME":"Seed Stock Group"}'
  p "godowns-delete" '/godowns?action=Delete' '{"NAME":"Seed Godown"}'
  p "uoms-delete" '/uoms?action=Delete' '{"NAME":"PCS"}'
  p "cost-centres-delete" '/cost-centres?action=Delete' '{"NAME":"Seed Centre"}'
  p "cost-categories-delete" '/cost-categories?action=Delete' '{"NAME":"Seed Category"}'
  p "ledgers-delete" '/ledgers?action=Delete' '{"NAME":"Seed Ledger"}'
  p "groups-delete" '/groups?action=Delete' '{"NAME":"Seed SubGroup"}'
  p "budgets-delete" '/budgets?action=Delete' '{"NAME":"Seed Budget"}'

  log_section "POST endpoints skipped on purpose"
  skip_request "companies-open" "Tally returns 'Unknown Request' for the current switch-company XML call, so this route is explicitly unsupported for now."
  skip_request "companies-create" "Current built-in XML create request disconnects Tally; kept explicit until a real company-creation path is verified."
  skip_request "price-levels / price-lists create-delete" "Only the verified safe alter smoke is automated; create/delete needs a business-specific pricing setup."
  skip_request "employees / employee-groups / pay-heads / attendance-types POST" "Payroll master writes still need feature-specific payloads; only the verified real-time GET exports are automated here."
  skip_request "generic and type-specific voucher POST routes" "Voucher writes need business-valid totals, items, taxes, and party setup; run them manually per scenario."
  skip_request "settings-security-roles POST" "No stable built-in Tally XML path was verified for security-role writes."
}

case "$MODE" in
  inventory)
    run_inventory_phase
    ;;
  seed)
    ensure_tally_ready
    run_seed_phase
    ;;
  get)
    ensure_tally_ready
    run_get_phase
    ;;
  full)
    ensure_tally_ready
    run_inventory_phase
    run_seed_phase
    run_get_phase
    run_full_post_phase
    ;;
esac

log_section "Summary"
printf 'Base URL      : %s\n' "$BASE_URL"
printf 'Tally URL     : %s\n' "$TALLY_URL"
printf 'Mode          : %s\n' "$MODE"
printf 'Total         : %d\n' "$TOTAL"
printf 'HTTP 2xx      : %d\n' "$HTTP_OK"
printf 'HTTP non-2xx  : %d\n' "$HTTP_ERROR"
printf 'Curl failures : %d\n' "$CURL_FAIL"
printf 'Skipped       : %d\n' "$SKIPPED"
