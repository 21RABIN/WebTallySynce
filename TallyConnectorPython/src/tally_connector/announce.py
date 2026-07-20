import asyncio
import logging
import re
import socket
from contextlib import suppress
from urllib.parse import urlparse

import httpx

from .config import get_settings

log = logging.getLogger(__name__)


_SLUG_RE = re.compile(r"[^a-z0-9]+")


def _slugify(value: str) -> str:
    text = (value or "").strip().lower()
    text = _SLUG_RE.sub("-", text).strip("-")
    return text


def _local_ip_for_target(target_url: str) -> str:
    parsed = urlparse(target_url)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    with suppress(OSError):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(1.5)
            sock.connect((host, port))
            return sock.getsockname()[0]
    return "127.0.0.1"


def _preferred_identity() -> str:
    settings = get_settings()
    for candidate in (settings.connector_id, settings.branch, settings.company, socket.gethostname()):
        text = _slugify(candidate)
        if text:
            return text
    return "connector"


def _connector_base_url() -> str:
    settings = get_settings()
    local_ip = _local_ip_for_target(str(settings.server_base_url))
    return f"http://{local_ip}:{settings.listen_port}"


def _build_payload() -> dict[str, object]:
    settings = get_settings()
    connector_id = _preferred_identity()
    return {
        "connectorId": connector_id,
        "baseUrl": _connector_base_url(),
        "agentKey": settings.agent_key,
        "company": settings.company or None,
        "branch": settings.branch or None,
        "description": f"Auto-announced connector from {socket.gethostname()}",
        "active": True,
    }


async def announce_once() -> bool:
    settings = get_settings()
    if not settings.connector_announce_enabled:
        return False
    if not settings.server_base_url:
        log.info("connector announce skipped: SERVER_BASE_URL missing")
        return False
    if settings.server_mode.lower() != "connector":
        log.info("connector announce skipped: SERVER_MODE=%s", settings.server_mode)
        return False

    announce_url = f"{str(settings.server_base_url).rstrip('/')}/api/connectors/register"
    payload = _build_payload()
    headers = {}
    if settings.server_agent_key:
        headers["X-AGENT-KEY"] = settings.server_agent_key

    timeout = httpx.Timeout(15.0, connect=5.0)
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(announce_url, json=payload, headers=headers)
            response.raise_for_status()
            try:
                body = response.json()
            except ValueError:
                body = response.text
            log.info("connector announce ok url=%s connector_id=%s body=%s", announce_url, payload.get("connectorId"), body)
            return True
    except Exception as exc:  # noqa: BLE001
        log.warning("connector announce failed url=%s connector_id=%s err=%s", announce_url, payload.get("connectorId"), exc)
        return False


async def continuous_announce():
    settings = get_settings()
    if not settings.connector_announce_enabled:
        log.info("connector announce disabled; exiting")
        return
    if not settings.server_base_url:
        log.warning("connector announce enabled but SERVER_BASE_URL missing; exiting")
        return
    if settings.server_mode.lower() != "connector":
        log.info("connector announce skipped because SERVER_MODE=%s", settings.server_mode)
        return

    interval = max(int(settings.connector_announce_interval_sec or 60), 5)
    log.info("starting connector announce loop interval=%ss", interval)
    while True:
        await announce_once()
        await asyncio.sleep(interval)
