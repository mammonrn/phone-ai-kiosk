"""Everything the "สายฝน" Colab notebook does that can go wrong, in one place.

The notebook is a thin list of calls into this file. CI runs the same notebook,
top to bottom, against a few seconds of synthetic audio — so the code Poom runs
on a T4 and the code CI runs on a laptop-sized runner are the same code, not a
copy that drifts.

Every number here that decides a shape was read out of openWakeWord v0.6.0's
source, not guessed. The ones that matter:

* ``openwakeword/train.py`` IGNORES any ``total_length`` in the YAML. It samples
  50 clips from positive_test, takes the median length, rounds to 1000, adds
  12000, and only snaps to 32000 when the result is within 4000 of it. Google's
  clips carry silence, so the median landed near 30000 and ``total_length``
  became 42000, then 40000 — which is why adding ``total_length: 32000`` to the
  YAML changed nothing.

* The features written for our clips use ``get_embedding_shape(total_length /
  16000)`` — a FLOAT number of seconds — while the model, the ACAV100M rows and
  the batch transform ``f(x, n=16)`` all assume 16 frames (the model uses
  ``total_length // 16000``, an INTEGER, which is 2 for anything under 48000).
  Measured on the real feature models: 32000 -> 16 frames, 40000 -> 22,
  42000 -> 24. Those are exactly the two mismatches Colab reported. Only a
  total_length of 32000–32999 gives 16 on both sides, so we pin 32000 and patch
  train.py to honour it.

* ``compute_features_from_generator`` is given ``n_total = len(os.listdir(dir))``
  — the number of FILES, not files × ``augmentation_rounds``. The generator stops
  after one pass, so ``augmentation_rounds: 25`` bought one round and 24 rounds
  of wasted CPU. The first model learned from 390 positives, not 9,750. We copy
  the files N times on disk instead (each copy gets its own random augmentation)
  and set ``augmentation_rounds: 1``.

* ``create_fixed_size_clip`` right-aligns every clip in the 2 s window, and
  truncates anything longer from a random end. A clip that ends in "…ครับ" or
  "…ช่วยหน่อย" teaches the model that the wake word is followed by more speech,
  and a long clip can lose the wake word altogether. So positives must END with
  the wake word, and must fit in the window with room for the 0–200 ms jitter.
"""

from __future__ import annotations

import csv
import importlib.util
import os
import random
import re
import shutil
import subprocess
import sys
import textwrap
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

WAKE_WORD = "สายฝน"
SAMPLE_RATE = 16000

#: The only length whose feature frame count matches the ACAV100M rows (16).
TOTAL_LENGTH = 32000
FRAMES = 16
EMBEDDING = 96

#: create_fixed_size_clip adds up to 200 ms of trailing silence (3200 samples).
#: A positive longer than this gets its wake word cut. 800 samples of spare.
MAX_POSITIVE_SAMPLES = TOTAL_LENGTH - 3200 - 800

FEATURE_FILES = (
    "positive_features_train.npy",
    "negative_features_train.npy",
    "positive_features_test.npy",
    "negative_features_test.npy",
)
SPLITS = ("positive_train", "positive_test", "negative_train", "negative_test")

#: ACAV100M_2000_hrs_16bit: float16, (rows, 16, 96). 17.28 GB observed on Colab.
ACAV_MIN_BYTES = 17_000_000_000


class NotReady(RuntimeError):
    """A check failed. Raised, not printed: Colab's Run all stops on it."""


def fail(problems: list[str], what_next: str) -> None:
    if problems:
        lines = "\n".join(f"  x {p}" for p in problems)
        raise NotReady(f"ยังไม่พร้อม {len(problems)} เรื่อง — {what_next}\n{lines}")


@dataclass
class Paths:
    root: Path = Path("/content")

    @property
    def clone(self) -> Path: return self.root / "openWakeWord"
    @property
    def stub(self) -> Path: return self.root / "piper_stub"
    @property
    def raw(self) -> Path: return self.root / "raw"
    #: train.py builds output_dir / model_name. model_name is "saifon", so the
    #: clips MUST be here — the first run wrote them to /content/saifon_model.
    @property
    def model_dir(self) -> Path: return self.root / "saifon"
    @property
    def rirs(self) -> Path: return self.root / "mit_rirs"
    @property
    def background(self) -> Path: return self.root / "background_clips"
    @property
    def validation(self) -> Path: return self.root / "validation_set_features.npy"
    @property
    def acav(self) -> Path: return self.root / "openwakeword_features_ACAV100M_2000_hrs_16bit.npy"
    @property
    def config(self) -> Path: return self.root / "saifon.yml"
    @property
    def onnx(self) -> Path: return self.root / "saifon.onnx"


