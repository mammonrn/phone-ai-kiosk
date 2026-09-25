"""Measure Thai internet radio on the free Radio Browser API (no key).

Run on the PC:  python tools/radio/measure.py [--json out.json]

1. Counts Thai stations (countrycode TH) and how many passed Radio Browser's
   last check (lastcheckok=1).
2. Looks for the hit stations by name and probes every candidate stream from
   this PC: an HTTP GET that reads the first bytes (Icecast/Shoutcast/MP3/AAC)
   or fetches the HLS playlist and its first child. Short timeouts, one try
   each, no retry.
3. Probes the official stream URLs in OFFICIAL (found by hand on each
   station's own website or player; the source is written next to each).

Prints a table. Nothing is sent anywhere except the GETs to Radio Browser and
to the stream URLs themselves.
"""
import json
import re
import socket
import sys
import urllib.parse

import requests

UA = "phone-ai-kiosk-radio-measure/1.0 (+https://github.com/mammonrn/phone-ai-kiosk)"
TIMEOUT = 8

# The hit stations to look for: label, name regexes (case-insensitive)
# matched against Radio Browser's station name.
HITS = [
    ("Cool Fahrenheit 93", [r"cool\s*(fahrenheit|93)", r"coolism", r"^cool\b"]),
    ("Eazy FM 105.5", [r"eazy"]),
    ("Green Wave 106.5", [r"green\s*wave"]),
    ("Virgin Hitz 95.5", [r"virgin"]),
    ("Hitz 95.5", [r"hitz"]),
    ("Chill FM 89", [r"chill"]),
    ("Met 107", [r"\bmet\s*107", r"met107"]),
    ("FM One 103.5", [r"fm\s*one", r"103\.5"]),
    ("Retro FM", [r"retro"]),
    ("Sanook", [r"sanook"]),
    ("Pynk", [r"pynk"]),
    ("EFM 94", [r"\befm\b", r"94\s*efm"]),
    ("Radio Thailand / สวท.", [r"radio\s*thailand", r"สวท", r"\bprd\b", r"\bnbt\b"]),
    ("MCOT / อสมท", [r"mcot", r"อสมท"]),
    ("Look Thung", [r"look\s*thung", r"ลูกทุ่ง", r"luk\s*thung"]),
]

GLOBAL_NAMES = [
    ("Eazy FM", "eazy"), ("Green Wave", "green wave"), ("Green Wave", "greenwave"), ("Virgin Hitz", "virgin hitz"),
    ("Hitz", "hitz 95"), ("Chill FM", "chill 89"), ("Chill FM", "chill fm"), ("Met 107", "met 107"),
    ("FM One", "fm one"), ("EFM", "efm"), ("Retro", "retro"), ("Sanook", "sanook"), ("Pynk", "pynk"),
    ("MCOT", "mcot"), ("Look Thung", "look thung"), ("Radio Thailand", "radio thailand"),
]

