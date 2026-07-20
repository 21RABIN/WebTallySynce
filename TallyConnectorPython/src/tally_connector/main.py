import asyncio
import logging
import os
import sys

import uvicorn
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from .announce import continuous_announce
from .routes import router
from .config import get_env_file, get_settings
from .auth import clear_token_claims, set_token_claims
from .auth import verify_token
from .sync import continuous_sync
from .agent import continuous_agent

app = FastAPI(title="Tally Connector (Python)", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=".*",
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Authorization", "Set-Cookie"],
)
app.include_router(router)

log = logging.getLogger(__name__)


@app.middleware("http")
async def auth_context_middleware(request: Request, call_next):
    clear_token_claims()
    settings = get_settings()
    auth_header = request.headers.get("Authorization")
    if auth_header and auth_header.lower().startswith("bearer "):
        token = auth_header[7:].strip()
        if token:
            try:
                claims = verify_token(token, settings.auth_token_secret, expected_audience="tally-api")
                set_token_claims(claims)
            except ValueError as exc:
                set_token_claims(None, str(exc))
    response = await call_next(request)
    clear_token_claims()
    return response


@app.on_event("startup")
async def start_background_tasks():
    settings = get_settings()
    settings.validate_runtime_requirements()
    env_file = get_env_file()
    log.info(
        "connector startup env_file=%s env=%s listen=%s:%s auth=%s connector_id=%s company=%s",
        str(env_file) if env_file else "<defaults>",
        settings.normalized_app_env(),
        settings.listen_addr,
        settings.listen_port,
        settings.auth_summary(),
        settings.connector_id or "<auto>",
        settings.company or "<active-company>",
    )
    if settings.connector_announce_enabled and settings.server_base_url and settings.server_mode.lower() == "connector":
        log.info("starting connector announce task")
        asyncio.create_task(continuous_announce())
    if settings.connector_agent_enabled:
        log.info("starting connector agent task")
        asyncio.create_task(continuous_agent())
    if settings.sync_enabled:
        log.info("starting background sync task")
        asyncio.create_task(continuous_sync())


def _default_reload() -> bool:
    if getattr(sys, "frozen", False):
        return False
    return os.getenv("TALLY_CONNECTOR_RELOAD", "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def run(reload: bool | None = None):
    settings = get_settings()
    if reload is None:
        reload = _default_reload()
    uvicorn.run(
        app,
        host=settings.listen_addr,
        port=settings.listen_port,
        reload=reload,
    )


if __name__ == "__main__":
    run()
