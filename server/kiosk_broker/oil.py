"""Thai retail fuel prices: the three cheapest brands for the fuels people buy.

THE SOURCE, and what it is (checked 2026-09-23, Poom's decision):
api.chnwt.dev/thai-oil-api — the same developer as the gold API this broker
already uses (github.com/max180643/thai-oil-api, MIT licence for the code).
Its README says it "crawl[s] data from gasprice.kapook.com", and its code does
that on EVERY request: there is no cache on their side, so each call to
/latest is a page load on Kapook. Hence the long TTL here — prices change at
most once a day, announced the evening before — and the cached value served
with its age when the source is down.

WHAT THE PRICES ARE: "Retail Prices in Bangkok & Vicinities" — the source's own
note. Chiang Rai's pumps are usually a little higher, so the screen and the
model both say "กรุงเทพฯ" rather than pass these off as local prices.

THE FUELS: diesel (B7), gasohol 95 and gasohol E20 — the three with the most
litres sold in Thailand, in that order (EPPO's sales statistics, checked for
the earlier research). Premium, V-Power and the like are left out: they are
not what "ราคาน้ำมัน" means to most people.

MATCHED BY NAME, NOT BY KEY. The live response does not follow the README:
Bangchak's diesel key is spelt "disel", "diesel" is B7 at some brands and not
others, and Shell's standard grades are "เชลล์ ฟิวเซฟ …". The Thai name each
entry carries is the stable part, so that is what is matched.
"""

from __future__ import annotations

import re

OIL_URL = "https://api.chnwt.dev/thai-oil-api/latest"
CREDIT = "ราคากรุงเทพฯ จาก kapook ผ่าน chnwt.dev"
AREA = "กรุงเทพฯ"

#: The fuels shown, in order: (id, label on screen, the names that mean it).
FUELS = (
    ("diesel", "ดีเซล", ("ดีเซล B7", "ดีเซล", "เชลล์ ฟิวเซฟ ดีเซล")),
    ("gasohol_95", "โซฮอล์ 95", ("แก๊สโซฮอล์ 95", "เชลล์ ฟิวเซฟ แก๊สโซฮอล์ 95")),
    ("e20", "E20", ("แก๊สโซฮอล์ E20", "เชลล์ ฟิวเซฟ แก๊สโซฮอล์ E20")),
)

#: The brands, as a person says them, in the order a tie is listed. The
#: "susco_dealers" entry repeats Susco's own prices and is not a brand.
BRANDS = (
    ("ptt", "ปตท."), ("bcp", "บางจาก"), ("shell", "เชลล์"), ("caltex", "คาลเท็กซ์"),
    ("pt", "PT"), ("susco", "ซัสโก้"), ("pure", "เพียว"), ("irpc", "IRPC"), ("esso", "เอสโซ่"),
)

#: How many of the cheapest to keep per fuel.
CHEAPEST = 3

_PRICE = re.compile(r"^\d{1,3}(\.\d{1,2})?$")


def parse(raw: dict) -> dict:
    """The source's /latest, reduced to what the kiosk shows.

    {"date": "23 กันยายน 2569", "area": "กรุงเทพฯ",
     "fuels": [{"id", "label", "cheapest": [{"brand", "price"}, ...]}]}
    Raises ValueError when nothing usable came back, so the dashboard serves the
    last good value instead of an empty panel.
    """
    if not isinstance(raw, dict) or raw.get("status") != "success":
        raise ValueError("oil source did not say success")
    response = raw.get("response") or {}
    stations = response.get("stations") or {}
    fuels = []
    for fuel_id, label, names in FUELS:
        offers = []
        for rank, (key, brand) in enumerate(BRANDS):
            grades = stations.get(key)
            if not isinstance(grades, dict):
                continue
            price = _price_for(grades, names)
            if price is not None:
                offers.append((price, rank, brand))
        offers.sort()
        if offers:
            fuels.append({"id": fuel_id, "label": label,
                          "cheapest": [{"brand": b, "price": p} for p, _, b in offers[:CHEAPEST]]})
    if not fuels:
        raise ValueError("no fuel prices in the oil source")
    return {"date": str(response.get("date", ""))[:40], "area": AREA, "fuels": fuels}


def _price_for(grades: dict, names: tuple[str, ...]) -> float | None:
    for entry in grades.values():
        if not isinstance(entry, dict):
            continue
        name = str(entry.get("name", "")).strip()
        price = str(entry.get("price", "")).strip()
        if name in names and _PRICE.match(price):
            value = float(price)
            if 5.0 <= value <= 200.0:
                return value
    return None


# ------------------------------------------------------- the chat's oil line ---

#: Words that make a question about fuel. Only then is the oil line added to
#: the prompt, so the other questions pay nothing for it.
_ASKS_ABOUT_OIL = ("น้ำมัน", "ดีเซล", "เบนซิน", "โซฮอล์", "แก๊สโซฮอล", "e20", "e85", "ปั๊ม", "เติม")

#: Older than this, "today's price" is not today's any more.
MAX_OIL_AGE_SECONDS = 36 * 3600

MAX_OIL_LINE_CHARS = 220

NO_OIL_LINE = "น้ำมัน: ยังไม่มีข้อมูล ห้ามเดา"


def asks_about_oil(text: str) -> bool:
    squashed = "".join((text or "").split()).lower()
    return any(word in squashed for word in _ASKS_ABOUT_OIL)


def oil_line(found: tuple[int, dict] | None) -> str:
    """One prompt line from the dashboard's cached oil panel, never fetched."""
    if found is None or found[0] > MAX_OIL_AGE_SECONDS:
        return NO_OIL_LINE
    data = found[1]
    parts = []
    for fuel in data.get("fuels", []):
        cheapest = fuel.get("cheapest") or []
        if not cheapest:
            continue
        low = cheapest[0]["price"]
        brands = "/".join(c["brand"] for c in cheapest if c["price"] == low)
        parts.append(f"{fuel['label']} {_n(low)} ({brands})")
    if not parts:
        return NO_OIL_LINE
    # The persona says the model knows no prices; this line is the exception
    # and says so itself, so the fixed prompt every question pays for is not
    # made longer by a rule that only fuel questions need.
    head = (f"ราคาน้ำมันถูกสุด{data.get('area', AREA)} {data.get('date', '')}"
            "(รู้ราคานี้ ตอบจากค่านี้สั้นๆ บอกว่าเป็นราคากรุงเทพฯ):")
    return (head + " " + ", ".join(parts))[:MAX_OIL_LINE_CHARS]


def _n(value: float) -> str:
    return f"{value:.2f}".rstrip("0").rstrip(".")
