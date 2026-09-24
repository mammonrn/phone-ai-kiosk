"""0.51.0: a map command is heard by Qwen as well, and every other command is
exactly as it was (Poom, 2026-09-24). See maps_rescue.py."""

from __future__ import annotations

import json
import logging
import time

import pytest

from kiosk_broker import (auth, envfile, free_tier, maps_rescue, qwen_stt, store, stt_hints,
                          thai_numbers)
from kiosk_broker.pricing import Pricing
from kiosk_broker.service import handle_stt

from conftest import FakeGroq
from test_qwen_stt import QWEN_KEY, FakeQwen
from test_stt_providers import wav


@pytest.fixture(autouse=True)
def fresh():
    maps_rescue.reset_for_tests()
    yield
    maps_rescue.reset_for_tests()


@pytest.fixture
def key(cfg):
    cfg.env_path.write_text(f"QWEN_API_KEY={QWEN_KEY}\n", encoding="utf-8")


@pytest.fixture
def hints(cfg):
    path = cfg.home / stt_hints.FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"phrases": ["จาร์วิส", "กล้อง", "ไฟ"],
                                "qwen_phrases": ["ภูชี้ฟ้า", "ดอยตุง"]}, ensure_ascii=False),
                    encoding="utf-8")
    return path


def _stt(conn, cfg, groq_text, qwen, provider=None, wake=None, seconds=3.0):
    token = auth.issue(conn, "kiosk-a07")
    return handle_stt(conn, cfg, FakeGroq(text=groq_text, duration=seconds),
                      authorization=f"Bearer {token}", content_type="audio/wav",
                      body=wav(seconds), provider=provider, wake=wake, qwen_transport=qwen)


def _qwen_rows(conn):
    return conn.execute("SELECT model, quantity, cost_usd FROM usage"
                        " WHERE service = 'stt' AND model LIKE 'qwen-%'").fetchall()


def _allowance(cfg):
    return free_tier.allowances(Pricing.load(cfg.pricing_path), voice_family=cfg.tts_voice_family,
                                google_stt_model=cfg.google_stt_model,
                                qwen_stt_model=cfg.qwen_stt_model)[free_tier.STT_QWEN]


# ------------------------------------------------------------ other commands

@pytest.mark.parametrize("said", ["เปิดไฟหน้าบ้าน", "ขอดูกล้องหน่อยครับ", "ตั้งปลุก 11 โมงเช้า",
                                  "วันนี้อากาศเป็นยังไง", "วันนี้มีนัดอะไรบ้าง"])
def test_other_commands_never_reach_qwen_nor_read_its_key(conn, cfg, key, hints, monkeypatch, said):
    qwen = FakeQwen(text="ไม่ควรถูกเรียก")
    monkeypatch.setattr(envfile, "reader", lambda _p: pytest.fail("key read for a non-map turn"))
    status, body = _stt(conn, cfg, said, qwen)
    assert status == 200 and body["text"] == said and body["rescue"] == "-"
    assert body["provider"] == "groq-hints"
    assert not qwen.requests and not _qwen_rows(conn)


def test_other_transcribers_are_never_second_guessed(conn, cfg, key, hints):
    qwen = FakeQwen(text="พาไปภูชี้ฟ้า")
    status, body = _stt(conn, cfg, "พาไปพูชีฟ้า", qwen, provider="groq")
    assert body["text"] == "พาไปพูชีฟ้า" and body["rescue"] == "-" and not qwen.requests


def test_the_groq_prompt_is_unchanged_by_qwen_only_words(hints):
    loaded = stt_hints.load(hints)
    assert loaded.whisper_prompt() == "จาร์วิส กล้อง ไฟ"
    assert qwen_stt.context_text(loaded) == "จาร์วิส กล้อง ไฟ ภูชี้ฟ้า ดอยตุง"


def test_the_shipped_hints_file_keeps_groqs_prompt_and_fits_qwens_400():
    shipped = stt_hints.load(__import__("pathlib").Path("stt_hints.json"))
    assert shipped is not None
    assert len(shipped.whisper_prompt()) <= stt_hints.MAX_PROMPT_CHARS
    assert len(qwen_stt.context_text(shipped)) <= qwen_stt.MAX_CONTEXT_CHARS


# ------------------------------------------------------------ map commands

