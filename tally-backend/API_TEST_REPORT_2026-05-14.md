# API Test Report - 2026-05-14

Base URL: `http://127.0.0.1:9090`

This report combines:
- live backend testing run on `2026-05-14`
- live safe write checks done against the current local Tally setup
- verified payload patterns from the backend/controller code
- previously saved connector audit evidence in `tallyconnector/TallyConnectorPython/endpoint_results_audit/`

## 1. Login / Auth

### Working login payload

```json
{
  "username": "admin",
  "password": "admin@123",
  "include_token": true
}
```

### Live response

```json
{
  "message": "authenticated",
  "token_type": "Bearer",
  "expires_in": 3600,
  "user": {
    "username": "admin",
    "displayName": "Administrator",
    "roles": ["ADMIN"]
  },
  "access_token": "<token when include_token=true>"
}
```

Use header:

```http
Authorization: Bearer <token>
```

## 2. Live Sweep Summary

OpenAPI `GET` routes found: `171`

Live results:
- `200`: `110`
- `404`: `5`
- `422`: `2`
- `500`: `2`
- `501`: `52`

### 200 OK examples confirmed live

- `GET /api/health`
- `GET /api/health/readiness`
- `GET /api/health/capabilities`
- `GET /api/health/implementation`
- `GET /api/auth/users`
- `GET /api/connectors`
- `GET /api/companies`
- `GET /api/companies/list`
- `GET /api/currencies`
- `GET /api/groups`
- `GET /api/ledgers`
- `GET /api/stock-items`
- `GET /api/price-lists`
- `GET /api/vouchers/sales`
- `GET /api/reports/companies`
- `GET /api/reports/day-book`
- `GET /api/settings/company-features`
- `GET /api/settings/einvoice`
- `GET /api/xml/access/check`
- `GET /api/xml/trace/status`
- `GET /api/routing/debug`

### 422 routes

- `GET /api/reports/ledger-vouchers`
  - needs required filters like `from_date`, `to_date`, and usually a ledger selector
- `GET /api/xml/trace/file`
  - needs a file identifier/query param

### 500 routes

- `GET /api/reports/gstr-3b`
- `GET /api/tally/xml-preview/product/{productId}`

### 501 Not Implemented groups

- payroll exports:
  - `/api/attendance-types`
  - `/api/employee-groups`
  - `/api/employees`
- reports:
  - `/api/reports/bank-book`
  - `/api/reports/cash-book`
  - `/api/reports/form-24q`
  - `/api/reports/form-26q`
  - `/api/reports/form-27eq`
  - `/api/reports/gstr-1`
  - `/api/reports/gstr-2`
  - `/api/reports/stock-ageing-analysis`
  - `/api/reports/tds-outstandings`
- settings:
  - `/api/settings/security-roles`
- most XML preview routes under:
  - `/api/tally/xml-preview/*`

### 404 cases

- generic proxy placeholders:
  - `/api/tally`
  - `/api/tally/`
  - `/api/tally/**`
- test IDs not found:
  - `/api/tally-mappings/ledgers/1`
  - `/api/tally-sync/logs/1`

## 3. Safe Write APIs Tested Live

These were executed today with temporary data and succeeded.

### A. `POST /api/groups?action=Create`

Request body used:

```json
{
  "NAME": "API_TEST_GROUP_<timestamp>",
  "PARENT": "Direct Expenses"
}
```

Live response:

```json
{
  "RESPONSE": {
    "CREATED": "1",
    "ALTERED": "0",
    "DELETED": "0",
    "ERRORS": "0",
    "EXCEPTIONS": "0"
  }
}
```

### B. `POST /api/groups?action=Delete`

Request body used:

```json
{
  "NAME": "API_TEST_GROUP_<timestamp>"
}
```

Live response:

```json
{
  "RESPONSE": {
    "CREATED": "0",
    "ALTERED": "0",
    "DELETED": "1",
    "ERRORS": "0",
    "EXCEPTIONS": "0"
  }
}
```

### C. `POST /api/auth/users`

Request body used:

```json
{
  "username": "apitest_<timestamp>",
  "password": "Test@123",
  "displayName": "API Test User",
  "roles": ["ADMIN"],
  "active": true
}
```

Live response:

```json
{
  "username": "apitest_<timestamp>",
  "displayName": "API Test User",
  "roles": ["ADMIN"],
  "active": true
}
```

