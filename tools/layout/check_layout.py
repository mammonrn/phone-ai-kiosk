"""The layout check on the real phone (0.63.0, Poom 2026-09-25).

Walks every screen of the kiosk through adb — home, the Control Panel and its
folder pop-ups, every app, every slide of the calculator, the video player in
full screen standing and lying down — and checks each one from the view
hierarchy, not from a picture:

  overlap   no button or text lies on another (a node inside another is fine:
            a label inside its own button is not an overlap)
  touch     every button is at least 48dp on its short side
  cut       no text is cut by the screen: "…" (ellipsized) or lines taller
            than their view. uiautomator always reports the WHOLE text, so the
            app itself says which views it cut (debug TEST_LAYOUT_REPORT)
  home      the Jarvis window at least 156dp tall, the exit corner exactly
            where it always was

One screenshot per screen is kept on the PC (never on a screen with a camera
open: those are checked from the hierarchy only), and a table of pass/fail per
screen is written next to them and printed.

RULE (CLAUDE.md): every UI change runs this before it is reported; a new
screen is added to SCREENS below.

    python tools/layout/check_layout.py [OUT_DIR] [--only NAME[,NAME...]]

Needs: the phone on adb with the debug APK from CI, in the kiosk (LOCKED).
Leaves it on the home screen. The one video it opens is paused at once, so
its place moves by a second or two at most.
"""

from __future__ import annotations

import datetime
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PKG = "com.mammonrn.phoneaikiosk.debug"
ACT = "com.mammonrn.phoneaikiosk"
#: The exit corner's place on the A07 (DESIGN: never moved, never shrunk).
EXIT_CORNER = (585, 1465, 720, 1600)
JARVIS_CARD_MIN_DP = 156
TOUCH_MIN_DP = 48
#: A node covering more than this share of the screen is a backdrop (a scrim,
#: a page), not something that can lie on top of a button.
BACKDROP_SHARE = 0.35
#: Touching edges and hairline borders are not overlaps.
SLACK_PX = 3