def test_a_map_command_takes_the_place_name_from_qwen(conn, cfg, key, hints, caplog):
    qwen = FakeQwen(text="พาไปภูชี้ฟ้าเชียงราย", seconds=3)
    with caplog.at_level(logging.INFO):
        status, body = _stt(conn, cfg, "พาไปพูชีฟ้าเซ็นลาย", qwen)
    assert status == 200 and body["text"] == "พาไปภูชี้ฟ้าเชียงราย" and body["rescue"] == "used"
    assert body["gate"]["pass"] and len(qwen.requests) == 1
    sent = json.loads(qwen.requests[0].data)
    assert "ภูชี้ฟ้า" in sent["input"]["messages"][0]["content"][0]["text"]
    # From the free seconds: in the ledger, nothing paid.
    assert [tuple(r) for r in _qwen_rows(conn)] == [("qwen-qwen3-asr-flash-2026-02-10", 3.0, 0.0)]
    assert "text_from=qwen rescue=used" in caplog.text and "rescue_ms=" in caplog.text
    assert "ภูชี้ฟ้า" not in caplog.text                     # never the words


def test_the_same_answer_is_logged_as_same(conn, cfg, key, hints, caplog):
    with caplog.at_level(logging.INFO):
        _, body = _stt(conn, cfg, "พาไปดอยตุง", FakeQwen(text="พาไปดอยตุง"))
    assert body["rescue"] == "same" and "text_from=qwen rescue=same" in caplog.text


def test_qwens_number_words_become_digits(conn, cfg, key, hints):
    _, body = _stt(conn, cfg, "นำทางไปซอย 3", FakeQwen(text="นำทางไปซอยสาม"))
    assert body["text"] == "นำทางไปซอย 3"


def test_the_wake_word_is_cut_from_qwens_answer_too(conn, cfg, key, hints):
    _, body = _stt(conn, cfg, "จาร์วิส พาไปดอยตรง", FakeQwen(text="จาร์วิส พาไปดอยตุง"), wake="0.9")
    assert body["text"] == "พาไปดอยตุง"


@pytest.mark.parametrize("qwen, why", [
    (FakeQwen(status=500, code="InternalError"), "fallback-error"),
    (FakeQwen(status=429, code="Throttling"), "fallback-rate-limit"),
    (FakeQwen(status=401, code="InvalidApiKey"), "fallback-auth"),
    (FakeQwen(text=""), "fallback-empty"),
    (FakeQwen(text="วันนี้อากาศดี"), "fallback-not-maps"),
])
def test_a_qwen_problem_never_breaks_the_command(conn, cfg, key, hints, qwen, why, caplog):
    with caplog.at_level(logging.INFO):
        status, body = _stt(conn, cfg, "พาไปดอยตรง", qwen)
    assert status == 200 and body["text"] == "พาไปดอยตรง" and body["rescue"] == why
    assert f"text_from=groq-hints rescue={why}" in caplog.text
    assert QWEN_KEY not in caplog.text


def test_a_slow_qwen_is_not_waited_for(conn, cfg, key, hints):
    cfg = type(cfg)(**{**cfg.__dict__, "maps_rescue_timeout_s": 0.2})

    class Slow(FakeQwen):
        def __call__(self, request, timeout):
            time.sleep(1.0)
            return super().__call__(request, timeout)

    started = time.monotonic()
    status, body = _stt(conn, cfg, "พาไปดอยตรง", Slow(text="พาไปดอยตุง"))
    assert time.monotonic() - started < 0.9
    assert status == 200 and body["text"] == "พาไปดอยตรง" and body["rescue"] == "fallback-timeout"
    # It may still be billed by Alibaba: counted against the free seconds anyway.
    assert [r["quantity"] for r in _qwen_rows(conn)] == [3.0]


def test_no_key_means_groq_alone(conn, cfg, hints):
    qwen = FakeQwen()
    _, body = _stt(conn, cfg, "พาไปดอยตรง", qwen)
    assert body["rescue"] == "off-no-key" and body["text"] == "พาไปดอยตรง" and not qwen.requests


def test_switched_off_in_config(conn, cfg, key, hints):
    cfg = type(cfg)(**{**cfg.__dict__, "maps_rescue": False})
    qwen = FakeQwen(text="พาไปดอยตุง")
    _, body = _stt(conn, cfg, "พาไปดอยตรง", qwen)
    assert body["rescue"] == "-" and not qwen.requests


# ------------------------------------------------------------ the free seconds