### D. `DELETE /api/auth/users/{username}`

Live response:

```json
{
  "message": "user_deactivated",
  "username": "apitest_<timestamp>"
}
```

### E. `POST /api/connectors`

Request body used:

```json
{
  "connectorId": "apitest-connector-<timestamp>",
  "baseUrl": "http://127.0.0.1:8082",
  "agentKey": "local-dev-key",
  "company": "API Test Company",
  "branch": "API",
  "description": "temp",
  "active": true
}
```

Live response:

```json
{
  "message": "Connector registered successfully",
  "connector": {
    "connectorId": "apitest-connector-<timestamp>",
    "active": true
  }
}
```

### F. `POST /api/routing/connector`

Request body used:

```json
{
  "connectorId": "apitest-connector-<timestamp>"
}
```

Live response:

```json
{
  "message": "connector_selected"
}
```

### G. `DELETE /api/routing/connector`

Live response:

```json
{
  "message": "connector_selection_cleared"
}
```

### H. `POST /api/connectors/{connectorId}/deactivate`

Live response:

```json
{
  "message": "Connector deactivated",
  "connector": {
    "connectorId": "apitest-connector-<timestamp>",
    "active": false
  }
}
```

## 4. Correct Payloads By API Family

## Health APIs

Routes:
- `GET /api/health`
- `GET /api/health/readiness`
- `GET /api/health/capabilities`
- `GET /api/health/implementation`

Request payload:
- none

Sample response:

```json
{
  "version": "0.1.0",
  "status": "ok"
}
```

## Auth APIs

### `POST /api/auth/token`

```json
{
  "username": "admin",
  "password": "admin@123",
  "include_token": true
}
```

### `POST /api/auth/logout`

Payload:
- none required

### `GET /api/auth/users`

Payload:
- none

### `POST /api/auth/users`

```json
{
  "username": "newuser",
  "password": "Test@123",
  "displayName": "New User",
  "roles": ["ADMIN"],
  "active": true
}
```

### `DELETE /api/auth/users/{username}`

Payload:
- none

## Connector Routing APIs

### `GET /api/connectors`
### `GET /api/connectors/{connectorId}`

Payload:
- none

### `POST /api/connectors`

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

### `POST /api/connectors/{connectorId}/deactivate`

Payload:
- none

### `GET /api/routing/debug`

Payload:
- none

### `POST /api/routing/connector`

Any one of these is accepted:

```json
{
  "connectorId": "branch-chennai"
}
```

```json
{
  "connector_id": "branch-chennai"
}
```

```json
{
  "company": "Ridsys Chennai"
}
```

```json
{
  "branch": "Chennai"
}
```

### `DELETE /api/routing/connector`

Payload:
- none

## Company APIs

### `GET /api/companies`
### `GET /api/companies/list`

Payload:
- none

### `POST /api/companies`

Generic company write wrapper:

```json
{
  "COMPANY": [
    {
      "NAME": "New Company Name"
    }
  ]
}
```

### `POST /api/companies/open`

Use:

```json
{
  "NAME": "Ridsys"
}
```

or:

```json
{
  "companyName": "Ridsys"
}
```

### `POST /api/companies/update`
### `POST /api/companies/alter`

Use Tally company fields, for example:

```json
{
  "NAME": "Ridsys",
  "EMAIL": "info@ridsys.com",
  "PINCODE": "560001",
  "STATENAME": "Maharashtra"
}
```

### `POST /api/companies/create`

Use a full company payload. Minimum validated fields should include:

```json
{
  "NAME": "Acme Pvt Ltd",
  "BOOKSFROM": "20260401",
  "FINANCIALYEARFROM": "20260401",
  "STARTINGFROM": "20260401",
  "COUNTRYNAME": "India",
  "STATENAME": "Tamil Nadu"
}
```

Note:
- this route is not safe to mass-test automatically against a live machine

## Master Data APIs

These routes share the same pattern:

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

Supported query params:
- `action=Create|Alter|Delete`
- `fetch=NAME`
- `filter=...`
- `limit=20`
- `view=summary|full|raw`

### Accepted body style

Bare object:

```json
{
  "NAME": "Frontend Test Group",
  "PARENT": "Direct Expenses"
}
```

Wrapped object:

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

### Correct examples

#### Groups

```json
{
  "NAME": "Frontend Test Group",
  "PARENT": "Direct Expenses"
}
```

