# Local Connector TDL Scaffold

This folder is the repo-managed home for Tally-side customizations that the local connector may require when built-in XML is not enough for the current TallyPrime build.

Current connector readiness reports these TDL-backed areas as required or recommended:

- `company_open`
- `company_create`
- `security_roles`
- `price_levels`
- `payroll_exports`
- `tds_outstandings`

Expected workflow:

1. Implement or update the required TDL reports/functions in this folder.
2. Load the TDL package in the local TallyPrime instance.
3. Set connector env vars:
   - `TDL_INTEGRATION_ENABLED=true`
   - `TDL_PROFILE_NAME=<package-name>`
   - `TDL_PACKAGE_PATH=<path-to-local_connector_profile.tdl>` if you are not using the repo default path
   - `TDL_MANIFEST_PATH=<path-to-manifest.json>` if you are not using the repo default path
4. Mark each feature as `ready` in `manifest.json` only after the corresponding TDL collections/reports are live and verified.
5. Restart the connector and re-check:
   - `GET /health/readiness`
   - `GET /capabilities`

This scaffold is intentionally lightweight; the connector now exposes the runtime capability and readiness contract needed to validate a real TDL package once it is added.
