# TallyConnectorPython (Python 3.11+)

FastAPI-based Tally connector covering key masters (create/update + list):
- Company, Currency
- Group, Ledger (with GST / bank fields), Ledger Group alias
- Cost Category, Cost Centre / Projects
- Unit of Measure, Godown, Stock Group, Stock Category, Stock Item, BOM
- Price Level / Price List, Voucher Type, Budget
- Payroll: Employee Group, Employee, Pay Head, Attendance / Production Type
- Reference/settings APIs for company features, GST registration, numbering rules, tax tables, pricing, stock controls, security roles, UQC mappings, e-invoice, and e-way bill

## Quick start
```
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # edit values
PYTHONPATH=src uvicorn tally_connector.main:app --reload --port 8082
```

## Single-shot API check

You can list and smoke-test the implemented APIs from one script:

```bash
# Print the current A-to-Z API inventory from the FastAPI app
MODE=inventory ./test_all_endpoints.sh

# Run verified-safe GET checks
MODE=get ./test_all_endpoints.sh

# Run seed + GET + verified-safe POST smoke/cleanup
MODE=full ./test_all_endpoints.sh
```

Optional:

```bash
MODE=full OUTPUT_DIR=endpoint_results ./test_all_endpoints.sh
```

That stores each response body under `endpoint_results/`.

## Implementation matrix

To identify which connector APIs are fully implemented, partial, TDL/experimental, or dependent on the current Tally build:

```bash
curl -H "X-AGENT-KEY: local-dev-key" http://127.0.0.1:8082/implementation-matrix
```

The response groups every FastAPI route into one of these statuses:
- `implemented`
- `partial`
- `tdl_or_experimental`
- `tally_build_dependent`

## XML discovery on live TallyPrime

When you need to finish a missing API, the safest workflow is:
1. turn on connector XML tracing
2. probe the live Tally XML gateway for the target report or collection
3. inspect the decoded `tallyhttp.log` and the saved request/response snapshots

Enable connector-side XML tracing in `.env`:

```env
TALLY_XML_TRACE_ENABLED=true
TALLY_XML_TRACE_DIR=/absolute/path/to/xml-trace
```

Every connector call will then save `request.xml`, `response.xml`, and `meta.json` under that trace directory.

Probe unresolved report or collection names directly against the running Tally instance:

```bash
python3 probe_tally_xml.py --preset form-24q
python3 probe_tally_xml.py --preset security-roles
python3 probe_tally_xml.py --kind report --candidate "Balance Sheet"
python3 probe_tally_xml.py --kind collection --candidate "List of Companies"
```

Inspect the local Wine Tally HTTP log in readable UTF-8:

```bash
python3 decode_tally_http_log.py --list-ids
python3 decode_tally_http_log.py --filter GSTR
python3 decode_tally_http_log.py --save-decoded tallyhttp-decoded.txt
```

These two scripts are intended to help implement the remaining connector gaps, especially where the route exists but the exact built-in Tally report name or collection name is still uncertain.

## Windows `.exe` build

Build the Windows executable on a Windows machine. PyInstaller does not reliably cross-build a Windows `.exe` from Linux.

1. Install Python 3.11+ on the Windows build machine.
2. Open `cmd` in this project folder.
3. Run:

```bat
build_windows_exe.bat
```

