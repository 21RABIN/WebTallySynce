import os
import sys
from functools import lru_cache
from pathlib import Path
from typing import List, Optional

from pydantic import AnyHttpUrl, Field
from pydantic_settings import BaseSettings


APP_NAME = "TallyConnectorPython"
DEFAULT_ENV_TEXT = """TALLY_BASE_URL=http://127.0.0.1:9000
COMPANY=
BRANCH=
CONNECTOR_ID=
AGENT_KEY=local-dev-key
AUTH_CLIENT_ID=connector-client
AUTH_CLIENT_SECRET=local-dev-key
AUTH_TOKEN_SECRET=local-dev-key
AUTH_TOKEN_TTL_SEC=3600
AUTH_ACCEPT_STATIC_KEY=true
LISTEN_ADDR=0.0.0.0
LISTEN_PORT=8082
GST_SERVICE_BASE_URL=http://127.0.0.1:8081/api/gst/mi
GST_SERVICE_TIMEOUT_SEC=60
CONNECTOR_ANNOUNCE_ENABLED=true
CONNECTOR_ANNOUNCE_INTERVAL_SEC=60
SYNC_ENABLED=false
SYNC_DIRECTION=both
SYNC_INTERVAL_SEC=60
SERVER_MODE=connector
EXPERIMENTAL_TALLY_COMPANY_OPEN_ENABLED=false
EXPERIMENTAL_TALLY_COMPANY_CREATE_ENABLED=false
EXPERIMENTAL_TALLY_SECURITY_ROLES_ENABLED=false
APP_ENV=development
STRICT_STARTUP_VALIDATION=false
TALLY_REQUEST_MAX_RETRIES=2
TALLY_REQUEST_BACKOFF_SEC=0.5
TALLY_XML_TRACE_ENABLED=false
TALLY_XML_TRACE_DIR=
GST_REQUEST_MAX_RETRIES=1
GST_REQUEST_BACKOFF_SEC=0.5
TDL_INTEGRATION_ENABLED=false
TDL_PROFILE_NAME=
TDL_PACKAGE_PATH=
TDL_MANIFEST_PATH=
TDL_REQUIRED_FEATURES=company_open,company_create,security_roles,price_levels,payroll_exports
# SERVER_BASE_URL=http://server-connector:8082
# SERVER_AGENT_KEY=prod-agent-key
"""


