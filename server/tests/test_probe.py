"""`probe tmd`/`probe gistda`: redaction, personal-data stripping, the JWT
peek, and full runs against a fake `fetch` — never a real socket."""

from __future__ import annotations

import io
import json
import urllib.parse

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


def test_probe_tmd_with_only_the_retired_uid_ukey_reports_no_key_and_makes_no_request():
    # TMDAPI (uid/ukey) was dropped for good (Poom 2026-09-26: no way to sign
    # up) — even if old values are still sitting in the env file, `probe tmd`
    # must not use them, and it must not make any request.
    calls = []
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        calls.append(url)
        raise AssertionError("must not be called")

    rc = probe.run_tmd(_secret({"TMD_UID": "u", "TMD_UKEY": "k"}), out=out, fetch=fetch,
                        sleep=lambda s: None)
    assert rc == 1
    assert calls == []
    assert "ยังไม่มี key" in out.getvalue()


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


def test_probe_tmd_runs_nwp_even_when_the_retired_uid_ukey_are_also_set():
    # Both may be present at once (an old env file plus the current NWP
    # token) — `probe tmd` still only ever probes NWP.
    calls = []

    def fetch(url, timeout, limit, headers=None):
        calls.append(url)
        return 200, b"{}"

    rc = probe.run_tmd(
        _secret({"TMD_UID": "u", "TMD_UKEY": "k", "TMD_NWP_TOKEN": SAMPLE_JWT}),
        out=io.StringIO(), fetch=fetch, sleep=lambda s: None,
    )
    assert rc == 0
    assert len(calls) == len(probe.NWP_ENDPOINTS)


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


# ------------------------------------------------------- nested discovery ---

def test_discover_records_finds_the_deepest_nested_list():
    # Shaped like data.tmd.go.th/nwpapi's own docs:
    # {"WeatherForecasts": [{"location": {...}, "forecasts": [{"time": ..., "data": {...}}]}]}
    body = {
        "WeatherForecasts": [
            {"location": {"province": "กรุงเทพมหานคร"},
             "forecasts": [
                 {"time": "2026-09-26T00:00:00", "data": {"tc": 30, "rh": 80}},
                 {"time": "2026-09-26T03:00:00", "data": {"tc": 29, "rh": 82}},
             ]},
        ],
    }
    path, records = probe._discover_records(body)
    assert path == "WeatherForecasts[].forecasts[]"
    assert [r["time"] for r in records] == ["2026-09-26T00:00:00", "2026-09-26T03:00:00"]


def test_discover_records_empty_for_no_nested_list():
    assert probe._discover_records({"a": 1, "b": "x"}) == ("", [])


def test_flatten_record_merges_one_nested_dict_field():
    flat = probe._flatten_record({"time": "t1", "data": {"tc": 30, "rh": 80}})
    assert flat == {"time": "t1", "tc": 30, "rh": 80}


def test_probe_tmd_nwp_shows_flattened_fields_and_a_time_range():
    out = io.StringIO()

    def fetch(url, timeout, limit, headers=None):
        body = {"WeatherForecasts": [
            {"location": {"province": "กรุงเทพมหานคร"},
             "forecasts": [
                 {"time": "2026-09-26T00:00:00", "data": {"tc": 30, "rh": 80}},
                 {"time": "2026-09-27T00:00:00", "data": {"tc": 29, "rh": 82}},
             ]},
        ]}
        return 200, json.dumps(body).encode("utf-8")

    rc = probe.run_tmd(_secret({"TMD_NWP_TOKEN": SAMPLE_JWT}), out=out, fetch=fetch, sleep=lambda s: None)
    assert rc == 0
    text = out.getvalue()
    assert "records path: WeatherForecasts[].forecasts[]" in text
    assert "tc" in text and "rh" in text
    assert "ตั้งแต่ 2026-09-26T00:00:00 ถึง 2026-09-27T00:00:00" in text


# ------------------------------------------------------------- pagination ---

def _feature(i):
    return {"id": i, "properties": {"pv_tn": f"จังหวัด{i % 3}", "img_date": f"2026-09-{10 + i:02d}"}}


