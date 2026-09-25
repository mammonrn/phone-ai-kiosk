"""Qwen3-ASR-Flash (Alibaba Cloud Model Studio, Singapore), synchronous, for
the transcriber comparison (0.50.0, Poom 2026-09-24). Not the default:
groq-hints stays that until Poom chooses otherwise.

CHECKED ON ALIBABA CLOUD'S OWN PAGES on 2026-09-24 (links in INSTALL.md):
  * DashScope protocol, synchronous:
      POST {base}/services/aigc/multimodal-generation/generation
    with "Authorization: Bearer <key>". For Singapore the docs now give
    https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api/v1 and say the
    old domain dashscope-intl.aliyuncs.com "remains fully functional" — so
    that is the default here, and a workspace id (QWEN_WORKSPACE_ID) switches
    to the new one.
  * Body: {"model", "input": {"messages": [system?, user]}, "parameters":
    {"asr_options": {"language": "th", "enable_itn": false}}}; the user
    message's content is [{"audio": <URL, local path or base64 data URI>}].
    Thai "th" is in the language list.
  * Context: a SYSTEM message, "background text and entity glossaries".
    The context page (written for the Qwen-Audio ASR models) says it works
    "mainly through word-list matching", as a word list or a paragraph, and
    that "the total text length per turn ... must not exceed 400 characters".
    UNCLEAR whether that limit is qwen3-asr-flash's too; 400 is kept here.
  * Answer: output.choices[0].message.content[0].text; the billed duration is
    usage.seconds.
  * One file: up to 10 MB and 5 minutes. Rate limits for this call: NOT
    STATED on the pages read ("100 QPS" is written for local-file uploads).

THE KEY IS A HEADER, never a URL, so nothing that prints a request or an
error can carry it; every error below is built from a status code and
Alibaba's own error code word, `from None`. The transcript is returned and
never logged here. The audio goes to Alibaba's servers in Singapore and
comes back as text; nothing is written to disk.
"""

from __future__ import annotations

import base64
import json
import re
import urllib.error
import urllib.request

from .google_stt import wav_info
from .stt import SttError, Transcript
from .stt_hints import Hints

LEGACY_BASE = "https://dashscope-intl.aliyuncs.com/api/v1"
WORKSPACE_BASE = "https://{workspace}.ap-southeast-1.maas.aliyuncs.com/api/v1"
PATH = "/services/aigc/multimodal-generation/generation"

#: The snapshot Poom's free 36,000 seconds are on (Model Studio console,
#: 2026-09-24). Whether the plain "qwen3-asr-flash" alias draws on the same
#: quota is not said anywhere Poom or this module looked, so the snapshot is
#: named explicitly.
DEFAULT_MODEL = "qwen3-asr-flash-2026-02-10"

#: The context page's limit per turn (see the module notes).
MAX_CONTEXT_CHARS = 400

MAX_RESPONSE_BYTES = 256 * 1024

#: The error code Alibaba answers 403 with once the free quota is used up and
#: "Stop on Exhaust" is on in the console: "AllocationQuota.FreeTierOnly".
QUOTA_CODE_PREFIX = "AllocationQuota"
_WORKSPACE = re.compile(r"^[A-Za-z0-9-]{4,64}$")


def base_url(workspace: str | None) -> str:
    """The new Singapore domain when a workspace id is given, else the old one."""
    if workspace and _WORKSPACE.match(workspace):
        return WORKSPACE_BASE.format(workspace=workspace)
    return LEGACY_BASE


def context_phrases(hints: Hints | None) -> list[str]:
    """The hint phrases that fit under the limit, cut at a whole phrase: the
    shared ones first, then the ones for Qwen alone (0.51.0), each once."""
    if not hints:
        return []
    out, length = [], 0
    for phrase in dict.fromkeys((*hints.phrases, *hints.qwen_phrases)):
        extra = len(phrase) + (1 if out else 0)
        if length + extra > MAX_CONTEXT_CHARS:
            break
        out.append(phrase)
        length += extra
    return out


