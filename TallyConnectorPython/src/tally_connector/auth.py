import base64
import contextvars
import hashlib
import hmac
import json
import secrets
import time
from typing import Any, Dict, Optional


_token_claims_ctx: contextvars.ContextVar[Optional[Dict[str, Any]]] = contextvars.ContextVar(
    "token_claims", default=None
)
_token_error_ctx: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "token_error", default=None
)


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode((value + padding).encode("ascii"))


def issue_token(
    secret: str,
    subject: str,
    issuer: str,
    audience: str,
    ttl_sec: int,
    extra_claims: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    issued_at = int(time.time())
    payload: Dict[str, Any] = {
        "sub": subject,
        "iss": issuer,
        "aud": audience,
        "iat": issued_at,
        "exp": issued_at + max(int(ttl_sec), 1),
        "jti": secrets.token_hex(12),
    }
    for key, value in (extra_claims or {}).items():
        if value is not None:
            payload[key] = value

    header = {"alg": "HS256", "typ": "TALLYTOKEN"}
    header_b64 = _b64url_encode(json.dumps(header, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    payload_b64 = _b64url_encode(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    token = f"{header_b64}.{payload_b64}.{_b64url_encode(signature)}"
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": payload["exp"] - issued_at,
        "issued_at": issued_at,
        "claims": payload,
    }


def verify_token(token: str, secret: str, expected_audience: Optional[str] = None) -> Dict[str, Any]:
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("Malformed token")

    header_b64, payload_b64, signature_b64 = parts
    signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
    expected_sig = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    received_sig = _b64url_decode(signature_b64)
    if not hmac.compare_digest(expected_sig, received_sig):
        raise ValueError("Invalid token signature")

    payload = json.loads(_b64url_decode(payload_b64).decode("utf-8"))
    now = int(time.time())
    exp = int(payload.get("exp", 0))
    if exp and now >= exp:
        raise ValueError("Token expired")
    if expected_audience and payload.get("aud") not in {expected_audience, "shared"}:
        raise ValueError("Invalid token audience")
    return payload


def set_token_claims(claims: Optional[Dict[str, Any]], error: Optional[str] = None) -> None:
    _token_claims_ctx.set(claims)
    _token_error_ctx.set(error)


def clear_token_claims() -> None:
    _token_claims_ctx.set(None)
    _token_error_ctx.set(None)


def get_token_claims() -> Optional[Dict[str, Any]]:
    return _token_claims_ctx.get()


def get_token_error() -> Optional[str]:
    return _token_error_ctx.get()
