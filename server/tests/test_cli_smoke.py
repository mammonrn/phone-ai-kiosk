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
