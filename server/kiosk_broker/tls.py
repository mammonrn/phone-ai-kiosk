"""TLS for the public data sources whose servers send a broken certificate chain.

WHY THIS EXISTS (checked 2026-09-26 with `openssl s_client -showcerts`):

* www.tmd.go.th (TMD's CAP / warning feeds) sends ONLY its leaf certificate
  (CN=*.tmd.go.th, issued by "GlobalSign GCC R6 AlphaSSL CA 2025") and not
  the intermediate. Browsers fetch the missing piece themselves; Python and
  curl do not, so every fetch failed "unable to get local issuer certificate".
* air4thai.pcd.go.th (Air4Thai PM2.5) sends its leaf (CN=air4thai.net, SAN
  includes air4thai.pcd.go.th, issued by Let's Encrypt "YR1") followed by an
  UNRELATED Sectigo/USERTrust/AAA chain. YR1 is under ISRG "Root YR", which is
  not in the Mozilla store yet; the cross-signed Root YR (issued by ISRG Root
  X1, which is) completes the path.

THE FIX, and nothing more: the missing INTERMEDIATES, fetched from the "CA
Issuers" (AIA) URL inside each certificate, are kept in `certs/` (public
certificates, not secrets — each file says where it came from and its
fingerprint) and added to the trust store of ONE context that is used ONLY for
the hosts in `AFFECTED_HOSTS`. Every other host keeps Python's default context.

What this module never does: turn verification off, skip the hostname check,
or trust a root of its own. A self-signed certificate in `certs/` is refused,
and OpenSSL's "partial chain" flag is cleared so an intermediate on its own is
not accepted as an anchor — the chain still has to end at a root the system
(or certifi, if installed) already trusts.

Failures are visible: a TLS failure logs one "tls: <host> ..." warning line
(once per host per `LOG_EVERY` seconds, and again when it recovers — never a
URL, a query string or a body), and the last status per host is kept here and,
when the broker is serving, in `tls_status.json` in the broker's home, which
`python -m kiosk_broker health` prints next to a live probe of every source.
"""

from __future__ import annotations

import json
import logging
import os
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable

log = logging.getLogger("kiosk_broker.tls")

CERT_DIR = Path(__file__).with_name("certs")

#: The hosts that get the extended context. Nothing else does.
AFFECTED_HOSTS = frozenset({"www.tmd.go.th", "air4thai.pcd.go.th"})

#: A failing host is logged at most once in this many seconds.
LOG_EVERY = 600.0

USER_AGENT = "phone-ai-kiosk/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"

#: Every public source the broker reads, for `health`. Scheme, host and path
#: only — no query string ever (some real URLs carry keys in one).
SOURCES: tuple[tuple[str, str], ...] = (
    ("tmd_cap", "https://www.tmd.go.th/api/xml/CAP"),
    ("tmd_rss", "https://www.tmd.go.th/api/xml/warning-news"),
    ("air4thai", "https://air4thai.pcd.go.th/services/getNewAQI_JSON.php"),
    ("thaiwater", "https://api-v3.thaiwater.net/api/v1/thaiwater30/public/waterlevel_load"),
    ("gdacs", "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH"),
    ("open_meteo", "https://api.open-meteo.com/v1/forecast"),
    ("open_meteo_air", "https://air-quality-api.open-meteo.com/v1/air-quality"),
    ("open_meteo_ensemble", "https://ensemble-api.open-meteo.com/v1/ensemble"),
    ("rainviewer", "https://api.rainviewer.com/public/weather-maps.json"),
    ("gold", "https://api.chnwt.dev/thai-gold-api/latest"),
)


class InsecureContextError(RuntimeError):
    """Somebody turned verification off on a context this module handed out."""


# ------------------------------------------------------------------ contexts ---

def _base_context() -> ssl.SSLContext:
    """The system's default store, plus certifi's when it is installed."""
    ctx = ssl.create_default_context()
    try:
        import certifi  # noqa: PLC0415 — optional; the VPS venv may not have it
    except ImportError:
        pass
    else:
        ctx.load_verify_locations(cafile=certifi.where())
    return ctx


def _is_self_signed_or_not_ca(pem_path: Path) -> str | None:
    """A reason to refuse this file, or None. Needs `cryptography` (a broker
    dependency, install.sh); without it nothing extra is loaded at all."""
    from cryptography import x509  # noqa: PLC0415

    data = pem_path.read_bytes()
    certs = x509.load_pem_x509_certificates(data)
    if not certs:
        return "no certificate"
    for cert in certs:
        if cert.subject == cert.issuer:
            return "self-signed (a root) — roots are never added here"
        try:
            bc = cert.extensions.get_extension_for_class(x509.BasicConstraints).value
        except x509.ExtensionNotFound:
            return "not a CA certificate"
        if not bc.ca:
            return "not a CA certificate"
    return None


