# Angular Tally UI Implementation Plan

This guide maps the current Tally backend and connector APIs into a practical Angular frontend implementation plan.

## 1. Goal

Build an Angular UI that:

- authenticates against the backend
- calls Tally backend APIs from the Angular app
- displays master, report, health, and sync data
- supports create/update/delete flows where supported
- handles loading, errors, validation, auth, and empty states cleanly

Recommended runtime flow:

`Angular UI -> Spring backend (:9090) -> Tally connector (:8082) -> TallyPrime (:9000)`

Angular should call the backend, not the connector directly.

## 2. Suggested Angular App Structure

Create the app with standalone Angular structure:

```text
src/app/
  core/
    api/
      auth-api.service.ts
      health-api.service.ts
      masters-api.service.ts
      reports-api.service.ts
      settings-api.service.ts
      sync-api.service.ts
    auth/
      auth.service.ts
      auth.interceptor.ts
      auth.guard.ts
    config/
      app.config.ts
      api.config.ts
    models/
      auth.models.ts
      common.models.ts
      health.models.ts
      master.models.ts
      report.models.ts
      settings.models.ts
      sync.models.ts
  shared/
    components/
      app-shell/
      page-header/
      stat-card/
      data-table/
      confirm-dialog/
      api-error-banner/
      empty-state/
      loading-state/
      json-viewer/
      filter-bar/
      form-actions/
    pipes/
    utils/
  features/
    auth/
      login/
    dashboard/
    health/
    companies/
    groups/
    ledgers/
    stock-items/
    currencies/
    price-lists/
    reports/
      day-book/
      companies-report/
      ledger-vouchers/
    settings/
      company-features/
      gst-registration/
      company-currency/
    sync/
      xml-preview/
      sync-logs/
      manual-sync/
  app.routes.ts
  app.component.ts
```

## 3. Implementation Order

Build in this order.

### Step 1. Project bootstrap

Create Angular app:

```bash
ng new tally-ui --routing --style=scss
```

Install useful packages:

```bash
npm install @angular/material @angular/cdk
```

Optional:

```bash
npm install ngx-toastr
```

### Step 2. Global configuration

Create environment config:

```ts
export const environment = {
  production: false,
  apiBaseUrl: 'http://127.0.0.1:9090'
};
```

Create `api.config.ts`:

```ts
export const API_ENDPOINTS = {
  authToken: '/api/auth/token',
  health: '/api/health',
  readiness: '/api/health/readiness',
  capabilities: '/api/health/capabilities',
  companies: '/api/companies',
  groups: '/api/groups',
  ledgers: '/api/ledgers',
  currencies: '/api/currencies',
  stockItems: '/api/stock-items',
  priceLists: '/api/price-lists',
  companyCurrency: '/api/settings/company-currency',
  companyFeatures: '/api/settings/company-features',
  gstRegistration: '/api/settings/gst-registration',
  reportsDayBook: '/api/reports/day-book',
  reportsCompanies: '/api/reports/companies',
  reportsLedgerVouchers: '/api/reports/ledger-vouchers',
  syncLogs: '/api/tally-sync/logs',
  xmlPreviewBase: '/api/tally/xml-preview'
};
```

### Step 3. Auth flow

Implement login first because most backend routes are protected.

Backend login API:

- `POST /api/auth/token`

Request:

```json
{
  "username": "admin",
  "password": "admin@123",
  "include_token": true
}
```

Build:

- `LoginComponent`
- `AuthService`
- `AuthApiService`
- `AuthInterceptor`
- `AuthGuard`

Responsibilities:

- submit login form
- store bearer token in memory or local storage
- attach `Authorization: Bearer <token>` header
- redirect to dashboard after success

### Step 4. App shell and navigation

Create layout:

- left sidebar
- top header
- content outlet

Menu sections:

- Dashboard
- Health
- Masters
- Reports
- Settings
- Sync

Suggested routes:

```text
/login
/dashboard
/health
/masters/companies
/masters/groups
/masters/ledgers
/masters/currencies
/masters/stock-items
/masters/price-lists
/reports/day-book
/reports/companies
/reports/ledger-vouchers
/settings/company-currency
/settings/company-features
/settings/gst-registration
/sync/xml-preview
/sync/logs
```

### Step 5. Shared data-table component

Before building each page, create reusable UI blocks:

- `DataTableComponent`
- `FilterBarComponent`
- `LoadingStateComponent`
- `EmptyStateComponent`
- `ApiErrorBannerComponent`
- `ConfirmDialogComponent`

Table should support:

- column config
- pagination
- loading state
- empty state
- retry action
- row actions: view, edit, delete

### Step 6. Health dashboard

Implement first because it is easy and validates the app wiring.

APIs:

- `GET /api/health`
- `GET /api/health/readiness`
- `GET /api/health/capabilities`

UI:

- overall status cards
- Tally connectivity status
- GST service status
- TDL integration status
- warnings list
- capabilities summary

Use cards and badges:

- green = ok
- amber = warning
- red = error

### Step 7. Master screens

Build one generic pattern and reuse it.

#### 7.1 Companies

APIs:

- `GET /api/companies`
- `GET /api/companies/list`
- `POST /api/companies/update`
- `POST /api/companies/open`
- `POST /api/companies/create`

UI:

- company list table
- active/open company indicator
- open company action
- company create form
- company settings edit form

#### 7.2 Groups

APIs:

- `GET /api/groups`
- `POST /api/groups?action=Create`
- `POST /api/groups?action=Alter`
- `POST /api/groups?action=Delete`

UI:

- group list
- create dialog
- edit dialog
- delete confirmation

Form fields:

- `NAME`
- `PARENT`

#### 7.3 Ledgers

APIs:

- `GET /api/ledgers`
- `POST /api/ledgers`