# --------------------------------------------------------------------- patches

def write_piper_stub(paths: Paths) -> Path:
    """train.py does ``from generate_samples import generate_samples`` at the top
    of ``__main__``, unconditionally. The real piper-sample-generator then
    imports webrtcvad and espeak_phonemizer, neither of which we need: the
    function is only called under ``--generate_clips``, and our clips come from
    Google. A stub that refuses to run is both enough and honest.
    """
    paths.stub.mkdir(parents=True, exist_ok=True)
    (paths.stub / "generate_samples.py").write_text(textwrap.dedent('''\
        """Stand-in for piper-sample-generator. The saifon clips come from Google TTS."""


        def generate_samples(*args, **kwargs):
            raise RuntimeError("--generate_clips is not used for saifon: "
                               "the clips come from wake-samples.zip")
        '''), encoding="utf-8")
    return paths.stub


#: (name, anchor, replacement). Each anchor must appear exactly once in v0.6.0.
TRAIN_PATCHES = (
    (
        # Unconditional at the end of __main__, needs tensorflow-cpu==2.8.1 and
        # onnx-tf, which do not install on 3.12+. The phone runs the .onnx.
        "skip tflite",
        'def convert_onnx_to_tflite(onnx_model_path, output_path):\n'
        '    """Converts an ONNX version of an openwakeword model to the Tensorflow tflite format."""\n',
        'def convert_onnx_to_tflite(onnx_model_path, output_path):\n'
        '    """Converts an ONNX version of an openwakeword model to the Tensorflow tflite format."""\n'
        '    print("[saifon] tflite conversion skipped: the phone runs the .onnx")\n'
        '    return None\n',
    ),
    (
        # torch >= 2.9 defaults to the dynamo exporter, which needs onnxscript.
        # The TorchScript exporter needs nothing extra and gives the same graph
        # for a model this small. Input stays [1, 16, 96]: the phone feeds one
        # window at a time.
        "onnx export without onnxscript",
        'os.path.join(output_dir, model_name + ".onnx"), opset_version=13)',
        'os.path.join(output_dir, model_name + ".onnx"), opset_version=13, dynamo=False)',
    ),
    (
        # See the module docstring: train.py overwrites total_length from the
        # median clip length. Put ours back AFTER its calculation.
        "honour total_length",
        '    elif abs(config["total_length"] - 32000) <= 4000:\n'
        '        config["total_length"] = 32000\n',
        '    elif abs(config["total_length"] - 32000) <= 4000:\n'
        '        config["total_length"] = 32000\n'
        '    if "saifon_total_length" in config:\n'
        '        config["total_length"] = int(config["saifon_total_length"])\n'
        '    print(f"[saifon] total_length = {config[\'total_length\']}")\n',
    ),
)


def patch_train_py(paths: Paths) -> list[str]:
    train_py = paths.clone / "openwakeword" / "train.py"
    src = train_py.read_text(encoding="utf-8")
    report = []
    for name, anchor, replacement in TRAIN_PATCHES:
        if replacement in src:
            report.append(f"{name}: ใส่ไว้แล้ว")
            continue
        count = src.count(anchor)
        if count != 1:
            raise NotReady(f"train.py patch '{name}': เจอจุดที่ต้องแก้ {count} ที่ (ต้อง 1) — "
                           "openWakeWord ไม่ใช่ v0.6.0 แล้ว หยุดก่อน")
        src = src.replace(anchor, replacement)
        report.append(f"{name}: ใส่แล้ว")
    train_py.write_text(src, encoding="utf-8")
    after = train_py.read_text(encoding="utf-8")
    for name, _, replacement in TRAIN_PATCHES:
        if replacement not in after:
            raise NotReady(f"train.py patch '{name}' ไม่ติด")
    return report