def build_extended_context(cert_dir: Path = CERT_DIR) -> ssl.SSLContext:
    """Default trust + the intermediates in `cert_dir`. Verification and the
    hostname check stay on; partial chains are not accepted."""
    ctx = _base_context()
    # Python 3.13+ turns on X509_V_FLAG_PARTIAL_CHAIN by default, which would let
    # one of our intermediates act as a trust anchor. Off: the chain has to end
    # at a real root in the store.
    partial = getattr(ssl, "VERIFY_X509_PARTIAL_CHAIN", 0)
    if partial:
        ctx.verify_flags &= ~partial
    try:
        import cryptography  # noqa: F401, PLC0415
    except ImportError:
        log.warning("tls: cryptography missing - extra intermediates NOT loaded")
        return _strict(ctx)
    for pem in sorted(cert_dir.glob("*.pem")):
        reason = _is_self_signed_or_not_ca(pem)
        if reason:
            log.warning("tls: refused %s: %s", pem.name, reason)
            continue
        ctx.load_verify_locations(cafile=str(pem))
    return _strict(ctx)


def _strict(ctx: ssl.SSLContext) -> ssl.SSLContext:
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx


def assert_strict(ctx: ssl.SSLContext) -> None:
    if ctx.verify_mode != ssl.CERT_REQUIRED or not ctx.check_hostname:
        raise InsecureContextError("certificate verification must stay on")


_ctx_lock = threading.Lock()
_extended: ssl.SSLContext | None = None


def extended_context() -> ssl.SSLContext:
    """Built once, on first use."""
    global _extended
    with _ctx_lock:
        if _extended is None:
            _extended = build_extended_context()
        ctx = _extended
    assert_strict(ctx)
    return ctx


def context_for(url: str) -> ssl.SSLContext | None:
    """The extended context for an affected https host; None (= urllib's
    default, verified context) for everything else."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "https" or (parts.hostname or "").lower() not in AFFECTED_HOSTS:
        return None
    return extended_context()


# ------------------------------------------------------------ classification ---

def tls_reason(exc: BaseException) -> str | None:
    """A short, URL-free reason if `exc` is a TLS failure; None otherwise."""
    inner = exc.reason if isinstance(exc, urllib.error.URLError) else exc
    if isinstance(inner, ssl.SSLCertVerificationError):
        msg = inner.verify_message or "certificate verify failed"
        return f"certificate verify failed ({msg})"
    if isinstance(inner, ssl.SSLError):
        return f"tls error ({getattr(inner, 'reason', None) or type(inner).__name__})"
    return None


# ------------------------------------------------------------------- status ---

_status_lock = threading.Lock()
_status: dict[str, dict] = {}
_last_logged: dict[str, float] = {}
_status_file: Path | None = None


def set_status_file(path: Path | None) -> None:
    """Where the serving broker writes the per-host status (for `health`)."""
    global _status_file
    _status_file = path


def status() -> dict[str, dict]:
    with _status_lock:
        return {h: dict(v) for h, v in _status.items()}


def reset_status() -> None:
    with _status_lock:
        _status.clear()
        _last_logged.clear()


def _write_status_file(snapshot: dict) -> None:
    path = _status_file
    if path is None:
        return
    try:
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(snapshot, sort_keys=True), encoding="utf-8")
        os.replace(tmp, path)
    except OSError as exc:
        log.info("tls: status file not written: %s", type(exc).__name__)


def record(host: str, ok: bool, reason: str = "", now: float | None = None) -> None:
    """Keep the host's TLS status; log a failure (throttled) and a recovery."""
    now = time.time() if now is None else now
    host = (host or "").lower()
    with _status_lock:
        before = _status.get(host)
        _status[host] = {"ok": ok, "reason": reason, "at": int(now)}
        changed = before is None or before.get("ok") != ok or before.get("reason") != reason
        if ok:
            if before is not None and not before.get("ok"):
                log.warning("tls: %s verified again", host)
            _last_logged.pop(host, None)
        elif changed or now - _last_logged.get(host, 0.0) >= LOG_EVERY:
            log.warning("tls: %s %s", host, reason)
            _last_logged[host] = now
        snapshot = {h: dict(v) for h, v in _status.items()} if changed else None
    if snapshot is not None:
        _write_status_file(snapshot)