#### Ledgers

```json
{
  "NAME": "Frontend Test Ledger",
  "PARENT": "Sundry Debtors"
}
```

#### Stock Items

```json
{
  "NAME": "Frontend Test Item",
  "PARENT": "Primary",
  "BASEUNITS": "Nos"
}
```

#### UOM

```json
{
  "NAME": "Box",
  "ORIGINALNAME": "Box",
  "ISSIMPLEUNIT": "Yes"
}
```

#### Budgets

```json
{
  "NAME": "FY26 Budget"
}
```

### Typical success response for create / alter / delete

```json
{
  "RESPONSE": {
    "CREATED": "1",
    "ALTERED": "0",
    "DELETED": "0",
    "ERRORS": "0"
  }
}
```

## Payroll Master APIs

Routes:
- `/api/employees`
- `/api/employee-groups`
- `/api/pay-heads`
- `/api/attendance-types`

Current live GET status:
- these returned `501` today in this environment

Validated payload rules from connector code:

### Employee

```json
{
  "NAME": "LC Employee",
  "PARENT": "Staff Group",
  "EMAIL": "a@example.com"
}
```

Normalized internally to payroll-style fields like `EMAILID`, `USEASEMPLOYEE`, `FORPAYROLL`.

### Employee Group

```json
{
  "NAME": "LC Group"
}
```

### Pay Head

```json
{
  "NAME": "LC Test Pay Head",
  "PARENT": "Indirect Expenses"
}
```

### Attendance Type

```json
{
  "NAME": "LC Attendance"
}
```

## Reports APIs

All report routes are `GET`.

Common params:
- `from_date=20260401`
- `to_date=20260430`
- `limit=50`
- `fetch=NAME`
- `view=summary|full|raw`
- `filter=...`

Examples:

```http
GET /api/reports/day-book?from_date=20260401&to_date=20260430
GET /api/reports/companies
GET /api/reports/ledger-vouchers?from_date=20260401&to_date=20260430
```

Live day-book response shape:

```json
{
  "TALLYMESSAGE": [
    {
      "VOUCHER": {
        "VCHTYPE": "Payment"
      }
    }
  ]
}
```

## Settings APIs

Routes include:
- `/api/settings/company-features`
- `/api/settings/gst-registration`
- `/api/settings/company-currency`
- `/api/settings/numbering-rules`
- `/api/settings/tax-rate-tables`
- `/api/settings/price-structures`
- `/api/settings/stock-controls`
- `/api/settings/security-roles`
- `/api/settings/uqc-mappings`
- `/api/settings/einvoice`
- `/api/settings/ewaybill`

GET payload:
- none

POST payload:
- Tally-field-based, depends on the exact setting

Simple example:

```json
{
  "enabled": true
}
```

Better real-world example for GST/company fields:

```json
{
  "ISGSTON": "Yes",
  "COUNTRYNAME": "India",
  "STATENAME": "Tamil Nadu"
}
```

Live response example from `GET /api/settings/company-features`:

```json
{
  "COMPANY": {
    "NAME": "Software Development",
    "BOOKSFROM": "20260401",
    "ISINVENTORYON": "Yes",
    "ISGSTON": "Yes"
  }
}
```

## Voucher APIs

Routes:
- `GET/POST /api/vouchers`
- all typed voucher routes under `/api/vouchers/...`

Supported query params:
- `action=Create|Alter|Cancel|Delete`
- `from_date=20260401`
- `to_date=20260430`
- `limit=50`
- `fetch=...`
- `view=summary|full|raw`

### Generic accepted body

Bare voucher object:

```json
{
  "DATE": "20260407",
  "VOUCHERTYPENAME": "Payment",
  "VOUCHERNUMBER": "101",
  "NARRATION": "Payment from frontend",
  "PARTYLEDGERNAME": "Cash"
}
```

Wrapped voucher object:

```json
{
  "VOUCHER": [
    {
      "DATE": "20260407",
      "VOUCHERTYPENAME": "Payment",
      "VOUCHERNUMBER": "101",
      "PARTYLEDGERNAME": "Cash"
    }
  ]
}
```

### Sales voucher example

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

Note:
- voucher writes were not mass-executed in this run because they create real accounting entries in the live company

## E-Invoice / E-Way Bill APIs

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

Accepted body pattern:

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

## XML APIs