TA_ANCHOR = "        info = torchaudio.info(str(file_path))\n"
TA_REPLACEMENT = (
    "        # [saifon] torchaudio >= 2.9 has no info(). soundfile reads the same header.\n"
    "        import soundfile as _sf\n"
    "        _info = _sf.info(str(file_path))\n"
    "        return _info.frames, _info.samplerate\n"
)


def patch_torch_audiomentations() -> str:
    """AddBackgroundNoise measures every background file with torchaudio.info,
    which torchaudio removed in 2.9. Patched on disk, because train.py runs in a
    separate process that no monkeypatch in the notebook would reach.

    Patched only when torchaudio really lacks info(): on an older torchaudio the
    original code is right and stays.
    """
    spec = importlib.util.find_spec("torch_audiomentations")
    if spec is None or not spec.origin:
        raise NotReady("ไม่มี torch_audiomentations")
    io_py = Path(spec.origin).parent / "utils" / "io.py"
    src = io_py.read_text(encoding="utf-8")
    if TA_REPLACEMENT in src:
        return f"{io_py}: ใส่ไว้แล้ว"
    import torchaudio
    if hasattr(torchaudio, "info"):
        return f"{io_py}: torchaudio {torchaudio.__version__} ยังมี info() ไม่ต้องแก้"
    if src.count(TA_ANCHOR) != 1:
        raise NotReady(f"torch_audiomentations io.py: เจอจุดที่ต้องแก้ {src.count(TA_ANCHOR)} ที่ "
                       "(ต้อง 1) — รุ่นเปลี่ยน หยุดก่อน")
    io_py.write_text(src.replace(TA_ANCHOR, TA_REPLACEMENT), encoding="utf-8")
    return f"{io_py}: ใส่แล้ว"


def check_torch_audiomentations_reads(sample: Path) -> None:
    """Runs the patched metadata path on a real file, in a fresh process, the way
    train.py will. An import that works is not a read that works."""
    code = ("from torch_audiomentations.utils.io import Audio;"
            f"n, sr = Audio.get_audio_metadata({str(sample)!r});"
            "assert sr == 16000 and n > 0, (n, sr); print(n, sr)")
    result = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True)
    if result.returncode != 0:
        raise NotReady("torch_audiomentations อ่าน header ไฟล์เสียงไม่ได้:\n" + result.stderr[-1500:])


def check_onnx_export() -> None:
    """The export call at the end of train.py, as patched, on a two-layer model
    of the same input shape, in a fresh process. Costs a second; the real one
    comes after an hour of training."""
    code = textwrap.dedent(f"""
        import tempfile, os, torch, onnxruntime as ort, numpy as np
        m = torch.nn.Sequential(torch.nn.Flatten(), torch.nn.Linear({FRAMES * EMBEDDING}, 1), torch.nn.Sigmoid())
        path = os.path.join(tempfile.mkdtemp(), "probe.onnx")
        torch.onnx.export(m, torch.rand(({FRAMES}, {EMBEDDING}))[None, ], path, opset_version=13, dynamo=False)
        s = ort.InferenceSession(path)
        assert s.get_inputs()[0].shape == [1, {FRAMES}, {EMBEDDING}], s.get_inputs()[0].shape
        s.run(None, {{s.get_inputs()[0].name: np.zeros((1, {FRAMES}, {EMBEDDING}), np.float32)}})
        print("export ok")
        """)
    result = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True)
    if result.returncode != 0:
        raise NotReady("export ONNX ไม่ได้ (จะพังตอนจบการเทรน):\n" + result.stderr[-1500:])


# ---------------------------------------------------------------------- clips

def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise ValueError(f"{path.name}: ต้องเป็น mono 16-bit "
                             f"(ได้ {w.getnchannels()} ch, {8 * w.getsampwidth()}-bit)")
        rate = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data, rate


def write_wav(path: Path, samples: np.ndarray, rate: int = SAMPLE_RATE) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(np.asarray(samples, dtype=np.int16).tobytes())


def trim_silence(samples: np.ndarray, rate: int = SAMPLE_RATE, floor_db: float = -40.0,
                 pad_ms: int = 100) -> np.ndarray:
    """Cuts the leading and trailing silence Google puts round every clip.

    Relative to the clip's own loudest 20 ms, so a quiet voice is not cut into.
    Keeps 100 ms either side so the onset and the release survive.
    """
    frame = rate // 50
    if len(samples) < frame:
        return samples
    x = samples.astype(np.float32)
    n = len(x) // frame
    rms = np.sqrt((x[: n * frame].reshape(n, frame) ** 2).mean(axis=1) + 1e-9)
    loud = np.nonzero(rms >= rms.max() * 10 ** (floor_db / 20))[0]
    if loud.size == 0:
        return samples
    pad = rate * pad_ms // 1000
    start = max(0, loud[0] * frame - pad)
    end = min(len(samples), (loud[-1] + 1) * frame + pad)
    return samples[start:end]


