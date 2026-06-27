#!/usr/bin/env python3
"""
Shared mock Supabase backend for cross-device pairing tests.

Mirrors the wire shapes of the static fixtures in
`app/src/main/assets/mock-supabase/` but keeps mutable state in memory so two
Android devices (phone + emulator) running against this server can actually
exchange pairing codes and see each other in the parent's device list.

Endpoints implemented:
  POST /auth/v1/token                                 -> anon auth
  POST /functions/v1/create-pairing-code              -> parent generates code
  POST /functions/v1/pairing                          -> child consumes code
  GET  /functions/v1/get-devices-for-parent           -> parent lists devices
  GET  /functions/v1/get-templates                    -> template list (empty)
  GET  /rest/v1/time_requests                         -> pending requests (empty)
  POST /rest/v1/time_requests                         -> create time request

Unknown paths return 404 with `{"error":"unknown route <path>"}`.

Run:
  python3 tools/shared-mock-server/server.py [--port 8787] [--host 0.0.0.0]

Then on each Android device (with USE_SHARED_MOCK=true and
SHARED_MOCK_URL=http://<host-ip>:8787 baked into the APK):
  adb -s <serial> reverse tcp:8787 tcp:8787

The phone reaches the server via the reverse-tunnel; the emulator can reach
the host directly via 10.0.2.2:8787 or via reverse-tunnel as well.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import http.server
import json
import secrets
import string
import threading
from http import HTTPStatus
from urllib.parse import urlparse

# ---------------------------------------------------------------------------
# In-memory state — process-wide singleton protected by a lock.
# ---------------------------------------------------------------------------

_lock = threading.Lock()

# Fixed "parent" identity. In a real Supabase setup this would be the
# authenticated user's UUID. We keep it constant so any device that finishes
# the anon auth handshake ends up as the same logical parent.
PARENT_ID = "00000000-0000-0000-0000-000000000001"

# Pending pairing codes, keyed by the 8-char code (uppercase). Value carries
# the parent_id and the device_name the parent typed in the "Emparejar
# dispositivo" sheet — so when the child consumes the code we know what to
# show in the parent's device list.
_pairing_codes: dict[str, dict] = {}

# Registered child devices. Each entry matches the wire shape returned by
# `get-devices-for-parent` (see `assets/mock-supabase/devices.json`).
_devices: list[dict] = []

# Pending time requests from children (the "Pedir tiempo" flow).
_time_requests: list[dict] = []


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _now_iso() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _generate_code(length: int = 8) -> str:
    """8-char code using only unambiguous uppercase + digits — matches the
    input field on the child side which only accepts `[A-Z0-9]`."""
    alphabet = string.ascii_uppercase + string.digits
    # Avoid the confusable chars O/0/I/1 for human-readability — the static
    # fixture `ABCDEFGH` doesn't, but for new codes we filter for sanity.
    alphabet = alphabet.translate(str.maketrans("", "", "OI"))
    return "".join(secrets.choice(alphabet) for _ in range(length))


def _read_json_body(handler: http.server.BaseHTTPRequestHandler) -> dict:
    length = int(handler.headers.get("Content-Length", "0") or 0)
    if length <= 0:
        return {}
    raw = handler.rfile.read(length).decode("utf-8")
    if not raw.strip():
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def _send_json(
    handler: http.server.BaseHTTPRequestHandler,
    status: HTTPStatus,
    body: dict | list,
) -> None:
    payload = json.dumps(body).encode("utf-8")
    handler.send_response(int(status))
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(payload)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(payload)


# ---------------------------------------------------------------------------
# Request handlers
# ---------------------------------------------------------------------------


def handle_auth_token(handler: http.server.BaseHTTPRequestHandler, _body: dict) -> None:
    """`POST /auth/v1/token` — anonymous auth. Returns a fake bearer token
    and a user whose id matches our fixed PARENT_ID so subsequent calls
    (which carry `Authorization: Bearer …`) can be correlated server-side."""
    token = "anon-" + secrets.token_urlsafe(16)
    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "access_token": token,
            "refresh_token": "refresh-" + secrets.token_urlsafe(8),
            "expires_in": 3600,
            "expires_at": int(_dt.datetime.now().timestamp()) + 3600,
            "user": {
                "id": PARENT_ID,
                "email": "anonymous@placeholder.local",
                "app_metadata": {"device_id": "shared-mock-" + secrets.token_hex(4)},
            },
        },
    )


def handle_create_pairing_code(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /functions/v1/create-pairing-code` — parent generates an 8-char
    code. Body: `{"device_name":"…","age_band":"…","ttl_minutes":N}`."""
    device_name = (body.get("device_name") or "Sin nombre").strip()
    age_band = body.get("age_band") or "7-12"
    ttl_minutes = int(body.get("ttl_minutes") or 10)

    code = _generate_code(8)
    with _lock:
        _pairing_codes[code] = {
            "parent_id": PARENT_ID,
            "device_name": device_name,
            "age_band": age_band,
            "created_at": _now_iso(),
            "ttl_minutes": ttl_minutes,
        }

    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "code": code,
            "expires_at": _dt.datetime.now(_dt.timezone.utc).isoformat(),
            "deeplink": f"parentalcontrol://pair?code={code}",
        },
    )