The build script will:
- create `.venv-build`
- install runtime + build dependencies
- generate `dist\TallyConnectorPython.exe`
- copy `.env.example` and `README.md` into `dist\`
- generate `dist\start_connector.bat`

If you want a Teams-style single installer `.exe`, build the installer too:

```bat
build_windows_setup.bat
```

That produces:
- `installer-dist\TallyConnectorPython-Setup.exe`

The setup `.exe` installs the connector into `Program Files`, creates shortcuts, and can optionally auto-start it with Windows.

## Ubuntu build

Build the Ubuntu/Linux executable on an Ubuntu machine:

```bash
chmod +x build_ubuntu.sh start_connector.sh
./build_ubuntu.sh
```

The build script will:
- create `.venv-build-linux`
- install runtime + build dependencies
- generate `dist-ubuntu/TallyConnectorPython`
- copy `.env` when present, plus `.env.example` and `README.md`
- copy `dist-ubuntu/start_connector.sh`

Run the packaged Linux build with:

```bash
cd dist-ubuntu
./start_connector.sh
```

## Windows target-system setup

If you want to install this connector on another Windows system, keep these ready:

1. Windows 10/11 x64
2. TallyPrime installed and working
3. The target company data available in TallyPrime
4. Tally HTTP/XML enabled in TallyPrime on the configured port (usually `9000`)
5. The company opened in TallyPrime before using connector APIs that depend on live company data
6. Firewall access for the connector port (default `8082`) if another machine/backend will call it

Python is not required on the target machine if you ship the bundled `.exe` or installer.

Important reality:
- we can make the connector app itself self-contained
- but we cannot remove the dependency on TallyPrime being installed and reachable
- so "single `.exe`" is realistic for the connector, not for the whole Tally environment

## Windows install steps

### Portable app mode

After building, copy the entire `dist` folder to the target Windows system.

1. Copy:
   - `TallyConnectorPython.exe`
   - `.env.example`
   - `README.md`
   - `start_connector.bat`
2. Start TallyPrime and open the required company
3. Run `start_connector.bat` or `TallyConnectorPython.exe`
4. The packaged app will auto-create a runtime `.env` in:
   - `%APPDATA%\TallyConnectorPython\.env`
5. Optionally edit that generated `.env` later if you need different values
6. Verify:

```bat
curl http://127.0.0.1:8082/health
```

If the backend is on another machine, use the Windows machine IP instead of `127.0.0.1`, for example:

```bat
curl http://192.168.1.25:8082/health
```

### Installed app mode

If you built `TallyConnectorPython-Setup.exe`:

1. Copy `installer-dist\TallyConnectorPython-Setup.exe` to the target machine
2. Run the installer
3. Start TallyPrime and open the required company
4. Launch the installed connector from Start Menu or desktop shortcut
5. The connector will auto-create runtime config in:
   - `%APPDATA%\TallyConnectorPython\.env`
6. Verify the health endpoint

## Configuration (.env)
```
TALLY_BASE_URL=http://127.0.0.1:9000
COMPANY=
BRANCH=
CONNECTOR_ID=
AGENT_KEY=local-dev-key
AUTH_CLIENT_ID=connector-client
AUTH_CLIENT_SECRET=local-dev-key
AUTH_TOKEN_SECRET=local-dev-key
AUTH_TOKEN_TTL_SEC=3600
AUTH_ACCEPT_STATIC_KEY=true
LISTEN_ADDR=0.0.0.0
LISTEN_PORT=8082
# Optional connector announce / routing registration target (backend)
CONNECTOR_ANNOUNCE_ENABLED=true
CONNECTOR_ANNOUNCE_INTERVAL_SEC=60
# When SERVER_MODE=connector, SERVER_BASE_URL is the backend registry API.
# When SYNC_ENABLED=true, SERVER_BASE_URL is also used by the sync worker.
SERVER_BASE_URL=http://backend-server:9090
SERVER_AGENT_KEY=prod-agent-key
SYNC_ENABLED=false
SYNC_DIRECTION=both   # push | pull | both
SYNC_INTERVAL_SEC=60
SERVER_MODE=connector # connector | tally (tally = direct Tally XML port; only push supported)
EXPERIMENTAL_TALLY_COMPANY_OPEN_ENABLED=false
EXPERIMENTAL_TALLY_COMPANY_CREATE_ENABLED=false
EXPERIMENTAL_TALLY_SECURITY_ROLES_ENABLED=false
```

Notes:
- Leave `COMPANY` blank to use whichever company is currently active/open in TallyPrime.
- Set `COMPANY` only if you want to force all requests to a specific company name.
- `AGENT_KEY` is the legacy static key fallback.
- `AUTH_CLIENT_ID` / `AUTH_CLIENT_SECRET` are used to mint bearer tokens at `POST /auth/token`.
- `AUTH_TOKEN_SECRET` should match the backend `auth.token-secret` if you want backend-issued bearer tokens to work on the connector too.
- `CONNECTOR_ID` lets you pin a stable registry id for this machine; otherwise the connector falls back to branch, company, or hostname.
- `BRANCH` and `COMPANY` are included in the backend registry record so the central backend can route by branch/company when needed.
- `SERVER_BASE_URL` is the central backend URL when `SERVER_MODE=connector`; the connector announces itself there at startup and on an interval.
- `SERVER_AGENT_KEY` should match the backend's accepted static agent key if you want auto-announce/registry updates to succeed.
- The three `EXPERIMENTAL_*` flags keep historically unstable Tally XML paths disabled by default. Turn them on only when you are ready to verify behavior against the exact Tally build you are running.

Notes for packaged Windows runs:
- The connector looks for `.env` in this order:
  1. `TALLY_CONNECTOR_ENV_FILE` if you set it
  2. `%APPDATA%\TallyConnectorPython\.env` for packaged Windows runs
  3. the same folder as the `.exe`
  4. the current working directory
  5. the project folder
- Packaged runs default to `reload=false`
- Dev hot-reload is still available when you run with `uvicorn --reload`
- If no packaged runtime config exists, the app creates one automatically with safe defaults

## Connector auto-announce / backend registry

When you want the connector to auto-register itself with the central backend:

1. Set `SERVER_MODE=connector`
2. Set `SERVER_BASE_URL` to the backend base URL, for example:
   - `http://192.168.70.100:9090`
