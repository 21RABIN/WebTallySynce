# API Implementation Guide

This project already uses a simple proxy-controller pattern for most Tally APIs:

`Client -> Spring Boot backend -> Python connector -> Tally XML`

The `companies` controller is the cleanest example:
- [CompaniesApiController.java](/home/user/Videos/TALLY/Tally_BackUp/tallyconnector/tally-backend/src/main/java/com/tallybackend/web/CompaniesApiController.java:1)
- [AbstractConnectorController.java](/home/user/Videos/TALLY/Tally_BackUp/tallyconnector/tally-backend/src/main/java/com/tallybackend/web/AbstractConnectorController.java:1)

## Standard Pattern

### 1. Add or confirm the Python connector route

Example in `routes.py`:

```python
@router.get("/companies/list")
async def company_list(...):
    ...
```

The connector route is the source of truth for:
- request handling
- XML building
- Tally integration
- response shaping

### 2. Expose the backend proxy in the same style as `companies`

If the connector route is `GET /companies/list`, the backend proxy becomes:

```java
@GetMapping("/list")
public ResponseEntity<String> listCompanies(HttpServletRequest request) {
    return get("/companies/list", request);
}
```

If the connector route is `POST /companies/create`, the backend proxy becomes:

```java
@PostMapping("/create")
public ResponseEntity<String> createCompany(HttpServletRequest request,
                                            @RequestBody(required = false) String body) {
    return post("/companies/create", request, body);
}
```

### 3. Put the proxy in the right controller

Use the existing controllers by resource area:
- `CompaniesApiController`
- `SettingsApiController`
- `ReportsApiController`
- `VouchersApiController`
- `PayrollApiController`
- `MasterDataApiController`

Create a new controller only when the route family does not fit any existing resource area.

### 4. Reuse `AbstractConnectorController`

All proxy methods should delegate through:
- `get(...)`
- `post(...)`
- `put(...)`
- `patch(...)`
- `delete(...)`

This keeps request forwarding logic centralized in:
- [AbstractConnectorController.java](/home/user/Videos/TALLY/Tally_BackUp/tallyconnector/tally-backend/src/main/java/com/tallybackend/web/AbstractConnectorController.java:1)
- [ConnectorGatewayService.java](/home/user/Videos/TALLY/Tally_BackUp/tallyconnector/tally-backend/src/main/java/com/tallybackend/service/ConnectorGatewayService.java:1)

## Implementation Checklist

When adding a new API, follow this order:

1. Add the Python connector route in `routes.py`
2. Add or update XML builders in `xml_builder.py` if required
3. Verify the route directly on the connector
4. Add the backend proxy method in the matching controller
5. Verify the backend Swagger path
6. Run the route parity audit script

## Route Parity Audit

To verify that every connector route has a backend proxy:

```bash
cd /home/user/Videos/TALLY/Tally_BackUp/tallyconnector
python3 audit_connector_backend_parity.py
```

JSON output:

```bash
python3 audit_connector_backend_parity.py --json
```

This compares:
- Python connector routes in `TallyConnectorPython/src/tally_connector/routes.py`
- Java backend controller mappings in `tally-backend/src/main/java/com/tallybackend/web`

## Current Status

At the time of writing, the parity audit reports:
- `Missing backend proxies: 0`

That means the backend already exposes the current connector route surface through explicit proxy controllers.

## Practical Rule

If you want a new API to look like the `companies` APIs, keep it:
- explicit
- one method per route
- same resource grouping
- forwarded through `AbstractConnectorController`

That is the project’s current stable pattern.
