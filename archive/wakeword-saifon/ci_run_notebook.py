"""Runs the Colab notebook, every cell, top to bottom, the way Run all does.

Not a copy of the notebook's steps — the notebook itself, through a real
Jupyter kernel, with `!pip` and `!git` lines executed as written. The only
differences from Colab, all behind ``SAIFON_CI=1`` inside the notebook:

* a synthetic wake-samples.zip (seconds of tones and noise, with a real
  manifest.csv holding every positive phrase the VPS has ever produced, so the
  "ends with สายฝน" filter is exercised on the texts that broke it);
* a few fake RIR/background files instead of streaming Hugging Face datasets;
* a 4000-row fake ACAV100M file and a 3000-row validation file, same dtypes and
  shapes as the real ones (float16 (N, 16, 96) and float32 (N, 96));
* 60 training steps instead of 20000, and no GPU.

Everything that broke on Colab — the piper import in train.py's __main__, the
/content/saifon path, torchaudio.info, stale features, the 16-vs-24 frame
mismatch, the onnxscript export — sits on this path and fails it.

Run on a GitHub runner, never on the VPS. Needs /content to exist and be
writable, which the workflow arranges.
"""

from __future__ import annotations

import csv
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
NOTEBOOK = HERE / "train_saifon_colab.ipynb"
CONTENT = Path("/content")

sys.path.insert(0, str(HERE))
import saifon_pipeline as sp  # noqa: E402

#: Every phrase the VPS's first run produced — the old list, on purpose.
OLD_POSITIVES = ("สายฝน", "สายฝนครับ", "นี่สายฝน", "สายฝน ช่วยหน่อย", "โอเค สายฝน")
NEAR_MISS = ("สายลม", "สายฝัน", "ชายฝน")
BACKGROUND = ("วันนี้อากาศดีมากเลยนะ", "อย่าลืมเอาร่มไปด้วยนะ")
VOICES = tuple(f"Voice{c}" for c in "ABCDEFG")     # 7 voices -> 1 held out


def speechlike(rng, seconds: float, lead: float = 0.35, tail: float = 0.4) -> np.ndarray:
    """Noise bursts with Google-style silence either side, so the trimmer has
    something to trim."""
    body = int(seconds * sp.SAMPLE_RATE)
    t = np.arange(body) / sp.SAMPLE_RATE
    envelope = np.abs(np.sin(2 * np.pi * 3 * t)) + 0.2
    voiced = np.sin(2 * np.pi * rng.uniform(120, 240) * t) * 0.5 + rng.normal(0, 0.2, body)
    x = voiced * envelope * 8000
    pad = lambda s: np.zeros(int(s * sp.SAMPLE_RATE))  # noqa: E731
    return np.clip(np.concatenate([pad(lead), x, pad(tail)]), -32767, 32767).astype(np.int16)


