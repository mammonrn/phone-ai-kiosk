# kiosk broker

The only thing the kiosk phone is allowed to talk to. Phase 2: it takes a Thai
question over HTTPS, asks Claude Haiku, and returns a short answer meant to be
read aloud. It has no tools and returns `action: null` on every response.

**Running in production** on 45.76.157.64 since 22 Sep 2026, behind
`kiosk.xn--l3cgts1b3bzcvf.com`. Verified end to end from a Windows machine over
real HTTPS: a valid token gets a Thai reply with `action: null`, no token and a
wrong token both get 401, `/` gets 404, and plain HTTP redirects. The API key is
readable only by `kioskbroker` — `linuxuser` gets Permission denied, which is
the point. Renewal reloads nginx on its own, verified with
`certbot renew --dry-run --run-deploy-hooks` across all three certificates on
the host. [INSTALL.md](INSTALL.md) carries the measurements.

Install and operate: **[INSTALL.md](INSTALL.md)** (Thai).

## API

### `POST /v1/chat`

```
Authorization: Bearer <device-token>
Content-Type: application/json

{"text": "วันนี้อากาศเป็นยังไง", "conversation_id": "optional"}
```

```json
{
  "reply": "ผมยังดูสภาพอากาศให้ไม่ได้ครับ",
  "action": null,
  "conversation_id": "Yk2vQ1pR8sNf"
}
```

`conversation_id` is generated when the client does not send one; send it back
on the next request to continue the conversation.

`action` is **always `null` in phase 2**, and that is enforced by the server,
not by the prompt — see `actions.ENABLED_ACTION_TYPES`, which is an empty
frozenset. Phase 4 adds `"open_maps"` to it and nothing else. The shape the
field will take is already fixed:

```json
{"action": {"type": "open_maps", "query": "เซ็นทรัลเวิลด์"}}
```

Errors are `{"error": {"code": "...", "message": "..."}}` with a Thai message
fit to read aloud:

| Status | `code` | When |
|---|---|---|
| 400 | `bad_request`, `text_too_long` | Malformed body, missing or over-long text, bad `conversation_id` |
| 401 | `unauthorized` | Missing, malformed, unknown **or revoked** token — deliberately one code for all four |
| 402 | `budget_exhausted` | The month's budget is gone. No fallback to a cheaper model, by decision |
| 413 | `payload_too_large` | Body over the cap |
| 429 | `rate_limited`, `rate_limited_daily` | Per-token limits |
| 502 | `upstream_error` | The API could not be reached |

### `GET /healthz`

`{"status": "ok"}`, no token needed. It reports nothing about spend, tokens or
the model — it exists so nginx and a person can ask "is it up".

## Design notes

**Separation is by identity.** The broker runs as `kioskbroker`: no sudo, no
shell, and not a member of `shared`, `linuxuser`, `hermes` or `builder`. The
systemd unit replaces every other home directory with an empty tmpfs and binds
only its own back in, so `~hermes` does not exist from inside the process
rather than merely being unreadable. It never touches the Hermes gateway,
`hermes-claude`, `hermes-deploy`, Gmail, Calendar, Drive, or anything holding
money.

**The listener is loopback-only.** nginx terminates TLS on its own vhost with
its own `server_name`; the thaitrack and monthreport vhosts are untouched.

**Tokens are stored as SHA-256 only**, one per device, revocable by row. The
raw token exists once, on the terminal that issued it. SHA-256 rather than a
slow KDF because these are 32 random bytes, not a password — a slow hash would
buy nothing against 256 bits of entropy and would hand anyone a way to make
the broker unresponsive.

**Cost comes from the usage the API reports**, priced from `pricing.json`,
which carries its own source URL and the date it was checked. Nothing here
computes a price from a multiplier or carries a rate in code: the last time a
rate was inlined on this machine the answer came out 18x wrong because the
model had changed underneath it.

**The budget guard runs before the call**, and refuses when one more request
*could* cross the cap rather than when it already has. When it refuses, it
refuses — there is no cheaper model to fall back to.

**The request log holds no message text.** It records the device, the outcome,
and the length. History is kept separately, as conversation state rather than a
log: a few turns, pruned by count and by age, because every turn is resent as
input tokens and history is the quiet way to spend a monthly budget.

**The register is enforced in code, not only asked for in the prompt.** The
prompt says ผม and ครับ, and production still answered
`"สวัสดีครับ ผมพร้อมช่วยเหลือค่ะ มีอะไรให้ผมช่วยได้บ้างครับ"` — three clauses,
two genders. Replies are now corrected on the way out, and the count of
corrections is recorded (never the text) so `usage` can say whether the prompt
is holding on its own. The hard part is that Thai has no spaces: a plain
replace of `คะ` turns `คะแนน` (score) into `ครับแนน`, so the particles are only
replaced where no Thai letter follows them. `test_register.py` is mostly a list
of words that must survive.

**The prompt is the running cost.** Thai runs near one token per character on
this model, and the system prompt is resent on every request — the first
production version measured 1,107 input tokens for a one-line question, which
was most of what each answer cost. Shortening it to 631 characters measured
656 on production, 41% less per question, and a test holds the ceiling. Prompt caching cannot help: Haiku 4.5 will not cache a prefix
under 4,096 tokens and returns no error when it declines, and padding the
prompt up to that floor needs more than 24 questions inside every 5-minute
cache window before it breaks even. `prompt-size` measures the prompt through
the token-counting endpoint, which is free.

**History is bounded in both directions** — a few turns, capped by count and by
age, in memory and on disk. Every turn is resent as input tokens, so an
unbounded history is a bill that grows with use.

**No web framework.** stdlib `http.server` behind nginx, with the Anthropic SDK
as the only third-party dependency. One phone, two routes, and a dependency
tree is a thing to keep patched.

## Operator commands

Run as `kioskbroker` on the VPS; none of them are reachable over HTTP.

| Command | What it does |
|---|---|
| `issue-token <label>` | Creates a device and prints its token once |
| `revoke-token <label>` | Revokes it |
| `list-devices` | Labels and whether each is active |
| `usage` | This month's spend, the cap, and where the prices came from |
| `selftest` | One real call, printing input tokens and the measured cost. Not written to the ledger |
| `prompt-size` | The prompt's size in tokens, through the free token-counting endpoint. No answer generated, nothing billed |

## Development

The tests need no key, no network and no VPS:

```bash
python3 -m pip install --target ./vendor pytest anthropic
PYTHONPATH=./vendor python3 -m pytest tests/ -q
```

`tests/test_llm_sdk.py` runs the real Anthropic SDK against a local stub of
`/v1/messages`, which is what checks the request shape and the usage field
names. Everything else uses a fake client.
