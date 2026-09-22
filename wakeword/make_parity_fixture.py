"""Records what openWakeWord's own pipeline does, so the Kotlin one can be held to it.

The Android detector is a re-implementation. A re-implementation that is only
tested against itself is a re-implementation that is confidently wrong, so this
runs the REAL openWakeWord on fixed audio and writes down every number it
produces. `WakeWordParityTest` then runs the Kotlin pipeline over the same
samples with the same ONNX files and compares.

WHY THE FIRST CHUNKS ARE NOT COMPARED. openWakeWord seeds its feature buffer
with four seconds of `np.random.randint(-1000, 1000)` — actual random noise, a
different buffer every process. `get_features(16)` reads the last 16 rows, so
until 16 chunks of real audio have pushed that seed out, the score depends on
numbers no other implementation could reproduce. From chunk 16 on, every row in
the window came from the audio, and the two pipelines are comparable. We compare
from CHUNK_COMPARE_FROM, which leaves a margin.

The melspectrogram buffer is seeded with ones, which is deterministic, so that
one is reproduced exactly on the Kotlin side.

No voice recording is involved: this is about arithmetic, not acoustics. A tone
and some noise exercise the mel filterbank, the embedding convolutions and the
classifier just as well, and a synthetic clip is one nobody has to license.
"""

from __future__ import annotations

import argparse
import json
import struct
import wave
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16000
CHUNK = 1280                 # 80 ms, openWakeWord's natural step
CHUNKS = 40                  # 3.2 s
#: Past the 16 chunks it takes to flush openWakeWord's random feature seed.
CHUNK_COMPARE_FROM = 20

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parent / "app" / "src" / "test" / "resources" / "parity"
MODELS = HERE.parent / "app" / "src" / "main" / "assets" / "models"


def deterministic_audio(chunks: int = CHUNKS) -> np.ndarray:
    """The same samples on every machine, every run.

    Three things on purpose: a sweep so the mel bins light up in turn, a burst
    so there is an onset to find, and seeded noise so nothing is degenerate.
    """
    n = chunks * CHUNK
    t = np.arange(n) / SAMPLE_RATE
    sweep = np.sin(2 * np.pi * (200 + 900 * t / t[-1]) * t) * 0.35
    burst = np.zeros(n)
    burst[SAMPLE_RATE: int(SAMPLE_RATE * 1.4)] = 0.45
    noise = np.random.default_rng(20260922).normal(0, 0.03, n)
    x = (sweep * (0.3 + burst) + noise) * 32767 * 0.8
    return np.clip(x, -32768, 32767).astype(np.int16)


def write_wav(path: Path, samples: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(samples.tobytes())


def reference_scores(samples: np.ndarray, model_path: Path, frame: int) -> list[float]:
    """openWakeWord itself, fed `frame` samples at a time."""
    from openwakeword.model import Model

    model = Model(wakeword_models=[str(model_path)], inference_framework="onnx")
    name = list(model.models.keys())[0]
    scores = []
    for start in range(0, len(samples) - frame + 1, frame):
        prediction = model.predict(samples[start: start + frame])
        scores.append(float(prediction[name]))
    return scores


def melspectrogram_of(samples: np.ndarray) -> np.ndarray:
    """The mel stage alone, including openWakeWord's own /10 + 2 transform."""
    from openwakeword.utils import AudioFeatures

    return AudioFeatures(device="cpu")._get_melspectrogram(samples)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="regenerate and compare with the committed fixture "
                             "instead of overwriting it")
    args = parser.parse_args()

    model_path = MODELS / "hey_jarvis_v0.1.onnx"
    if not model_path.exists():
        raise SystemExit(f"missing {model_path} — the models are committed under "
                         "app/src/main/assets/models (see NOTICE for their licence)")

    samples = deterministic_audio()

    # A short, fixed slice of the mel stage: enough to localise a mismatch to the
    # first model rather than only seeing a wrong score at the end.
    mel = melspectrogram_of(samples[: CHUNK * 2])

    payload = {
        "sample_rate": SAMPLE_RATE,
        "chunk": CHUNK,
        "chunks": CHUNKS,
        "compare_from": CHUNK_COMPARE_FROM,
        "model": model_path.name,
        "mel_shape": list(mel.shape),
        # First two frames, every bin: the mel filterbank and the /10 + 2 transform.
        "mel_first_frames": [[round(float(v), 5) for v in row] for row in mel[:2]],
        # Fed 1280 at a time (openWakeWord's natural step)...
        "scores_1280": [round(s, 6) for s in reference_scores(samples, model_path, CHUNK)],
        # ...and 1000 at a time, which is what Recorder actually produces, so the
        # remainder handling is held to the same standard as the clean case.
        "scores_1000": [round(s, 6) for s in reference_scores(samples, model_path, 1000)],
    }

    audio_path = FIXTURES / "audio.wav"
    json_path = FIXTURES / "expected.json"

    if args.check:
        old = json.loads(json_path.read_text(encoding="utf-8"))
        drift = [k for k in payload if k != "scores_1000" and old.get(k) != payload[k]]
        # scores_1000 is compared with a tolerance: the 1000-sample path crosses
        # openWakeWord's "return the previous prediction" branch, and the float
        # noise there is larger than exact equality can survive across versions.
        near = np.allclose(old.get("scores_1000", []), payload["scores_1000"], atol=2e-3)
        if drift or not near:
            print("fixture drift:", drift or "scores_1000")
            return 1
        print("fixture matches openWakeWord on this machine")
        return 0

    write_wav(audio_path, samples)
    json_path.write_text(json.dumps(payload, indent=1) + "\n", encoding="utf-8")
    peak = max(payload["scores_1280"])
    print(f"wrote {audio_path.relative_to(HERE.parent)} ({len(samples)} samples)")
    print(f"wrote {json_path.relative_to(HERE.parent)}")
    print(f"mel shape {payload['mel_shape']}, peak hey_jarvis score {peak:.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
