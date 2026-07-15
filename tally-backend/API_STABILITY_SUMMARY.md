# API Stability Summary

Date: `2026-05-14`

This summary reflects the current connector/backend status after the recent API hardening work in this repo.

## 1. Current Route Status

Connector route inventory summary:

- `implemented`: `162`
- `partial`: `21`
- `tally_build_dependent`: `3`
- `tdl_or_experimental`: `4`

Backend parity summary:

- connector routes audited: `185`
- backend connector-surface routes audited: `185`
- missing backend proxies: `0`
- backend-only connector-surface routes: `0`

## 2. Stabilized Areas

These areas were hardened so they now behave more predictably and return clearer errors instead of misleading success responses.

### Companies

- `POST /api/companies/open`
  - verifies whether the requested company actually became active
- `POST /api/companies/create`
  - rejects no-effect create acknowledgements
  - attempts company-list verification after create

### Security Roles

- `GET /api/settings/security-roles`
- `POST /api/settings/security-roles`
  - reject scaffold/placeholder TDL responses
  - block unsafe generic fallback write behavior

### Payroll

- `GET /api/employees`
- `GET /api/employee-groups`
- `GET /api/pay-heads`
- `GET /api/attendance-types`
  - prefer TDL-backed route collections only when `payroll_exports` is truly ready
  - reject scaffold payroll responses

### Price Levels / Price Structures

- `GET /api/price-levels`
- `GET /api/settings/price-structures`
- `POST /api/price-levels`
- `POST /api/settings/price-structures`
  - reject scaffold placeholder TDL output for standalone price-level support
  - keep item-linked `POST /api/price-lists` as the stable writable path

### Generic Compliance Routes

- `POST /api/vouchers/einvoice/generate`
- `POST /api/vouchers/ewaybill/generate`
- `POST /api/vouchers/einvoice-ewaybill/generate`
- `POST /api/vouchers/einvoice-ready`
- `POST /api/vouchers/ewaybill-ready`
- `POST /api/vouchers/einvoice-ewaybill-ready`
  - now return clearer unsupported contracts for non-implemented voucher types
  - include `implemented_voucher_types`, `documented_voucher_types`, and `use_instead`

### TDS Outstandings

- `GET /api/reports/tds-outstandings`
  - rejects scaffold/placeholder TDL responses
  - no longer treats placeholder TDL output like a real statutory report

## 3. Still Partial

These APIs are still marked `partial` because the remaining limitation is real, not just bad error handling.

### Generic Master Limitations

- `/api/companies`
  - generic create is intentionally blocked; use `/api/companies/create`
- `/api/currencies`
  - generic currency create is intentionally blocked

### Payroll

- `/api/employees`
- `/api/employee-groups`
- `/api/pay-heads`
- `/api/attendance-types`

Reason:
- success still depends on payroll being enabled in Tally
- some flows still need a real TDL-backed payroll implementation for full support

### Price Levels

- `/api/price-levels`
- `/api/settings/price-structures`

Reason:
- item-linked price-list rows work
- standalone company-level price-level naming still needs a real native/TDL implementation

### Statutory / TDL-Backed Reporting

- `/api/reports/tds-outstandings`

Reason:
- native XML support is build-dependent
- a real TDL report is still required when native XML is unavailable

### Generic E-Invoice / E-Way Bill

- `/api/vouchers/einvoice/generate`
- `/api/vouchers/ewaybill/generate`
- `/api/vouchers/einvoice-ewaybill/generate`
- `/api/vouchers/einvoice-ready`
- `/api/vouchers/ewaybill-ready`
- `/api/vouchers/einvoice-ewaybill-ready`

Reason:
- only `Sales` is truly implemented
- other voucher types are documented but not implemented in this connector build

## 4. TDL / Experimental Areas

These routes are still intentionally guarded because stable native behavior is not verified in the current Tally build.

- `/api/companies/open`
- `/api/companies/create`
- `/api/settings/security-roles`

Current meaning:
- the routes exist
- behavior is safer than before
- but long-term full support still depends on verified TDL/native implementation

## 5. Tally Build Dependent Areas

These are implemented connector routes whose success still depends heavily on whether the current Tally build exposes the report through XML:

- `/api/reports/form-24q`
- `/api/reports/form-26q`
- `/api/reports/form-27eq`

## 6. Practical Use Guidance

Use these confidently now:

- health APIs
- auth APIs
- connector registry/routing APIs
- core master GET APIs
- many report GET APIs
- `POST /api/price-lists` for item-linked price list maintenance
- `Sales`-specific e-invoice / e-way bill routes

Use these with caution:

- payroll master exports/writes
- standalone price-level APIs
- statutory report APIs
- generic voucher-type compliance routes outside `Sales`

Avoid assuming these are fully implemented just because the route exists:

- scaffold-only TDL-backed routes
- generic create flows intentionally blocked by the connector
- generic compliance routes for voucher types other than `Sales`

## 7. Verification Commands

Commands used during the hardening pass:

```bash
python3 -m unittest discover -s tests -p 'test_routes_helpers.py'
python3 -m unittest discover -s tests -p 'test_audit_connector_backend_parity.py'
python3 audit_connector_backend_parity.py --json
```

## 8. Main Conclusion

The API surface is now much more stable and honest than before:

- backend parity is complete
- misleading success responses were reduced
- scaffold TDL placeholder responses are now rejected in key unstable areas
- the remaining `partial` routes are mostly partial for real Tally/TDL reasons, not simple connector bugs
