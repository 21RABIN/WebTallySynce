import asyncio
import logging
import socket
from typing import Any, Dict, List, Optional

import httpx

from .config import get_settings

log = logging.getLogger(__name__)


def _connector_id() -> str:
    settings = get_settings()
    for value in (settings.connector_id, settings.branch, settings.company, socket.gethostname()):
        text = (value or "").strip()
        if text:
            return text
    return "connector"


def _server_url(path: str) -> str:
    settings = get_settings()
    return f"{str(settings.server_base_url).rstrip('/')}{path}"


def _local_url(path: str, query_string: Optional[str] = None) -> str:
    settings = get_settings()
    normalized = path if path.startswith("/") else f"/{path}"
    url = f"http://127.0.0.1:{settings.listen_port}{normalized}"
    if query_string:
        separator = "&" if "?" in url else "?"
        url = f"{url}{separator}{query_string}"
    return url


def _server_headers() -> Dict[str, str]:
    settings = get_settings()
    headers = {"Accept": "application/json"}
    if settings.server_agent_key:
        headers["X-AGENT-KEY"] = settings.server_agent_key
    return headers


def _local_headers(company: Optional[str], content_type: Optional[str]) -> Dict[str, str]:
    settings = get_settings()
    headers = {"Accept": "application/json", "X-AGENT-KEY": settings.agent_key}
    if company:
        headers["X-Company"] = company
    if content_type:
        headers["Content-Type"] = content_type
    else:
        headers["Content-Type"] = "application/json"
    return headers


def _registration_payload() -> Dict[str, Any]:
    settings = get_settings()
    return {
        "connector_id": _connector_id(),
        "company": settings.company or None,
        "branch": settings.branch or None,
        "hostname": socket.gethostname(),
        "version": "0.1.0",
        "mode": "outbound-agent",
        "capabilities": ["jobs", "snapshots", "tally-local-write"],
        "description": f"Outbound connector agent from {socket.gethostname()}",
    }


async def register_once(client: httpx.AsyncClient) -> bool:
    settings = get_settings()
    if not settings.server_base_url:
        return False
    try:
        response = await client.post(
            _server_url("/api/connectors/register"),
            json=_registration_payload(),
            headers=_server_headers(),
        )
        response.raise_for_status()
        log.info("connector agent registered connector_id=%s", _connector_id())
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("connector agent register failed err=%s", exc)
        return False


async def heartbeat_once(client: httpx.AsyncClient) -> bool:
    try:
        response = await client.post(
            _server_url("/api/connectors/heartbeat"),
            json=_registration_payload(),
            headers=_server_headers(),
        )
        response.raise_for_status()
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("connector agent heartbeat failed err=%s", exc)
        return False


async def fetch_jobs(client: httpx.AsyncClient) -> List[Dict[str, Any]]:
    settings = get_settings()
    params = {
        "connector_id": _connector_id(),
        "limit": max(settings.connector_agent_job_batch_size, 1),
        "lease_seconds": max(settings.connector_agent_job_lease_sec, 30),
    }
    if settings.company:
        params["company"] = settings.company
    response = await client.get(_server_url("/api/connector/jobs"), params=params, headers=_server_headers())
    response.raise_for_status()
    body = response.json()
    jobs = body.get("jobs", [])
    return jobs if isinstance(jobs, list) else []


async def execute_job(client: httpx.AsyncClient, job: Dict[str, Any]) -> Dict[str, Any]:
    method = str(job.get("http_method") or "POST").upper()
    path = str(job.get("connector_path") or "")
    query_string = job.get("query_string")
    company = job.get("company") or get_settings().company or None
    content_type = job.get("content_type") or "application/json"
    body = job.get("request_body")
    response = await client.request(
        method,
        _local_url(path, str(query_string) if query_string else None),
        content=body if body is not None else None,
        headers=_local_headers(str(company) if company else None, str(content_type) if content_type else None),
    )
    response_text = response.text
    result: Dict[str, Any] = {
        "connector_id": _connector_id(),
        "status": "APPLIED" if 200 <= response.status_code < 300 else "RETRY",
        "http_status": response.status_code,
        "response_body": response_text,
    }
    if response.status_code >= 400:
        result["error"] = f"Local connector returned HTTP {response.status_code}"
    return result