def test_it_stops_by_itself_when_the_free_seconds_are_used_up(conn, cfg, key, hints, caplog):
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=35_998, unit="seconds",
                                cost_usd=0.0)
    qwen = FakeQwen(text="พาไปดอยตุง")
    with caplog.at_level(logging.WARNING):
        _, first = _stt(conn, cfg, "พาไปดอยตรง", qwen)          # 3 s does not fit in 2
        _, again = _stt(conn, cfg, "พาไปดอยตรง", qwen)
    assert first["rescue"] == again["rescue"] == "off-quota" and not qwen.requests
    assert first["text"] == "พาไปดอยตรง"
    assert caplog.text.count("maps rescue stopped: Qwen's free seconds are used up") == 1
    assert not _qwen_rows(conn)                                  # never a paid second


def test_after_the_last_day_it_stops(conn, cfg, key, hints):
    allowance = _allowance(cfg)
    assert maps_rescue.quota_state(conn, allowance, 3.0, now=allowance.until + 1) == "expired"
    assert maps_rescue.quota_state(conn, allowance, 3.0, now=allowance.until - 60) == "ok"


def test_alibaba_saying_the_quota_is_gone_stops_it_until_restart(conn, cfg, key, hints, caplog):
    with caplog.at_level(logging.WARNING):
        _, first = _stt(conn, cfg, "พาไปดอยตรง",
                        FakeQwen(status=403, code="AllocationQuota.FreeTierOnly"))
        qwen = FakeQwen(text="พาไปดอยตุง")
        _, again = _stt(conn, cfg, "พาไปดอยตรง", qwen)
    assert first["rescue"] == "fallback-vendor-quota" and again["rescue"] == "off-vendor-quota"
    assert not qwen.requests and "Alibaba says the free quota is used up" in caplog.text


def test_warned_at_80_percent_by_a_map_command(conn, cfg, key, hints, caplog):
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=28_798, unit="seconds",
                                cost_usd=0.0)
    with caplog.at_level(logging.WARNING):
        _, body = _stt(conn, cfg, "พาไปดอยตรง", FakeQwen(text="พาไปดอยตุง", seconds=3))
    assert body["rescue"] == "used" and "free tier 80% used: Qwen ASR" in caplog.text


def test_usage_says_whether_it_is_on(conn, cfg):
    allowance = _allowance(cfg)
    assert maps_rescue.status_line(conn, True, allowance).startswith("on")
    assert maps_rescue.status_line(conn, False, allowance).startswith("off in config")
    store.record_training_usage(conn, job="x", service="stt:qwen", quantity=36_000, unit="seconds",
                                cost_usd=0.0)
    assert maps_rescue.status_line(conn, True, allowance).startswith("STOPPED")


# ------------------------------------------------------------ number words

@pytest.mark.parametrize("heard, written", [
    ("ตั้งปลุกสิบเอ็ดโมงเช้า", "ตั้งปลุก 11 โมงเช้า"),
    ("ตั้งปลุกหกโมง", "ตั้งปลุก 6 โมง"),
    ("ปลุกตีห้า", "ปลุกตี 5"),
    ("หกโมงสิบห้า", "6 โมง 15"),
    ("สองทุ่มครึ่ง", "2 ทุ่มครึ่ง"),
    ("ปลุก ๖ โมง", "ปลุก 6 โมง"),
    ("อีกยี่สิบห้านาที", "อีก 25 นาที"),
    # Names and words with a number word inside stay as they are.
    ("พาไปวัดเจ็ดยอด", "พาไปวัดเจ็ดยอด"),
    ("พาไปห้าแยกพ่อขุน", "พาไปห้าแยกพ่อขุน"),
    ("พาไปสามเหลี่ยมทองคำ", "พาไปสามเหลี่ยมทองคำ"),
    ("พาไปห้างเซ็นทรัล", "พาไปห้างเซ็นทรัล"),
    ("พาไปร้อยเอ็ด", "พาไปร้อยเอ็ด"),
    ("นำทางไปล้านนา", "นำทางไปล้านนา"),
    ("อยู่สองสามวัน", "อยู่สองสามวัน"),
    ("วันนี้มีนัดอะไรบ้าง", "วันนี้มีนัดอะไรบ้าง"),
])
def test_number_words(heard, written):
    assert thai_numbers.to_digits(heard) == written


def test_number_values():
    assert thai_numbers.value("สิบเอ็ด") == 11
    assert thai_numbers.value("ยี่สิบเอ็ด") == 21
    assert thai_numbers.value("สองพันห้าร้อย") == 2500
    assert thai_numbers.value("หนึ่งล้านสองแสน") == 1_200_000
    assert thai_numbers.value("สองสาม") is None
    assert thai_numbers.value("ยี่") is None
    assert thai_numbers.value("เอ็ด") is None