def adb(*args: str, check: bool = False) -> str:
    out = subprocess.run(["adb", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and out.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout


def shell(*args: str) -> str:
    return adb("shell", *args)


def broadcast(action: str, *extras: str) -> None:
    shell("am", "broadcast", "-a", f"{ACT}.{action}", "-p", PKG, *extras)


# ---------------------------------------------------------------- the tree

@dataclass
class Node:
    text: str
    desc: str
    rid: str
    cls: str
    package: str
    clickable: bool
    enabled: bool
    bounds: tuple[int, int, int, int]
    path: tuple[int, ...]            # position in the tree: ancestors are prefixes
    children: list = field(default_factory=list)

    @property
    def label(self) -> str:
        name = self.text or self.desc or self.rid.split("/")[-1] or self.cls.split(".")[-1]
        return " ".join(name.split())[:40]

    @property
    def w(self) -> int:
        return self.bounds[2] - self.bounds[0]

    @property
    def h(self) -> int:
        return self.bounds[3] - self.bounds[1]


def parse(xml: str) -> list[Node]:
    root = ET.fromstring(xml)
    nodes: list[Node] = []

    def walk(el, path):
        for i, child in enumerate(el.findall("node")):
            b = [int(v) for v in re.findall(r"-?\d+", child.get("bounds", "[0,0][0,0]"))]
            n = Node(child.get("text", ""), child.get("content-desc", ""), child.get("resource-id", ""),
                     child.get("class", ""), child.get("package", ""), child.get("clickable") == "true",
                     child.get("enabled") == "true", tuple(b), path + (i,))
            nodes.append(n)
            walk(child, path + (i,))

    walk(root, ())
    return nodes


def dump() -> tuple[str, list[Node]]:
    for _ in range(3):
        shell("uiautomator", "dump", "/sdcard/kiosk_layout.xml")
        xml = shell("cat", "/sdcard/kiosk_layout.xml")
        if xml.strip().startswith("<?xml"):
            return xml, parse(xml)
        time.sleep(1)
    raise RuntimeError("uiautomator dump failed")


# ---------------------------------------------------------------- moving about

def find(nodes: list[Node], prefix: str, nth: int = 0) -> Node | None:
    hits = [n for n in nodes if (n.text.startswith(prefix) or n.desc.startswith(prefix)) and n.w > 0]
    return hits[nth] if len(hits) > nth else None


def tap_node(n: Node, wait: float = 1.5) -> None:
    x, y = (n.bounds[0] + n.bounds[2]) // 2, (n.bounds[1] + n.bounds[3]) // 2
    shell("input", "tap", str(x), str(y))
    time.sleep(wait)


def tap(prefix: str, nth: int = 0, wait: float = 1.5) -> bool:
    _, nodes = dump()
    n = find(nodes, prefix, nth)
    if n is None:
        return False
    tap_node(n, wait)
    return True


def focus() -> str:
    line = next((l for l in shell("dumpsys", "window").splitlines() if "mCurrentFocus" in l), "")
    return line.split("/")[-1].rstrip("}").strip() if "/" in line else line.strip()


def home() -> None:
    broadcast("TEST_HOME")
    time.sleep(2)


def panel() -> None:
    for _ in range(3):
        if "SettingsActivity" in focus():
            return
        _, nodes = dump()
        button = next((n for n in nodes if n.rid.endswith(":id/settings_button")), None)
        if button:
            tap_node(button, 2)
    if "SettingsActivity" not in focus():
        raise RuntimeError("the Control Panel did not open")


def in_folder(folder: str, app: str) -> None:
    panel()
    if not tap(folder):
        raise RuntimeError(f"no folder {folder}")
    if not tap(app, wait=2.5):
        raise RuntimeError(f"no app {app} in {folder}")


def next_slide() -> bool:
    _, nodes = dump()
    dots = next((n for n in nodes if n.desc.startswith("หน้า ") and " จาก " in n.desc), None)
    if dots is None:
        return False
    tap_node(dots, 1.2)
    return True


def slide_count() -> int:
    _, nodes = dump()
    dots = next((n for n in nodes if n.desc.startswith("หน้า ") and " จาก " in n.desc), None)
    m = re.search(r"จาก (\d+)", dots.desc) if dots else None
    return int(m.group(1)) if m else 1


# ---------------------------------------------------------------- the checks

def density() -> float:
    m = re.search(r"(\d+)", shell("wm", "density").splitlines()[-1])
    return int(m.group(1)) / 160 if m else 1.875


def cut_report() -> list[tuple[str, tuple[int, int, int, int], str]]:
    adb("logcat", "-c")
    broadcast("TEST_LAYOUT_REPORT")
    lines: list[str] = []
    for _ in range(20):
        time.sleep(0.4)
        lines = adb("logcat", "-d", "-s", "KioskLayout").splitlines()
        if any("report done" in l for l in lines):
            break
    out = []
    for l in lines:
        m = re.search(r"cut kind=(\w+) bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] id=(\S+)", l)
        if m:
            out.append((m.group(1), tuple(int(m.group(i)) for i in range(2, 6)), m.group(6)))
    return out


def overlaps(nodes: list[Node], screen_area: int) -> list[str]:
    shown = [n for n in nodes if n.package == PKG and n.w > 0 and n.h > 0
             and (n.clickable or n.text.strip() or n.desc.strip())
             and n.w * n.h < BACKDROP_SHARE * screen_area]
    issues = []
    for i, a in enumerate(shown):
        for b in shown[i + 1:]:
            if a.path == b.path[:len(a.path)] or b.path == a.path[:len(b.path)]:
                continue                                   # one inside the other
            ix = min(a.bounds[2], b.bounds[2]) - max(a.bounds[0], b.bounds[0])
            iy = min(a.bounds[3], b.bounds[3]) - max(a.bounds[1], b.bounds[1])
            if ix > SLACK_PX and iy > SLACK_PX:
                issues.append(f"overlap: '{a.label}' {list(a.bounds)} × '{b.label}' {list(b.bounds)}")
    return issues


def small_targets(nodes: list[Node], dens: float) -> list[str]:
    least = TOUCH_MIN_DP * dens - 1
    return [f"touch: '{n.label}' {n.w / dens:.0f}×{n.h / dens:.0f}dp"
            for n in nodes
            if n.package == PKG and n.clickable and n.enabled and n.w > 0 and min(n.w, n.h) < least]


def cut_texts(nodes: list[Node], cuts) -> list[str]:
    out = []
    for kind, bounds, rid in cuts:
        same = [n for n in nodes if n.bounds == bounds and (n.text or n.desc)]
        what = same[0].label if same else rid
        out.append(f"cut ({kind}): '{what}' {list(bounds)}")
    return out


def home_rules(nodes: list[Node], dens: float) -> list[str]:
    out = []
    card = next((n for n in nodes if n.rid.endswith(":id/card_jarvis")), None)
    corner = next((n for n in nodes if n.rid.endswith(":id/exit_corner")), None)
    if card is None or card.h / dens < JARVIS_CARD_MIN_DP - 0.5:
        out.append(f"home: Jarvis window {card.h / dens:.0f}dp < {JARVIS_CARD_MIN_DP}dp" if card
                   else "home: no Jarvis window")
    if corner is None or corner.bounds != EXIT_CORNER:
        out.append(f"home: exit corner {list(corner.bounds) if corner else 'missing'} != {list(EXIT_CORNER)}")
    return out


# ---------------------------------------------------------------- the screens

@dataclass
class Screen:
    name: str
    go: object                       # a function that brings the screen up
    shot: bool = True                # False: a camera is open — no picture, ever
    extra: object = None             # extra checks: (nodes, density) -> issues


def app(folder: str | None, name: str):
    def go():
        home()
        if folder:
            in_folder(folder, name)
        else:
            panel()
            if not tap(name, wait=2.5):
                raise RuntimeError(f"no {name} in the Control Panel")
    return go


def panel_folder(folder: str):
    def go():
        home()
        panel()
        if not tap(folder):
            raise RuntimeError(f"no folder {folder}")
    return go


def calc_tab(tab: str):
    def go():
        app("เครื่องมือ", "เครื่องคิดเลข")()
        if not tap(tab):
            raise RuntimeError(f"no tab {tab}")
    return go


VIDEO_STATE: dict = {}


def video_full(landscape: bool):
    def go():
        home()
        in_folder("สื่อ", "เครื่องเล่นวิดีโอ")
        # The first film in the list, paused at once (its place moves a second at most).
        if not tap("ดูถึง", wait=3) and not tap("ยังไม่ได้ดู", wait=3):
            raise RuntimeError("no video in the list")
        shell("input", "tap", "360", "800")
        time.sleep(0.8)
        tap("หยุดชั่วคราว", wait=1) or tap("⏸", wait=1)
        shell("input", "tap", "360", "800")
        time.sleep(0.8)
        _, nodes = dump()
        fill = find(nodes, "เต็มจอ")
        if fill and "ปิด" in fill.text:
            tap_node(fill, 1.5)
        if landscape:
            broadcast("TEST_VIDEO_ROTATE", "--es", "to", "landscape")
            time.sleep(2.5)
        shell("input", "tap", "360", "400")          # the controls up for the check
        time.sleep(1)
    return go


def after_video() -> None:
    broadcast("TEST_VIDEO_ROTATE", "--es", "to", "portrait")
    time.sleep(1.5)


SCREENS: list[Screen] = [
    Screen("home", lambda: home(), extra=home_rules),
    Screen("panel", lambda: (home(), panel())),
    Screen("folder-media", panel_folder("สื่อ")),
    Screen("folder-tools", panel_folder("เครื่องมือ")),
    Screen("lights", app(None, "ไฟในบ้าน")),
    Screen("files", app(None, "จัดการไฟล์")),
    Screen("identity", app(None, "ยืนยันตัวตน"), shot=False),
    Screen("sources", app(None, "ที่มาข้อมูล")),
    Screen("music", app("สื่อ", "เครื่องเล่นเพลง")),
    Screen("video-list", app("สื่อ", "เครื่องเล่นวิดีโอ")),
    Screen("video-full-portrait", video_full(False)),
    Screen("video-full-landscape", video_full(True)),
    Screen("radio", app("สื่อ", "วิทยุ")),
    Screen("camera", app("สื่อ", "กล้อง"), shot=False),
    Screen("recorder", app("สื่อ", "บันทึกเสียง")),
    Screen("alarms", app("เครื่องมือ", "นาฬิกาปลุก")),
    Screen("timer", app("เครื่องมือ", "จับเวลา")),
    Screen("notes", app("เครื่องมือ", "โน้ต")),
    Screen("level", app("เครื่องมือ", "ระดับน้ำ")),
    Screen("calc-main", calc_tab("คำนวณ")),
    Screen("calc-history", calc_tab("ประวัติ")),
]
#: Tabs whose slides are each checked: the calculator's SlideDeck pages.
SLIDE_TABS = ["ไฟฟ้า", "โซลาร์", "หน่วย", "ราคา"]


def check_screen(name: str, shot: bool, extra, out: Path, dens: float, area: int) -> list[str]:
    time.sleep(0.8)
    xml, nodes = dump()
    (out / f"{name}.xml").write_text(xml, encoding="utf-8")
    if shot:
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
        (out / f"{name}.png").write_bytes(png)
    issues = overlaps(nodes, area) + small_targets(nodes, dens) + cut_texts(nodes, cut_report())
    if extra:
        issues += extra(nodes, dens)
    if not any(n.package == PKG for n in nodes):
        issues.append(f"not our screen: {focus()}")
    return issues


def main(argv: list[str]) -> int:
    only = None
    if "--only" in argv:
        i = argv.index("--only")
        only = set(argv[i + 1].split(","))
        argv = argv[:i] + argv[i + 2:]
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M")
    out = Path(argv[1]) if len(argv) > 1 else Path(os.environ.get("TEMP", "/tmp")) / "kiosk-layout" / stamp
    out.mkdir(parents=True, exist_ok=True)
    dens = density()
    size = re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1]
    area = int(size[0]) * int(size[1])
    rows: list[tuple[str, list[str]]] = []

    def run(name, go, shot=True, extra=None):
        if only and name not in only and name.split("-")[0] not in only:
            return
        try:
            go()
            issues = check_screen(name, shot, extra, out, dens, area)
        except Exception as exc:  # noqa: BLE001 — one screen failing must not stop the rest
            issues = [f"could not check: {exc}"]
        rows.append((name, issues))
        print(f"{'PASS' if not issues else 'FAIL'}  {name}" + "".join(f"\n      {i}" for i in issues), flush=True)

    for s in SCREENS:
        run(s.name, s.go, s.shot, s.extra)
        if s.name.startswith("video-full"):
            after_video()
    for tab in SLIDE_TABS:
        def first(tab=tab):
            calc_tab(tab)()
        run(f"calc-{tab}-1", first)
        if only and f"calc-{tab}-1" not in only and "calc" not in only:
            continue
        for k in range(2, slide_count() + 1):
            run(f"calc-{tab}-{k}", next_slide)
    home()

    lines = ["| หน้า | ผล | ปัญหา |", "|---|---|---|"]
    for name, issues in rows:
        lines.append(f"| {name} | {'ผ่าน' if not issues else 'ไม่ผ่าน'} | {'<br>'.join(issues) or '-'} |")
    (out / "results.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    failed = sum(1 for _, i in rows if i)
    print(f"\n{len(rows) - failed}/{len(rows)} screens pass · table and pictures in {out}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
