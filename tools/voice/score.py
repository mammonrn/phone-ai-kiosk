"""Score a speech-to-text comparison: the same clips through each transcriber.

    python tools/voice/score.py MANIFEST.tsv RESULTS.tsv [--worst 25]

MANIFEST.tsv (made with the clips; tab separated, header row):
    file  voice  gender  sentence_id  feature  expected_text  key_part  seconds
RESULTS.tsv (made on the phone by run_stt_clips.sh, or by hand; header row):
    file  provider  transcript  ms  [gate]
  `ms` may be empty (no latency then). `gate` is optional: empty when the broker's
  speech gate let the transcript through, else its reason. A gated clip comes
  back with NO text, so it is scored as a miss and also counted on its own line.
  A transcript starting "ERROR" is a failed request: a miss, counted apart.

Per provider it prints: exact-sentence match %, key-part-correct %, character
error rate (CER), median and p90 latency, the same broken down by gender, by
voice and by feature, the clips one provider got right and the other wrong
(paired, on the key part), and the worst mismatches.

HOW TEXT IS COMPARED (normalise(), applied the same way to BOTH sides, so it
never favours one transcriber):
  * Unicode NFC, lower case; Thai digits ๐-๙ become 0-9;
  * runs of Thai number words become digits ("สิบเอ็ด" = "11", "หก" = "6") —
    Groq writes digits and Qwen writes words, and neither is wrong;
  * ๆ repeats nothing: "สั้นๆ" = "สั้น ๆ" = "สั้นสั้น";
  * a few spellings that are the same word (SAME_WORD: ไหม/มั้ย, วิดีโอ/วีดีโอ,
    โน้ต/โน๊ต, เท่าไร/เท่าไหร่ ...);
  * every space, punctuation mark and symbol removed.
A KEY PART is "a|b": every part must be heard. A part "a!b" means a must be
heard and b must NOT ("ปิดไฟ!เปิดไฟ": "ปิดไฟ" is inside "เปิดไฟ").
Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import re
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

# ------------------------------------------------------------------ normalise

_THAI_DIGITS = str.maketrans("๐๑๒๓๔๕๖๗๘๙", "0123456789")
_DIGIT_WORDS = {"ศูนย์": 0, "หนึ่ง": 1, "เอ็ด": 1, "สอง": 2, "ยี่": 2, "สาม": 3, "สี่": 4,
                "ห้า": 5, "หก": 6, "เจ็ด": 7, "แปด": 8, "เก้า": 9}
_UNIT_WORDS = {"สิบ": 10, "ร้อย": 100, "พัน": 1_000, "หมื่น": 10_000, "แสน": 100_000}
_NUM_TOKEN = re.compile("|".join(sorted([*_DIGIT_WORDS, *_UNIT_WORDS], key=len, reverse=True)))
_NUM_RUN = re.compile(rf"(?:{_NUM_TOKEN.pattern})+")

#: Spellings of one word. Left side becomes the right side, on both texts.
SAME_WORD = (
    ("มั้ย", "ไหม"), ("มั๊ย", "ไหม"), ("มัย", "ไหม"),
    ("เท่าไร่", "เท่าไหร่"), ("เท่าไร", "เท่าไหร่"),
    ("วีดีโอ", "วิดีโอ"), ("วีดิโอ", "วิดีโอ"), ("วิดิโอ", "วิดีโอ"),
    ("โน๊ต", "โน้ต"), ("โน็ต", "โน้ต"),
    ("แอพ", "แอป"), ("ค่ะ", "คะ"),
    ("บอดี้แสลม", "บอดี้สแลม"), ("บอดีสแลม", "บอดี้สแลม"),
)


def _number_value(words: str) -> int | None:
    """"สิบเอ็ด" -> 11, "สามร้อย" -> 300, "ยี่สิบห้า" -> 25; None when it is not one number."""
    tokens = _NUM_TOKEN.findall(words)
    if not tokens or "".join(tokens) != words:
        return None
    total, digit = 0, None
    for i, tok in enumerate(tokens):
        if tok in _UNIT_WORDS:
            unit = _UNIT_WORDS[tok]
            if tok == "สิบ" and digit is None:
                digit = 1
            if digit is None:
                return None
            total += digit * unit
            digit = None
        else:
            if digit is not None:
                return None          # two digits in a row ("สองสาม", "a few"): not one number
            if tok == "เอ็ด" and i == 0:
                return None
            if tok == "ยี่" and (i + 1 >= len(tokens) or tokens[i + 1] != "สิบ"):
                return None
            digit = _DIGIT_WORDS[tok]
    if digit is not None:
        total += digit
    return total


def _numbers_to_digits(text: str) -> str:
    def repl(m: re.Match) -> str:
        value = _number_value(m.group(0))
        return m.group(0) if value is None else str(value)
    return _NUM_RUN.sub(repl, text)


def normalise(text: str) -> str:
    t = unicodedata.normalize("NFC", text or "").lower().translate(_THAI_DIGITS)
    # ๆ: drop it, then fold a word written twice into once (both sides the same).
    t = t.replace("ๆ", "")
    t = "".join(c for c in t if not (c.isspace() or unicodedata.category(c)[0] in "PSC"))
    for a, b in SAME_WORD:
        t = t.replace(a, b)
    t = _numbers_to_digits(t)
    t = re.sub(r"([ก-๛a-z]{2,6}?)\1", r"\1", t)
    return t


def cer(expected: str, heard: str) -> float:
    """Edits to turn `heard` into `expected`, over the length of `expected` (normalised)."""
    a, b = normalise(expected), normalise(heard)
    if not a:
        return 0.0 if not b else 1.0
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1] / len(a)


def key_ok(key_part: str, heard: str) -> bool:
    said = normalise(heard)
    for part in (key_part or "").split("|"):
        must, _, must_not = part.partition("!")
        if must and normalise(must) not in said:
            return False
        if must_not and normalise(must_not) in said:
            return False
    return True


def exact(expected: str, heard: str) -> bool:
    return normalise(expected) == normalise(heard)


# ------------------------------------------------------------------ reading

def read_tsv(path: Path) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t", quoting=csv.QUOTE_NONE))


def percentile(values: list[float], share: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(share * (len(ordered) - 1))))
    return ordered[index]


class Scored:
    def __init__(self, clip: dict, result: dict):
        self.clip, self.result = clip, result
        self.provider = result["provider"].strip()
        heard = result.get("transcript") or ""
        self.error = heard.startswith("ERROR")
        self.gated = bool((result.get("gate") or "").strip()) and not heard.strip()
        self.heard = "" if self.error else heard
        self.exact = exact(clip["expected_text"], self.heard)
        self.key = key_ok(clip["key_part"], self.heard)
        self.cer = cer(clip["expected_text"], self.heard)
        ms = (result.get("ms") or "").strip()
        self.ms = float(ms) if ms and not self.error else None


def score(manifest: list[dict], results: list[dict]) -> tuple[list[Scored], list[str]]:
    clips = {row["file"]: row for row in manifest}
    scored, problems = [], []
    for row in results:
        clip = clips.get(row.get("file", ""))
        if clip is None:
            problems.append(f"result for a file not in the manifest: {row.get('file')!r}")
            continue
        scored.append(Scored(clip, row))
    return scored, problems


def summary(rows: list[Scored]) -> dict:
    n = len(rows)
    ms = [r.ms for r in rows if r.ms is not None]
    return {
        "n": n,
        "exact": 100.0 * sum(r.exact for r in rows) / n if n else 0.0,
        "key": 100.0 * sum(r.key for r in rows) / n if n else 0.0,
        "cer": 100.0 * statistics.mean(r.cer for r in rows) if n else 0.0,
        "median_ms": statistics.median(ms) if ms else None,
        "p90_ms": percentile(ms, 0.9),
        "gated": sum(r.gated for r in rows),
        "errors": sum(r.error for r in rows),
    }


def _fmt_ms(value: float | None) -> str:
    return "-" if value is None else f"{value:.0f}"


def _line(label: str, s: dict) -> str:
    return (f"  {label:<28} n={s['n']:<4} exact={s['exact']:5.1f}%  key={s['key']:5.1f}%  "
            f"CER={s['cer']:5.1f}%  median={_fmt_ms(s['median_ms']):>5} ms  p90={_fmt_ms(s['p90_ms']):>5} ms"
            f"  gated={s['gated']}  errors={s['errors']}")


def report(scored: list[Scored], worst: int = 25, out=sys.stdout) -> None:
    providers = sorted({r.provider for r in scored})
    by_provider = {p: [r for r in scored if r.provider == p] for p in providers}
    print("OVERALL", file=out)
    for p in providers:
        print(_line(p, summary(by_provider[p])), file=out)
    for title, field in (("BY GENDER", "gender"), ("BY VOICE", "voice"), ("BY FEATURE", "feature")):
        print(f"\n{title}", file=out)
        for p in providers:
            groups = defaultdict(list)
            for r in by_provider[p]:
                groups[r.clip.get(field, "?")].append(r)
            for name in sorted(groups):
                print(_line(f"{p} / {name}", summary(groups[name])), file=out)

    if len(providers) >= 2:
        print("\nPAIRED ON THE KEY PART (same clip, both providers)", file=out)
        for i, a in enumerate(providers):
            for b in providers[i + 1:]:
                ra = {r.clip["file"]: r for r in by_provider[a]}
                rb = {r.clip["file"]: r for r in by_provider[b]}
                both = sorted(set(ra) & set(rb))
                only_a = [f for f in both if ra[f].key and not rb[f].key]
                only_b = [f for f in both if rb[f].key and not ra[f].key]
                print(f"  {a} vs {b}: {len(both)} clips in both; key right only in {a}: {len(only_a)}, "
                      f"only in {b}: {len(only_b)}", file=out)
                for f in only_a:
                    print(f"    {a} only  {f}: {b} heard {rb[f].heard!r}", file=out)
                for f in only_b:
                    print(f"    {b} only  {f}: {a} heard {ra[f].heard!r}", file=out)

    print(f"\nWORST {worst} (by CER, then key part missed)", file=out)
    ranked = sorted(scored, key=lambda r: (r.cer + (0 if r.key else 1)), reverse=True)
    for r in ranked[:worst]:
        tag = "GATED" if r.gated else "ERROR" if r.error else ("key-ok" if r.key else "KEY-MISSED")
        print(f"  {r.provider:<11} {r.clip['file']:<22} CER={100 * r.cer:5.1f}% {tag:<10} "
              f"said={r.clip['expected_text']!r} heard={r.heard!r}", file=out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("manifest", type=Path)
    parser.add_argument("results", type=Path)
    parser.add_argument("--worst", type=int, default=25)
    args = parser.parse_args(argv)
    scored, problems = score(read_tsv(args.manifest), read_tsv(args.results))
    for problem in problems:
        print("WARNING:", problem, file=sys.stderr)
    if not scored:
        print("no results to score", file=sys.stderr)
        return 1
    report(scored, args.worst)
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
