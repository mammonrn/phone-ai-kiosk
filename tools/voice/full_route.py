"""Every matrix row through service.handle_chat itself, on a throwaway broker home with
no eWeLink, no Google, no identity grant, no network, and a fake model that answers
"<MODEL>". Adds full_route_reply, full_route_action, model_called and full_route_agrees to
commands.tsv. Nothing leaves this PC."""

import json
import sys
import tempfile
import urllib.error
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "server"))
sys.path.insert(0, str(REPO / "server" / "tests"))

from kiosk_broker import auth, dashboard, store  # noqa: E402
from kiosk_broker.config import Config  # noqa: E402
from kiosk_broker.service import handle_chat  # noqa: E402
from conftest import FakeClient  # noqa: E402


def refuse(url, timeout):
    raise urllib.error.URLError("offline")


dashboard._get = refuse

home = Path(tempfile.mkdtemp(prefix="k61matrix-"))
(home / "pricing.json").write_text((REPO / "server" / "pricing.json").read_text(encoding="utf-8"),
                                   encoding="utf-8")
cfg = Config(home=home, rate_per_minute=1000, rate_per_day=100000, monthly_budget_usd=5.0)
conn = store.connect(cfg.db_path)
token = auth.issue(conn, "matrix")

path = Path(sys.argv[1])
rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()]
header, body = rows[0], rows[1:]
for extra in ("full_route_reply", "full_route_action", "model_called", "full_route_agrees"):
    if extra not in header:
        header.append(extra)
out = []
for row in body:
    row = row[:14]
    client = FakeClient(reply="<MODEL>")
    status, answer = handle_chat(conn, cfg, client, authorization=f"Bearer {token}",
                                 body=json.dumps({"text": row[2]}).encode("utf-8"))
    action = answer.get("action")
    row += [answer.get("reply") or f"HTTP {status} {answer}", json.dumps(action, ensure_ascii=False) if action else "none",
            "yes" if client.calls else "no"]
    expected_model = row[3].startswith("model")
    # D90 needs a held draft this throwaway broker cannot hold (no grant, no Google).
    got = (action or {}).get("type", "none")
    want = row[4].split(" ")[0]
    if row[0] == "D90":
        verdict = "n/a (needs a held draft)"
    elif expected_model:
        verdict = "yes" if client.calls else "NO"
    elif client.calls:
        verdict = "NO"
    elif row[1] == "lights":
        # No eWeLink here: every lights sentence ends at "not connected" (or both-verbs).
        verdict = "yes" if "ระบบไฟบ้าน" in answer.get("reply", "") or got == "none" else "NO"
    else:
        verdict = "yes" if got == want else "NO"
    row.append(verdict)
    out.append(row)
    print(row[0], "model" if client.calls else "code", (action or {}).get("type", "-"), row[14][:60])
with path.open("w", encoding="utf-8", newline="") as fh:
    for cells in [header] + out:
        fh.write("\t".join(str(c).replace("\t", " ") for c in cells) + "\n")
