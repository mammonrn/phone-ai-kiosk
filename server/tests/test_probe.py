"""`probe tmd`/`probe gistda`: redaction, personal-data stripping, the JWT
peek, and full runs against a fake `fetch` — never a real socket."""

from __future__ import annotations

import io
import json

import pytest

from kiosk_broker import probe

# A real-shaped (but harmless, made up) JWT: header.payload.signature, base64url,
# no padding. Payload: {"exp": 1999999999, "iat": 1699999999,
# "email": "someone@example.com", "name": "Poom"} — the email/name are there
# on purpose, to prove jwt_dates() never surfaces them.
SAMPLE_JWT = (
    "eyJhbGciOiAibm9uZSJ9."
    "eyJleHAiOiAxOTk5OTk5OTk5LCAiaWF0IjogMTY5OTk5OTk5OSwgImVtYWlsIjogInNvbWVvbmVAZXhhbXBsZS5jb20iLCAibmFtZSI6ICJQb29tIn0."
    "sig"
)


# ------------------------------------------------------------------ redact ---

def test_redact_hides_a_secret_in_plain_text():
    assert probe.redact("status ok, key=abc123", ["abc123"]) == "status ok, key=***"


def test_redact_hides_a_url_encoded_form_of_the_secret():
    # A key with characters that change under URL-encoding (space -> %20 or +).
    secret = "a b/c"
    encoded = "a%20b%2Fc"
    assert "***" in probe.redact(f"failed for ?ukey={encoded}", [secret])


def test_redact_hides_the_secret_inside_exception_text():
    secret = "sekret-value-1"
    try:
        raise ValueError(f"http error fetching https://x/?uid=u&ukey={secret}")
    except ValueError as exc:
        assert secret not in probe.redact(str(exc), [secret])


def test_redact_ignores_empty_secrets_and_empty_text():
    assert probe.redact("", ["x"]) == ""
    assert probe.redact("hello", ["", None]) == "hello"


def test_path_only_drops_the_query_string():
    assert probe.path_only("https://x.example/api/Foo/V2/?uid=1&ukey=2") == "https://x.example/api/Foo/V2/"


# ------------------------------------------------------------- strip_personal ---

def test_strip_personal_drops_a_name_field_but_keeps_place_names():
    record = {"name": "สมชาย ใจดี", "StationNameThai": "กรุงเทพ", "Province": "กรุงเทพมหานคร"}
    cleaned = probe.strip_personal(record)
    assert "name" not in cleaned
    assert cleaned["StationNameThai"] == "กรุงเทพ"
    assert cleaned["Province"] == "กรุงเทพมหานคร"


@pytest.mark.parametrize("field_name", [
    "phone", "เบอร์โทร", "email", "อีเมล", "owner", "เจ้าของ", "user", "contact", "line_id",
])
def test_strip_personal_drops_every_personal_field_name(field_name):
    cleaned = probe.strip_personal({field_name: "x", "keep": "y"})
    assert field_name not in cleaned
    assert cleaned["keep"] == "y"


def test_strip_personal_scrubs_email_and_phone_shaped_values_in_remaining_fields():
    cleaned = probe.strip_personal({"note": "ติดต่อ someone@example.com หรือ 081-234-5678"})
    assert "someone@example.com" not in cleaned["note"]
    assert "081-234-5678" not in cleaned["note"]


def test_strip_personal_recurses_into_nested_records_and_lists():
    record = {"properties": {"name": "ผู้ดูแล", "province": "เชียงใหม่"},
              "items": [{"owner": "x", "keep": 1}]}
    cleaned = probe.strip_personal(record)
    assert "name" not in cleaned["properties"]
    assert cleaned["properties"]["province"] == "เชียงใหม่"
    assert "owner" not in cleaned["items"][0]
    assert cleaned["items"][0]["keep"] == 1


def test_strip_personal_leaves_a_non_dict_alone():
    assert probe.strip_personal("just a string") == "just a string"


# ------------------------------------------------------------------- jwt ---

def test_jwt_dates_reads_only_exp_and_iat():
    dates = probe.jwt_dates(SAMPLE_JWT)
    assert dates == {"exp": "2033-05-18", "iat": "2023-11-14"}


def test_jwt_dates_never_surfaces_other_claims():
    dates = probe.jwt_dates(SAMPLE_JWT)
    assert "email" not in dates and "name" not in dates
    assert all(v not in json.dumps(dates) for v in ("Poom", "someone@example.com"))


@pytest.mark.parametrize("bad", ["", "not-a-jwt", "a.b", "a.b.c.d"])
def test_jwt_dates_returns_empty_for_anything_not_jwt_shaped(bad):
    assert probe.jwt_dates(bad) == {}


def test_jwt_dates_returns_empty_for_unreadable_payload():
    assert probe.jwt_dates("aGVhZGVy.bm90LWpzb24.sig") == {}


# --------------------------------------------------------------- probe runs ---

def _secret(values):
    return lambda name: values.get(name)


def test_probe_tmd_with_no_keys_at_all_makes_no_request_and_fails():
    calls = []
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        calls.append(url)
        raise AssertionError("must not be called")

    rc = probe.run_tmd(_secret({}), out=out, fetch=fetch, sleep=lambda s: None)
    assert rc == 1
    assert calls == []
    assert "ยังไม่มี key" in out.getvalue()