def test_paginate_features_follows_offset_until_a_short_page(monkeypatch):
    pages = [[_feature(i) for i in range(probe.GISTDA_PAGE_LIMIT)],
              [_feature(i) for i in range(probe.GISTDA_PAGE_LIMIT, probe.GISTDA_PAGE_LIMIT + 3)]]
    calls = []

    def fetch(url, timeout, limit, headers=None):
        calls.append(url)
        page = pages[len(calls) - 1]
        return 200, json.dumps({"features": page}).encode("utf-8")

    slept = []
    records, total_bytes, pages_fetched, note, status = probe._paginate_features(
        "https://x/features/flood/1day?bbox=a", [], fetch, None, slept.append, probe.FETCH_TIMEOUT)
    assert status == 200 and pages_fetched == 2 and note == ""
    assert len(records) == probe.GISTDA_PAGE_LIMIT + 3
    assert "offset=0" in calls[0] and f"offset={probe.GISTDA_PAGE_LIMIT}" in calls[1]
    assert slept == [probe.GISTDA_PAGE_PAUSE_SECONDS]


def test_paginate_features_stops_on_a_repeated_page(monkeypatch):
    page = [_feature(i) for i in range(probe.GISTDA_PAGE_LIMIT)]
    calls = []

    def fetch(url, timeout, limit, headers=None):
        calls.append(url)
        return 200, json.dumps({"features": page}).encode("utf-8")  # same page every time: offset ignored

    records, total_bytes, pages_fetched, note, status = probe._paginate_features(
        "https://x/features/flood/1day", [], fetch, None, lambda s: None, probe.FETCH_TIMEOUT)
    assert pages_fetched == 2  # asked a second time, saw the same ids, stopped
    assert "offset not honoured" in note
    assert len(records) == probe.GISTDA_PAGE_LIMIT  # only the first page kept


def test_paginate_features_stops_at_the_page_cap(monkeypatch):
    full_page = [_feature(i) for i in range(probe.GISTDA_PAGE_LIMIT)]

    def fetch(url, timeout, limit, headers=None):
        # A different id set each time so the repeat-detector never fires —
        # only the hard page cap should stop this.
        offset = int(dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))["offset"])
        page = [_feature(i + offset) for i in range(probe.GISTDA_PAGE_LIMIT)]
        return 200, json.dumps({"features": page}).encode("utf-8")

    records, total_bytes, pages_fetched, note, status = probe._paginate_features(
        "https://x/features/flood/1day", [], fetch, None, lambda s: None, probe.FETCH_TIMEOUT)
    assert pages_fetched == probe.GISTDA_MAX_PAGES
    assert f"{probe.GISTDA_MAX_PAGES}-page cap" in note


def test_paginate_features_first_page_non_200_is_reported_plainly():
    def fetch(url, timeout, limit, headers=None):
        return 404, b"not found"

    records, total_bytes, pages_fetched, note, status = probe._paginate_features(
        "https://x/features/flood-freq", [], fetch, None, lambda s: None, probe.FETCH_TIMEOUT)
    assert status == 404 and records == [] and pages_fetched == 1


def test_run_gistda_reports_the_true_total_across_pages():
    def fetch(url, timeout, limit, headers=None):
        offset = int(dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))["offset"])
        if offset == 0:
            page = [_feature(i) for i in range(probe.GISTDA_PAGE_LIMIT)]
        elif offset == probe.GISTDA_PAGE_LIMIT:
            page = [_feature(i) for i in range(probe.GISTDA_PAGE_LIMIT, probe.GISTDA_PAGE_LIMIT + 7)]
        else:
            page = []
        return 200, json.dumps({"features": page}).encode("utf-8")

    out = io.StringIO()
    rc = probe.run_gistda(_secret({"GISTDA_API_KEY": "k"}), out=out, fetch=fetch, sleep=lambda s: None)
    assert rc == 0
    text = out.getvalue()
    assert f"{probe.GISTDA_PAGE_LIMIT + 7} รายการ" in text
    assert "pages       : 2" in text


# --------------------------------------------------------------- endpoint fixes ---

def test_flood_freq_uses_a_longer_timeout():
    spec = next(s for s in probe.GISTDA_ENDPOINTS if "flood-freq" in s.url)
    assert spec.timeout > probe.FETCH_TIMEOUT

    seen_timeouts = []

    def fetch(url, timeout, limit, headers=None):
        if "flood-freq" in url:
            seen_timeouts.append(timeout)
        return 200, b'{"features": []}'

    probe.run_gistda(_secret({"GISTDA_API_KEY": "k"}), fetch=fetch, sleep=lambda s: None)
    assert seen_timeouts == [spec.timeout]


def test_drought_recurrence_uses_the_working_api_version():
    spec = next(s for s in probe.GISTDA_ENDPOINTS if "drought-recurrence" in s.url)
    assert "/v1.1/" in spec.url and "/v1.0/" not in spec.url
