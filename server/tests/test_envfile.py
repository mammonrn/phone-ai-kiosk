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

def test_tmd_is_retired_not_a_settable_key_group(tmp_path):
    # TMDAPI (uid/ukey) was dropped for good (Poom 2026-09-26: no way to sign
    # up) — "tmd" must not be a group `keys set` can write to any more, but
    # `keys unset` still needs to find it to clean an old env file.
    assert "tmd" not in envfile.KEY_GROUPS
    assert envfile.RETIRED_GROUPS["tmd"] == ("TMD_UID", "TMD_UKEY")
    assert "TMD_UID" not in envfile.SETTABLE
    assert "TMD_UKEY" not in envfile.SETTABLE


def test_replace_secrets_overwrites_where_set_key_would_refuse(tmp_path):
    path = _env(tmp_path, "TUYA_ACCESS_ID=old-id\nGROQ_API_KEY=ggg\nTUYA_ACCESS_SECRET=old-secret\n")
    envfile.replace_secrets(path, {"TUYA_ACCESS_ID": "new-id", "TUYA_ACCESS_SECRET": "new-secret"})
    lines = path.read_text(encoding="utf-8").splitlines()
    assert "TUYA_ACCESS_ID=new-id" in lines
    assert "TUYA_ACCESS_SECRET=new-secret" in lines
    assert "GROQ_API_KEY=ggg" in lines  # untouched, order aside
    assert "TUYA_ACCESS_ID=old-id" not in lines
    assert "TUYA_ACCESS_SECRET=old-secret" not in lines
    # No duplicate lines for the replaced names.
    assert sum(1 for l in lines if l.startswith("TUYA_ACCESS_ID=")) == 1
    assert sum(1 for l in lines if l.startswith("TUYA_ACCESS_SECRET=")) == 1


def test_replace_secrets_adds_a_name_not_previously_present(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    envfile.replace_secrets(path, {"TUYA_ACCESS_ID": SECRET})
    assert f"TUYA_ACCESS_ID={SECRET}" in path.read_text(encoding="utf-8").splitlines()


def test_replace_secrets_rejects_an_unknown_name_and_writes_nothing(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    with pytest.raises(envfile.EnvError):
        envfile.replace_secrets(path, {"NOT_A_REAL_NAME": SECRET})
    assert path.read_text(encoding="utf-8") == "GROQ_API_KEY=ggg\n"


def test_replace_secrets_rejects_the_retired_tmd_uid_and_writes_nothing(tmp_path):
    # TMD_UID is no longer in SETTABLE at all — `keys set tmd` must refuse,
    # not silently write it (see __main__._keys_set's own refusal for the
    # group name; this is the same refusal one level down).
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    with pytest.raises(envfile.EnvError):
        envfile.replace_secrets(path, {"TMD_UID": "some-value"})
    assert path.read_text(encoding="utf-8") == "GROQ_API_KEY=ggg\n"


def test_replace_secrets_rejects_a_bad_value_and_writes_nothing(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    with pytest.raises(envfile.EnvError):
        envfile.replace_secrets(path, {"TUYA_ACCESS_ID": "has a space"})
    assert path.read_text(encoding="utf-8") == "GROQ_API_KEY=ggg\n"


def test_replace_secrets_leaves_no_temp_file_behind(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    envfile.replace_secrets(path, {"TUYA_ACCESS_ID": SECRET, "TUYA_ACCESS_SECRET": SECRET})
    leftovers = [p for p in tmp_path.iterdir() if p.name != "env"]
    assert leftovers == []


@pytest.mark.skipif(os.name != "posix", reason="file modes are POSIX")
def test_replace_secrets_leaves_the_file_0600(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    os.chmod(path, 0o644)
    envfile.replace_secrets(path, {"TUYA_ACCESS_ID": SECRET, "TUYA_ACCESS_SECRET": SECRET})
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


# ---------------------------------------------- new key groups (0.69: gistda)

def test_gistda_and_tmd_nwp_are_known_key_groups():
    assert envfile.KEY_GROUPS["gistda"] == ("GISTDA_API_KEY",)
    assert envfile.KEY_GROUPS["tmd-nwp"] == ("TMD_NWP_TOKEN",)
    assert "GISTDA_API_KEY" in envfile.SETTABLE
    assert "TMD_NWP_TOKEN" in envfile.SETTABLE


# --------------------------------------------------------------- remove_secrets

def test_remove_secrets_deletes_only_the_named_lines(tmp_path):
    path = _env(tmp_path, "TMD_UID=wrong-value\nGROQ_API_KEY=ggg\nTMD_UKEY=also-wrong\n")
    removed = envfile.remove_secrets(path, ("TMD_UID", "TMD_UKEY"))
    assert removed == 2
    lines = path.read_text(encoding="utf-8").splitlines()
    assert lines == ["GROQ_API_KEY=ggg"]


def test_remove_secrets_on_a_group_with_nothing_present_is_a_no_op(tmp_path):
    path = _env(tmp_path, "GROQ_API_KEY=ggg\n")
    removed = envfile.remove_secrets(path, ("TMD_UID", "TMD_UKEY"))
    assert removed == 0
    assert path.read_text(encoding="utf-8") == "GROQ_API_KEY=ggg\n"


def test_remove_secrets_on_a_missing_file_is_a_no_op(tmp_path):
    path = tmp_path / "env"
    assert envfile.remove_secrets(path, ("TMD_UID",)) == 0
    assert not path.exists()


def test_remove_secrets_only_removes_the_named_half_of_a_pair(tmp_path):
    # The exact 2026-09-26 mix-up: TMD_UKEY held a GISTDA value. Unsetting
    # the whole tmd group must not disturb an unrelated key like gistda's.
    path = _env(tmp_path, "TMD_UID=x\nTMD_UKEY=y\nGISTDA_API_KEY=z\n")
    removed = envfile.remove_secrets(path, envfile.RETIRED_GROUPS["tmd"])
    assert removed == 2
    lines = path.read_text(encoding="utf-8").splitlines()
    assert lines == ["GISTDA_API_KEY=z"]


def test_remove_secrets_leaves_no_temp_file_behind(tmp_path):
    path = _env(tmp_path, "TMD_UID=x\nTMD_UKEY=y\n")
    envfile.remove_secrets(path, ("TMD_UID", "TMD_UKEY"))
    leftovers = [p for p in tmp_path.iterdir() if p.name != "env"]
    assert leftovers == []


@pytest.mark.skipif(os.name != "posix", reason="file modes are POSIX")
def test_remove_secrets_leaves_the_file_0600(tmp_path):
    path = _env(tmp_path, "TMD_UID=x\nTMD_UKEY=y\n")
    os.chmod(path, 0o644)
    envfile.remove_secrets(path, ("TMD_UID",))
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
