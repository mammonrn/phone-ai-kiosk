"""The CLI module imports and runs — the file systemd starts.

On 2026-09-23 a syntax error in __main__.py passed every test here and took
production down, because no test imported __main__. These do.
"""

from __future__ import annotations

import pytest

from kiosk_broker import __main__ as cli, config as config_mod


def test_the_cli_module_imports_and_prints_its_help(capsys):
    with pytest.raises(SystemExit) as done:
        cli.main(["--help"])
    assert done.value.code == 0
    assert "serve" in capsys.readouterr().out


def test_read_only_commands_run(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    assert cli.main(["analysis", "status"]) == 0
    assert cli.main(["analysis", "summary"]) == 0
    assert "speech analysis: off" in capsys.readouterr().out


# ---------------------------------------------------------- keys / keys set

import io
import os
import stat


def _stdin_lines(monkeypatch, *lines):
    """A non-tty stdin that answers each getpass() prompt with the next line
    — the same shape `keys set` reads real input from in production, minus
    the terminal."""
    monkeypatch.setattr("sys.stdin", io.StringIO("\n".join(lines) + "\n"))
    monkeypatch.setattr("sys.stdin.isatty", lambda: False, raising=False)


def test_keys_set_tmd_nwp_writes_the_value_hidden_and_never_prints_it(
        tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    _stdin_lines(monkeypatch, "my-nwp-token-123")

    assert cli.main(["keys", "set", "tmd-nwp"]) == 0
    out = capsys.readouterr().out
    assert "my-nwp-token-123" not in out

    env_text = (tmp_path / "env").read_text(encoding="utf-8")
    assert "TMD_NWP_TOKEN=my-nwp-token-123" in env_text


def test_keys_set_tmd_nwp_replaces_an_old_value_where_set_key_would_refuse(
        tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    (tmp_path / "env").write_text("TMD_NWP_TOKEN=old\nGROQ_API_KEY=ggg\n",
                                  encoding="utf-8")

    _stdin_lines(monkeypatch, "new-token")
    assert cli.main(["keys", "set", "tmd-nwp"]) == 0

    lines = (tmp_path / "env").read_text(encoding="utf-8").splitlines()
    assert "TMD_NWP_TOKEN=new-token" in lines
    assert "TMD_NWP_TOKEN=old" not in lines
    assert "GROQ_API_KEY=ggg" in lines


@pytest.mark.skipif(os.name != "posix", reason="file modes are POSIX")
def test_keys_set_tmd_nwp_leaves_the_env_file_0600(tmp_path, monkeypatch):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    _stdin_lines(monkeypatch, "k")
    assert cli.main(["keys", "set", "tmd-nwp"]) == 0
    assert stat.S_IMODE((tmp_path / "env").stat().st_mode) == 0o600


def test_keys_set_an_unknown_group_is_refused(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    assert cli.main(["keys", "set", "not-a-group"]) == 2
    assert not (tmp_path / "env").exists()


def test_keys_set_tmd_is_refused_as_retired(tmp_path, monkeypatch, capsys):
    # TMDAPI (uid/ukey) was dropped for good (Poom 2026-09-26) — `keys set
    # tmd` must refuse with a clear message pointing at `keys unset tmd`,
    # and must never write anything.
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    assert cli.main(["keys", "set", "tmd"]) == 2
    err = capsys.readouterr().err
    assert "เลิกใช้แล้ว" in err
    assert "keys unset tmd" in err
    assert not (tmp_path / "env").exists()


def test_keys_unset_tmd_still_removes_the_old_uid_ukey_values(
        tmp_path, monkeypatch, capsys):
    # `keys set tmd` is refused now, but `keys unset tmd` must still work so
    # Poom can clean an old wrong value out of his env file.
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    (tmp_path / "env").write_text("TMD_UID=old-uid\nTMD_UKEY=old-ukey\nGROQ_API_KEY=ggg\n",
                                  encoding="utf-8")
    monkeypatch.setattr("sys.stdin", io.StringIO("y\n"))
    monkeypatch.setattr("sys.stdin.isatty", lambda: False, raising=False)

    assert cli.main(["keys", "unset", "tmd"]) == 0
    lines = (tmp_path / "env").read_text(encoding="utf-8").splitlines()
    assert lines == ["GROQ_API_KEY=ggg"]


def test_keys_listing_shows_the_tmd_nwp_group_summary_without_a_value(
        tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    (tmp_path / "env").write_text("TMD_NWP_TOKEN=my-token-123\n",
                                  encoding="utf-8")
    assert cli.main(["keys"]) == 0
    out = capsys.readouterr().out
    assert "my-token-123" not in out
    assert "tmd-nwp" in out
    assert "มี" in out


def test_keys_listing_warns_about_leftover_retired_tmd_values(
        tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(config_mod, "DEFAULT_HOME", tmp_path)
    (tmp_path / "env").write_text("TMD_UID=old-uid\nTMD_UKEY=old-ukey\n",
                                  encoding="utf-8")
    assert cli.main(["keys"]) == 0
    out = capsys.readouterr().out
    assert "old-uid" not in out and "old-ukey" not in out
    assert "keys unset tmd" in out
