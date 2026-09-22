"""Does the Colab notebook's install actually produce a working openWakeWord?

Run in CI, never on the VPS — installing torch and speechbrain is gigabytes and
the VPS has neither the RAM nor a reason to.

WHY THIS READS THE NOTEBOOK INSTEAD OF REPEATING IT. A smoke test with its own
copy of the package list is a smoke test that passes while the notebook is
broken: the two drift the first time somebody edits one of them. So this pulls
the `pip install` lines and the imported module names out of the .ipynb itself.
If the notebook changes, this tests the change.

What it deliberately does NOT do: download a dataset, generate a clip, or train
anything. The failure being guarded against happens in the first thirty seconds,
and reproducing it needs no audio at all.

The one thing CI cannot check is the GPU, and the one thing CI must do that
Colab must not is install torch — a bare runner has none, while Colab ships a
build matched to its own CUDA, which is exactly why the notebook leaves it
alone.
"""

from __future__ import annotations

import argparse
import ast
import importlib
import importlib.util
import inspect
import json
import shlex
import subprocess
import sys
from pathlib import Path

NOTEBOOK = Path(__file__).resolve().parent / "train_saifon_colab.ipynb"

#: Where CI puts the clone. The notebook uses /content, which only exists in Colab.
CLONE_MARKER = "/content/openWakeWord"


def cells(kind: str) -> list[str]:
    notebook = json.loads(NOTEBOOK.read_text(encoding="utf-8"))
    return ["".join(c["source"]) for c in notebook["cells"] if c["cell_type"] == kind]


def pip_commands(clone: Path) -> list[list[str]]:
    """The notebook's own `!pip install` lines, retargeted at the CI clone."""
    found: list[list[str]] = []
    for source in cells("code"):
        # Join backslash continuations first: the notebook wraps its long list.
        joined = source.replace("\\\n", " ")
        for line in joined.splitlines():
            line = line.strip()
            if not line.startswith("!pip install"):
                continue
            line = line[1:].replace(CLONE_MARKER, str(clone))
            # shlex, not split(): the list carries a quoted pin ("scipy<1.17")
            # and splitting on whitespace hands pip an argument with quote
            # characters still in it.
            found.append(shlex.split(line))
    if not found:
        raise SystemExit("no `!pip install` lines found in the notebook — did it change shape?")
    return found


def module_names() -> list[str]:
    """Every module name the notebook's readiness cell imports by string.

    Parsed rather than regexed: the cell holds them in two for-loop tuples, and
    ast gets them without caring how they are laid out.
    """
    for source in cells("code"):
        if "importlib.import_module" not in source:
            continue
        names: list[str] = []
        for node in ast.walk(ast.parse(source)):
            # Only loops that import what they iterate over: the cell also
            # loops over model file names, which are not modules.
            if (isinstance(node, ast.For) and isinstance(node.iter, ast.Tuple)
                    and "import_module" in ast.unparse(node)):
                for element in node.iter.elts:
                    if isinstance(element, ast.Constant) and isinstance(element.value, str):
                        names.append(element.value)
        if names:
            return names
    raise SystemExit("could not find the readiness cell's module list")


def helper_source() -> str:
    """The cell that holds nothing but the audio-reading helpers.

    That cell is deliberately side-effect free so this can exec it and test the
    functions for real, rather than reimplementing them here and testing a copy.
    """
    for source in cells("code"):
        if "def audio_16k_mono(" in source and "load_dataset" not in source:
            return source
    raise SystemExit("could not find the audio-helper cell in the notebook")