# ------------------------------------------------------------ the real-audio test command

def test_stt_compare_rescue_runs_the_production_path(tmp_path, monkeypatch, capsys):
    """`stt-compare --providers groq-hints,rescue --maps-check`, with every
    vendor faked: the map recording gets Qwen's name and a Maps destination,
    the other recording never reaches Qwen, and the spend is in the ledger."""
    import shutil
    from types import SimpleNamespace

    from kiosk_broker import __main__ as cli, config as config_mod, llm, stt_router
    from kiosk_broker.stt import Transcript

    shutil.copy("pricing.json", tmp_path / "pricing.json")
    (tmp_path / "env").write_text(f"QWEN_API_KEY={QWEN_KEY}\nANTHROPIC_API_KEY=x\n", encoding="utf-8")
    audio = tmp_path / "wavs"
    audio.mkdir()
    (audio / "a-map.wav").write_bytes(wav(3.0))
    (audio / "b-light.wav").write_bytes(wav(2.0))
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)

    class Groq(FakeGroq):
        def create(self, **kwargs):
            size = len(kwargs["file"][1].getvalue())
            self.text = "พาไปดอยตรง" if size > len(wav(2.5)) else "เปิดไฟหน้าบ้าน"
            return super().create(**kwargs)

    qwen_calls = []

    def fake_qwen(**kwargs):
        qwen_calls.append(kwargs)
        return Transcript("พาไปดอยตุง", 3.0)

    monkeypatch.setattr(cli, "_stt_client", lambda: Groq())
    monkeypatch.setattr(stt_router.qwen_stt, "recognize", fake_qwen)
    asked = []

    def fake_ask(client, *, model, system, messages, max_tokens):
        asked.append(messages[0]["content"])
        where = messages[0]["content"].replace("พาไป", "")
        usage = SimpleNamespace(input_tokens=1000, output_tokens=40, cache_write_tokens=0,
                                cache_read_tokens=0)
        return SimpleNamespace(text=f"กำลังเปิดแผนที่ไป{where}ครับ [[action:open_maps|{where}]]",
                               usage=usage)

    monkeypatch.setattr(llm, "ask", fake_ask)
    monkeypatch.setattr(cli, "_client", lambda cfg: object())

    assert cli.main(["stt-compare", "--dir", str(audio), "--providers", "groq-hints,rescue",
                     "--maps-check"]) == 0
    out = capsys.readouterr().out
    assert "<used +" in out and "พาไปดอยตุง" in out
    assert "<-> เปิดไฟหน้าบ้าน" in out
    assert len(qwen_calls) == 1
    assert "rescue: 2 turns, 1 sent to Qwen" in out
    assert asked == ["พาไปดอยตรง", "พาไปดอยตุง"]            # Groq alone, and with the rescue
    assert "Maps: ดอยตุง" in out and "Maps: ดอยตรง" in out
    conn = store.connect(tmp_path / "broker.db")
    try:
        spent = {r["service"]: r["q"] for r in conn.execute(
            "SELECT service, SUM(quantity) AS q FROM training_usage WHERE job = 'stt-compare'"
            " GROUP BY service")}
    finally:
        conn.close()
    assert spent["stt:qwen"] == 3.0 and spent["chat"] == 2


def test_the_approved_hints_keep_groqs_prompt_and_every_word_reaches_qwen():
    from pathlib import Path

    shipped = stt_hints.load(Path("stt_hints.json"))
    # Groq's prompt is exactly what it was before 0.51.0, character for character.
    assert shipped.whisper_prompt() == (
        "จาร์วิส กล้อง ขอดูกล้อง เปิดกล้อง ดูกล้อง ตั้งปลุก ปลุก แผนที่ พาไป เซ็นทรัล "
        "เซ็นทรัลเชียงราย เชียงราย อากาศ ราคาทอง แม่ฟ้าหลวง ไฟ")
    assert {"ภูชี้ฟ้า", "ดอยตุง", "สิงห์ปาร์ค", "วัดร่องขุ่น", "ไฟหน้าบ้าน", "ไฟด้านหน้า"}         <= set(shipped.qwen_phrases)
    assert len(qwen_stt.context_text(shipped)) <= qwen_stt.MAX_CONTEXT_CHARS
    # None is cut off at the limit.
    assert len(qwen_stt.context_phrases(shipped)) == len(shipped.phrases) + len(shipped.qwen_phrases)