def urlopen(request: urllib.request.Request | str, timeout: float):
    """urllib's urlopen with the right context for the host, and TLS failures
    recorded. Everything else (errors included) is exactly urllib's."""
    url = request.full_url if isinstance(request, urllib.request.Request) else request
    host = urllib.parse.urlsplit(url).hostname or ""
    ctx = context_for(url)
    try:
        if ctx is None:
            response = urllib.request.urlopen(request, timeout=timeout)  # noqa: S310
        else:
            response = urllib.request.urlopen(request, timeout=timeout, context=ctx)  # noqa: S310
    except (urllib.error.URLError, OSError) as exc:
        if isinstance(exc, urllib.error.HTTPError):
            record(host, True)  # the handshake worked; HTTP is someone else's story
        else:
            reason = tls_reason(exc)
            if reason:
                record(host, False, reason)
        raise
    if url.startswith("https:"):
        record(host, True)
    return response


# ------------------------------------------------------------------- health ---

def probe(url: str, timeout: float = 15.0, opener=None) -> tuple[str, str]:
    """One small GET with the broker's own context: ("ok"|"TLS-FAIL"|"HTTP nnn"
    |"ERROR", reason). Reads at most 1 KiB."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    ctx = context_for(url)
    kwargs = {"timeout": timeout}
    if ctx is not None:
        kwargs["context"] = ctx
    try:
        with (opener or urllib.request.urlopen)(request, **kwargs) as response:
            response.read(1024)
            code = getattr(response, "status", 200)
    except urllib.error.HTTPError as exc:
        return f"HTTP {exc.code}", "tls ok"
    except (urllib.error.URLError, OSError) as exc:
        reason = tls_reason(exc)
        if reason:
            return "TLS-FAIL", reason
        inner = exc.reason if isinstance(exc, urllib.error.URLError) else exc
        return "ERROR", type(inner).__name__ if not isinstance(inner, str) else "unreachable"
    return ("ok" if code == 200 else f"HTTP {code}"), "tls ok"


#: `probe()` itself, captured before anything can monkeypatch the module
#: attribute of the same name — see `_health_rows`: only when the caller is
#: using this exact function (nobody passed their own `probe_fn`, and nobody
#: replaced `tls.probe`) does a location-based source get its own minimal
#: VALID request instead of a bare, query-free GET.
_DEFAULT_PROBE = probe

#: A public, well-known coordinate (central Bangkok) — used ONLY to build a
#: minimal valid `health` request for the three Open-Meteo endpoints below,
#: which answer HTTP 400 to their bare base URL (no latitude/longitude).
#: Never the kiosk's own position.
_HEALTH_LAT, _HEALTH_LON = 13.75, 100.50

#: A JSON health probe reads more than probe()'s 1 KiB (the ensemble answer
#: alone was ~120 KiB for a 3-day forecast) but is still bounded.
_MAX_HEALTH_BYTES = 512 * 1024


def _fetch_json_probe(url: str, timeout: float, opener) -> tuple[str, str, dict | None]:
    """Like `probe()`, but for a source that needs real query parameters to
    answer at all (a bare base URL is HTTP 400) and whose health check must
    look inside the JSON body, not just its status. Returns the same
    ("ok"|"TLS-FAIL"|"HTTP nnn"|"ERROR", reason) probe() does, plus the
    parsed body (None unless "ok")."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    ctx = context_for(url)
    kwargs = {"timeout": timeout}
    if ctx is not None:
        kwargs["context"] = ctx
    try:
        with (opener or urllib.request.urlopen)(request, **kwargs) as response:
            body = response.read(_MAX_HEALTH_BYTES + 1)
            code = getattr(response, "status", 200)
    except urllib.error.HTTPError as exc:
        return f"HTTP {exc.code}", "tls ok", None
    except (urllib.error.URLError, OSError) as exc:
        reason = tls_reason(exc)
        if reason:
            return "TLS-FAIL", reason, None
        inner = exc.reason if isinstance(exc, urllib.error.URLError) else exc
        return "ERROR", type(inner).__name__ if not isinstance(inner, str) else "unreachable", None
    if len(body) > _MAX_HEALTH_BYTES:
        return "ERROR", "response too large", None
    if code != 200:
        return f"HTTP {code}", "tls ok", None
    try:
        data = json.loads(body.decode("utf-8"))
    except ValueError:
        return "ERROR", "bad json", None
    return "ok", "tls ok", data


def _probe_open_meteo_forecast(timeout: float = 15.0, opener=None) -> tuple[str, str]:
    """The bare `/v1/forecast` base URL answers HTTP 400 (no location); this
    asks for one real field at a public Bangkok coordinate instead."""
    url = (f"https://api.open-meteo.com/v1/forecast?latitude={_HEALTH_LAT}"
           f"&longitude={_HEALTH_LON}&current=temperature_2m")
    result, reason, data = _fetch_json_probe(url, timeout, opener)
    if result != "ok":
        return result, reason
    if "temperature_2m" not in ((data or {}).get("current") or {}):
        return "ERROR", "no current.temperature_2m in answer"
    return "ok", "current.temperature_2m ok"


