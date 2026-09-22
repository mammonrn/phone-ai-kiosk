"""Botnoi Voice — an experiment, not the production voice.

Production speaks with Google Chirp 3 HD, voice Schedar, and this does not
change that. It exists so Poom can hear Thai male voices from another vendor
side by side with the one already chosen.

The token is never read by anything but this process, never printed, never
logged and never committed. It goes in the broker's own env file as
BOTNOI_TOKEN, the same 0600 file the other keys live in.

THE DANGEROUS PART IS NOT THE API, IT IS THE URL IT RETURNS. Botnoi answers
with an `audio_url` pointing at S3, and fetching a URL a remote service handed
you is a request forgery waiting to happen. Every download here must be HTTPS,
must land on an allow-listed host, must re-check the host on every redirect,
must stop at a byte limit and must give up on a timer. A presigned S3 URL also
carries credentials in its query string, so nothing here ever prints a whole
URL — only the scheme and host.

Documented at https://api-voice.botnoi.ai, checked 2026-09-22:
  POST /openapi/v1/generate_audio      (v1, classic voices)
  POST /openapi/v1/generate_audio_v2   (v2, premium voices)
  GET  /openapi/v1/get_speaker_data    and _v2
  header: botnoi-token
  body: text, speaker, volume 0.1-2.0, speed 0.5-2.0, type_media mp3|wav,
        save_file "True"|"False", language
"""

from __future__ import annotations

import http.client
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

BASE_URL = "https://api-voice.botnoi.ai"

#: Hosts an audio_url is allowed to point at. Suffix-matched on the registered
#: domain, so a bucket under either is fine and anything else is not.
#:
#: 🔶 The exact bucket has not been observed — no token here to call the API
#: with — so this is deliberately a little wider than it will need to be, and
#: every download prints the host it used so it can be narrowed to the real one.
#: Tightening this list is a one-line change in config.json.
DEFAULT_ALLOWED_HOSTS = ("botnoi.ai", "amazonaws.com")

#: A sample sentence is a few seconds of speech. Anything much larger is not the
#: thing that was asked for.
MAX_AUDIO_BYTES = 8 * 1024 * 1024

MAX_REDIRECTS = 3


class BotnoiError(RuntimeError):
    """Something went wrong, described without quoting the token or a URL."""

    def __init__(self, message: str, detail: str = ""):
        super().__init__(message)
        self.detail = detail


@dataclass(frozen=True)
class Speaker:
    speaker_id: str
    eng_name: str
    thai_name: str
    gender: str
    language: str
    price: str = ""

    @property
    def is_male(self) -> bool:
        return self.gender.strip().lower() in {"male", "m", "ชาย"}

    @property
    def is_thai(self) -> bool:
        return self.language.strip().lower() in {"th", "thai", "ไทย", "th-th"}

    def label(self) -> str:
        name = self.eng_name or self.thai_name or self.speaker_id
        return f"{self.speaker_id}-{name}".replace("/", "-").replace(" ", "")


@dataclass
class Generated:
    audio: bytes
    media_type: str
    #: What the call cost in Botnoi's own units. Their price per point is not
    #: something this code knows, so points are recorded as points.
    point: float = 0.0
    monthly_point: float = 0.0
    host: str = ""


