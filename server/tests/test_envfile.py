"""The env file: one line appended, nothing else touched, nothing echoed."""

from __future__ import annotations

import os
import stat

import pytest

from kiosk_broker import envfile

SECRET = "s3cr3t-value-never-printed-0123456789"


def _env(tmp_path, text: str):
    path = tmp_path / "env"
    path.write_bytes(text.encode("utf-8"))
    if os.name == "posix":
        os.chmod(path, 0o600)
    return path


def test_a_key_is_appended_and_every_existing_line_survives_byte_for_byte(tmp_path):
    before = "ANTHROPIC_API_KEY=aaa\n# a comment Poom wrote\nGROQ_API_KEY=ggg\n"
    path = _env(tmp_path, before)
    envfile.append_secret(path, "TUYA_ACCESS_ID", SECRET)
    after = path.read_bytes().decode("utf-8")
    assert after.startswith(before)
    assert after == before + f"TUYA_ACCESS_ID={SECRET}\n"


def test_a_file_without_a_final_newline_does_not_get_its_last_line_glued(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg")
    envfile.append_secret(path, "TUYA_DATA_CENTER", "eu")
    assert path.read_text(encoding="utf-8").splitlines() == [
        "GROQ_API_KEY=ggg", "TUYA_DATA_CENTER=eu"]


def test_a_name_already_present_is_refused_not_duplicated(tmp_path):
    path = _env(tmp_path, f"TUYA_ACCESS_SECRET={SECRET}\n")
    with pytest.raises(envfile.EnvError) as refused:
        envfile.append_secret(path, "TUYA_ACCESS_SECRET", "another-value")
    assert "already present" in str(refused.value)
    assert path.read_text(encoding="utf-8") == f"TUYA_ACCESS_SECRET={SECRET}\n"


def test_an_empty_line_for_a_name_does_not_count_as_present(tmp_path):
    # install.sh writes "ANTHROPIC_API_KEY=" empty; filling it in by append must work.
    path = _env(tmp_path, "TUYA_ACCESS_ID=\n")
    envfile.append_secret(path, "TUYA_ACCESS_ID", SECRET)
    assert f"TUYA_ACCESS_ID={SECRET}" in path.read_text(encoding="utf-8")


@pytest.mark.parametrize("bad,reason", [
    ("", "empty"),
    ("abc\ndef", "line break"),
    ("abc def", "whitespace"),
    ('"abc"', "without quotes"),
])
def test_a_bad_paste_is_refused_and_the_refusal_never_contains_it(tmp_path, bad, reason):
    path = _env(tmp_path, "")
    with pytest.raises(envfile.EnvError) as refused:
        envfile.append_secret(path, "TUYA_ACCESS_SECRET", bad)
    assert reason in str(refused.value)
    if bad.strip():
        assert bad.strip('"') not in str(refused.value)
    assert path.read_text(encoding="utf-8") == ""


def test_an_unknown_name_is_refused(tmp_path):
    path = _env(tmp_path, "")
    with pytest.raises(envfile.EnvError):
        envfile.append_secret(path, "TUYA_ACESS_ID", SECRET)   # the typo
    assert path.read_text(encoding="utf-8") == ""


def test_names_in_reports_names_only(tmp_path):
    path = _env(tmp_path, f"TUYA_ACCESS_ID={SECRET}\nTUYA_DATA_CENTER=\n#X=1\n")
    assert envfile.names_in(path) == {"TUYA_ACCESS_ID"}


@pytest.mark.skipif(os.name != "posix", reason="file modes are POSIX")
def test_the_file_stays_0600_and_is_tightened_if_it_was_not(tmp_path):
    path = _env(tmp_path, "")
    os.chmod(path, 0o644)
    envfile.append_secret(path, "TUYA_DATA_CENTER", "eu")
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.skipif(os.name != "posix", reason="file modes are POSIX")
def test_a_new_file_is_created_0600(tmp_path):
    path = tmp_path / "env"
    old = os.umask(0)
    try:
        envfile.append_secret(path, "TUYA_DATA_CENTER", "eu")
    finally:
        os.umask(old)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


# --------------------------------------------------- replace_secrets / groups

def test_tmd_is_a_known_key_group_of_uid_and_ukey():
    assert envfile.KEY_GROUPS["tmd"] == ("TMD_UID", "TMD_UKEY")


def test_replace_secrets_overwrites_where_set_key_would_refuse(tmp_path):
    path = _env(tmp_path, "TMD_UID=old-uid\nGROQ_API_KEY=ggg\nTMD_UKEY=old-ukey\n")
    envfile.replace_secrets(path, {"TMD_UID": "new-uid", "TMD_UKEY": "new-ukey"})
    lines = path.read_text(encoding="utf-8").splitlines()
    assert "TMD_UID=new-uid" in lines
    assert "TMD_UKEY=new-ukey" in lines
    assert "GROQ_API_KEY=ggg" in lines  # untouched, order aside
    assert "TMD_UID=old-uid" not in lines
    assert "TMD_UKEY=old-ukey" not in lines
    # No duplicate lines for the replaced names.
    assert sum(1 for l in lines if l.startswith("TMD_UID=")) == 1
    assert sum(1 for l in lines if l.startswith("TMD_UKEY=")) == 1


def test_replace_secrets_adds_a_name_not_previously_present(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    envfile.replace_secrets(path, {"TMD_UID": SECRET})
    assert f"TMD_UID={SECRET}" in path.read_text(encoding="utf-8").splitlines()


def test_replace_secrets_rejects_an_unknown_name_and_writes_nothing(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    with pytest.raises(envfile.EnvError):
        envfile.replace_secrets(path, {"NOT_A_REAL_NAME": SECRET})
    assert path.read_text(encoding="utf-8") == "GROQ_API_KEY=ggg\n"


def test_replace_secrets_rejects_a_bad_value_and_writes_nothing(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    with pytest.raises(envfile.EnvError):
        envfile.replace_secrets(path, {"TMD_UID": "has a space"})
    assert path.read_text(encoding="utf-8") == "GROQ_API_KEY=ggg\n"


def test_replace_secrets_leaves_no_temp_file_behind(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    envfile.replace_secrets(path, {"TMD_UID": SECRET, "TMD_UKEY": SECRET})
    leftovers = [p for p in tmp_path.iterdir() if p.name != "env"]
    assert leftovers == []


@pytest.mark.skipif(os.name != "posix", reason="file modes are POSIX")
def test_replace_secrets_leaves_the_file_0600(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    os.chmod(path, 0o644)
    envfile.replace_secrets(path, {"TMD_UID": SECRET, "TMD_UKEY": SECRET})
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
