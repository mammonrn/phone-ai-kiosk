# kiosk broker

The only thing the kiosk phone is allowed to talk to. Phase 2: it takes a Thai
question over HTTPS, asks Claude Haiku, and returns a short answer meant to be
read aloud. It has no tools and returns `action: null` on every response.

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

**No web framework.** stdlib `http.server` behind nginx, with the Anthropic SDK
as the only third-party dependency. One phone, two routes, and a dependency
tree is a thing to keep patched.

## Development

The tests need no key, no network and no VPS:

```bash
python3 -m pip install --target ./vendor pytest anthropic
PYTHONPATH=./vendor python3 -m pytest tests/ -q
```

`tests/test_llm_sdk.py` runs the real Anthropic SDK against a local stub of
`/v1/messages`, which is what checks the request shape and the usage field
names. Everything else uses a fake client.