def normalise_text(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def keeps_positive(text: str) -> bool:
    """Only phrases that END with the wake word. "สายฝนครับ" and "สายฝน ช่วยหน่อย"
    put speech after it, and the model is fed windows that end where the word
    ends."""
    return normalise_text(text).endswith(WAKE_WORD)


def find_sample_root(raw: Path) -> Path:
    for candidate in (raw, *sorted(p for p in raw.iterdir() if p.is_dir())):
        if (candidate / "positive").is_dir():
            return candidate
    raise NotReady(f"ไม่เจอโฟลเดอร์ positive/ ใน {raw} — zip ผิดไฟล์หรือเปล่า")


def read_manifest(root: Path) -> dict[str, str]:
    """file name -> text. The manifest is the only place the text lives: file
    names are ASCII on purpose, so there is no guessing the phrase from them."""
    manifest = root / "manifest.csv"
    if not manifest.exists():
        raise NotReady("zip ไม่มี manifest.csv — คัด positive ตามข้อความไม่ได้ "
                       "ใช้ zip ที่ wake-samples สร้างเท่านั้น")
    with open(manifest, encoding="utf-8", newline="") as handle:
        return {Path(row["file"]).name: row["text"] for row in csv.DictReader(handle)}


def voice_of(path: Path) -> str:
    # <label>_<index>_<Voice>_<rate>.wav
    parts = path.stem.split("_")
    return parts[2] if len(parts) > 2 else "unknown"


@dataclass
class SplitReport:
    counts: dict[str, int] = field(default_factory=dict)       # after copies
    originals: dict[str, int] = field(default_factory=dict)    # before copies
    kept_texts: dict[str, int] = field(default_factory=dict)
    dropped_texts: dict[str, int] = field(default_factory=dict)
    too_long: int = 0
    held_out_voices: list[str] = field(default_factory=list)
    longest_positive: int = 0


def prepare_clips(paths: Paths, *, positive_rounds: int, negative_rounds: int,
                  seed: int = 20260922) -> SplitReport:
    """wake-samples.zip (already unzipped into paths.raw) -> the four folders
    train.py reads, in the folder train.py actually reads them from.

    Split by VOICE, 85/15: a voice in both halves makes the test score a memory
    test. Files are copied ``rounds`` times because train.py only featurises as
    many augmented clips as there are files (see the module docstring).
    """
    root = find_sample_root(paths.raw)
    texts = read_manifest(root)

    shutil.rmtree(paths.model_dir, ignore_errors=True)
    dirs = {k: paths.model_dir / k for k in SPLITS}
    for d in dirs.values():
        d.mkdir(parents=True, exist_ok=True)

    voices = sorted({voice_of(p) for p in (root / "positive").glob("*.wav")})
    if len(voices) < 2:
        raise NotReady(f"positive มีเสียงผู้พูด {len(voices)} เสียง แบ่งชุดทดสอบตามเสียงไม่ได้")
    random.Random(seed).shuffle(voices)
    held_out = set(voices[: max(1, len(voices) * 15 // 100)])

    report = SplitReport(counts={k: 0 for k in SPLITS}, originals={k: 0 for k in SPLITS},
                         held_out_voices=sorted(held_out))
    problems: list[str] = []

    for label, kind in (("positive", "positive"), ("nearmiss", "negative"),
                        ("background", "negative")):
        for path in sorted((root / label).glob("*.wav")):
            text = normalise_text(texts.get(path.name, ""))
            if not text:
                problems.append(f"{label}/{path.name} ไม่อยู่ใน manifest.csv")
                continue
            if kind == "positive" and not keeps_positive(text):
                report.dropped_texts[text] = report.dropped_texts.get(text, 0) + 1
                continue
            try:
                samples, rate = read_wav(path)
            except Exception as exc:  # noqa: BLE001
                problems.append(f"{label}/{path.name}: {exc}")
                continue
            if rate != SAMPLE_RATE:
                # augment_clips raises on this, but only after the long part.
                problems.append(f"{label}/{path.name}: {rate} Hz (ต้อง 16000)")
                continue
            samples = trim_silence(samples)
            if kind == "positive":
                if len(samples) > MAX_POSITIVE_SAMPLES:
                    report.too_long += 1
                    continue
                report.longest_positive = max(report.longest_positive, len(samples))
                report.kept_texts[text] = report.kept_texts.get(text, 0) + 1
            split = "test" if voice_of(path) in held_out else "train"
            target = f"{kind}_{split}"
            rounds = positive_rounds if kind == "positive" else negative_rounds
            for r in range(rounds):
                write_wav(dirs[target] / f"{path.stem}_r{r:02d}.wav", samples)
            report.originals[target] += 1
            report.counts[target] += rounds

    fail(problems[:10] + ([f"...และอีก {len(problems) - 10} เรื่อง"] if len(problems) > 10 else []),
         "ไฟล์ใน zip มีปัญหา")
    return report


def check_split(report: SplitReport, *, min_positive_train: int, min_each: int,
                augmentation_batch_size: int) -> None:
    problems = []
    for k in SPLITS:
        # compute_features_from_generator raises when a folder holds fewer files
        # than one augmentation batch — after the other folders took minutes.
        if report.counts.get(k, 0) < augmentation_batch_size:
            problems.append(f"{k}: {report.counts.get(k, 0)} ไฟล์ น้อยกว่า augmentation_batch_size "
                            f"{augmentation_batch_size}")
    for k in SPLITS:
        need = min_positive_train if k == "positive_train" else min_each
        if report.originals.get(k, 0) < need:
            problems.append(f"{k}: {report.originals.get(k, 0)} คลิปต้นฉบับ ต้องอย่างน้อย {need}")
    for text in report.kept_texts:
        if not keeps_positive(text):
            problems.append(f"positive ที่ไม่ได้จบด้วย {WAKE_WORD} หลุดมา: {text!r}")
    if report.longest_positive > MAX_POSITIVE_SAMPLES:
        problems.append(f"positive ยาว {report.longest_positive} samples เกิน {MAX_POSITIVE_SAMPLES}")
    fail(problems, "อย่าเทรน")


# ------------------------------------------------------------------- config

def write_config(paths: Paths, *, steps: int, batch_n_per_class: dict[str, int],
                 augmentation_batch_size: int, layer_size: int = 32) -> str:
    """The YAML train.py reads. Only keys train.py actually reads, plus
    saifon_total_length, which the train.py patch honours."""
    import yaml

    config = {
        "model_name": "saifon",
        "target_phrase": [WAKE_WORD],
        # Only read under --generate_clips, which we never pass.
        "custom_negative_phrases": [],
        "n_samples": 1, "n_samples_val": 1, "tts_batch_size": 1,
        "piper_sample_generator_path": str(paths.stub),
        "output_dir": str(paths.root),
        "rir_paths": [str(paths.rirs)],
        "background_paths": [str(paths.background)],
        "background_paths_duplication_rate": [1],
        "false_positive_validation_data_path": str(paths.validation),
        # 1, because the rounds are real file copies (see the module docstring).
        "augmentation_rounds": 1,
        "augmentation_batch_size": augmentation_batch_size,
        "saifon_total_length": TOTAL_LENGTH,
        "feature_data_files": {"ACAV100M_sample": str(paths.acav)},
        "batch_n_per_class": dict(batch_n_per_class),
        "model_type": "dnn",
        "layer_size": layer_size,
        "steps": steps,
        "max_negative_weight": 1500,
        # Poom's target: at most one false wake per 8 hours.
        "target_false_positives_per_hour": 0.125,
    }
    text = yaml.safe_dump(config, allow_unicode=True, sort_keys=False)
    paths.config.write_text(text, encoding="utf-8")
    return text


# ------------------------------------------------------------ feature checks

def check_frame_math() -> None:
    """Both sides of the mismatch, computed by openWakeWord itself."""
    from openwakeword.utils import AudioFeatures

    F = AudioFeatures(device="cpu")
    written = F.get_embedding_shape(TOTAL_LENGTH / 16000)     # compute_features_from_generator
    model = F.get_embedding_shape(TOTAL_LENGTH // 16000)      # Model(input_shape=...)
    problems = []
    if written[0] != FRAMES:
        problems.append(f"feature ที่จะเขียนมี {written[0]} frames ไม่ใช่ {FRAMES}")
    if model[0] != FRAMES:
        problems.append(f"โมเดลจะรับ {model[0]} frames ไม่ใช่ {FRAMES}")
    fail(problems, "total_length ผิด")


def clean_features(paths: Paths) -> list[str]:
    """Deletes ONLY the four derived feature files, by exact name, inside the
    model folder. train.py skips augmentation when positive_features_train.npy
    exists, so a half-finished earlier run leaves it believing the job is done
    and then dies looking for negative_features_train.npy. They are rebuilt from
    the clips every time; nothing irreplaceable is ever in them."""
    removed = []
    for name in FEATURE_FILES:
        target = paths.model_dir / name
        if target.is_file() and target.parent.resolve() == paths.model_dir.resolve():
            target.unlink()
            removed.append(name)
    return removed


def check_features(paths: Paths, report: SplitReport | None = None) -> dict[str, tuple]:
    problems, shapes = [], {}
    for name in FEATURE_FILES:
        target = paths.model_dir / name
        if not target.exists():
            problems.append(f"{name}: ไม่มีไฟล์")
            continue
        array = np.load(target, mmap_mode="r")
        shapes[name] = array.shape
        if array.ndim != 3 or array.shape[1:] != (FRAMES, EMBEDDING):
            problems.append(f"{name}: shape {array.shape} ต้องเป็น (N, {FRAMES}, {EMBEDDING})")
        elif array.shape[0] == 0:
            problems.append(f"{name}: 0 แถว")
        elif not np.isfinite(np.asarray(array[: min(64, array.shape[0])])).all():
            problems.append(f"{name}: มีค่า NaN/inf")
    if report is not None:
        for name, split in zip(FEATURE_FILES, ("positive_train", "negative_train",
                                               "positive_test", "negative_test")):
            want = report.counts.get(split, 0)
            got = shapes.get(name, (0,))[0]
            # trim_mmap drops all-zero rows; a handful may go, most must not.
            if name in shapes and got < want * 0.9:
                problems.append(f"{name}: {got} แถว จาก {want} คลิป — หายเกิน 10%")
    fail(problems, "feature ผิด อย่าโหลด ACAV และอย่าเทรน")
    return shapes


def check_acav(paths: Paths, *, min_bytes: int = ACAV_MIN_BYTES) -> tuple:
    problems = []
    if not paths.acav.exists():
        fail([f"ไม่มี {paths.acav}"], "โหลดไม่สำเร็จ")
    size = paths.acav.stat().st_size
    if size < min_bytes:
        problems.append(f"ขนาด {size / 1e9:.2f} GB น้อยกว่าที่ควร {min_bytes / 1e9:.2f} GB — โหลดไม่ครบ")
    try:
        array = np.load(paths.acav, mmap_mode="r")
    except Exception as exc:  # noqa: BLE001
        fail(problems + [f"อ่าน header ไม่ได้: {exc}"], "ไฟล์เสีย ลบแล้วโหลดใหม่")
    if array.ndim != 3 or array.shape[1:] != (FRAMES, EMBEDDING):
        problems.append(f"shape {array.shape} ต้องเป็น (N, {FRAMES}, {EMBEDDING})")
    fail(problems, "ACAV ใช้ไม่ได้")
    return array.shape


def check_validation(paths: Paths, *, min_rows: int) -> tuple:
    array = np.load(paths.validation, mmap_mode="r")
    problems = []
    if array.ndim != 2 or array.shape[1] != EMBEDDING or array.shape[0] < min_rows:
        problems.append(f"validation_set_features shape {array.shape} "
                        f"ต้องเป็น (>= {min_rows}, {EMBEDDING})")
    fail(problems, "ไฟล์ validation ผิด")
    return array.shape


def free_gb(path: Path) -> float:
    return shutil.disk_usage(path).free / 1e9


# ---------------------------------------------------------------- training

def run_train(paths: Paths, stage: str) -> None:
    """``python -m openwakeword.train`` as its own process — the same way
    upstream runs it, so its ``__main__`` imports are exercised for real.
    Output is streamed line by line because a notebook does not show a child
    process's stdout on its own."""
    flags = {"augment": ["--augment_clips", "--overwrite"], "train": ["--train_model"]}[stage]
    cmd = [sys.executable, "-u", "-m", "openwakeword.train",
           "--training_config", str(paths.config), *flags]
    env = dict(os.environ, PYTHONPATH=f"{paths.clone}{os.pathsep}{os.environ.get('PYTHONPATH', '')}")
    print("$", " ".join(cmd), flush=True)
    process = subprocess.Popen(cmd, cwd=paths.clone, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    tail: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="", flush=True)
        tail = (tail + [line])[-40:]
    if process.wait() != 0:
        raise NotReady(f"openwakeword.train ({stage}) ล้ม exit {process.returncode}:\n"
                       + "".join(tail))


def find_model(paths: Paths) -> Path:
    candidate = paths.root / "saifon.onnx"      # export_model: output_dir / model_name.onnx
    if not candidate.exists():
        raise NotReady(f"ไม่มี {candidate} — export ไม่สำเร็จ")
    return candidate


# -------------------------------------------------------------- evaluation

@dataclass
class Evaluation:
    input_shape: list
    pos_scores: np.ndarray
    neg_scores: np.ndarray
    val_scores: np.ndarray | None
    val_hours: float

    def table(self, thresholds=(0.3, 0.5, 0.7, 0.9, 0.95)) -> str:
        lines = [f"{'threshold':>9} {'recall':>8} {'ปลุกผิดใน negative_test':>26} {'ปลุกผิด/ชม.':>12}"]
        for t in thresholds:
            recall = (self.pos_scores >= t).mean() * 100
            neg_hits = int((self.neg_scores >= t).sum())
            per_hour = ""
            if self.val_scores is not None and self.val_hours > 0:
                per_hour = f"{count_activations(self.val_scores, t) / self.val_hours:.3f}"
            lines.append(f"{t:>9.2f} {recall:>7.1f}% {neg_hits:>12}/{len(self.neg_scores):<13} {per_hour:>12}")
        return "\n".join(lines)


def count_activations(scores: np.ndarray, threshold: float, refractory: int = 25) -> int:
    """Wakes the way the phone would: a detection, then 2 s (25 frames of 80 ms)
    in which nothing else can count — one utterance keeps a detector above
    threshold for several frames, and the phone wakes once, not five times."""
    count, next_allowed = 0, 0
    for i in np.nonzero(scores >= threshold)[0]:
        if i >= next_allowed:
            count += 1
            next_allowed = i + refractory
    return count


def evaluate(paths: Paths, *, validation_limit: int | None = None) -> Evaluation:
    """ONE window per run, which is the only shape the exported model takes
    ([1, 16, 96]) and exactly how the phone will call it."""
    import onnxruntime as ort

    session = ort.InferenceSession(str(find_model(paths)), providers=["CPUExecutionProvider"])
    feed = session.get_inputs()[0]
    shape = list(feed.shape)
    if shape[-2:] != [FRAMES, EMBEDDING]:
        raise NotReady(f"โมเดลรับ input {shape} ไม่ใช่ [1, {FRAMES}, {EMBEDDING}]")

    def score(windows: np.ndarray) -> np.ndarray:
        out = np.empty(len(windows), dtype=np.float32)
        for i in range(len(windows)):
            out[i] = session.run(None, {feed.name: windows[i: i + 1].astype(np.float32)})[0].reshape(-1)[0]
        return out

    pos = np.load(paths.model_dir / "positive_features_test.npy")
    neg = np.load(paths.model_dir / "negative_features_test.npy")
    pos_scores, neg_scores = score(pos), score(neg)

    val_scores, val_hours = None, 0.0
    if paths.validation.exists():
        frames = np.load(paths.validation, mmap_mode="r")
        if validation_limit:
            frames = frames[:validation_limit]
        n = frames.shape[0] - FRAMES
        if n > 0:
            view = np.lib.stride_tricks.sliding_window_view(np.asarray(frames, dtype=np.float32),
                                                            FRAMES, axis=0)
            # sliding_window_view puts the window axis last: (n, 96, 16) -> (n, 16, 96)
            windows = view[:n].transpose(0, 2, 1)
            val_scores = score(windows)
            val_hours = n * 0.08 / 3600
    return Evaluation(shape, pos_scores, neg_scores, val_scores, val_hours)