### `POST /api/xml/execute`

Accepted content types:
- `application/xml`
- `text/xml`
- `text/plain`
- `application/json`

XML request example:

```xml
<ENVELOPE>
  <HEADER>
    <TALLYREQUEST>Export</TALLYREQUEST>
  </HEADER>
</ENVELOPE>
```

### `GET /api/xml/access/check`
### `GET /api/xml/access/steps`
### `GET /api/xml/access/samples`
### `GET /api/xml/trace/status`
### `GET /api/xml/trace/files`
### `GET /api/xml/trace/file`

Request payload:
- none

For `/api/xml/trace/file`, pass the expected query parameter or filename reference.

## 5. Useful Existing Evidence Files

- Backend API guide:
  - `tallyconnector/tally-backend/BACKEND_API.md`
- Connector audit summary:
  - `tallyconnector/TallyConnectorPython/endpoint_results_audit_report.md`
- Saved live responses:
  - `tallyconnector/TallyConnectorPython/endpoint_results_audit/`

## 6. Practical Conclusion

What is working well right now:
- auth
- health
- connector registry/routing
- core master GETs
- many reports
- group create/delete write flow

What still needs attention:
- payroll export routes
- security roles
- GSTR 1/2/3B consistency
- form 24Q / 26Q / 27EQ
- many XML preview routes
- generic `/api/tally` placeholder paths shown in Swagger

## 7. Post-Hardening Status Update

After the later connector hardening pass in this repo, several previously unstable connector-backed APIs now have clearer and safer behavior even when the underlying Tally build still lacks a real native/TDL implementation.

### Stabilized connector behaviors

- `POST /api/companies/open`
  - no longer reports success just because a request was submitted
  - now verifies whether the requested company actually became active
- `POST /api/companies/create`
  - no longer treats no-effect create acknowledgements as success
  - now attempts company-list verification after create
- `GET/POST /api/settings/security-roles`
  - now rejects scaffold/placeholder TDL responses
  - no longer pretends generic experimental security-level writes are safe
- payroll APIs:
  - `GET /api/employees`
  - `GET /api/employee-groups`
  - `GET /api/pay-heads`
  - `GET /api/attendance-types`
  - now prefer TDL-backed payroll collections only when the feature is truly marked ready
  - now reject scaffold placeholder payroll exports instead of looking successful
- `GET /api/price-levels`
- `GET/POST /api/settings/price-structures`
  - now reject scaffold placeholder TDL responses for standalone price-level features
  - stable item-linked price list maintenance still remains `POST /api/price-lists`
- generic compliance routes:
  - `POST /api/vouchers/einvoice/generate`
  - `POST /api/vouchers/ewaybill/generate`
  - `POST /api/vouchers/einvoice-ewaybill/generate`
  - `POST /api/vouchers/einvoice-ready`
  - `POST /api/vouchers/ewaybill-ready`
  - `POST /api/vouchers/einvoice-ewaybill-ready`
  - now return a clearer unsupported contract for non-implemented voucher types, including `implemented_voucher_types`, `documented_voucher_types`, and `use_instead`

### Backend parity status

Connector/backend route parity was re-checked after fixing the audit script parser for Spring annotations with `value = ...` and `consumes = ...`.

Current parity result:
- connector routes audited: `185`
- backend connector-surface routes audited: `185`
- missing backend proxies: `0`
- backend-only connector-surface routes: `0`

### Validation completed

The stabilization work above was covered by local unit tests in:
- `tallyconnector/TallyConnectorPython/tests/test_routes_helpers.py`
- `tallyconnector/tests/test_audit_connector_backend_parity.py`

Latest verification used during this hardening pass:

```bash
python3 -m unittest discover -s tests -p 'test_routes_helpers.py'
python3 -m unittest discover -s tests -p 'test_audit_connector_backend_parity.py'
python3 audit_connector_backend_parity.py --json
```

### Important remaining limitation

This hardening pass makes the APIs more predictable and honest, but it does not magically create missing Tally functionality.

The following areas can still remain limited by the exact Tally build, payroll/company feature enablement, or missing real TDL implementations:
- statutory reports such as GSTR/24Q/26Q/27EQ/TDS where XML/report exposure is build-dependent
- standalone security-role and standalone company price-level management until real TDL logic is implemented
- any route that still depends on scaffold-only TDL packages rather than verified live TDL collections/reports