UI:

- ledger table
- search by name
- create/edit form

Form fields:

- `NAME`
- `PARENT`
- `GSTREGISTRATIONTYPE`
- `PARTYGSTIN`
- bank fields if needed

#### 7.4 Currencies

API:

- `GET /api/currencies`

Important:

- current connector now normalizes the response
- raw Tally still returns placeholder fields in this build

UI:

- read-only grid for now
- show warning chip if row contains normalization warning

#### 7.5 Stock Items

APIs:

- `GET /api/stock-items`
- `POST /api/stock-items`

UI:

- stock item list
- create/edit form
- item details panel

#### 7.6 Price Lists

APIs:

- `GET /api/price-lists`
- `POST /api/price-lists`

UI:

- filter by stock group
- filter by price level
- item-level price row display

## 4. Reports Screens

### 4.1 Day Book

API:

- `GET /api/reports/day-book`

Filters:

- `from_date`
- `to_date`

UI:

- date filter bar
- voucher list table
- totals summary

### 4.2 Companies Report

API:

- `GET /api/reports/companies`

UI:

- simple read-only company summary report

### 4.3 Ledger Vouchers

API:

- `GET /api/reports/ledger-vouchers`

Required query params:

- `from_date`
- `to_date`
- ledger selector

UI:

- mandatory filter form
- do not auto-load without filters
- validation message before request

## 5. Settings Screens

### 5.1 Company Currency

APIs:

- `GET /api/settings/company-currency`
- `POST /api/settings/company-currency`

UI:

- display current company currency settings
- edit form

### 5.2 Company Features

APIs:

- `GET /api/settings/company-features`
- `POST /api/settings/company-features`

UI:

- grouped feature toggles
- save button

### 5.3 GST Registration

APIs:

- `GET /api/settings/gst-registration`
- `POST /api/settings/gst-registration`

UI:

- GST details form
- state, GSTIN, registration type

## 6. Sync Screens

### 6.1 XML Preview

API base:

- `/api/tally/xml-preview/*`

UI:

- entity selector
- ID input
- raw XML/code viewer

Suggested flow:

- choose type: customer, supplier, product, invoice
- enter record ID
- click preview
- render XML with monospace viewer

### 6.2 Sync Logs

API:

- `/api/tally-sync/logs`

UI:

- table of sync results
- filters by entity type and status
- details drawer

### 6.3 Manual Sync

Use backend sync APIs from the Java service layer.

Suggested UI:

- entity type dropdown
- record ID input
- force sync checkbox
- submit button
- result card

## 7. Angular Service Layer

Use one service per concern.

Example:

```ts
export class MastersApiService {
  constructor(private http: HttpClient) {}

  getGroups(params?: Record<string, string>) {
    return this.http.get('/api/groups', { params });
  }

  createGroup(payload: unknown) {
    return this.http.post('/api/groups?action=Create', payload);
  }

  updateGroup(payload: unknown) {
    return this.http.post('/api/groups?action=Alter', payload);
  }

  deleteGroup(payload: unknown) {
    return this.http.post('/api/groups?action=Delete', payload);
  }
}
```

## 8. UI State Handling

For each page maintain:

- `loading`
- `submitting`
- `data`
- `error`
- `filters`

Pattern:

1. show loading spinner
2. call API
3. map response for UI
4. show empty state if no rows
5. show inline error with retry on failure

## 9. Form Handling

Use Angular reactive forms.

Each master form should include:

- required validators
- backend error display
- reset/cancel buttons
- save and save-and-close behavior

Example group form:

```ts
this.form = this.fb.group({
  NAME: ['', Validators.required],
  PARENT: ['', Validators.required]
});
```

## 10. Error Handling Rules

Handle these status codes:

- `401`: token expired or missing
- `403`: logged in but not allowed
- `404`: missing record
- `422`: validation error or required filters missing
- `500`: backend issue
- `501`: route exists but not implemented

UI behavior:

- `401`: redirect to login
- `422`: show form/filter validation message
- `501`: show "Available in backend contract, not implemented yet"

## 11. Backend Reality You Must Reflect In UI

Not all routes are production-clean yet.

UI should mark these as:

- `Implemented`
- `Experimental`
- `Read-only`
- `Not ready`

Examples:

- `/currencies`: read-only and normalized workaround
- many `/api/tally/xml-preview/*`: partial or not ready
- some statutory reports: not ready

Do not present every backend route as fully supported in the UI.

## 12. Step-by-Step Delivery Plan

### Phase 1

- Angular bootstrap
- login
- app shell
- health dashboard

### Phase 2

- groups UI
- ledgers UI
- stock items UI
- currencies read-only UI

### Phase 3

- reports day-book
- reports companies
- ledger vouchers with filters

### Phase 4

- settings company currency
- company features
- GST registration

### Phase 5

- XML preview tool
- sync logs
- manual sync UI

### Phase 6

- polish
- route guards
- table reuse
- form dialogs
- notifications

## 13. Recommended First 5 Screens

If you want fastest value, build these first:

1. Login
2. Dashboard
3. Health
4. Groups
5. Ledgers

That gives you:

- authentication
- API connectivity
- read/write validation
- reusable table and form patterns

## 14. Recommended Visual Design Direction

Use a clean operations dashboard style:

- warm off-white background
- dark slate text
- teal for positive states
- amber for warning states
- red for failures
- strong section headers
- compact tables
- right-side edit drawers instead of too many modal popups

Typography:

- headings with a distinctive sans family
- body with a neutral readable sans

## 15. Next Build Step

If implementation starts now, the first coding task should be:

`Scaffold Angular app + login page + auth interceptor + health dashboard`

After that, use the same service/component pattern for `groups`, `ledgers`, and `stock-items`.