3. Set `SERVER_AGENT_KEY` to the backend's accepted static key, or use the backend token flow
4. Keep `CONNECTOR_ANNOUNCE_ENABLED=true`

On startup, the connector will POST its current `connectorId`, LAN-reachable `baseUrl`, company/branch metadata, and `AGENT_KEY` to:

- `POST /api/connectors`

It will repeat that on `CONNECTOR_ANNOUNCE_INTERVAL_SEC`, so the backend registry can treat it like a heartbeat / last-seen refresh.

## Notes
- XML → JSON conversion uses xmltodict for quick wins; keep envelopes small.
- All master POST endpoints upsert (`ACTION=Alter`) for idempotence.
- Add auth/mTLS/retries/rate limits before production.
- GET on any master endpoint exports masters (optional `fetch`, `filter`, `limit` query params map to Tally collection fetch/filter/MAXRECORDS).

## Continuous sync (local Tally ↔ remote server)

- Enable by setting `SYNC_ENABLED=true` in `.env` and providing `SERVER_BASE_URL` (points to another instance of this connector running against the remote/server Tally data store or your API gateway). `SYNC_DIRECTION` controls flow: `push` (local→server), `pull` (server→local), or `both`.
- If your `SERVER_BASE_URL` points directly to a Tally XML port (not another connector), set `SERVER_MODE=tally`; pulls will be disabled automatically because Tally won’t serve the REST endpoints.
- Background task runs on startup and loops every `SYNC_INTERVAL_SEC`; for each master it exports from the source and replays as upserts into the target. The sync is intentionally simple (idempotent upserts); add domain filters if needed (e.g., limit to one company/project).
- Current master list included in sync: company, currency, group, ledger, cost category/centre, unit, godown, stock group/category/item, BOM, price level, voucher type, budget, employee/group, pay head, attendance type.
- For richer conflict handling (timestamps/versioning), extend `sync.py` to compare payload hashes before upsert or add a “last modified” custom field in Tally.

## Endpoint map

