# Tally Backend API

Base URL: `http://127.0.0.1:9090`

Backend health:
- `GET /api/health`
- `GET /api/health/readiness`
- `GET /api/health/capabilities`
- `GET /api/health/implementation`
- `POST /api/auth/token`

Notes:
- Frontend should call Spring endpoints, not connector endpoints directly.
- GET responses are normalized in Spring before returning to UI.
- POST request bodies can be sent as clean JSON; Spring reshapes them before forwarding to the connector.
- Query params like `action`, `fetch`, `filter`, `limit`, `from_date`, `to_date`, `view`, `company` are passed through to the connector.
- Optional header:
  - `X-Company: <company name>` to target a specific company instead of the active one.
  - `X-Connector-Id: <connector id>` to route the request to a registered connector machine.
  - `X-Connector-Base-Url: http://host:8082` to override routing for one request.
  - `X-Connector-Agent-Key: <agent key>` optional when using direct base-url override.

## Authentication

Backend now supports real user login, bearer tokens, and keeps the old static `X-AGENT-KEY` as a migration fallback.

Get token:

```http
POST /api/auth/token
Content-Type: application/json
```

```json
{
  "username": "admin",
  "password": "admin@123"
}
```

Use token:

```http
Authorization: Bearer <token>
```

Automatic browser/session behavior:
- backend also sets an `HttpOnly` cookie named `TALLY_AUTH_TOKEN`
- after login, browser calls to backend APIs work without manually attaching the token each time
- backend reads that cookie, authorizes the request, and forwards the same bearer token to connector
- connector verifies that token before talking to local Tally
- login responses return user metadata by default; the raw `access_token` is only included when `include_token=true` or `expose_token=true` is sent in the login request

User directory:
- backend authenticates against a database-backed `auth_users` table
- `application.properties` configures the datasource and bootstrap admin credentials for first-time setup only
- those bootstrap values seed the DB once; all later username/password checks come from the database table
- after first deployment, create real users in the DB and keep the database under controlled access

Production note:
- set the same signing secret in backend and connector
- backend: `auth.token-secret`
- connector: `AUTH_TOKEN_SECRET`
- then backend-issued bearer tokens will work end to end through connector forwarding
- backend now forwards the incoming bearer token to the connector as the primary internal auth header
- static `X-AGENT-KEY` is only used as fallback when no bearer token is present

Swagger usage:
- login once with `POST /api/auth/token`
- browser receives the auth cookie automatically
- you can also use Swagger `Authorize` button with the bearer token if you want

## Multi-System Connector Routing

Production can now route requests to many installed connector systems instead of one fixed `ingest.base-url`.

Resolution order:
- `X-Connector-Base-Url` header
- `X-Connector-Id` header
- `connector_id` query param
- `X-Company` header / `company` query param when exactly one active connector is registered for that company
- caller machine IP automatically mapped to `http://<client-ip>:8082` when `connector.auto-route.by-client-ip=true`
- fallback default connector from `application.properties`

Zero-touch production mode:
- install the connector on each client/Tally machine
- keep connector listening on `0.0.0.0:8082`
- let users access the central backend directly from that same machine/browser
- backend will automatically forward the request to the connector running on that caller IP

Properties:

```properties
connector.auto-route.by-client-ip=true
connector.auto-route.port=8082
```

This means a request coming from `192.168.1.221` will automatically forward to:

```text
http://192.168.1.221:8082
```

Manual connector registration is still available for:
- branch/company-specific routing
- non-standard connector ports
- calls coming through shared proxy/jump-host setups

Registry endpoints:
- `GET /api/connectors`
- `GET /api/connectors/{connectorId}`
- `POST /api/connectors`
- `POST /api/connectors/{connectorId}/deactivate`

Sample registration body:

```json
{
  "connectorId": "branch-chennai",
  "baseUrl": "http://192.168.1.221:8082",
  "agentKey": "replace-with-strong-secret",
  "company": "Ridsys Chennai",
  "branch": "Chennai",
  "description": "Chennai Tally connector machine",
  "active": true
}
```

Then call any normal API with:

```http
GET /api/reports/companies
X-Connector-Id: branch-chennai
```

Login request with explicit token exposure for CLI/testing tools:

```json
{
  "username": "admin",
  "password": "admin@123",
  "include_token": true
}
```

Note:
- `admin/admin@123` is only the bootstrap seed user when the database is first created
- normal production users should be created in the database and authenticated from there

User management endpoints:
- `GET /api/auth/users` - list non-secret user accounts
- `POST /api/auth/users` - create/update a user
- `DELETE /api/auth/users/{username}` - deactivate a user

User management requests require an authenticated token with `ADMIN` role.

## Response Style

Read responses are JSON, with XML-style wrappers cleaned where possible.

Example:

```json
{
  "COMPANY": {
    "NAME": "Testconpany",
    "RESERVEDNAME": ""
  }
}
```