def check_audio_parser() -> list[str]:
    """Feeds the notebook's parser every shape a `datasets` row has ever been.

    The one that matters is the torchcodec AudioDecoder, because that is what
    broke the notebook: `datasets` wraps it in a __getitem__ that serves "array"
    and "sampling_rate" and raises TypeError on everything else, so
    row["audio"]["path"] died with "object is not subscriptable".
    """
    import numpy as np

    namespace: dict = {}
    exec(compile(helper_source(), "<notebook helper cell>", "exec"), namespace)
    audio_16k_mono = namespace["audio_16k_mono"]
    to_int16 = namespace["to_int16"]

    failures: list[str] = []

    def case(label: str, value, expected_samples: int, tolerance: int = 60):
        try:
            wave, rate = audio_16k_mono(value)
        except Exception as exc:  # noqa: BLE001
            failures.append(f"parser {label}: {type(exc).__name__}: {exc}")
            print(f"  FAIL {label}  <- {type(exc).__name__}: {exc}")
            return
        problems = []
        if rate != 16000:
            problems.append(f"rate {rate}")
        if wave.ndim != 1:
            problems.append(f"not mono, shape {wave.shape}")
        if abs(len(wave) - expected_samples) > tolerance:
            problems.append(f"{len(wave)} samples, wanted ~{expected_samples}")
        if wave.dtype != np.float32:
            problems.append(f"dtype {wave.dtype}")
        if problems:
            failures.append(f"parser {label}: {', '.join(problems)}")
            print(f"  FAIL {label}  <- {', '.join(problems)}")
        else:
            print(f"  ok   parser {label}")

    # ---- the old shape, which some datasets versions still produce ----------
    case("old dict 16 kHz", {"array": np.zeros(16000), "sampling_rate": 16000}, 16000)
    case("old dict 44.1 kHz resampled",
         {"array": np.zeros(44100), "sampling_rate": 44100}, 16000)
    case("old dict stereo",
         {"array": np.zeros((2, 32000)), "sampling_rate": 32000}, 16000)

    # ---- the new shape, for real if torchcodec is installed -----------------
    try:
        import torch
        from torchcodec.decoders import AudioDecoder
    except Exception as exc:  # noqa: BLE001
        failures.append(f"torchcodec unavailable, the shape that broke the "
                        f"notebook went untested: {type(exc).__name__}: {exc}")
        print(f"  FAIL real AudioDecoder  <- {type(exc).__name__}: {exc}")
        return failures

    # A real wav, encoded to bytes, decoded by a real torchcodec decoder.
    import io
    import wave as wavemodule

    buffer = io.BytesIO()
    with wavemodule.open(buffer, "wb") as handle:
        handle.setnchannels(2)
        handle.setsampwidth(2)
        handle.setframerate(48000)
        tone = (np.sin(np.linspace(0, 400 * np.pi, 48000)) * 20000).astype("<i2")
        handle.writeframes(np.repeat(tone, 2).tobytes())
    payload = buffer.getvalue()

    decoder = AudioDecoder(payload)

    # Straight torchcodec: 48 kHz stereo in, 16 kHz mono out.
    case("real AudioDecoder 48 kHz stereo", decoder, 16000)

    # And the datasets wrapper, whose __getitem__ is the thing that raised.
    class DatasetsWrapper(AudioDecoder):
        """Reproduces datasets/features/_torchcodec.py, including the raise."""

        def __getitem__(self, key):
            if key == "array":
                y = self.get_all_samples().data.cpu().numpy()
                return np.mean(y, axis=tuple(range(y.ndim - 1))) if y.ndim > 1 else y
            if key == "sampling_rate":
                return self.get_samples_played_in_range(0, 0).sample_rate
            raise TypeError("'torchcodec.decoders.AudioDecoder' object is not subscriptable")

    wrapped = DatasetsWrapper(payload)
    case("datasets AudioDecoder wrapper", wrapped, 16000)

    # The exact call that failed on Colab must still fail, or this test is
    # asserting against a world that no longer exists.
    try:
        wrapped["path"]
        failures.append("the datasets wrapper no longer raises on ['path'] — this test's "
                        "premise has changed, re-read datasets/features/_torchcodec.py")
        print("  FAIL ['path'] no longer raises")
    except TypeError:
        print("  ok   ['path'] still raises TypeError, as it did on Colab")

    # Undecoded rows: what Audio(decode=False) hands over.
    case("undecoded bytes", {"bytes": payload, "path": None}, 16000)

    # ---- clipping ----------------------------------------------------------
    # resample_poly rings above 1.0 at edges; int16 would wrap that into a bang.
    loud = to_int16(np.array([0.0, 1.4, -1.4], dtype=np.float32))
    if loud.max() != 32767 or loud.min() != -32767:
        failures.append(f"to_int16 did not clip: {loud}")
        print(f"  FAIL to_int16 clipping <- {loud}")
    else:
        print("  ok   to_int16 clips instead of wrapping")

    return failures


def notebook_says_no_tflite() -> None:
    """The notebook must keep explaining itself, not just work by accident."""
    prose = "\n".join(cells("code") + cells("markdown"))
    for needed in ("--no-deps", "tflite-runtime"):
        if needed not in prose:
            raise SystemExit(f"the notebook no longer mentions {needed!r}")


def run(command: list[str]) -> None:
    print("$", " ".join(command), flush=True)
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--clone", required=True, help="where openWakeWord v0.6.0 is checked out")
    parser.add_argument("--install", action="store_true", help="run the notebook's pip lines")
    args = parser.parse_args()

    clone = Path(args.clone).resolve()
    notebook_says_no_tflite()

    if args.install:
        for command in pip_commands(clone):
            run([sys.executable, "-m"] + command)
        return 0

    # ---- the checks themselves ------------------------------------------
    print(f"python {sys.version.split()[0]}")
    failures: list[str] = []

    for name in module_names():
        try:
            importlib.import_module(name)
            print(f"  ok   {name}")
        except Exception as exc:  # noqa: BLE001 — every failure is worth naming
            failures.append(f"{name}: {type(exc).__name__}: {exc}")
            print(f"  FAIL {name}  <- {type(exc).__name__}: {exc}")

    # The point of the whole exercise: none of that needed tflite.
    if importlib.util.find_spec("tflite_runtime") is not None:
        failures.append("tflite_runtime is installed — the notebook is not testing what it claims")
    else:
        print("  ok   tflite_runtime absent, as intended")

    from openwakeword.utils import AudioFeatures

    framework = inspect.signature(AudioFeatures.__init__).parameters["inference_framework"].default
    if framework != "onnx":
        failures.append(f"AudioFeatures defaults to {framework!r}, not 'onnx' — training would "
                        "need the tflite runtime after all")
    else:
        print("  ok   AudioFeatures defaults to onnx")

    # The unconditional call at the end of train.py, which the notebook patches
    # out. If upstream ever removes it, the notebook's assert fires instead of
    # silently doing nothing — better to hear about it here.
    train_py = (clone / "openwakeword" / "train.py").read_text(encoding="utf-8")
    if "def convert_onnx_to_tflite(" not in train_py:
        failures.append("convert_onnx_to_tflite is gone from train.py — the notebook's patch "
                        "will refuse to apply; check whether it is still needed")
    else:
        print("  ok   convert_onnx_to_tflite still present, notebook patch still applies")

    print()
    print("audio parser, against every row shape datasets has produced:")
    failures.extend(check_audio_parser())

    print()
    if failures:
        for failure in failures:
            print("  x", failure)
        return 1
    print("the notebook's dependency set imports cleanly and its audio parser holds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