| Path | Master type | Methods |
| --- | --- | --- |
| /companies | Company | POST, GET |
| /currencies | Currency | POST, GET |
| /groups, /ledger-groups | Group | POST, GET |
| /ledgers | Ledger | POST, GET |
| /cost-categories | Cost Category | POST, GET |
| /cost-centres, /projects | Cost Centre | POST, GET |
| /price-levels, /price-lists | Price Level | POST, GET |
| /uoms | Unit | POST, GET |
| /godowns | Godown / Warehouse | POST, GET |
| /stock-groups | Stock Group | POST, GET |
| /stock-categories | Stock Category | POST, GET |
| /stock-items | Stock Item | POST, GET |
| /boms | BOM | POST, GET |
| /voucher-types | Voucher Type | POST, GET |
| /budgets | Budget | POST, GET |
| /employee-groups | Employee Group | POST, GET |
| /employees | Employee | POST, GET |
| /pay-heads | Pay Head | POST, GET |
| /attendance-types | Attendance / Production Type | POST, GET |

Pricing note:
- `POST /price-lists` persists item-wise `FULLPRICELIST` rows on stock items.
- `POST /price-levels` also works through that same item-linked path when you send `ITEMNAME` plus `PRICELEVEL`.
- Standalone company price-level names are still maintained in Tally's Inventory Features screen and are not yet exposed through a verified standalone XML create/rename path in this connector.

Price-list exports prefer the native Tally `Price List` report flow first.
If that report is unavailable in the connected Tally build, the connector falls back to
the stock-item nested price-list export.

## Settings / reference APIs

These endpoints are meant for the web app when it needs Tally configuration/reference data without manually composing XML. All of them use live local Tally data via the connector.

| Path | Purpose | Methods |
| --- | --- | --- |
| /settings/company-features | Company feature flags and basic company setup | GET, POST |
| /settings/gst-registration | Company GST registration details | GET, POST |
| /settings/numbering-rules | Voucher type numbering / rounding rules | GET, POST |
| /settings/tax-rate-tables | GST/HSN/SAC/tax-related stock + ledger data | GET |
| /settings/price-structures | Price levels / discount structures | GET, POST |
| /settings/stock-controls | Reorder / stock-control oriented stock item + godown data | GET, POST |
| /settings/security-roles | Security level / role data | GET, POST |
| /health/readiness | Local connector readiness with Tally/GST/TDL status | GET |
| /capabilities | Connector capability matrix and feature flags | GET |

Payroll note:
- `/employees`, `/employee-groups`, `/pay-heads`, and `/attendance-types` now return explicit payroll feature or TDL guidance when Tally exposes only metadata and not real payroll records through XML.
- When `TDL_INTEGRATION_ENABLED=true`, the connector reads `tdl/manifest.json` and exposes the TDL feature contract through `/health/readiness` and `/capabilities`.
| /settings/uqc-mappings | Unit/UQC mappings | GET |
| /settings/einvoice | E-invoice related company settings | GET, POST |
| /settings/ewaybill | E-way bill related company settings | GET, POST |

## Operational utility / report APIs

These routes are intended for pull/sync use cases in the web app.

| Path | Purpose |
| --- | --- |
| /reports/day-book | Day Book for a date range |
| /reports/stock-items | Lightweight stock item list |
| /reports/stock-groups | Stock group list |
| /reports/ledgers | Ledger list |
| /reports/cost-centres | Cost centre list |
| /reports/cost-categories | Cost category list |
| /reports/godowns | Godown list |
| /reports/uoms | Unit of measure list |
| /reports/tax-rates | Stock/ledger tax rate data |
| /reports/outstanding-receivables | Bills receivable report |
| /reports/outstanding-payables | Bills payable report |
| /reports/ledger-vouchers | Ledger account statement style voucher pull |
| /reports/stock-summary | Stock summary / closing view |
| /reports/batch-availability | Batch availability summary |
| /reports/price-lists | Price-list export sourced from stock-item price data |
| /reports/companies | Company list |
| /reports/bank-reco-status | Bank reconciliation report |
| /reports/trial-balance | Trial Balance |
| /reports/balance-sheet | Balance Sheet |
| /reports/profit-loss | Profit & Loss A/c |

