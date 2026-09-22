"""Reading the length of the audio out of the audio.

This is what settles "was the vendor slow, or was the answer long". It has to be
right about the number and, more importantly, it has to never raise: a voice
must not stop working because a container could not be parsed.

The page builder here is deliberate. Its lacing is written from the Ogg spec
rather than from the parser, and one test asserts the parser consumes the whole
file it produces — the same invariant that was checked by hand against a real
multi-page Ogg file, and the thing that would break first if the segment-table
arithmetic were wrong.
"""

from __future__ import annotations

import struct

from kiosk_broker import oggopus


def page(payload: bytes, *, granule: int, serial: int = 1, sequence: int = 0,
         header_type: int = 0) -> bytes:
    """One Ogg page. Lacing per the spec: 255-byte runs, terminated by a short one."""
    laces = []
    remaining = len(payload)
    while remaining >= 255:
        laces.append(255)
        remaining -= 255
    laces.append(remaining)  # Always present, even at zero: that is what ends the packet.

    header = (b"OggS" + bytes([0, header_type]) + struct.pack("<q", granule)
              + struct.pack("<II", serial, sequence)
              + b"\x00\x00\x00\x00"  # CRC, which nothing here verifies
              + bytes([len(laces)]) + bytes(laces))
    return header + payload


def opus_head(pre_skip: int = 312, channels: int = 1, rate: int = 24000) -> bytes:
    return (b"OpusHead" + bytes([1, channels]) + struct.pack("<H", pre_skip)
            + struct.pack("<I", rate) + struct.pack("<h", 0) + bytes([0]))


def opus_file(*, granule: int, pre_skip: int = 312) -> bytes:
    """A minimal but structurally real Ogg Opus stream: head, tags, then audio."""
    return (page(opus_head(pre_skip), granule=0, sequence=0, header_type=2)
            + page(b"OpusTags" + struct.pack("<I", 0) + struct.pack("<I", 0),
                   granule=0, sequence=1)
            # A page whose packet does not finish on it: granule -1 is not a
            # sample count and must not be read as one.
            + page(b"\x00" * 300, granule=-1, sequence=2)
            + page(b"\x00" * 200, granule=granule, sequence=3, header_type=4))


# ------------------------------------------------------------------ the number

def test_the_duration_is_the_last_granule_less_the_pre_skip():
    """Opus always decodes at 48 kHz, whatever it was captured at."""
    # 48,312 samples with 312 of pre-skip is exactly one second of audio.
    assert oggopus.duration_ms(opus_file(granule=48_312, pre_skip=312)) == 1000


def test_a_ten_second_answer_measures_as_ten_seconds():
    """The shape of the thing that was mistaken for latency."""
    assert oggopus.duration_ms(opus_file(granule=48_000 * 10 + 312)) == 10_000


def test_the_pre_skip_is_subtracted_rather_than_ignored():
    with_skip = oggopus.duration_ms(opus_file(granule=96_000, pre_skip=4800))
    without = oggopus.duration_ms(opus_file(granule=96_000, pre_skip=0))
    assert without - with_skip == 100  # 4800 samples at 48 kHz


def test_a_page_with_no_finished_packet_is_not_read_as_a_sample_count():
    """granule -1 means "nothing ends here"; treating it as zero or as -1 samples
    would give a negative or a wrong duration."""
    assert oggopus.duration_ms(opus_file(granule=48_312)) == 1000


def test_audio_shorter_than_its_own_pre_skip_is_zero_not_negative():
    assert oggopus.duration_ms(opus_file(granule=100, pre_skip=312)) == 0


# ---------------------------------------------------------------- never raises

def test_the_parser_consumes_the_whole_file_it_is_given():
    """The invariant that catches bad lacing arithmetic.

    If the segment table were summed wrongly the walker would desync partway
    through and silently stop, and the duration would come from whatever page it
    happened to stop on.
    """
    data = opus_file(granule=48_312)
    walked = list(oggopus._pages(data))
    assert len(walked) == 4
    assert sum(27 + 1 + (len(p) // 255) + len(p) for _, p in walked) == len(data)


def test_things_that_are_not_ogg_opus_return_none_rather_than_raising():
    for payload in (b"", b"ID3\x04 an mp3", b"OggS", b"OggS" + b"\x00" * 60,
                    b"RIFF....WAVE", bytes(range(256))):
        assert oggopus.duration_ms(payload) is None


def test_an_ogg_file_that_is_not_opus_returns_none():
    """Ogg Vorbis also has pages and granules, but no OpusHead and no pre-skip,
    and its granule counts at the capture rate rather than at 48 kHz."""
    vorbis = page(b"\x01vorbis" + b"\x00" * 24, granule=0, header_type=2)
    assert oggopus.duration_ms(vorbis) is None


def test_a_truncated_file_returns_none_rather_than_reading_past_the_end():
    data = opus_file(granule=48_312)
    for cut in (30, 45, len(data) - 100, len(data) - 1):
        # Either a number or None, never an exception, and never a number that
        # came from reading off the end of the buffer.
        assert oggopus.duration_ms(data[:cut]) in (None, 0, 1000)


def test_a_lying_segment_table_does_not_run_off_the_end():
    """A page claiming a payload longer than the file is refused, not read."""
    header = (b"OggS" + bytes([0, 2]) + struct.pack("<q", 0)
              + struct.pack("<II", 1, 0) + b"\x00\x00\x00\x00"
              + bytes([1, 255]))  # says 255 bytes follow; none do
    assert oggopus.duration_ms(header) is None
