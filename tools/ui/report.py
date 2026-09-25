"""The UI check's summary (scripts/ui-check, 0.63.0): a table per screen, every
screen on one sheet with its name under it, and one picture per failing screen.

    python tools/ui/report.py PULLED_DIR OUT_DIR

PULLED_DIR holds what the walk wrote on the phone: results.json and NAME.png
(no picture for a screen with a camera open — a grey card says so instead).
"""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

THUMB_W, THUMB_H, LABEL_H, COLS = 216, 480, 44, 8


def font(size: int):
    for name in ("C:/Windows/Fonts/tahoma.ttf", "C:/Windows/Fonts/leelawad.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main(pulled: Path, out: Path) -> int:
    rows = json.loads((pulled / "results.json").read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=True)
    failed_dir = out / "failed"
    if failed_dir.exists():
        shutil.rmtree(failed_dir)
    failed_dir.mkdir()

    lines = ["| หน้า | ผล | ปัญหา | ข้อยกเว้น (รอ Poom) |", "|---|---|---|---|"]
    for r in rows:
        issues = r.get("issues", [])
        lines.append(f"| {r['name']} | {'ผ่าน' if r.get('pass') else 'ไม่ผ่าน'} | "
                     f"{'<br>'.join(issues) or '-'} | {'<br>'.join(r.get('exceptions', [])) or '-'} |")
        pic = pulled / f"{r['name']}.png"
        if not r.get("pass") and pic.exists():
            shutil.copy(pic, failed_dir / pic.name)
    (out / "results.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    n = len(rows)
    sheet_rows = (n + COLS - 1) // COLS
    sheet = Image.new("RGB", (COLS * THUMB_W, sheet_rows * (THUMB_H + LABEL_H)), "white")
    draw = ImageDraw.Draw(sheet)
    label_font = font(15)
    for i, r in enumerate(rows):
        x, y = (i % COLS) * THUMB_W, (i // COLS) * (THUMB_H + LABEL_H)
        pic = pulled / f"{r['name']}.png"
        if pic.exists():
            im = Image.open(pic).convert("RGB")
            im.thumbnail((THUMB_W - 6, THUMB_H - 6))
            sheet.paste(im, (x + (THUMB_W - im.width) // 2, y + 3))
        else:
            draw.rectangle([x + 3, y + 3, x + THUMB_W - 4, y + THUMB_H - 4], fill=(200, 200, 200))
            draw.text((x + 12, y + THUMB_H // 2), "no picture\n(camera screen)", fill="black", font=label_font)
        colour = (0, 120, 0) if r.get("pass") else (190, 0, 0)
        mark = "PASS" if r.get("pass") else "FAIL"
        draw.rectangle([x, y + THUMB_H, x + THUMB_W - 1, y + THUMB_H + LABEL_H - 1], outline=colour, width=2)
        draw.text((x + 6, y + THUMB_H + 4), f"{mark} {r['name']}"[:30], fill=colour, font=label_font)
    sheet.save(out / "sheet.png")

    passed = sum(1 for r in rows if r.get("pass"))
    for r in rows:
        if not r.get("pass"):
            print(f"FAIL  {r['name']}")
            for issue in r.get("issues", []):
                print(f"      {issue}")
    print(f"\n{passed}/{n} screens pass · {out / 'results.md'} · {out / 'sheet.png'}")
    return 0 if passed == n else 1


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2])))