def handle_pairing(handler: http.server.BaseHTTPRequestHandler, body: dict) -> None:
    """`POST /functions/v1/pairing` — child consumes a code. Body:
    `{"code":"ABCDEFGH","device_name":"…","device_model":"…",
       "os_version":"…","app_version":"…"}`.

    On success returns `{device_id, parent_id}` and registers the child
    device in `_devices` so the parent will see it on next
    `get-devices-for-parent` call."""
    code = (body.get("code") or "").strip().upper()
    child_device_name = (body.get("device_name") or "Hijo").strip()
    device_model = body.get("device_model") or "unknown"
    os_version = str(body.get("os_version") or "0")
    app_version = body.get("app_version") or "1.0.0"

    with _lock:
        entry = _pairing_codes.pop(code, None)
        if entry is None:
            _send_json(
                handler,
                HTTPStatus.NOT_FOUND,
                {"error": f"unknown or already-consumed code '{code}'"},
            )
            return

        # Use the device_name the parent typed at code-creation time (the
        # parent's intent) — the child doesn't necessarily know its own name
        # yet at this point in the flow.
        device_id = "device-child-" + secrets.token_hex(4)
        _devices.append(
            {
                "id": device_id,
                "device_name": entry["device_name"],
                "device_model": device_model,
                "os_version": os_version,
                "app_version": app_version,
                "device_state": "ACTIVE",
                "policy_version": 1,
                "last_seen_at": _now_iso(),
                "parent_id": entry["parent_id"],
                "_child_reported_name": child_device_name,
            }
        )

    _send_json(
        handler,
        HTTPStatus.OK,
        {"device_id": device_id, "parent_id": entry["parent_id"]},
    )


def handle_get_devices(handler: http.server.BaseHTTPRequestHandler) -> None:
    """`GET /functions/v1/get-devices-for-parent` — returns the list of
    devices paired with the parent. We strip the internal
    `_child_reported_name` field before sending (the real edge function
    doesn't return it)."""
    with _lock:
        public = []
        for d in _devices:
            public.append({k: v for k, v in d.items() if not k.startswith("_")})
    _send_json(handler, HTTPStatus.OK, public)


def handle_get_templates(handler: http.server.BaseHTTPRequestHandler) -> None:
    """Empty template list — the app handles `[]` fine."""
    _send_json(handler, HTTPStatus.OK, [])


def handle_get_time_requests(handler: http.server.BaseHTTPRequestHandler) -> None:
    """`GET /rest/v1/time_requests` — pending requests. We don't populate
    this in the pairing test, so it's always empty."""
    with _lock:
        _send_json(handler, HTTPStatus.OK, list(_time_requests))


def handle_post_time_requests(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /rest/v1/time_requests` — child asks for more time. Echoes the
    inserted row back."""
    row = {
        "id": "req-" + secrets.token_hex(4),
        "device_id": body.get("device_id") or "unknown",
        "minutes": int(body.get("minutes") or 0),
        "reason": body.get("reason"),
        "status": "PENDING",
        "created_at": _now_iso(),
    }
    with _lock:
        _time_requests.append(row)
    _send_json(handler, HTTPStatus.CREATED, [row])


# ---------------------------------------------------------------------------
# HTTP server glue
# ---------------------------------------------------------------------------


class _Handler(http.server.BaseHTTPRequestHandler):
    # Silence the default access log; we print our own one-liners below.
    def log_message(self, _format: str, *_args: object) -> None:  # noqa: A003
        return

    def _route(self, method: str) -> None:
        path = urlparse(self.path).path
        body = _read_json_body(self) if method == "POST" else {}

        with _lock:
            n_codes = len(_pairing_codes)
            n_devices = len(_devices)
        print(
            f"[{_now_iso()}] {method} {path}  "
            f"(codes={n_codes}, devices={n_devices})",
            flush=True,
        )

        if method == "POST" and path.endswith("/auth/v1/token"):
            handle_auth_token(self, body)
        elif method == "POST" and path.endswith("/functions/v1/create-pairing-code"):
            handle_create_pairing_code(self, body)
        elif method == "POST" and path.endswith("/functions/v1/pairing"):
            handle_pairing(self, body)
        # Read endpoints accept both GET (HTTP-spec-correct) and POST
        # (which the current Android client uses — see
        # `ParentRepository.getDevices` calling `httpClient.post(...)`).
        # The real Supabase edge function accepts both, so we mirror that.
        elif path.endswith("/functions/v1/get-devices-for-parent") and method in ("GET", "POST"):
            handle_get_devices(self)
        elif (
            path.endswith("/functions/v1/get-templates")
            or path.endswith("/rest/v1/templates")
        ) and method in ("GET", "POST"):
            handle_get_templates(self)
        elif path.startswith("/rest/v1/time_requests") and method == "GET":
            handle_get_time_requests(self)
        elif path.startswith("/rest/v1/time_requests") and method == "POST":
            handle_post_time_requests(self, body)
        else:
            _send_json(
                self,
                HTTPStatus.NOT_FOUND,
                {"error": f"unknown route {method} {path}"},
            )

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        self._route("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._route("POST")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--host", default="0.0.0.0", help="bind host (default 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8787, help="bind port (default 8787)")
    args = parser.parse_args()

    server = http.server.ThreadingHTTPServer((args.host, args.port), _Handler)
    print(
        f"shared-mock-server listening on http://{args.host}:{args.port}\n"
        f"  parent_id (fixed) = {PARENT_ID}\n"
        f"  state: 0 codes, 0 devices",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down", flush=True)
        server.server_close()


if __name__ == "__main__":
    main()