# Official streams found by hand: label, url, where it came from.
OFFICIAL = [
    ("Cool Fahrenheit 93", "https://coolism-web.cdn.byteark.com/;stream/1", "coolism.net player JS (default linkWeb + /;stream/1)"),
    ("EFM 94", "https://atimeonline.smartclick.co.th/efm", "atime.live page JSON radio_feed_desktop"),
    ("Green Wave 106.5", "https://atimeonline2.smartclick.co.th/green", "atime.live page JSON radio_feed_desktop"),
    ("Hotwave (icy-name Chill Online)", "https://atimeonline.smartclick.co.th/hotwave", "atime.live page JSON, Hotwave entry"),
    ("Met 107", "https://play-fm107.mcot.net/fm107/fm107.m3u8", "api-service.mcot.net/api/web/v1/met107/setting/"),
    ("MCOT News FM 100.5", "https://play-fm1005.mcot.net/fm1005/fm1005.m3u8", "api-service.mcot.net/api/web/v1/news1005/setting/"),
    ("ลูกทุ่งมหานคร FM 95", "https://play-fm95.mcot.net/fm95/fm95.m3u8", "api-service.mcot.net/api/web/v1/ltmfm95/setting/"),
    ("FM 99 Active", "https://play-fm99.mcot.net/fm99/fm99.m3u8", "api-service.mcot.net/api/web/v1/fm99activeradio/setting/"),
    ("Smooth FM 105.5", "https://play-fm1055.mcot.net/fm1055/fm1055.m3u8", "api-service.mcot.net/api/web/v1/smoothfm/setting/"),
    ("คลื่นความคิด 96.5", "https://play-fm965.mcot.net/fm965/fm965.m3u8", "api-service.mcot.net/api/web/v1/thinkingradio/setting/"),
    ("สวท. FM 92.5", "https://cdn-edge.iiptvcdn.com/radio_edge/45d4-d521-cbfc-d686-0123/playlist.m3u8", "nbtplus.prd.go.th/radio -> api-gw-ott.prd.go.th radio/detail stream_url"),
    ("สวท. AM 891", "https://cdn-edge.iiptvcdn.com/radio_edge/51dd-90a9-c6ae-c7f7-49d4/playlist.m3u8", "nbtplus.prd.go.th/radio -> api-gw-ott.prd.go.th radio/detail stream_url"),
    ("ลูกทุ่งรักไทย FM 90", "https://radio11.plathong.net/8896/;stream.mp3", "90rakthai.com <audio><source> in page HTML"),
]


def pick_server():
    """Radio Browser's documented way: resolve all.api.radio-browser.info and
    use a server name; fall back to the known mirrors."""
    names = []
    try:
        for info in socket.getaddrinfo("all.api.radio-browser.info", 443, proto=socket.IPPROTO_TCP):
            try:
                names.append(socket.gethostbyaddr(info[4][0])[0])
            except OSError:
                pass
    except OSError:
        pass
    for n in names + ["de1.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info"]:
        try:
            r = requests.get(f"https://{n}/json/stats", headers={"User-Agent": UA}, timeout=TIMEOUT)
            if r.ok:
                return n
        except requests.RequestException:
            continue
    raise SystemExit("no Radio Browser server answered")