def test_probe_tmd_runs_tmdapi_only_when_only_uid_ukey_are_set():
    calls = []
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        calls.append((url, headers))
        xml = b"<root><Station><StationNameThai>test</StationNameThai>" \
              b"<Observation><DateTime>09/26/2026 13:00:00</DateTime></Observation>" \
              b"</Station></root>"
        return 200, xml

    rc = probe.run_tmd(_secret({"TMD_UID": "u", "TMD_UKEY": "k"}), out=out, fetch=fetch,
                        sleep=lambda s: None)
    assert rc == 0
    assert len(calls) == len(probe.TMD_ENDPOINTS)
    for url, headers in calls:
        assert not headers
    text = out.getvalue()
    assert "ukey=k" not in text and "uid=u" not in text


def test_probe_tmd_runs_nwp_only_when_only_the_token_is_set():
    calls = []
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        calls.append((url, headers))
        return 200, json.dumps({"WeatherForecasts": [{"date": "2026-09-26", "tc": 30}]}).encode()

    rc = probe.run_tmd(_secret({"TMD_NWP_TOKEN": SAMPLE_JWT}), out=out, fetch=fetch,
                        sleep=lambda s: None)
    assert rc == 0
    assert len(calls) == len(probe.NWP_ENDPOINTS)
    for url, headers in calls:
        assert headers == {"Authorization": f"Bearer {SAMPLE_JWT}"}
    assert SAMPLE_JWT not in out.getvalue()


def test_probe_tmd_runs_both_when_both_are_set():
    calls = []

    def fetch(url, timeout, limit, headers=None):
        calls.append(url)
        if "nwpapi" in url:
            return 200, b"{}"
        return 200, b"<root><Station></Station></root>"

    rc = probe.run_tmd(
        _secret({"TMD_UID": "u", "TMD_UKEY": "k", "TMD_NWP_TOKEN": SAMPLE_JWT}),
        out=io.StringIO(), fetch=fetch, sleep=lambda s: None,
    )
    assert rc == 0
    assert len(calls) == len(probe.NWP_ENDPOINTS) + len(probe.TMD_ENDPOINTS)


def test_probe_tmd_never_prints_the_uid_or_ukey_even_on_a_transport_error():
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        raise OSError(f"connection refused for {url}")

    rc = probe.run_tmd(_secret({"TMD_UID": "very-secret-uid", "TMD_UKEY": "very-secret-ukey"}),
                        out=out, fetch=fetch, sleep=lambda s: None)
    assert rc == 0  # a probe reports errors; it does not itself fail the command
    text = out.getvalue()
    assert "very-secret-uid" not in text
    assert "very-secret-ukey" not in text


def test_probe_tmd_never_prints_the_nwp_token_on_a_transport_error():
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        raise OSError(f"connection refused, auth={headers}")

    rc = probe.run_tmd(_secret({"TMD_NWP_TOKEN": SAMPLE_JWT}), out=out, fetch=fetch,
                        sleep=lambda s: None)
    assert rc == 0
    assert SAMPLE_JWT not in out.getvalue()


def test_probe_gistda_with_no_key_makes_no_request_and_fails():
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        raise AssertionError("must not be called")

    rc = probe.run_gistda(_secret({}), out=out, fetch=fetch, sleep=lambda s: None)
    assert rc == 1
    assert "ยังไม่มี key" in out.getvalue()


def test_probe_gistda_sends_the_key_as_a_query_param_and_never_prints_it():
    calls = []
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        calls.append((url, headers))
        body = json.dumps({"features": [
            {"properties": {"pv_tn": "เชียงใหม่", "date": "2026-09-26", "owner": "someone"}},
        ]}).encode()
        return 200, body

    secret_key = "gistda-super-secret-key"
    rc = probe.run_gistda(_secret({"GISTDA_API_KEY": secret_key}), out=out, fetch=fetch,
                          sleep=lambda s: None)
    assert rc == 0
    assert len(calls) == len(probe.GISTDA_ENDPOINTS)
    for url, headers in calls:
        assert f"api_key={secret_key}" in url
        assert not headers
    text = out.getvalue()
    assert secret_key not in text
    assert "owner" not in text  # personal-looking field must not survive into print


def test_probe_gistda_reports_a_non_200_status_honestly():
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        return 401, b"unauthorized"

    rc = probe.run_gistda(_secret({"GISTDA_API_KEY": "k"}), out=out, fetch=fetch, sleep=lambda s: None)
    assert rc == 0
    assert "401" in out.getvalue()


def test_probe_json_output_is_valid_json_and_still_redacted():
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        return 200, b'{"features": []}'

    rc = probe.run_gistda(_secret({"GISTDA_API_KEY": "topsecret"}), out=out, fetch=fetch, as_json=True,
                          sleep=lambda s: None)
    assert rc == 0
    text = out.getvalue()
    parsed = json.loads(text)
    assert isinstance(parsed, list) and len(parsed) == len(probe.GISTDA_ENDPOINTS)
    assert "topsecret" not in text


def test_probe_run_dispatches_by_name_and_rejects_unknown():
    assert probe.run("nope", _secret({})) == 2


def test_probe_pauses_between_calls_but_not_before_the_first(monkeypatch):
    sleeps = []

    def fetch(url, timeout, limit, headers=None):
        return 200, b'{"features": []}'

    probe.run_gistda(_secret({"GISTDA_API_KEY": "k"}), fetch=fetch, sleep=sleeps.append)
    assert len(sleeps) == len(probe.GISTDA_ENDPOINTS) - 1
    assert all(s == probe.PAUSE_SECONDS for s in sleeps)
