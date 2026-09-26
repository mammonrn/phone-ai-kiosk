"""0.72: the day's UV peak checked against NWP's daily shortwave (uv_check)
reaches compute_uv as primary_suspect: the Open-Meteo series is not used
that day and the backup is asked instead."""
from kiosk_broker import weather_checks


def _series():
    times = [f"2026-09-26T{h:02d}:00" for h in range(24)]
    values = [0.0] * 7 + [1, 3, 5, 7, 9, 10, 9, 7, 5, 3, 1] + [0.0] * 6
    return times, values


def test_a_plausible_series_is_used_without_the_backup():
    times, values = _series()
    calls = []
    uv, source = weather_checks.compute_uv(times, values, "2026-09-26T12:00", 13.75, 100.5, 10,
                                           lambda: calls.append(1) or {})
    assert source == "open-meteo" and uv is not None and calls == []


def test_a_suspect_series_goes_to_the_backup():
    times, values = _series()
    calls = []

    def backup():
        calls.append(1)
        raise OSError("offline in tests")
    uv, source = weather_checks.compute_uv(times, values, "2026-09-26T12:00", 13.75, 100.5, 10,
                                           backup, primary_suspect=True)
    assert calls == [1]
    assert uv is None and source == ""