def build_zip(target: Path) -> dict:
    rng = np.random.default_rng(7)
    stage = Path(tempfile.mkdtemp()) / "wake-samples"
    rows, index = [], 0
    expected = {"kept": 0, "dropped": 0, "too_long": 0}

    def add(label: str, text: str, voice: str, rate: float, seconds: float):
        nonlocal index
        name = f"{label}_{index:05d}_{voice}_{rate:g}.wav"
        (stage / label).mkdir(parents=True, exist_ok=True)
        sp.write_wav(stage / label / name, speechlike(rng, seconds))
        rows.append((f"{label}/{name}", label, text, voice, f"{rate:g}", str(len(text))))
        index += 1

    for text in OLD_POSITIVES:
        for voice in VOICES:
            for rate in (0.9, 1.2):
                # 1.65–1.7 s after trimming: inside the 2 s window, but long enough
                # that train.py's own median rule would pick 38000–39000 samples
                # (21 frames) — so this run fails if the total_length patch is lost.
                add("positive", text, voice, rate, 1.45 if rate > 1 else 1.5)
                if sp.keeps_positive(text):
                    expected["kept"] += 1
                else:
                    expected["dropped"] += 1
    # One positive that is right in text but too long for the 2 s window.
    add("positive", "โอเค สายฝน", VOICES[1], 0.5, 2.3)
    expected["too_long"] += 1
    for text in NEAR_MISS:
        for voice in VOICES:
            for rate in (0.95, 1.15):
                add("nearmiss", text, voice, rate, 0.9)
    for text in BACKGROUND:
        for voice in VOICES[:4]:
            add("background", text, voice, 1.0, 2.8)   # longer than 2 s: must be fine

    with open(stage / "manifest.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, quoting=csv.QUOTE_ALL)
        writer.writerow(("file", "label", "text", "voice", "speaking_rate", "characters"))
        writer.writerows(rows)

    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(stage.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(stage.parent))
    return expected


def fake_colab(root: Path, zip_path: Path) -> Path:
    """google.colab.files, as far as the notebook uses it."""
    package = root / "google" / "colab"
    package.mkdir(parents=True)
    (package / "__init__.py").write_text("", encoding="utf-8")
    (package / "files.py").write_text(
        "import os, shutil\n"
        "def upload():\n"
        f"    src = {str(zip_path)!r}\n"
        "    shutil.copy(src, os.path.basename(src))\n"
        "    return {os.path.basename(src): open(src, 'rb').read()}\n"
        "def download(path):\n"
        "    assert os.path.getsize(path) > 0, path\n"
        "    open('/content/downloaded.txt', 'w').write(str(path))\n",
        encoding="utf-8")
    return root


def run_notebook(env: dict) -> None:
    import nbformat
    from nbclient import NotebookClient

    nb = nbformat.read(NOTEBOOK, as_version=4)
    client = NotebookClient(nb, timeout=1800, kernel_name="python3",
                            resources={"metadata": {"path": str(CONTENT)}})
    os.environ.update(env)
    try:
        client.execute()
    finally:
        # Print every cell's output, pass or fail: the log is the evidence.
        for i, cell in enumerate(nb.cells):
            if cell.cell_type != "code":
                continue
            print(f"\n======== cell {i} ========")
            for out in cell.get("outputs", []):
                if out.get("output_type") == "stream":
                    print(out.get("text", "")[-6000:], end="")
                elif out.get("output_type") == "error":
                    print("\n".join(out.get("traceback", []))[-6000:])
        (CONTENT / "executed.ipynb").write_text(json.dumps(nb, ensure_ascii=False), encoding="utf-8")


def main() -> int:
    if not CONTENT.is_dir() or not os.access(CONTENT, os.W_OK):
        raise SystemExit("/content must exist and be writable (the workflow creates it)")
    zip_path = Path(tempfile.mkdtemp()) / "wake-samples.zip"
    expected = build_zip(zip_path)
    fake = fake_colab(Path(tempfile.mkdtemp()), zip_path)

    env = {
        "SAIFON_CI": "1",
        "SAIFON_REPO_DIR": str(HERE.parent),
        "PYTHONPATH": f"{fake}{os.pathsep}{os.environ.get('PYTHONPATH', '')}",
        # No GPU on the runner; make sure nothing tries.
        "CUDA_VISIBLE_DEVICES": "",
    }
    run_notebook(env)

    # ---- what Run all must have produced ------------------------------------
    failures = []
    paths = sp.Paths()
    model = paths.root / "saifon.onnx"
    if not model.exists():
        failures.append("saifon.onnx was not exported")
    else:
        import onnxruntime as ort
        shape = ort.InferenceSession(str(model)).get_inputs()[0].shape
        if list(shape) != [1, sp.FRAMES, sp.EMBEDDING]:
            failures.append(f"model input {shape}, the phone feeds [1, 16, 96]")
    if (CONTENT / "downloaded.txt").read_text().strip() != str(model):
        failures.append("the download cell did not hand over saifon.onnx")

    manifest = {}
    root = sp.find_sample_root(paths.raw)
    manifest = sp.read_manifest(root)
    for split in ("positive_train", "positive_test"):
        for f in (paths.model_dir / split).glob("*.wav"):
            original = f.name.rsplit("_r", 1)[0] + ".wav"
            if not sp.keeps_positive(manifest[original]):
                failures.append(f"{split}/{f.name} is {manifest[original]!r}, which does not end in สายฝน")
            data, rate = sp.read_wav(f)
            if len(data) > sp.MAX_POSITIVE_SAMPLES or rate != 16000:
                failures.append(f"{split}/{f.name}: {len(data)} samples at {rate} Hz")
    n_pos = sum(len(list((paths.model_dir / s).glob("*.wav"))) for s in ("positive_train", "positive_test"))
    want = (expected["kept"]) * 2   # POSITIVE_ROUNDS = 2 in CI
    if n_pos != want:
        failures.append(f"{n_pos} positive files, expected {want} "
                        f"({expected['kept']} kept x 2 rounds; {expected['dropped']} dropped, "
                        f"{expected['too_long']} too long)")
    for name in sp.FEATURE_FILES:
        a = np.load(paths.model_dir / name, mmap_mode="r")
        if a.shape[1:] != (sp.FRAMES, sp.EMBEDDING):
            failures.append(f"{name} {a.shape}")

    # The patched torch_audiomentations really is on the path train.py used.
    probe = subprocess.run([sys.executable, "-c",
                            "import torchaudio, torch_audiomentations.utils.io as io, inspect;"
                            "print(hasattr(torchaudio,'info'), 'soundfile' in inspect.getsource(io))"],
                           capture_output=True, text=True)
    print("\ntorchaudio.info present / io.py patched:", probe.stdout.strip())
    if probe.stdout.strip() == "False False":
        failures.append("torchaudio has no info() and torch_audiomentations was not patched")

    print()
    if failures:
        for f in failures:
            print("  x", f)
        return 1
    print(f"Run all finished: {expected['kept']} positives kept, {expected['dropped']} dropped "
          f"for trailing words, {expected['too_long']} too long; features (N, 16, 96); "
          f"saifon.onnx exported with input [1, 16, 96]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
