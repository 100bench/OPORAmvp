#!/usr/bin/env python3
"""Tiny localhost-only OpenAI gateway for USB Android development.

The phone reaches this process through `adb reverse`. The OpenAI credential
never leaves the Mac and is never included in the APK.
"""

from __future__ import annotations

import http.client
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HOST = "127.0.0.1"
PORT = int(os.environ.get("OPORA_PROXY_PORT", "8787"))
UPSTREAM_HOST = "api.openai.com"
PROJECT_ROOT = Path(__file__).resolve().parents[1]


def load_local_property(name: str) -> str:
    path = PROJECT_ROOT / "local.properties"
    if not path.exists():
        return ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == name:
            return value.strip()
    return ""


def api_key() -> str:
    return os.environ.get("OPENAI_API_KEY", "").strip() or load_local_property(
        "openai.api.key"
    )


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "OporaDevProxy/1.0"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path == "/health":
            self._json(200, {"ok": True, "key_configured": bool(api_key())})
            return
        self._forward()

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._forward()

    def _forward(self) -> None:
        started = time.monotonic()
        if not self.path.startswith("/v1/"):
            self._json(404, {"error": "Only /v1/* is proxied"})
            return

        key = api_key()
        if not key:
            self._json(
                503,
                {"error": "Set OPENAI_API_KEY or openai.api.key in local.properties"},
            )
            return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else None
        headers = {
            "Authorization": f"Bearer {key}",
            "Accept": self.headers.get("Accept", "*/*"),
            "User-Agent": "opora-android-dev-proxy/1.0",
        }
        content_type = self.headers.get("Content-Type")
        if content_type:
            headers["Content-Type"] = content_type

        connection = http.client.HTTPSConnection(UPSTREAM_HOST, timeout=20)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            upstream = connection.getresponse()
            response_body = upstream.read()
            self.send_response(upstream.status)
            for name in ("Content-Type", "OpenAI-Request-ID", "X-Request-ID"):
                value = upstream.getheader(name)
                if value:
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(response_body)
            elapsed_ms = int((time.monotonic() - started) * 1000)
            print(f"{self.command} {self.path} -> {upstream.status} ({elapsed_ms} ms)", flush=True)
        except Exception as exc:
            elapsed_ms = int((time.monotonic() - started) * 1000)
            print(
                f"{self.command} {self.path} -> gateway error "
                f"({type(exc).__name__}, {elapsed_ms} ms)",
                flush=True,
            )
            self._json(502, {"error": "OpenAI gateway request failed"})
        finally:
            connection.close()

    def _json(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        # Avoid BaseHTTPRequestHandler's duplicate per-request log lines.
        return


def main() -> None:
    if not api_key():
        raise SystemExit(
            "OpenAI key not found. Set OPENAI_API_KEY or openai.api.key "
            "in local.properties."
        )
    server = ThreadingHTTPServer((HOST, PORT), ProxyHandler)
    print(f"Opora OpenAI proxy: http://{HOST}:{PORT}", flush=True)
    print(f"Android USB route: adb reverse tcp:{PORT} tcp:{PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
