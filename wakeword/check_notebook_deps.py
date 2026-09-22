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
            if isinstance(node, ast.For) and isinstance(node.iter, ast.Tuple):
                for element in node.iter.elts:
                    if isinstance(element, ast.Constant) and isinstance(element.value, str):
                        names.append(element.value)
        if names:
            return names
    raise SystemExit("could not find the readiness cell's module list")


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
    if failures:
        for failure in failures:
            print("  x", failure)
        return 1
    print("the notebook's dependency set imports cleanly")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
