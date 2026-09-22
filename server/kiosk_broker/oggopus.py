"""How long an Ogg Opus file plays for, read out of the container.

This exists because of a measurement that misled us for a day. The phone logged
"tts ok 28813 bytes 9343 ms" and that got read as "synthesis took nine seconds".
It did not: the phone's timer was wrapped around synthesis AND playback, and
playback of a spoken Thai sentence is most of ten seconds all by itself.

The broker can settle that argument without asking anyone, because the length of
the audio is written in the audio. So it logs it, next to how long the request
took, and the two numbers together say which part was slow.

Opus always decodes at 48 kHz regardless of the rate it was captured at, so the
granule position of the last page is a sample count at 48,000 — and the pre-skip
in the header is the part of that which is decoder warm-up and never played.

Nothing here raises. A header this cannot parse returns None and the log says
"unknown"; a voice must not stop working because a duration could not be read.
"""

from __future__ import annotations

import struct

_CAPTURE = b"OggS"
_SAMPLE_RATE = 48_000
_HEADER_BYTES = 27  # up to and including the page_segments count


def _pages(data: bytes):
    """Yields (granule_position, payload) for each Ogg page, in order."""
    offset = 0
    end = len(data)
    while offset + _HEADER_BYTES <= end:
        if data[offset:offset + 4] != _CAPTURE:
            return  # Not a page boundary: refuse to go hunting through audio.
        granule = struct.unpack_from("<q", data, offset + 6)[0]
        segments = data[offset + 26]
        table_end = offset + _HEADER_BYTES + segments
        if table_end > end:
            return
        payload_length = sum(data[offset + _HEADER_BYTES:table_end])
        payload_end = table_end + payload_length
        if payload_end > end:
            return
        yield granule, data[table_end:payload_end]
        offset = payload_end


def duration_ms(audio: bytes) -> int | None:
    """Playing time in milliseconds, or None if this is not readable Ogg Opus."""
    if not audio.startswith(_CAPTURE):
        return None

    pre_skip = None
    last_granule = None
    for granule, payload in _pages(audio):
        if pre_skip is None and payload.startswith(b"OpusHead") and len(payload) >= 12:
            # OpusHead: magic(8) version(1) channels(1) pre_skip(2 LE).
            pre_skip = struct.unpack_from("<H", payload, 10)[0]
        # -1 means "no packet finishes on this page"; it is not a sample count.
        if granule >= 0:
            last_granule = granule

    if pre_skip is None or last_granule is None:
        return None

    samples = last_granule - pre_skip
    if samples <= 0:
        return 0
    return round(samples * 1000 / _SAMPLE_RATE)
