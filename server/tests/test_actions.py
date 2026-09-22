"""action must be null in phase 2, whatever the model says."""

from kiosk_broker import actions


def test_no_action_types_are_enabled_in_phase_2():
    assert actions.ENABLED_ACTION_TYPES == frozenset()


def test_plain_text_has_no_action():
    text, raw = actions.extract("วันนี้อากาศดีครับ")
    assert text == "วันนี้อากาศดีครับ"
    assert raw is None


def test_a_marker_is_parsed_but_dropped():
    text, raw = actions.extract("ไปเซ็นทรัลเวิลด์นะครับ [[action: open_maps | เซ็นทรัลเวิลด์]]")
    assert raw == {"type": "open_maps", "query": "เซ็นทรัลเวิลด์"}
    # Parsed for phase 4, refused for phase 2.
    assert actions.sanitize(raw) is None
    # And never read out loud.
    assert "[[" not in text
    assert text == "ไปเซ็นทรัลเวิลด์นะครับ"


def test_an_unknown_action_type_is_dropped_too():
    _, raw = actions.extract("เรียบร้อย [[action: wipe_device]]")
    assert actions.sanitize(raw) is None


def test_sanitize_accepts_nothing_at_all():
    for candidate in [
        {"type": "open_maps", "query": "x"},
        {"type": "open_maps"},
        {"type": "run_shell", "query": "rm -rf /"},
        {},
        None,
    ]:
        assert actions.sanitize(candidate) is None


def test_only_the_first_marker_is_taken_and_all_are_stripped():
    text, raw = actions.extract("ก [[action: open_maps | a]] ข [[action: open_maps | b]]")
    assert raw["query"] == "a"
    assert "[[" not in text


def test_a_long_argument_is_truncated_not_rejected():
    _, raw = actions.extract("ok [[action: open_maps | " + "ก" * 500 + "]]")
    assert len(raw["query"]) == actions.MAX_ARG_CHARS


def test_enabling_a_type_is_the_only_switch_needed(monkeypatch):
    """Proves the allowlist is what gates it — the phase 4 change, tested now."""
    monkeypatch.setattr(actions, "ENABLED_ACTION_TYPES", frozenset({"open_maps"}))
    _, raw = actions.extract("ไปครับ [[action: open_maps | สยาม]]")
    assert actions.sanitize(raw) == {"type": "open_maps", "query": "สยาม"}
    # And still nothing else.
    _, other = actions.extract("[[action: open_shell]]")
    assert actions.sanitize(other) is None