## Common Write Body Pattern

For master APIs, frontend can usually send clean JSON like:

```json
{
  "GROUP": [
    {
      "NAME": "Frontend Test Group",
      "PARENT": "Direct Expenses"
    }
  ]
}
```

or:

```json
{
  "NAME": "Frontend Test Group",
  "PARENT": "Direct Expenses"
}
```

Spring will reshape it for the connector.

## Health

- `GET /api/health`
- `GET /api/health/implementation`

Sample response:

```json
{
  "status": "ok",
  "version": "0.1.0"
}
```

Implementation matrix:
- `GET /api/health/implementation`
- Returns the routed connector's per-route implementation status so you can identify which APIs are:
  - `implemented`
  - `partial`
  - `tdl_or_experimental`
  - `tally_build_dependent`

## Companies

- `GET /api/companies`
- `POST /api/companies`
- `GET /api/companies/list`
- `POST /api/companies/open`
- `POST /api/companies/update`
- `POST /api/companies/create`
- `POST /api/companies/alter`

Sample body:

```json
{
  "COMPANY": [
    {
      "NAME": "New Company Name"
    }
  ]
}
```

## Master Data

Each of these supports:
- `GET`
- `POST`

Paths:
- `/api/currencies`
- `/api/groups`
- `/api/ledger-groups`
- `/api/ledgers`
- `/api/cost-categories`
- `/api/cost-centres`
- `/api/projects`
- `/api/uoms`
- `/api/godowns`
- `/api/stock-groups`
- `/api/stock-categories`
- `/api/stock-items`
- `/api/boms`
- `/api/price-levels`
- `/api/price-lists`
- `/api/voucher-types`
- `/api/budgets`
- `/api/employees`
- `/api/employee-groups`
- `/api/pay-heads`
- `/api/attendance-types`

Common query params:
- `action=Create|Alter|Delete`
- `fetch=NAME`
- `filter=$PARENT="Sundry Debtors"`
- `limit=20`
- `view=summary|full|raw`

Note on pricing exports:
- `/api/price-lists` and `/api/reports/price-lists` now prefer the native Tally `Price List` report flow.
- The connector can optionally pass the screen-style filters `stock_group`, `stock_category`, `price_level`, `price_level_date`, and `show_all_items`.
- If the native report is unavailable in a Tally build, the connector falls back to the stock-item nested price-list export.

Sample group create:

```json
{
  "GROUP": [
    {
      "NAME": "Frontend Test Group",
      "PARENT": "Direct Expenses"
    }
  ]
}
```

Sample ledger create:

```json
{
  "LEDGER": [
    {
      "NAME": "Frontend Test Ledger",
      "PARENT": "Sundry Debtors"
    }
  ]
}
```

Sample stock item create:

```json
{
  "STOCKITEM": [
    {
      "NAME": "Frontend Test Item",
      "PARENT": "Primary",
      "BASEUNITS": "Nos"
    }
  ]
}
```

Sample UOM create:

```json
{
  "UNIT": [
    {
      "NAME": "Box",
      "ORIGINALNAME": "Box",
      "ISSIMPLEUNIT": "Yes"
    }
  ]
}
```

## Reports

All report routes support `GET`.

Paths:
- `/api/reports/day-book`
- `/api/reports/stock-items`
- `/api/reports/stock-groups`
- `/api/reports/ledgers`
- `/api/reports/cost-centres`
- `/api/reports/cost-categories`
- `/api/reports/godowns`
- `/api/reports/uoms`
- `/api/reports/tax-rates`
- `/api/reports/outstanding-receivables`
- `/api/reports/outstanding-payables`
- `/api/reports/ledger-vouchers`
- `/api/reports/stock-summary`
- `/api/reports/batch-availability`
- `/api/reports/price-lists`
- `/api/reports/companies`
- `/api/reports/bank-reco-status`
- `/api/reports/trial-balance`
- `/api/reports/balance-sheet`
- `/api/reports/profit-loss`

Common query params:
- `from_date=20260401`
- `to_date=20260430`
- `limit=50`
- `fetch=NAME`
- `view=summary|full|raw`
- `filter=...`

Examples:
- `GET /api/reports/companies`
- `GET /api/reports/day-book?from_date=20260401&to_date=20260430`
- `GET /api/reports/ledger-vouchers?from_date=20260401&to_date=20260430`

## Settings

Routes:
- `GET /api/settings/company-features`
- `POST /api/settings/company-features`
- `GET /api/settings/gst-registration`
- `POST /api/settings/gst-registration`
- `GET /api/settings/numbering-rules`
- `POST /api/settings/numbering-rules`
- `GET /api/settings/tax-rate-tables`
- `GET /api/settings/price-structures`
- `POST /api/settings/price-structures`
- `GET /api/settings/stock-controls`
- `POST /api/settings/stock-controls`
- `GET /api/settings/security-roles`
- `POST /api/settings/security-roles`
- `GET /api/settings/uqc-mappings`
- `GET /api/settings/einvoice`
- `POST /api/settings/einvoice`
- `GET /api/settings/ewaybill`
- `POST /api/settings/ewaybill`