def thai_stations(server):
    r = requests.get(
        f"https://{server}/json/stations/search",
        params={"countrycode": "TH", "hidebroken": "false", "limit": 100000},
        headers={"User-Agent": UA},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def probe(url, depth=0):
    """One GET, short timeout. Returns (ok, detail)."""
    try:
        with requests.get(url, headers={"User-Agent": UA, "Icy-MetaData": "1"}, timeout=TIMEOUT,
                          stream=True, allow_redirects=True) as r:
            ctype = r.headers.get("Content-Type", "").split(";")[0].strip().lower()
            if r.status_code != 200:
                return False, f"HTTP {r.status_code}"
            first = b""
            for chunk in r.iter_content(4096):
                first += chunk
                if len(first) >= 4096:
                    break
            path = url.lower().split("?")[0]
            is_playlist = path.endswith((".m3u8", ".m3u", ".pls")) or "mpegurl" in ctype \
                or first.startswith(b"#EXTM3U") or first.lstrip().startswith(b"[playlist]")
            if is_playlist:
                text = first.decode("utf-8", "replace")
                if "#EXT-X-" in text:
                    lines = [ln.strip() for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]
                    if not lines:
                        return False, "HLS playlist empty"
                    nxt = urllib.parse.urljoin(r.url, lines[0])
                    try:
                        r2 = requests.get(nxt, headers={"User-Agent": UA}, timeout=TIMEOUT, stream=True)
                        ok2 = r2.status_code == 200
                        code = r2.status_code
                        r2.close()
                    except requests.RequestException as e:
                        return False, f"HLS child failed ({type(e).__name__})"
                    return ok2, "HLS ok" if ok2 else f"HLS child HTTP {code}"
                m = re.search(r"https?://\S+", text)
                if m and depth < 2:
                    ok3, d3 = probe(m.group(0).strip(), depth + 1)
                    return ok3, "playlist -> " + d3
                return False, "playlist without URL"
            icy = r.headers.get("icy-name") is not None or r.headers.get("icy-br") is not None
            if ctype.startswith("text/html"):
                return False, "HTML page, not a stream"
            return len(first) > 0, f"{ctype or 'no type'} {len(first)}B" + (" icy" if icy else "")
    except requests.RequestException as e:
        return False, type(e).__name__


def main():
    out = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
    server = pick_server()
    print(f"server: {server}")
    st = thai_stations(server)
    ok = [s for s in st if s.get("lastcheckok") == 1]
    print(f"TH stations: {len(st)}  lastcheckok=1: {len(ok)}  hls: {sum(1 for s in st if s.get('hls'))}")
    results = {"server": server, "total": len(st), "lastcheckok": len(ok), "hits": [], "official": []}
    for label, pats in HITS:
        found = [s for s in st if any(re.search(p, s["name"], re.I) for p in pats)]
        found.sort(key=lambda s: (-s.get("lastcheckok", 0), -s.get("votes", 0)))
        if not found:
            print(f"\n## {label}: NOT in Radio Browser")
            results["hits"].append({"label": label, "found": []})
            continue
        print(f"\n## {label}: {len(found)} match(es)")
        rows = []
        for s in found[:6]:
            url = s.get("url_resolved") or s.get("url")
            good, detail = probe(url)
            print(f"  [{'OK ' if good else 'BAD'}] {s['name']!r} {s.get('codec')} {s.get('bitrate')}kbps "
                  f"check={s.get('lastcheckok')} votes={s.get('votes')} clicks={s.get('clickcount')}\n"
                  f"        {url}  ({detail})  home={s.get('homepage')}")
            rows.append({"name": s["name"], "url": url, "codec": s.get("codec"), "bitrate": s.get("bitrate"),
                         "lastcheckok": s.get("lastcheckok"), "votes": s.get("votes"), "probe_ok": good,
                         "probe": detail, "homepage": s.get("homepage")})
        results["hits"].append({"label": label, "found": rows})
    # Some Thai stations are listed with no country or another one: search the
    # whole directory by name too, and keep what looks Thai.
    print("\n## name search, all countries")
    for label, query in GLOBAL_NAMES:
        r = requests.get(f"https://{server}/json/stations/search", params={"name": query, "limit": 300},
                         headers={"User-Agent": UA}, timeout=30)
        rows = [s for s in r.json() if s["countrycode"] != "TH" and
                re.search(r"thai|bangkok|ไทย|\.th\b|\.th/", s["tags"] + s["name"] + s["homepage"], re.I)]
        print(f"  {label} ({query!r}): {len(rows)} outside TH")
        for s in rows[:4]:
            url = s.get("url_resolved") or s.get("url")
            good, detail = probe(url)
            print(f"    [{'OK ' if good else 'BAD'}] {s['countrycode'] or '--'} {s['name']!r} {s.get('codec')} "
                  f"{s.get('bitrate')} {url} ({detail})")
    if OFFICIAL:
        print("\n## official streams")
    for label, url, source in OFFICIAL:
        good, detail = probe(url)
        print(f"  [{'OK ' if good else 'BAD'}] {label}: {url}  ({detail})  source: {source}")
        results["official"].append({"label": label, "url": url, "source": source, "probe_ok": good, "probe": detail})
    if "--list-top" in sys.argv:
        print("\n## top 40 by votes (lastcheckok=1)")
        for s in sorted(ok, key=lambda s: -s.get("votes", 0))[:40]:
            print(f"  {s['votes']:4d} {s.get('clickcount'):4d} {s['name']!r} {s.get('codec')} {s.get('bitrate')} hls={s.get('hls')}")
    if out:
        with open(out, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
