"""The HTTP listener: loopback only, two routes, nothing else.

`http.server` rather than a framework, and no third-party dependency beyond the
Anthropic SDK. This service faces one phone through nginx; a dependency tree is
a thing to keep patched, and there is nothing here that needs one. The tradeoff
is accepted with eyes open: it is bound to 127.0.0.1, nginx normalises every
request before it arrives, and the body size is capped twice.
"""

from __future__ import annotations

import json
import logging
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import store
from .config import Config
from .service import handle_chat

log = logging.getLogger("kiosk_broker")

# Read before the body is, so a lying Content-Length cannot make us allocate.
HARD_BODY_CEILING = 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    # Set by make_server.
    config: Config
    client: object
    db_path: str

    server_version = "kiosk-broker"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        # The default writes the request line to stderr. Harmless today, but it
        # is one refactor away from writing a query string, so it is replaced
        # rather than trusted; the real logging happens in the service.
        log.debug("http %s", fmt % args)

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/healthz":
            # Deliberately says nothing about tokens, spend or the model: it is
            # reachable from nginx and exists only to answer "is it up".
            self._send(200, {"status": "ok"})
            return
        self._send(404, {"error": {"code": "not_found", "message": "ไม่พบปลายทางนี้"}})

    def do_POST(self) -> None:
        if self.path != "/v1/chat":
            self._send(404, {"error": {"code": "not_found", "message": "ไม่พบปลายทางนี้"}})
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._send(400, {"error": {"code": "bad_request", "message": "รูปแบบคำขอไม่ถูกต้อง"}})
            return

        if length < 0 or length > HARD_BODY_CEILING:
            self._send(413, {"error": {"code": "payload_too_large",
                                       "message": "ข้อความยาวเกินกำหนด"}})
            return

        body = self.rfile.read(length) if length else b""

        conn = sqlite3.connect(self.db_path, timeout=10.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            status, payload = handle_chat(
                conn,
                self.config,
                self.client,
                authorization=self.headers.get("Authorization"),
                body=body,
            )
        except Exception:
            # Nothing from the traceback goes to the phone. It can carry the
            # prompt, and on a bad day an API key.
            log.exception("unhandled error")
            status, payload = 500, {"error": {"code": "internal",
                                              "message": "ระบบขัดข้องครับ ลองอีกครั้งนะ"}}
        finally:
            conn.close()

        self._send(status, payload)


def make_server(config: Config, client: object) -> ThreadingHTTPServer:
    store.connect(config.db_path).close()  # create/migrate once, up front

    handler = type("BoundHandler", (Handler,), {
        "config": config,
        "client": client,
        "db_path": str(config.db_path),
    })
    return ThreadingHTTPServer((config.host, config.port), handler)