def _request(method: str, path: str, token: str, *, body: dict | None = None,
             timeout: float = 30.0, base_url: str = BASE_URL,
             ssl_context: ssl.SSLContext | None = None) -> dict:
    """One JSON call. The token travels in a header and nowhere else."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        base_url + path, data=data, method=method,
        headers={
            "accept": "application/json",
            "botnoi-token": token,
            **({"Content-Type": "application/json"} if data else {}),
        },
    )

    opener = (urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl_context))
              if ssl_context is not None else urllib.request.build_opener())
    try:
        with opener.open(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403):
            raise BotnoiError("Botnoi rejected the token", f"http {exc.code}") from exc
        if exc.code == 429:
            raise BotnoiError("Botnoi is rate limiting", f"http {exc.code}") from exc
        # The body can echo the request, so it is summarised rather than quoted.
        raise BotnoiError(f"Botnoi answered {exc.code}", f"http {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise BotnoiError("could not reach Botnoi", type(exc).__name__) from exc
    except (http.client.HTTPException, OSError) as exc:
        raise BotnoiError("the connection to Botnoi failed", type(exc).__name__) from exc
    except ValueError as exc:
        raise BotnoiError("Botnoi returned something that is not JSON",
                          type(exc).__name__) from exc


def list_speakers(token: str, *, v2: bool = True, timeout: float = 30.0,
                  base_url: str = BASE_URL,
                  ssl_context: ssl.SSLContext | None = None) -> list[Speaker]:
    """Every voice the account can use.

    The response shape is not in the published docs, so the rows are read
    defensively: a bare list, or a list under any of the usual wrapper keys.
    """
    path = "/openapi/v1/get_speaker_data_v2" if v2 else "/openapi/v1/get_speaker_data"
    payload = _request("GET", path, token, timeout=timeout, base_url=base_url,
                       ssl_context=ssl_context)

    rows = payload if isinstance(payload, list) else None
    if rows is None:
        for key in ("data", "result", "speakers", "speaker"):
            value = payload.get(key) if isinstance(payload, dict) else None
            if isinstance(value, list):
                rows = value
                break
    if rows is None:
        raise BotnoiError("could not find a list of speakers in the response",
                          f"keys={sorted(payload)[:8] if isinstance(payload, dict) else type(payload)}")

    speakers = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        speakers.append(Speaker(
            speaker_id=str(row.get("speaker_id", "")).strip(),
            eng_name=str(row.get("eng_name", "")).strip(),
            thai_name=str(row.get("thai_name", "")).strip(),
            # Two spellings in the field list Poom quoted; either will do.
            gender=str(row.get("eng_gender") or row.get("gender") or "").strip(),
            language=str(row.get("language", "")).strip(),
            price=str(row.get("price", "")).strip(),
        ))
    return [s for s in speakers if s.speaker_id]


def generate(token: str, *, text: str, speaker: str, language: str = "th",
             speed: float = 1.0, volume: float = 1.0, media: str = "mp3",
             v2: bool = True, timeout: float = 60.0, base_url: str = BASE_URL,
             allowed_hosts: tuple[str, ...] = DEFAULT_ALLOWED_HOSTS,
             max_bytes: int = MAX_AUDIO_BYTES,
             ssl_context: ssl.SSLContext | None = None) -> Generated:
    """Synthesises one line and fetches the audio it points at.

    `save_file` is "False" because there is no reason to leave a copy of a test
    sentence on somebody else's storage.
    """
    if not text.strip():
        raise BotnoiError("nothing to say")
    if not str(speaker).strip():
        raise BotnoiError("no speaker given")

    path = "/openapi/v1/generate_audio_v2" if v2 else "/openapi/v1/generate_audio"
    payload = _request("POST", path, token, timeout=timeout, base_url=base_url,
                       ssl_context=ssl_context, body={
        "text": text,
        "speaker": str(speaker),
        "volume": volume,
        "speed": speed,
        "type_media": media,
        "save_file": "False",
        "language": language,
    })

    url = payload.get("audio_url")
    if not url:
        raise BotnoiError("Botnoi returned no audio_url",
                          f"keys={sorted(payload)[:8]}")

    audio, host = fetch_audio(url, allowed_hosts=allowed_hosts, max_bytes=max_bytes,
                              timeout=timeout, ssl_context=ssl_context)
    return Generated(
        audio=audio,
        media_type=media,
        point=float(payload.get("point") or 0.0),
        monthly_point=float(payload.get("user_monthly_point") or 0.0),
        host=host,
    )


def host_is_allowed(host: str, allowed_hosts: tuple[str, ...]) -> bool:
    """Suffix match on the registered domain, anchored at a dot.

    Anchoring matters: a plain `endswith("botnoi.ai")` would also accept
    `evil-botnoi.ai`, which is a different company entirely.
    """
    host = (host or "").lower().strip().rstrip(".")
    if not host:
        return False
    return any(host == allowed or host.endswith("." + allowed) for allowed in allowed_hosts)


def fetch_audio(url: str, *, allowed_hosts: tuple[str, ...], max_bytes: int,
                timeout: float, ssl_context: ssl.SSLContext | None = None) -> tuple[bytes, str]:
    """Downloads a URL a remote service handed us, carefully.

    Returns the bytes and the host they came from. Never returns, logs or raises
    the URL itself: a presigned S3 link carries credentials in its query string.

    `ssl_context` is for tests pointing at a stub with its own certificate.
    Left None, urllib verifies against the system trust store, which is what
    production does.
    """
    seen = 0
    current = url

    while True:
        parsed = urllib.parse.urlsplit(current)

        if parsed.scheme != "https":
            raise BotnoiError("the audio link was not HTTPS",
                              f"scheme={parsed.scheme!r}")
        if not host_is_allowed(parsed.hostname or "", allowed_hosts):
            raise BotnoiError(
                f"the audio link pointed at an unexpected host: {parsed.hostname}",
                "add it to botnoi_audio_hosts in config.json only if it is really Botnoi's",
            )

        request = urllib.request.Request(current, method="GET")
        handlers: list = [_NoRedirects]
        if ssl_context is not None:
            handlers.append(urllib.request.HTTPSHandler(context=ssl_context))
        opener = urllib.request.build_opener(*handlers)
        try:
            with opener.open(request, timeout=timeout) as response:
                declared = response.headers.get("Content-Length")
                if declared and int(declared) > max_bytes:
                    raise BotnoiError("the audio file is larger than the limit",
                                      f"content-length={declared}")

                # Read one byte past the limit: a missing or lying
                # Content-Length must not be able to stream forever.
                audio = response.read(max_bytes + 1)
                if len(audio) > max_bytes:
                    raise BotnoiError("the audio file is larger than the limit",
                                      f"read>{max_bytes}")
                if not audio:
                    raise BotnoiError("the audio link returned nothing")
                return audio, parsed.hostname or ""

        except _Redirect as redirect:
            # Followed only after the new host passes the same check, which is
            # the whole point: an open redirect to somewhere else is how this
            # kind of fetch turns into a request forgery.
            seen += 1
            if seen > MAX_REDIRECTS:
                raise BotnoiError("too many redirects on the audio link") from None
            current = urllib.parse.urljoin(current, redirect.location)
            continue
        except urllib.error.HTTPError as exc:
            raise BotnoiError(f"the audio link answered {exc.code}",
                              f"http {exc.code}") from exc
        except urllib.error.URLError as exc:
            raise BotnoiError("could not fetch the audio link",
                              type(exc).__name__) from exc
        except (http.client.HTTPException, OSError) as exc:
            raise BotnoiError("the audio download failed",
                              type(exc).__name__) from exc


class _Redirect(Exception):
    def __init__(self, location: str):
        super().__init__("redirect")
        self.location = location


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    """Turns a redirect into an exception so the caller can re-check the host.

    urllib follows redirects by itself otherwise, which would let a response
    walk the download to any host it liked.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise _Redirect(newurl)