class Settings(BaseSettings):
    tally_base_url: AnyHttpUrl = Field("http://127.0.0.1:9000", env="TALLY_BASE_URL")
    company: str = Field("", env="COMPANY")
    branch: str = Field("", env="BRANCH")
    connector_id: str = Field("", env="CONNECTOR_ID")
    agent_key: str = Field("", env="AGENT_KEY")
    auth_client_id: str = Field("connector-client", env="AUTH_CLIENT_ID")
    auth_client_secret: str = Field("local-dev-key", env="AUTH_CLIENT_SECRET")
    auth_token_secret: str = Field("local-dev-key", env="AUTH_TOKEN_SECRET")
    auth_token_ttl_sec: int = Field(3600, env="AUTH_TOKEN_TTL_SEC")
    auth_accept_static_key: bool = Field(True, env="AUTH_ACCEPT_STATIC_KEY")
    listen_addr: str = Field("0.0.0.0", env="LISTEN_ADDR")
    listen_port: int = Field(8082, env="LISTEN_PORT")
    gst_service_base_url: AnyHttpUrl = Field("http://127.0.0.1:8081/api/gst/mi", env="GST_SERVICE_BASE_URL")
    gst_service_timeout_sec: float = Field(60.0, env="GST_SERVICE_TIMEOUT_SEC")
    connector_announce_enabled: bool = Field(True, env="CONNECTOR_ANNOUNCE_ENABLED")
    connector_announce_interval_sec: int = Field(60, env="CONNECTOR_ANNOUNCE_INTERVAL_SEC")
    # Optional remote server (for two-way sync)
    server_base_url: Optional[AnyHttpUrl] = Field(None, env="SERVER_BASE_URL")
    server_agent_key: str = Field("", env="SERVER_AGENT_KEY")
    sync_enabled: bool = Field(False, env="SYNC_ENABLED")
    sync_interval_sec: int = Field(60, env="SYNC_INTERVAL_SEC")
    sync_direction: str = Field("both", env="SYNC_DIRECTION")  # push|pull|both
    server_mode: str = Field("connector", env="SERVER_MODE")  # connector|tally
    experimental_tally_company_open_enabled: bool = Field(False, env="EXPERIMENTAL_TALLY_COMPANY_OPEN_ENABLED")
    experimental_tally_company_create_enabled: bool = Field(False, env="EXPERIMENTAL_TALLY_COMPANY_CREATE_ENABLED")
    experimental_tally_security_roles_enabled: bool = Field(False, env="EXPERIMENTAL_TALLY_SECURITY_ROLES_ENABLED")
    app_env: str = Field("development", env="APP_ENV")
    strict_startup_validation: bool = Field(False, env="STRICT_STARTUP_VALIDATION")
    tally_request_max_retries: int = Field(2, env="TALLY_REQUEST_MAX_RETRIES")
    tally_request_backoff_sec: float = Field(0.5, env="TALLY_REQUEST_BACKOFF_SEC")
    tally_xml_trace_enabled: bool = Field(False, env="TALLY_XML_TRACE_ENABLED")
    tally_xml_trace_dir: str = Field("", env="TALLY_XML_TRACE_DIR")
    gst_request_max_retries: int = Field(1, env="GST_REQUEST_MAX_RETRIES")
    gst_request_backoff_sec: float = Field(0.5, env="GST_REQUEST_BACKOFF_SEC")
    tdl_integration_enabled: bool = Field(False, env="TDL_INTEGRATION_ENABLED")
    tdl_profile_name: str = Field("", env="TDL_PROFILE_NAME")
    tdl_package_path: str = Field("", env="TDL_PACKAGE_PATH")
    tdl_manifest_path: str = Field("", env="TDL_MANIFEST_PATH")
    tdl_required_features: str = Field(
        "company_open,company_create,security_roles,price_levels,payroll_exports",
        env="TDL_REQUIRED_FEATURES",
    )

    class Config:
        env_file_encoding = "utf-8"

    def normalized_app_env(self) -> str:
        return (self.app_env or "development").strip().lower()

    def is_production_like(self) -> bool:
        return self.normalized_app_env() in {"prod", "production", "staging"}

    def parsed_tdl_required_features(self) -> List[str]:
        values = []
        for raw in (self.tdl_required_features or "").split(","):
            item = raw.strip()
            if item:
                values.append(item)
        return values

    def resolved_tdl_package_path(self) -> Path:
        configured = (self.tdl_package_path or "").strip()
        if configured:
            return Path(configured).expanduser().resolve()
        return Path(__file__).resolve().parents[2] / "tdl" / "local_connector_profile.tdl"

    def resolved_tdl_manifest_path(self) -> Path:
        configured = (self.tdl_manifest_path or "").strip()
        if configured:
            return Path(configured).expanduser().resolve()
        return Path(__file__).resolve().parents[2] / "tdl" / "manifest.json"

    def resolved_tally_xml_trace_dir(self) -> Path:
        configured = (self.tally_xml_trace_dir or "").strip()
        if configured:
            return Path(configured).expanduser().resolve()
        return Path(__file__).resolve().parents[2] / "xml-trace"

    def insecure_runtime_warnings(self) -> List[str]:
        warnings: List[str] = []
        tdl_package_path = self.resolved_tdl_package_path()
        tdl_manifest_path = self.resolved_tdl_manifest_path()
        if self.agent_key.strip() in {"", "local-dev-key"}:
            warnings.append("AGENT_KEY is unset or still using the local-dev-key default.")
        if self.auth_client_secret.strip() in {"", "local-dev-key"}:
            warnings.append("AUTH_CLIENT_SECRET is unset or still using the local-dev-key default.")
        if self.auth_token_secret.strip() in {"", "local-dev-key"}:
            warnings.append("AUTH_TOKEN_SECRET is unset or still using the local-dev-key default.")
        if self.auth_accept_static_key:
            warnings.append("AUTH_ACCEPT_STATIC_KEY is enabled; bearer token authentication is not enforced exclusively.")
        if not self.tdl_integration_enabled:
            warnings.append("TDL integration is disabled; TDL-backed connector APIs will remain unavailable.")
        elif not tdl_package_path.is_file():
            warnings.append(
                f"TDL integration is enabled, but the package file is missing at {tdl_package_path}."
            )
        elif not tdl_manifest_path.is_file():
            warnings.append(
                f"TDL integration is enabled, but the manifest file is missing at {tdl_manifest_path}."
            )
        return warnings

    def validate_runtime_requirements(self) -> None:
        if not self.strict_startup_validation and not self.is_production_like():
            return

        missing = []
        if self.agent_key.strip() in {"", "local-dev-key"}:
            missing.append("AGENT_KEY")
        if self.auth_client_secret.strip() in {"", "local-dev-key"}:
            missing.append("AUTH_CLIENT_SECRET")
        if self.auth_token_secret.strip() in {"", "local-dev-key"}:
            missing.append("AUTH_TOKEN_SECRET")
        if missing:
            raise ValueError(
                "Connector startup validation failed; configure non-default values for: "
                + ", ".join(sorted(missing))
            )
        if self.auth_accept_static_key:
            raise ValueError("Connector startup validation failed; AUTH_ACCEPT_STATIC_KEY must be false in strict mode.")
        if self.tdl_integration_enabled:
            package_path = self.resolved_tdl_package_path()
            manifest_path = self.resolved_tdl_manifest_path()
            if not package_path.is_file():
                raise ValueError(
                    "Connector startup validation failed; TDL_INTEGRATION_ENABLED is true but "
                    f"the package file is missing: {package_path}"
                )
            if not manifest_path.is_file():
                raise ValueError(
                    "Connector startup validation failed; TDL_INTEGRATION_ENABLED is true but "
                    f"the manifest file is missing: {manifest_path}"
                )