def _probe_open_meteo_air(timeout: float = 15.0, opener=None) -> tuple[str, str]:
    """Same problem as the forecast API above, one field (pm2_5) instead."""
    url = (f"https://air-quality-api.open-meteo.com/v1/air-quality?latitude={_HEALTH_LAT}"
           f"&longitude={_HEALTH_LON}&current=pm2_5")
    result, reason, data = _fetch_json_probe(url, timeout, opener)
    if result != "ok":
        return result, reason
    if "pm2_5" not in ((data or {}).get("current") or {}):
        return "ERROR", "no current.pm2_5 in answer"
    return "ok", "current.pm2_5 ok"


def _probe_open_meteo_ensemble(timeout: float = 15.0, opener=None) -> tuple[str, str]:
    """The real request local_rain.py makes (see its own module docstring:
    82 members from ecmwf_ifs025 + gfs025) — reused here rather than
    duplicated, so this probe and the actual feature never drift apart."""
    from . import local_rain  # noqa: PLC0415 — deferred: local_rain.py imports this module
    url = local_rain.ENSEMBLE_URL.format(lat=_HEALTH_LAT, lon=_HEALTH_LON)
    result, reason, data = _fetch_json_probe(url, timeout, opener)
    if result != "ok":
        return result, reason
    hourly = (data or {}).get("hourly") or {}
    members = sum(1 for key in hourly if key.startswith("precipitation"))
    if not members:
        return "ERROR", "no precipitation members in answer"
    return "ok", f"{members} members"


#: Sources whose bare-URL GET (probe()'s default) answers HTTP 400 because
#: they need real query parameters to mean anything — see each function's
#: own docstring for what "minimal valid" means for that source.
_LOCATION_PROBES: dict[str, Callable[[], tuple[str, str]]] = {
    "open_meteo": _probe_open_meteo_forecast,
    "open_meteo_air": _probe_open_meteo_air,
    "open_meteo_ensemble": _probe_open_meteo_ensemble,
}

#: TMD's plain "เตือนภัย" RSS (see alerts.py) is a secondary source — the
#: warning card already works from CAP alone — that has been slow to answer;
#: a health failure classified as a timeout reads as this instead of the bare
#: exception name.
_TMD_RSS_TIMEOUT_NOTE = "timeout (แหล่งสำรอง)"


def health_lines(sources=SOURCES, probe_fn=probe, last: dict | None = None) -> list[str]:
    """The `health` table. `last` is the serving broker's own last status per
    host (tls_status.json), shown when there is one."""
    return _format(_health_rows(sources, probe_fn, last))


def _health_rows(sources, probe_fn, last):
    rows = []
    for name, url in sources:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ""
        override = _LOCATION_PROBES.get(name) if probe_fn is _DEFAULT_PROBE else None
        if override is None and probe_fn is _DEFAULT_PROBE and name.startswith("obs_"):
            # A measured-station reader (obs.py): one real whole-country
            # fetch, answered with how many stations it returned and how many
            # are fresh — the numbers the card's choice has to work with.
            from . import obs  # noqa: PLC0415 — deferred: the readers import this module

            override = (lambda module: lambda: obs.health_probe(module))(name[4:])
        if override is not None:
            result, reason = override()
        else:
            result, reason = probe_fn(f"{parts.scheme}://{parts.netloc}{parts.path}")
        if name == "tmd_rss" and result == "ERROR" and reason == "TimeoutError":
            reason = _TMD_RSS_TIMEOUT_NOTE
        seen = ""
        if last and host in last:
            entry = last[host]
            when = time.strftime("%Y-%m-%d %H:%M", time.localtime(entry.get("at", 0)))
            seen = f"{'ok' if entry.get('ok') else 'TLS-FAIL'} {when}"
        rows.append((name, host + (" *" if host in AFFECTED_HOSTS else ""), result, reason, seen))
    return rows


def _format(rows) -> list[str]:
    rows = [("source", "host", "result", "reason", "broker last seen"), *rows]
    widths = [max(len(r[i]) for r in rows) for i in range(4)]
    out = ["  ".join(r[i].ljust(widths[i]) for i in range(4)) + "  " + r[4] for r in rows]
    out = [line.rstrip() for line in out]
    out.append("* = uses the extra intermediates in kiosk_broker/certs/ (verification on)")
    return out


def read_status_file(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def health_main(home: Path) -> int:
    """`python -m kiosk_broker health`: 0 when no source failed TLS."""
    from . import obs  # noqa: PLC0415 — deferred: the readers import this module

    rows = _health_rows(SOURCES + tuple(obs.health_sources()), probe,
                        read_status_file(home / "tls_status.json"))
    print("\n".join(_format(rows)))
    return 1 if any(r[2] == "TLS-FAIL" for r in rows) else 0