Sample body:

```json
{
  "enabled": true
}
```

Note:
- Settings payloads are more Tally-specific than master payloads. Final body shape depends on the exact setting being updated.

## Vouchers

General voucher routes:
- `GET /api/vouchers`
- `POST /api/vouchers`

Typed voucher routes:
- `GET /api/vouchers/sales`
- `POST /api/vouchers/sales`
- `GET /api/vouchers/purchase`
- `POST /api/vouchers/purchase`
- `GET /api/vouchers/sales-orders`
- `POST /api/vouchers/sales-orders`
- `GET /api/vouchers/purchase-orders`
- `POST /api/vouchers/purchase-orders`
- `GET /api/vouchers/delivery-notes`
- `POST /api/vouchers/delivery-notes`
- `GET /api/vouchers/goods-receipts`
- `POST /api/vouchers/goods-receipts`
- `GET /api/vouchers/stock-journals`
- `POST /api/vouchers/stock-journals`
- `GET /api/vouchers/material-in`
- `POST /api/vouchers/material-in`
- `GET /api/vouchers/material-out`
- `POST /api/vouchers/material-out`
- `GET /api/vouchers/manufacturing`
- `POST /api/vouchers/manufacturing`
- `GET /api/vouchers/receipt`
- `POST /api/vouchers/receipt`
- `GET /api/vouchers/payment`
- `POST /api/vouchers/payment`
- `GET /api/vouchers/contra`
- `POST /api/vouchers/contra`
- `GET /api/vouchers/journal`
- `POST /api/vouchers/journal`
- `GET /api/vouchers/credit-notes`
- `POST /api/vouchers/credit-notes`
- `GET /api/vouchers/debit-notes`
- `POST /api/vouchers/debit-notes`
- `GET /api/vouchers/rejections-in`
- `POST /api/vouchers/rejections-in`
- `GET /api/vouchers/rejections-out`
- `POST /api/vouchers/rejections-out`
- `GET /api/vouchers/payroll`
- `POST /api/vouchers/payroll`

Common voucher query params:
- `action=Create|Alter|Delete`
- `from_date=20260401`
- `to_date=20260430`
- `limit=50`
- `fetch=...`
- `view=summary|full|raw`

Sample payment voucher body:

```json
{
  "VOUCHER": [
    {
      "DATE": "20260407",
      "VOUCHERTYPENAME": "Payment",
      "VOUCHERNUMBER": "101",
      "NARRATION": "Payment from frontend",
      "PARTYLEDGERNAME": "Cash"
    }
  ]
}
```

## E-Invoice / E-Way Bill

Routes:
- `POST /api/vouchers/einvoice/generate`
- `POST /api/vouchers/sales/einvoice/generate`
- `POST /api/vouchers/ewaybill/generate`
- `POST /api/vouchers/sales/ewaybill/generate`
- `POST /api/vouchers/einvoice-ewaybill/generate`
- `POST /api/vouchers/sales/einvoice-ewaybill/generate`
- `POST /api/vouchers/einvoice-ready`
- `POST /api/vouchers/sales/einvoice-ready`
- `GET /api/vouchers/sales/einvoice-status`
- `POST /api/vouchers/ewaybill-ready`
- `POST /api/vouchers/sales/ewaybill-ready`
- `GET /api/vouchers/sales/ewaybill-status`
- `POST /api/vouchers/einvoice-ewaybill-ready`
- `POST /api/vouchers/sales/einvoice-ewaybill-ready`

Sample body:

```json
{
  "VOUCHER": [
    {
      "DATE": "20260407",
      "VOUCHERTYPENAME": "Sales"
    }
  ]
}
```

## Legacy Generic Proxy

A generic proxy still exists:
- `/api/tally/**`

Example:
- `GET /api/tally/reports/companies`

Frontend recommendation:
- Prefer the clean Spring endpoints documented above.
- Use `/api/tally/**` only for connector routes that have not yet been given a dedicated backend path.

## Quick Frontend Examples

Get companies:

```http
GET /api/reports/companies
```

Get groups:

```http
GET /api/groups?fetch=NAME&limit=20
```

Create group:

```http
POST /api/groups?action=Create
Content-Type: application/json

{
  "GROUP": [
    {
      "NAME": "UI Group",
      "PARENT": "Direct Expenses"
    }
  ]
}
```

Get payment vouchers:

```http
GET /api/vouchers/payment?from_date=20260401&to_date=20260430
```

Create ledger:

```http
POST /api/ledgers?action=Create
Content-Type: application/json

{
  "LEDGER": [
    {
      "NAME": "UI Ledger",
      "PARENT": "Sundry Debtors"
    }
  ]
}
```
