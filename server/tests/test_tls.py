"""tls.py — extra intermediates for hosts with broken chains, never less verification.

Certificates are generated here (cryptography, a broker dependency); the only
sockets are on 127.0.0.1. No real network.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import socket
import ssl
import threading
import urllib.error
import urllib.request
from pathlib import Path

import pytest

cryptography = pytest.importorskip("cryptography")
from cryptography import x509  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.x509.oid import NameOID  # noqa: E402

from kiosk_broker import __main__ as cli  # noqa: E402
from kiosk_broker import tls  # noqa: E402

TEST_HOST = "kiosk-test.invalid"


# ------------------------------------------------------------------ fixtures ---

def _name(cn: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def _cert(subject, issuer, key, signer_key, *, ca: bool, san: str | None = None):
    now = dt.datetime.now(dt.timezone.utc)
    b = (x509.CertificateBuilder().subject_name(_name(subject)).issuer_name(_name(issuer))
         .public_key(key.public_key()).serial_number(x509.random_serial_number())
         .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=30))
         .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
    if ca:
        b = b.add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False),
                            critical=True)
    if san:
        b = b.add_extension(x509.SubjectAlternativeName([x509.DNSName(san)]), critical=False)
    return b.sign(signer_key, hashes.SHA256())


def _pem(cert) -> bytes:
    return cert.public_bytes(serialization.Encoding.PEM)


@pytest.fixture(scope="module")
def pki(tmp_path_factory):
    d = tmp_path_factory.mktemp("pki")
    root_k, int_k, leaf_k = (ec.generate_private_key(ec.SECP256R1()) for _ in range(3))
    root = _cert("Test Root", "Test Root", root_k, root_k, ca=True)
    inter = _cert("Test Intermediate", "Test Root", int_k, root_k, ca=True)
    leaf = _cert(TEST_HOST, "Test Intermediate", leaf_k, int_k, ca=False, san=TEST_HOST)
    (d / "root.pem").write_bytes(_pem(root))
    (d / "leaf.pem").write_bytes(_pem(leaf))
    (d / "leaf.key").write_bytes(leaf_k.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()))
    certs = d / "certs"
    certs.mkdir()
    (certs / "intermediate.pem").write_bytes(b"# header comment like the real files\n" + _pem(inter))
    return {"dir": d, "certs": certs, "root": d / "root.pem", "inter_pem": _pem(inter),
            "root_pem": _pem(root), "leaf_pem": _pem(leaf)}


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    tls.reset_status()
    tls.set_status_file(None)
    monkeypatch.setattr(tls, "_extended", None)
    yield
    tls.reset_status()
    tls.set_status_file(None)


def _root_only_base(pki, monkeypatch):
    def base():
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.load_verify_locations(cafile=str(pki["root"]))
        return ctx
    monkeypatch.setattr(tls, "_base_context", base)


def _subjects(ctx) -> set[str]:
    out = set()
    for c in ctx.get_ca_certs():
        for rdn in c["subject"]:
            for k, v in rdn:
                if k == "commonName":
                    out.add(v)
    return out


# ------------------------------------------------------------------ contexts ---

def test_extended_context_adds_intermediates_and_stays_strict(pki, monkeypatch):
    _root_only_base(pki, monkeypatch)
    ctx = tls.build_extended_context(pki["certs"])
    assert ctx.verify_mode == ssl.CERT_REQUIRED
    assert ctx.check_hostname is True
    assert "Test Intermediate" in _subjects(ctx)
    assert not ctx.verify_flags & getattr(ssl, "VERIFY_X509_PARTIAL_CHAIN", 0)


def test_self_signed_and_non_ca_files_are_refused(pki, monkeypatch, tmp_path, caplog):
    _root_only_base(pki, monkeypatch)
    (tmp_path / "a-root.pem").write_bytes(pki["root_pem"])
    (tmp_path / "b-leaf.pem").write_bytes(pki["leaf_pem"])
    with caplog.at_level(logging.WARNING, logger="kiosk_broker.tls"):
        ctx = tls.build_extended_context(tmp_path)
    # The only root in the store is the one the base put there; the leaf is not.
    assert _subjects(ctx) == {"Test Root"}
    assert "a-root.pem: self-signed" in caplog.text
    assert "b-leaf.pem: not a CA certificate" in caplog.text


def test_shipped_certs_are_intermediates_only():
    files = sorted(tls.CERT_DIR.glob("*.pem"))
    assert len(files) == 3
    for pem in files:
        text = pem.read_text(encoding="utf-8")
        assert "# Source (AIA CA Issuers URL): http://" in text
        assert "# SHA-256:" in text
        assert tls._is_self_signed_or_not_ca(pem) is None, pem.name
    ctx = tls.extended_context()
    assert {"GlobalSign GCC R6 AlphaSSL CA 2025", "YR1", "Root YR"} <= _subjects(ctx)


def test_only_affected_hosts_get_the_extended_context():
    ext = tls.context_for("https://www.tmd.go.th/api/xml/CAP")
    assert ext is not None and ext is tls.context_for("https://AIR4THAI.pcd.go.th/x?y=1")
    for url in ("https://data.tmd.go.th/api/x", "https://api.open-meteo.com/v1/forecast",
                "http://www.tmd.go.th/api/xml/CAP", "https://tmd.go.th/"):
        assert tls.context_for(url) is None, url


def test_verification_cannot_be_turned_off(monkeypatch):
    lax = ssl.create_default_context()
    lax.check_hostname = False
    lax.verify_mode = ssl.CERT_NONE
    monkeypatch.setattr(tls, "_extended", lax)
    with pytest.raises(tls.InsecureContextError):
        tls.context_for("https://www.tmd.go.th/api/xml/CAP")


# --------------------------------------------------------- real handshake, local ---

def _serve_leaf_only(pki):
    """A TLS server on 127.0.0.1 that, like www.tmd.go.th, sends only its leaf."""
    sctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    sctx.load_cert_chain(str(pki["dir"] / "leaf.pem"), str(pki["dir"] / "leaf.key"))
    lsock = socket.socket()
    lsock.bind(("127.0.0.1", 0))
    lsock.listen(4)

    def run():
        while True:
            try:
                conn, _ = lsock.accept()
            except OSError:
                return
            try:
                with sctx.wrap_socket(conn, server_side=True) as s:
                    s.recv(1)
            except (ssl.SSLError, OSError):
                pass

    threading.Thread(target=run, daemon=True).start()
    return lsock


def _handshake(ctx, port, name=TEST_HOST):
    with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
        with ctx.wrap_socket(s, server_hostname=name):
            return True


def test_leaf_only_server_needs_the_intermediate_and_a_real_root(pki, monkeypatch):
    lsock = _serve_leaf_only(pki)
    port = lsock.getsockname()[1]
    try:
        _root_only_base(pki, monkeypatch)
        # Root only: the missing-intermediate failure the VPS saw.
        with pytest.raises(ssl.SSLCertVerificationError) as info:
            _handshake(_strict_root_only(pki), port)
        assert "unable to get local issuer certificate" in tls.tls_reason(info.value)
        # Root + intermediate: verifies.
        assert _handshake(tls.build_extended_context(pki["certs"]), port)
        # Hostname check still on.
        with pytest.raises(ssl.SSLCertVerificationError):
            _handshake(tls.build_extended_context(pki["certs"]), port, name="other.invalid")
        # Intermediate WITHOUT its root: refused (no partial chains).
        monkeypatch.setattr(tls, "_base_context", lambda: ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT))
        with pytest.raises(ssl.SSLCertVerificationError):
            _handshake(tls.build_extended_context(pki["certs"]), port)
    finally:
        lsock.close()


def _strict_root_only(pki):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.load_verify_locations(cafile=str(pki["root"]))
    return ctx


# ------------------------------------------------------- classification / log ---

def test_tls_reason_classifies_only_tls_errors():
    err = ssl.SSLCertVerificationError(1, "x")
    err.verify_message = "unable to get local issuer certificate"
    assert tls.tls_reason(urllib.error.URLError(err)) == \
        "certificate verify failed (unable to get local issuer certificate)"
    assert tls.tls_reason(ssl.SSLError(1, "wrong version")).startswith("tls error")
    assert tls.tls_reason(urllib.error.URLError(ConnectionRefusedError())) is None
    assert tls.tls_reason(TimeoutError()) is None


def _verify_error():
    err = ssl.SSLCertVerificationError(1, "certificate verify failed")
    err.verify_message = "unable to get local issuer certificate"
    return urllib.error.URLError(err)


def test_urlopen_logs_host_only_once_and_recovery(monkeypatch, caplog, tmp_path):
    tls.set_status_file(tmp_path / "tls_status.json")
    seen = {}

    def failing(request, timeout, **kw):
        seen["context"] = kw.get("context")
        raise _verify_error()

    monkeypatch.setattr(urllib.request, "urlopen", failing)
    url = "https://www.tmd.go.th/api/xml/CAP?secret=abc"
    with caplog.at_level(logging.INFO, logger="kiosk_broker.tls"):
        for _ in range(3):
            with pytest.raises(urllib.error.URLError):
                tls.urlopen(urllib.request.Request(url), timeout=1)
    assert seen["context"] is not None  # affected host got the extended context
    lines = [r.getMessage() for r in caplog.records if r.getMessage().startswith("tls:")]
    assert lines == ["tls: www.tmd.go.th certificate verify failed "
                     "(unable to get local issuer certificate)"]
    assert "secret" not in caplog.text and "/api/" not in caplog.text
    saved = json.loads((tmp_path / "tls_status.json").read_text(encoding="utf-8"))
    assert saved["www.tmd.go.th"]["ok"] is False

    class _Resp:
        status = 200

    monkeypatch.setattr(urllib.request, "urlopen", lambda request, timeout, **kw: _Resp())
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="kiosk_broker.tls"):
        tls.urlopen(url, timeout=1)
    assert "tls: www.tmd.go.th verified again" in caplog.text
    assert tls.status()["www.tmd.go.th"]["ok"] is True


def test_failure_is_logged_again_after_the_throttle_window(caplog):
    with caplog.at_level(logging.WARNING, logger="kiosk_broker.tls"):
        tls.record("air4thai.pcd.go.th", False, "x", now=1000)
        tls.record("air4thai.pcd.go.th", False, "x", now=1000 + tls.LOG_EVERY - 1)
        tls.record("air4thai.pcd.go.th", False, "x", now=1000 + tls.LOG_EVERY)
    assert caplog.text.count("tls: air4thai.pcd.go.th x") == 2


def test_other_hosts_use_urllibs_default_context(monkeypatch):
    seen = {}

    def ok(request, timeout, **kw):
        seen.update(kw)

        class R:
            status = 200
        return R()

    monkeypatch.setattr(urllib.request, "urlopen", ok)
    tls.urlopen("https://api.open-meteo.com/v1/forecast?latitude=1", timeout=1)
    assert "context" not in seen


# ------------------------------------------------------------------- health ---

def test_health_table_format_and_no_query_strings():
    asked = []

    def fake_probe(url):
        asked.append(url)
        if "air4thai" in url:
            return "TLS-FAIL", "certificate verify failed (unable to get local issuer certificate)"
        return "ok", "tls ok"

    last = {"air4thai.pcd.go.th": {"ok": False, "reason": "x", "at": 0}}
    lines = tls.health_lines(
        sources=(("tmd_cap", "https://www.tmd.go.th/api/xml/CAP"),
                 ("air4thai", "https://air4thai.pcd.go.th/services/getNewAQI_JSON.php"),
                 ("gdacs", "https://www.gdacs.org/a/SEARCH?country=THA")),
        probe_fn=fake_probe, last=last)
    assert lines[0].split() == ["source", "host", "result", "reason", "broker", "last", "seen"]
    assert lines[1].split()[:4] == ["tmd_cap", "www.tmd.go.th", "*", "ok"]
    assert "TLS-FAIL" in lines[2] and "unable to get local issuer" in lines[2]
    assert lines[2].rstrip().split()[-3] == "TLS-FAIL"  # broker last seen
    assert lines[3].split()[:3] == ["gdacs", "www.gdacs.org", "ok"]
    assert lines[-1].startswith("* = ")
    assert all("?" not in u for u in asked)


def test_health_sources_carry_no_query_or_key():
    for _, url in tls.SOURCES:
        assert "?" not in url and "key" not in url.lower() and "uid" not in url.lower()


def test_probe_classifies(monkeypatch):
    def tls_fail(request, **kw):
        raise _verify_error()
    assert tls.probe("https://www.tmd.go.th/x", opener=tls_fail)[0] == "TLS-FAIL"

    def http_404(request, **kw):
        raise urllib.error.HTTPError(request.full_url, 404, "nf", {}, None)
    assert tls.probe("https://x.invalid/", opener=http_404) == ("HTTP 404", "tls ok")

    def refused(request, **kw):
        raise urllib.error.URLError(ConnectionRefusedError())
    assert tls.probe("https://x.invalid/", opener=refused) == ("ERROR", "ConnectionRefusedError")


class _JsonResponse:
    def __init__(self, body: bytes, status: int = 200):
        self._body = body
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self, _n):
        return self._body


def _json_opener(payload: dict, status: int = 200):
    body = json.dumps(payload).encode("utf-8")

    def opener(request, **kw):
        return _JsonResponse(body, status)
    return opener


def test_open_meteo_forecast_probe_needs_real_params_not_a_bare_url():
    ok = tls._probe_open_meteo_forecast(opener=_json_opener({"current": {"temperature_2m": 30.1}}))
    assert ok == ("ok", "current.temperature_2m ok")
    missing = tls._probe_open_meteo_forecast(opener=_json_opener({"current": {}}))
    assert missing[0] == "ERROR"


def test_open_meteo_air_probe_reads_pm25():
    ok = tls._probe_open_meteo_air(opener=_json_opener({"current": {"pm2_5": 12.0}}))
    assert ok == ("ok", "current.pm2_5 ok")
    missing = tls._probe_open_meteo_air(opener=_json_opener({"current": {}}))
    assert missing[0] == "ERROR"


def test_open_meteo_ensemble_probe_counts_members():
    hourly = {"time": ["x"], **{f"precipitation_member{i:02d}": [1.0] for i in range(82)}}
    ok = tls._probe_open_meteo_ensemble(opener=_json_opener({"hourly": hourly}))
    assert ok == ("ok", "82 members")
    empty = tls._probe_open_meteo_ensemble(opener=_json_opener({"hourly": {"time": ["x"]}}))
    assert empty[0] == "ERROR"


def test_health_rows_use_location_probes_only_for_the_real_default_probe(monkeypatch):
    # A custom probe_fn (as every existing test passes) must never be
    # bypassed — only tls.probe itself, unpatched, triggers the override.
    calls = []

    def fake_probe(url):
        calls.append(url)
        return "ok", "tls ok"

    rows = tls._health_rows(
        (("open_meteo_ensemble", "https://ensemble-api.open-meteo.com/v1/ensemble"),),
        fake_probe, None)
    assert calls == ["https://ensemble-api.open-meteo.com/v1/ensemble"]
    assert rows[0][2] == "ok"

    monkeypatch.setitem(tls._LOCATION_PROBES, "open_meteo_ensemble", lambda: ("ok", "82 members"))
    rows = tls._health_rows(
        (("open_meteo_ensemble", "https://ensemble-api.open-meteo.com/v1/ensemble"),),
        tls.probe, None)
    assert rows[0][2:4] == ("ok", "82 members")


def test_health_prints_a_thai_note_for_a_tmd_rss_timeout():
    def fake_probe(url):
        return ("ERROR", "TimeoutError") if "warning-news" in url else ("ok", "tls ok")

    lines = tls.health_lines(
        sources=(("tmd_rss", "https://www.tmd.go.th/api/xml/warning-news"),),
        probe_fn=fake_probe, last=None)
    assert "timeout (แหล่งสำรอง)" in lines[1]


def test_health_command_dispatch_and_exit_code(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(cli.config_mod, "DEFAULT_HOME", tmp_path)
    monkeypatch.setattr(tls, "probe", lambda url: ("ok", "tls ok"))
    assert cli.main(["health"]) == 0
    assert "tmd_cap" in capsys.readouterr().out
    monkeypatch.setattr(tls, "probe", lambda url: (
        ("TLS-FAIL", "certificate verify failed (x)") if "tmd.go.th/api" in url else ("ok", "tls ok")))
    assert cli.main(["health"]) == 1