@lru_cache
def get_runtime_config_dir() -> Path:
    if sys.platform.startswith("win"):
        appdata = os.getenv("APPDATA")
        base = Path(appdata) if appdata else (Path.home() / "AppData" / "Roaming")
        return base / APP_NAME
    return Path.home() / ".config" / APP_NAME.lower()


def _candidate_env_templates() -> list[Path]:
    candidates = []
    if getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).resolve().parent
        candidates.extend([exe_dir / ".env", exe_dir / ".env.example"])
    project_root = Path(__file__).resolve().parents[2]
    candidates.extend([project_root / ".env", project_root / ".env.example"])
    return candidates


def get_packaged_env_text() -> str:
    for candidate in _candidate_env_templates():
        try:
            if candidate.is_file():
                text = candidate.read_text(encoding="utf-8").strip()
                if text:
                    return text + "\n"
        except OSError:
            continue
    return DEFAULT_ENV_TEXT


def ensure_runtime_env_file() -> Optional[Path]:
    if not getattr(sys, "frozen", False):
        return None
    env_file = get_runtime_config_dir() / ".env"
    env_file.parent.mkdir(parents=True, exist_ok=True)
    if not env_file.exists():
        env_file.write_text(get_packaged_env_text(), encoding="utf-8")
    return env_file


@lru_cache
def get_env_file() -> Optional[Path]:
    explicit = os.getenv("TALLY_CONNECTOR_ENV_FILE")
    candidates = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    if getattr(sys, "frozen", False):
        runtime_env = ensure_runtime_env_file()
        if runtime_env is not None:
            candidates.append(runtime_env)
        candidates.append(Path(sys.executable).resolve().parent / ".env")
    candidates.append(Path.cwd() / ".env")
    candidates.append(Path(__file__).resolve().parents[2] / ".env")

    seen = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        if resolved.is_file():
            return resolved
    return None


@lru_cache
def get_settings() -> Settings:
    env_file = get_env_file()
    if env_file is None:
        return Settings()
    return Settings(_env_file=str(env_file))
