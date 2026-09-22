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
from .service import handle_chat, handle_stt, handle_tts

log = logging.getLogger("kiosk_broker")

# Read before the body is, so a lying Content-Length cannot make us allocate.
HARD_BODY_CEILING = 1024 * 1024

#: How much of an over-sized body to read and throw away before answering.
#: Answering without consuming the request body poisons a keep-alive connection:
#: the proxy is still writing when the response arrives, and nginx turns that
#: into a 502 for the phone instead of passing on the broker's Thai error. Found
#: by putting a real nginx in front of this and sending 1.1 MB of audio.
#: Bounded, so nobody can make the broker read forever.
MAX_DRAIN_BYTES = 8 * 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    # Set by make_server.
    config: Config
    client: object
    stt_client: object
    tts_api_key: str
    botnoi_token: str
    db_path: str

    server_version = "kiosk-broker"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        # The default writes the request line to stderr. Harmless today, but it
        # is one refactor away from writing a query string, so it is replaced
        # rather than trusted; the real logging happens in the service.
        log.debug("http %s", fmt % args)

    def _drain(self, remaining: int) -> bool:
        """Reads and discards a request body we are about to refuse.

        Returns False when the body is too large to be worth draining, in which
        case the caller must close the connection rather than leave it
        half-read.
        """
        if remaining > MAX_DRAIN_BYTES:
            return False
        while remaining > 0:
            chunk = self.rfile.read(min(65536, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
        return True

    def _refuse_oversized(self, length: int) -> None:
        """413, with the connection left in a state nginx can use."""
        if not self._drain(length):
            self.close_connection = True
        self._send(413, {"error": {"code": "payload_too_large",
                                   "message": "ข้อความยาวเกินกำหนด"}})

    def _send(self, status: int, payload: dict) -> None:
        """JSON, unless the handler produced audio.

        /v1/tts answers with the audio itself rather than base64 inside JSON:
        it saves a third of the bytes over the wire and saves the phone a
        decode step before it can start playing.
        """
        if "audio" in payload:
            body = payload["audio"]
            content_type = payload.get("content_type", "application/octet-stream")
        else:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            content_type = "application/json; charset=utf-8"

        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in (payload.get("headers") or {}).items():
            self.send_header(name, value)
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
        if self.path not in ("/v1/chat", "/v1/stt", "/v1/tts"):
            self._send(404, {"error": {"code": "not_found", "message": "ไม่พบปลายทางนี้"}})
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._send(400, {"error": {"code": "bad_request", "message": "รูปแบบคำขอไม่ถูกต้อง"}})
            return

        # Deliberately well above the per-route caps in the handler, and above
        # what nginx lets through, so an over-long recording is refused by the
        # HANDLER — which says "เสียงยาวเกินไปครับ ลองถามสั้นลงนะ" — rather than
        # here, which can only say "the body is too big". This is a backstop for
        # a request that did not come through nginx at all.
        ceiling = max(HARD_BODY_CEILING, self.config.max_audio_bytes * 2)
        if length < 0:
            self._send(400, {"error": {"code": "bad_request",
                                       "message": "รูปแบบคำขอไม่ถูกต้อง"}})
            return
        if length > ceiling:
            self._refuse_oversized(length)
            return

        body = self.rfile.read(length) if length else b""

        conn = sqlite3.connect(self.db_path, timeout=10.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            if self.path == "/v1/chat":
                status, payload = handle_chat(
                    conn, self.config, self.client,
                    authorization=self.headers.get("Authorization"), body=body,
                )
            elif self.path == "/v1/stt":
                status, payload = handle_stt(
                    conn, self.config, self.stt_client,
                    authorization=self.headers.get("Authorization"),
                    content_type=self.headers.get("Content-Type"), body=body,
                )
            else:
                status, payload = handle_tts(
                    conn, self.config, self.tts_api_key,
                    authorization=self.headers.get("Authorization"), body=body,
                    botnoi_token=self.botnoi_token,
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


def make_server(config: Config, client: object, stt_client: object = None,
                tts_api_key: str = "", botnoi_token: str = "") -> ThreadingHTTPServer:
    store.connect(config.db_path).close()  # create/migrate once, up front

    handler = type("BoundHandler", (Handler,), {
        "config": config,
        "client": client,
        "stt_client": stt_client,
        "tts_api_key": tts_api_key,
        "botnoi_token": botnoi_token,
        "db_path": str(config.db_path),
    })
    return ThreadingHTTPServer((config.host, config.port), handler)
