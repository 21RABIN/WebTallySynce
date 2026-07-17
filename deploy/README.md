# Production Deployment Guide

This project has 3 runtime parts:

1. `tally-ui` - Angular frontend
2. `tally-backend` - Spring Boot backend
3. `TallyConnectorPython` - Python connector that talks to Tally

Recommended production architecture for `crm.ridsys.in`:

1. Deploy `tally-ui` + `tally-backend` + MySQL on the Linux live server.
2. Run `TallyConnectorPython` on the machine that can actually reach TallyPrime.
3. Point the backend to that connector with `INGEST_BASE_URL`.

Important:

- If TallyPrime is not running on `crm.ridsys.in`, do not deploy the connector there and expect live Tally sync to work.
- The connector should usually run on the Tally machine, or on a host in the same LAN/VPN with access to `http://127.0.0.1:9000` on the Tally host.

## Files Added

- `deploy/docker-compose.crm-ridsys.yml`
- `deploy/nginx.crm.ridsys.in.conf`
- `deploy/backend.env.example`
- `deploy/mysql.env.example`
- `deploy/tally-connector.env.example`
- `deploy/tally-connector.service.example`

## Recommended Server Layout

On the Linux live server:

- Nginx
- Docker + Docker Compose plugin
- This repo checked out under `/opt/tallyconnector`

On the Tally machine:

- Python 3.11+
- `TallyConnectorPython`
- TallyPrime running and XML/HTTP enabled on port `9000`

## Step 1: Prepare the Live Server

Install packages on Ubuntu/Debian:

```bash
sudo apt update
sudo apt install -y nginx docker.io docker-compose-plugin certbot python3-certbot-nginx
sudo systemctl enable --now docker nginx
```

Clone the project:

```bash
sudo mkdir -p /opt/tallyconnector
sudo chown -R $USER:$USER /opt/tallyconnector
cd /opt/tallyconnector
git clone <your-repo-url> .
```

## Step 2: Create Production Environment Files

Create the backend env file:

```bash
cp deploy/backend.env.example deploy/backend.env
```

Edit `deploy/backend.env` and set:

- `INGEST_BASE_URL`
- `INGEST_AGENT_KEY`
- `AUTH_TOKEN_SECRET`
- `SPRING_DATASOURCE_PASSWORD`
- `AUTH_BOOTSTRAP_PASSWORD`

Create the MySQL env file:

```bash
cp deploy/mysql.env.example deploy/mysql.env
```

Edit `deploy/mysql.env` and set a strong `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD`.

## Step 3: Start Backend + UI + MySQL

From repo root:

```bash
docker compose -f deploy/docker-compose.crm-ridsys.yml --env-file deploy/backend.env up -d --build
```

Check status:

```bash
docker compose -f deploy/docker-compose.crm-ridsys.yml ps
docker compose -f deploy/docker-compose.crm-ridsys.yml logs -f backend
docker compose -f deploy/docker-compose.crm-ridsys.yml logs -f ui
```

Expected ports:

- UI container: `4200`
- Backend container: `9090`
- MySQL container: `3306` inside Docker network

## Step 4: Configure Nginx for crm.ridsys.in

Copy the Nginx config:

```bash
sudo cp deploy/nginx.crm.ridsys.in.conf /etc/nginx/sites-available/crm.ridsys.in
sudo ln -s /etc/nginx/sites-available/crm.ridsys.in /etc/nginx/sites-enabled/crm.ridsys.in
sudo nginx -t
sudo systemctl reload nginx
```

Make sure DNS for `crm.ridsys.in` points to this server first.

## Step 5: Add SSL

Run Certbot:

```bash
sudo certbot --nginx -d crm.ridsys.in
```

## Step 6: Deploy the Python Connector on the Tally Machine

On the machine that has access to TallyPrime:

```bash
cd /path/to/project/TallyConnectorPython
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp ../deploy/tally-connector.env.example .env
```

Edit `.env` and set:

- `TALLY_BASE_URL=http://127.0.0.1:9000`
- `AGENT_KEY` to match backend `INGEST_AGENT_KEY`
- `AUTH_CLIENT_SECRET`
- `AUTH_TOKEN_SECRET`
- `LISTEN_ADDR=0.0.0.0`
- `LISTEN_PORT=8082`

Start manually first:

```bash
PYTHONPATH=src ./.venv/bin/uvicorn tally_connector.main:app --host 0.0.0.0 --port 8082
```

Health check:

```bash
curl http://127.0.0.1:8082/health
```

If the connector is on another machine, confirm the backend server can reach it:

```bash
curl http://<connector-host-or-ip>:8082/health
```

## Step 7: Run Connector as a Service

Copy the service file:

```bash
sudo cp deploy/tally-connector.service.example /etc/systemd/system/tally-connector.service
```

Edit these values in the service file:

- `User`
- `Group`
- `WorkingDirectory`
- `EnvironmentFile`
- `ExecStart`

Then enable it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tally-connector
sudo systemctl status tally-connector
```

## Step 8: Verify End-to-End

From the live server:

```bash
curl http://127.0.0.1:9090/api/health
curl http://127.0.0.1:9090/api/cache/status
curl http://127.0.0.1:4200
```

From a browser:

- `https://crm.ridsys.in`

## Step 9: Login

Default bootstrap user comes from backend env:

- username: `admin`
- password: value of `AUTH_BOOTSTRAP_PASSWORD`

Change this immediately after first login.

## Deployment Commands

Rebuild after code changes:

```bash
cd /opt/tallyconnector
git pull
docker compose -f deploy/docker-compose.crm-ridsys.yml --env-file deploy/backend.env up -d --build
```

View logs:

```bash
docker compose -f deploy/docker-compose.crm-ridsys.yml logs -f
```

Stop stack:

```bash
docker compose -f deploy/docker-compose.crm-ridsys.yml down
```

## Common Problems

### UI opens but API fails

Check:

- backend container is up
- Nginx `/api/` route is present
- backend env secrets are set

### Backend works but Tally data does not sync

Check:

- `INGEST_BASE_URL` points to the real connector host
- connector is running
- TallyPrime is open
- Tally XML/HTTP is enabled on port `9000`

### Connector health is OK but readiness is degraded

This usually means:

- connector process is alive
- but Tally itself is unreachable or the company is not open

### MySQL connection error

Check that:

- `SPRING_DATASOURCE_URL` points to `mysql:3306`
- backend and mysql passwords match

## Notes

- This deployment file set assumes Docker on the Linux server.
- The connector service file is intentionally separate because Tally access is the deciding factor.
- If you want, the next step can be a second deployment pack for "everything on one Windows machine with Tally installed".