async def report_job_result(client: httpx.AsyncClient, queue_id: Any, result: Dict[str, Any]) -> None:
    response = await client.post(
        _server_url(f"/api/connector/jobs/{queue_id}/result"),
        json=result,
        headers=_server_headers(),
    )
    response.raise_for_status()


async def process_jobs_once(client: httpx.AsyncClient) -> int:
    jobs = await fetch_jobs(client)
    processed = 0
    for job in jobs:
        queue_id = job.get("queue_id")
        if queue_id is None:
            continue
        try:
            result = await execute_job(client, job)
        except Exception as exc:  # noqa: BLE001
            result = {
                "connector_id": _connector_id(),
                "status": "RETRY",
                "error": str(exc),
                "retry_after_ms": 30000,
            }
        try:
            await report_job_result(client, queue_id, result)
            processed += 1
        except Exception as exc:  # noqa: BLE001
            log.warning("connector agent result report failed queue_id=%s err=%s", queue_id, exc)
    if processed:
        log.info("connector agent processed jobs=%s", processed)
    return processed


async def upload_snapshot(client: httpx.AsyncClient, dataset_key: str, local_path: str) -> None:
    settings = get_settings()
    params: Dict[str, Any] = {"view": "summary"}
    if settings.company:
        params["company"] = settings.company
    response = await client.get(
        _local_url(local_path),
        params=params,
        headers=_local_headers(settings.company or None, None),
    )
    response.raise_for_status()
    data = response.json()
    rows: Any = data
    if isinstance(data, dict):
        for key in ("VOUCHER", "LEDGER", "GROUP", "STOCKITEM", "COMPANY"):
            if isinstance(data.get(key), list):
                rows = data[key]
                break
    payload = {
        "connector_id": _connector_id(),
        "company": settings.company or None,
        "dataset_key": dataset_key,
        "snapshot_key": f"{dataset_key}:{settings.company or _connector_id()}",
        "data": data,
        "rows": rows if isinstance(rows, list) else [rows],
        "request_params": params,
    }
    upload_response = await client.post(
        _server_url("/api/connector/snapshots"),
        json=payload,
        headers=_server_headers(),
    )
    upload_response.raise_for_status()


async def upload_snapshots_once(client: httpx.AsyncClient) -> None:
    datasets = [
        ("companies", "/reports/companies"),
        ("groups", "/groups"),
        ("ledgers", "/ledgers"),
        ("stock_items", "/stock-items"),
        ("day_book", "/reports/day-book"),
    ]
    for dataset_key, path in datasets:
        try:
            await upload_snapshot(client, dataset_key, path)
        except Exception as exc:  # noqa: BLE001
            log.warning("connector agent snapshot upload failed dataset=%s err=%s", dataset_key, exc)


async def continuous_agent():
    settings = get_settings()
    if not settings.connector_agent_enabled:
        log.info("connector agent disabled; exiting")
        return
    if not settings.server_base_url:
        log.warning("connector agent enabled but SERVER_BASE_URL missing; exiting")
        return
    timeout = httpx.Timeout(60.0, connect=10.0)
    job_interval = max(settings.connector_agent_job_interval_sec, 2)
    snapshot_interval = max(settings.connector_agent_snapshot_interval_sec, 60)
    next_snapshot_in = 0
    async with httpx.AsyncClient(timeout=timeout) as client:
        await register_once(client)
        while True:
            await heartbeat_once(client)
            await process_jobs_once(client)
            if next_snapshot_in <= 0:
                await upload_snapshots_once(client)
                next_snapshot_in = snapshot_interval
            await asyncio.sleep(job_interval)
            next_snapshot_in -= job_interval