Notes:
- `GET /reports/batch-availability` and `GET /reports/bank-reco-status` now return a structured `report_state: "empty_or_unsupported"` payload instead of raw `{"ENVELOPE": null}` when Tally exposes those reports ambiguously through XML.

Examples:

```bash
# Day Book
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/reports/day-book?from_date=20260401&to_date=20260430"

# Lightweight stock items
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/reports/stock-items?limit=100"

# Ledger vouchers / account statement
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/reports/ledger-vouchers?ledger_name=Acme%20Corp&from_date=20260401&to_date=20260430"

# Trial Balance
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/reports/trial-balance?from_date=20260401&to_date=20270331"
```

Notes:
- `GET` endpoints return pruned export data, not the full Tally envelope.
- Most `GET` endpoints have a default field set; if you need different Tally fields, pass repeated `fetch` params.
- Aggregate endpoints such as `/settings/tax-rate-tables` and `/settings/stock-controls` expose dedicated query params like `stock_fetch`, `ledger_fetch`, and `godown_fetch`.
- `POST` settings endpoints are thin wrappers over the relevant Tally masters, so the payload still uses native Tally field names.

Examples:

```bash
# Read company features for the default company
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/settings/company-features"

# Update GST registration details on the current company
curl -X POST -H "X-AGENT-KEY: local-dev-key" -H "Content-Type: application/json" \
  -d '{
        "GSTREGISTRATIONTYPE": "Regular",
        "PARTYGSTIN": "27ABCDE1234F1Z5",
        "STATENAME": "Maharashtra"
      }' \
  "http://127.0.0.1:8082/settings/gst-registration"

# Read tax tables for stock items + ledgers
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/settings/tax-rate-tables?stock_limit=50&ledger_limit=50"

# Read voucher numbering rules
curl -H "X-AGENT-KEY: local-dev-key" \
  "http://127.0.0.1:8082/settings/numbering-rules?fetch=NAME&fetch=NUMBERINGMETHOD&fetch=PREFIX"
```

## Payload examples (POST)

```jsonc
// Ledger with GST and bank
{
  "NAME": "Acme Supplies",
  "PARENT": "Sundry Creditors",
  "GSTREGISTRATIONTYPE": "Regular",
  "PARTYGSTIN": "27ABCDE1234F1Z5",
  "COUNTRYNAME": "India",
  "STATENAME": "Maharashtra",
  "PINCODE": "400001",
  "LEDSTATENAME": "Maharashtra",
  "EMAIL": "ap@acme.com",
  "BANKDETAILS.LIST": {
    "BANKNAME": "HDFC Bank",
    "IFSCODE": "HDFC0001234",
    "ACCOUNTNUMBER": "50100012345678"
  }
}

// Stock item with GST and BOM link
{
  "NAME": "Widget A",
  "PARENT": "Finished Goods",
  "GSTAPPLICABLE": "Applicable",
  "GSTTYPEOFSUPPLY": "Goods",
  "TAXABILITY": "Taxable",
  "HSNCODE": "84799090",
  "BASICTARIFF": 18,
  "BOMNAME": "Widget A BOM"
}

// BOM (components)
{
  "NAME": "Widget A BOM",
  "PARENT": "Standard BOM",
  "DESCRIPTION": "1 unit finished",
  "COMPONENTLIST.LIST": [
    { "STOCKITEMNAME": "Part X", "QTY": 2 },
    { "STOCKITEMNAME": "Part Y", "QTY": 1 }
  ]
}

// Price level / list
{
  "NAME": "Retail",
  "PRICELIST.LIST": [
    { "STOCKITEMNAME": "Widget A", "RATE": "1000/PCS", "DISCOUNT": 5 }
  ]
}

// Voucher type with custom numbering
{
  "NAME": "Sales (Auto #)",
  "ISDEEMEDPOSITIVE": "No",
  "NUMBERINGMETHOD": "Automatic (Manual Override)",
  "PREFIX": "SAL-",
  "STARTINGNUMBER": 1
}
```
