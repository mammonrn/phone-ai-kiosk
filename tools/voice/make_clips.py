"""Every speakable matrix sentence in every Thai edge-tts voice, as 16 kHz mono 16-bit WAV,
plus manifest.tsv. No gate, no filtering: every row marked speakable is in.

    pip install edge-tts        (free, no sign-up; needs ffmpeg on PATH)
    python tools/voice/make_clips.py OUT_DIR      -> OUT_DIR/clips/*.wav, OUT_DIR/manifest.tsv

OUT_DIR must be outside the repo: no audio is committed. edge-tts had two Thai
voices on 2026-09-25 (Premwadee F, Niwat M); th-TH-AcharaNeural was not offered."""
import asyncio
import csv
import subprocess
import sys
import wave
from pathlib import Path

import edge_tts

sys.path.insert(0, str(Path(__file__).resolve().parent))
from matrix_rows import ROWS  # noqa: E402

HERE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
CLIPS = HERE / "clips"
CLIPS.mkdir(parents=True, exist_ok=True)
VOICES = [("th-TH-PremwadeeNeural", "F", "premwadee"), ("th-TH-NiwatNeural", "M", "niwat")]
# Trim edge-tts's own leading/trailing silence, then 0.25 s before and 0.30 s after,
# like a turn that stops shortly after the speech.
FILTER = ("silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
          "silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
          "adelay=250,apad=pad_dur=0.3")


async def synth(text: str, voice: str, out: Path) -> None:
    await edge_tts.Communicate(text, voice).save(str(out))


def main() -> None:
    rows = []
    total = 0.0
    for rid, feature, text, *_rest in ROWS:
        speakable, key = _rest[-2], _rest[-1]
        if speakable != "yes":
            continue
        for voice, gender, short in VOICES:
            wav = CLIPS / f"{rid}-{short}.wav"
            if not wav.exists():
                mp3 = CLIPS / f"{rid}-{short}.mp3"
                asyncio.run(synth(text, voice, mp3))
                subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(mp3), "-af", FILTER,
                                "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", str(wav)], check=True)
                mp3.unlink()
            with wave.open(str(wav)) as w:
                assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2)
                seconds = w.getnframes() / w.getframerate()
            total += seconds
            rows.append([wav.name, short, gender, rid, feature, text, key, f"{seconds:.2f}"])
    with (HERE / "manifest.tsv").open("w", encoding="utf-8", newline="") as fh:
        out = csv.writer(fh, delimiter="\t", lineterminator="\n")
        out.writerow(["file", "voice", "gender", "sentence_id", "feature", "expected_text", "key_part", "seconds"])
        out.writerows(rows)
    maps = sum(float(r[7]) for r in rows if r[4] == "maps")
    print(f"clips={len(rows)} sentences={len(rows) // len(VOICES)} total_seconds={total:.1f} "
          f"map_clip_seconds={maps:.1f}")


if __name__ == "__main__":
    main()