def context_text(hints: Hints | None) -> str:
    """The phrases that fit, as one word list."""
    return " ".join(context_phrases(hints))


def request_body(audio: bytes, *, model: str, language: str, hints: Hints | None) -> dict:
    wav_info(audio)                                     # a real WAV, or SttError
    messages = []
    context = context_text(hints)
    if context:
        messages.append({"role": "system", "content": [{"text": context}]})
    messages.append({"role": "user", "content": [
        {"audio": "data:audio/wav;base64," + base64.b64encode(audio).decode("ascii")}]})
    return {"model": model, "input": {"messages": messages},
            "parameters": {"asr_options": {"language": language, "enable_itn": False}}}


def recognize(*, api_key: str, audio: bytes, language: str = "th", model: str = DEFAULT_MODEL,
              hints: Hints | None = None, workspace: str | None = None,
              timeout: float = 20.0, transport=None) -> Transcript:
    """One synchronous recognition. `transport(request, timeout)` is for tests."""
    if not api_key:
        raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ", "no Qwen key", kind="no_key")
    body = json.dumps(request_body(audio, model=model, language=language, hints=hints)).encode("utf-8")
    _, local_seconds = wav_info(audio)
    request = urllib.request.Request(
        base_url(workspace) + PATH, data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Authorization": f"Bearer {api_key}"})
    send = transport or (lambda req, t: urllib.request.urlopen(req, timeout=t))
    try:
        with send(request, timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        code = _error_code(exc)
        if exc.code == 403 and code.startswith(QUOTA_CODE_PREFIX):
            # Stop-on-Exhaust (Poom, 0.63.0): the free seconds are gone and the
            # console refuses rather than bills. Not an auth problem.
            raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ", f"qwen quota_exhausted {exc.code} {code}",
                           kind="quota") from None
        if exc.code in (401, 403):
            raise SttError("ระบบถอดเสียงยังต่อไม่ได้ครับ", f"qwen auth {exc.code} {code}",
                           kind="auth") from None
        if exc.code == 429:
            raise SttError("ตอนนี้คนใช้เยอะครับ ลองอีกครั้งในอีกสักครู่",
                           f"qwen rate_limit {code}", kind="rate_limit") from None
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", f"qwen http {exc.code} {code}",
                       kind="http") from None
    except (urllib.error.URLError, OSError) as exc:
        # A timeout is an OSError too (socket.timeout / TimeoutError).
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", f"qwen {type(exc).__name__}",
                       kind="network") from None
    if len(raw) > MAX_RESPONSE_BYTES:
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", "qwen response too large", kind="response")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise SttError("ถอดเสียงไม่สำเร็จครับ ลองอีกครั้งนะ", "qwen response not JSON",
                       kind="response") from None

    usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
    try:
        seconds = float(usage.get("seconds"))
    except (TypeError, ValueError):
        # Not in the answer: the audio's own length, which is what is billed.
        seconds = local_seconds
    text = ""
    try:
        content = payload["output"]["choices"][0]["message"]["content"]
        text = "".join(str(part.get("text", "")) for part in content if isinstance(part, dict)).strip()
    except (KeyError, IndexError, TypeError):
        text = ""
    if not text:
        raise SttError("ไม่ได้ยินว่าพูดอะไรครับ ลองพูดอีกครั้งนะ",
                       f"qwen empty transcript, duration={seconds:.1f}", seconds=seconds, kind="empty")
    return Transcript(text=text, seconds=seconds)


def _error_code(exc: urllib.error.HTTPError) -> str:
    """Alibaba's error `code` word ("InvalidApiKey"), or ''. Never the message."""
    try:
        data = json.loads(exc.read(8192).decode("utf-8"))
        return re.sub(r"[^A-Za-z0-9_.-]", "", str(data.get("code", "")))[:40]
    except Exception:  # noqa: BLE001 — a reason word is a nicety, not a need
        return ""
