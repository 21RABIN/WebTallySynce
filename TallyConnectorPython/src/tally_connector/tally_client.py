import asyncio
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import httpx
from typing import Optional


def _auth_headers(agent_key: str | None) -> dict:
    return {"X-AGENT-KEY": agent_key} if agent_key else {}


_XML_LABEL_PATTERNS = (
    re.compile(r"<ID>\s*([^<]+?)\s*</ID>", re.IGNORECASE),
    re.compile(r"<REPORTNAME>\s*([^<]+?)\s*</REPORTNAME>", re.IGNORECASE),
    re.compile(r"<TYPE>\s*([^<]+?)\s*</TYPE>", re.IGNORECASE),
)


def trace_label_from_xml(xml: str) -> str:
    if not xml:
        return "tally-request"
    for pattern in _XML_LABEL_PATTERNS:
        match = pattern.search(xml)
        if match:
            label = (match.group(1) or "").strip()
            if label:
                return label
    return "tally-request"


def trace_slug(label: str) -> str:
    text = re.sub(r"[^a-zA-Z0-9]+", "-", (label or "").strip().lower()).strip("-")
    return text or "tally-request"


def write_trace_snapshot(
    trace_dir: Path,
    request_xml: str,
    *,
    response_text: Optional[str] = None,
    error_text: Optional[str] = None,
    status_code: Optional[int] = None,
) -> Path:
    trace_dir = Path(trace_dir).expanduser().resolve()
    trace_dir.mkdir(parents=True, exist_ok=True)
    label = trace_label_from_xml(request_xml)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    entry_dir = trace_dir / f"{timestamp}_{trace_slug(label)}"
    entry_dir.mkdir(parents=True, exist_ok=True)
    (entry_dir / "request.xml").write_text(request_xml or "", encoding="utf-8")
    if response_text is not None:
        (entry_dir / "response.xml").write_text(response_text, encoding="utf-8")
    if error_text is not None:
        (entry_dir / "error.txt").write_text(error_text, encoding="utf-8")
    metadata = {
        "label": label,
        "status_code": status_code,
        "has_response": response_text is not None,
        "has_error": error_text is not None,
        "created_utc": timestamp,
    }
    (entry_dir / "meta.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return entry_dir


def candidate_base_urls(base_url: str) -> list[str]:
    normalized = str(base_url or "").rstrip("/")
    if not normalized:
        return [normalized]
    parsed = urlsplit(normalized)
    hostname = parsed.hostname or ""
    scheme = parsed.scheme or "http"
    port = f":{parsed.port}" if parsed.port else ""
    netloc = parsed.netloc
    candidates = [normalized]

    def append_host(host: str):
        host_netloc = f"[{host}]{port}" if ":" in host and not host.startswith("[") else f"{host}{port}"
        candidate = urlunsplit((scheme, host_netloc, parsed.path, parsed.query, parsed.fragment)).rstrip("/")
        if candidate not in candidates:
            candidates.append(candidate)

    if hostname == "127.0.0.1":
        append_host("::1")
        append_host("localhost")
    elif hostname == "localhost":
        append_host("::1")
        append_host("127.0.0.1")
    return candidates


class TallyClient:
    """
    Minimal HTTP client for Tally's XML interface.
    """

    def __init__(
        self,
        base_url,
        timeout: float = 30.0,
        agent_key: Optional[str] = None,
        max_retries: int = 0,
        retry_backoff_sec: float = 0.0,
        trace_enabled: bool = False,
        trace_dir: Optional[Path] = None,
    ):
        self.base_url = str(base_url).rstrip("/")
        self.base_urls = candidate_base_urls(self.base_url)
        self.agent_key = agent_key or None
        self.timeout = timeout
        self.max_retries = max(int(max_retries), 0)
        self.retry_backoff_sec = max(float(retry_backoff_sec), 0.0)
        self.trace_enabled = bool(trace_enabled)
        self.trace_dir = Path(trace_dir).expanduser().resolve() if trace_dir else None
        self.http = self._build_http_client()

    def _build_http_client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            timeout=self.timeout,
            limits=httpx.Limits(max_keepalive_connections=0, max_connections=10),
        )

    async def _reset_http_client(self):
        await self.http.aclose()
        self.http = self._build_http_client()

    async def post_xml(self, xml: str) -> str:
        attempts = self.max_retries + 1
        last_exc: Optional[Exception] = None
        for attempt in range(1, attempts + 1):
            for candidate_url in self.base_urls:
                try:
                    resp = await self.http.post(
                        candidate_url,
                        content=xml.encode("utf-8"),
                        headers={
                            "Content-Type": "text/xml",
                            "Connection": "close",
                            **_auth_headers(self.agent_key),
                        },
                    )
                    resp.raise_for_status()
                    if self.trace_enabled and self.trace_dir:
                        write_trace_snapshot(
                            self.trace_dir,
                            xml,
                            response_text=resp.text,
                            status_code=resp.status_code,
                        )
                    return resp.text
                except httpx.HTTPStatusError as exc:
                    last_exc = exc
                    if self.trace_enabled and self.trace_dir:
                        response_text = exc.response.text if exc.response is not None else None
                        status_code = exc.response.status_code if exc.response is not None else None
                        write_trace_snapshot(
                            self.trace_dir,
                            xml,
                            response_text=response_text,
                            error_text=repr(exc),
                            status_code=status_code,
                        )
                    should_retry = exc.response is not None and exc.response.status_code >= 500 and attempt < attempts
                    if not should_retry:
                        raise
                    break
                except (httpx.TimeoutException, httpx.TransportError) as exc:
                    last_exc = exc
                    await self._reset_http_client()
                    if self.trace_enabled and self.trace_dir:
                        write_trace_snapshot(
                            self.trace_dir,
                            xml,
                            error_text=repr(exc),
                        )
                    if candidate_url == self.base_urls[-1] and attempt >= attempts:
                        raise
            if self.retry_backoff_sec > 0:
                await asyncio.sleep(self.retry_backoff_sec * attempt)
        if last_exc is not None:
            raise last_exc
        raise RuntimeError("Tally request failed without a captured exception")

    async def get_json(self, path: str, params: Optional[dict] = None) -> dict:
        resp = await self.http.get(
            f"{self.base_url}{path}",
            params=params or {},
            headers={"Accept": "application/json", **_auth_headers(self.agent_key)},
        )
        resp.raise_for_status()
        return resp.json()

    async def close(self):
        await self.http.aclose()
