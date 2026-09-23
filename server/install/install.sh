#!/usr/bin/env bash
#
# Installs the kiosk broker. Run as root, from the repo, on the VPS:
#
#     sudo bash server/install/install.sh
#
# Idempotent: safe to re-run after a code change. It never touches the
# thaitrack or monthreport vhosts, and it checks that both still answer before
# and after it reloads nginx.
#
# It does NOT issue the certificate and does NOT enable the nginx site — those
# need the DNS record to exist first, so INSTALL.md walks them separately.
set -euo pipefail

USER_NAME=kioskbroker
HOME_DIR=/home/$USER_NAME
APP_DIR=$HOME_DIR/app
VENV_DIR=$HOME_DIR/venv
CONF_DIR=$HOME_DIR/.config/kiosk-broker
REPO_SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say() { printf '\n== %s\n' "$*"; }

[[ $EUID -eq 0 ]] || { echo "run this with sudo" >&2; exit 1; }

# ---------------------------------------------------------------- baseline
# Recorded before anything changes, so "it still works" is a comparison rather
# than an impression.
say "Checking the existing sites before touching anything"
before_thaitrack=$(curl -s -o /dev/null -w '%{http_code}' -k -H 'Host: xn--l3cgts1b3bzcvf.com' https://127.0.0.1/ || echo 000)
before_monthreport=$(curl -s -o /dev/null -w '%{http_code}' -k -H 'Host: ubet89.house' https://127.0.0.1/ || echo 000)
echo "  thaitrack=$before_thaitrack monthreport=$before_monthreport"
if [[ $before_thaitrack == 000 || $before_monthreport == 000 ]]; then
    echo "  One of them is already not answering. Fix that first — this script will not" >&2
    echo "  be able to tell afterwards whether it was to blame." >&2
    exit 1
fi

# ------------------------------------------------------------------- user
say "User $USER_NAME"
if id "$USER_NAME" &>/dev/null; then
    echo "  already exists"
else
    # No sudo, no shell login, and not a member of shared, linuxuser, hermes or
    # builder. Nothing this user can read belongs to any other service.
    # --user-group is explicit on purpose: a --system user does not reliably get
    # a group of its own, and the unit's Group=kioskbroker would then fail to
    # start with a name-lookup error rather than anything that points at this.
    useradd --system --user-group --create-home --home-dir "$HOME_DIR" \
        --shell /usr/sbin/nologin "$USER_NAME"
    echo "  created"
fi

# Fails loudly rather than installing into a user that can reach other people's
# files — the whole design rests on this being true.
for forbidden in shared linuxuser hermes builder sudo adm; do
    if id -nG "$USER_NAME" | tr ' ' '\n' | grep -qx "$forbidden"; then
        echo "  $USER_NAME is in group '$forbidden'. Remove it before continuing." >&2
        exit 1
    fi
done
echo "  groups: $(id -nG "$USER_NAME")"

# ------------------------------------------------------------------ python
say "Python virtualenv"
if ! python3 -c 'import ensurepip' &>/dev/null; then
    echo "  python3-venv is missing; installing it"
    apt-get install -y "python3.$(python3 -c 'import sys; print(sys.version_info.minor)')-venv"
fi
install -d -o "$USER_NAME" -g "$USER_NAME" -m 0755 "$APP_DIR"
if [[ ! -x $VENV_DIR/bin/python ]]; then
    sudo -u "$USER_NAME" python3 -m venv "$VENV_DIR"
fi
sudo -u "$USER_NAME" "$VENV_DIR/bin/pip" install --quiet --upgrade pip
# groq for speech-to-text. Google Text-to-Speech is deliberately NOT a library:
# it is one JSON POST with an API key, which urllib does with nothing installed,
# and an API key can be restricted to one API and one IP in a way a service
# account key cannot.
sudo -u "$USER_NAME" "$VENV_DIR/bin/pip" install --quiet anthropic groq
echo "  anthropic $(sudo -u "$USER_NAME" "$VENV_DIR"/bin/python -c 'import anthropic; print(anthropic.__version__)')"
echo "  groq      $(sudo -u "$USER_NAME" "$VENV_DIR"/bin/python -c 'import groq; print(groq.__version__)')"
# nlpo3: the Thai word segmenter for the voice (kiosk_broker/wordcut.py, 0.38).
# A 2.6 MB wheel (Apache-2.0); the dictionary is shipped in the code. OPTIONAL
# on purpose: there are wheels for x86_64 only, and if it cannot be installed
# the broker speaks unsegmented text exactly as before — so a failure here is
# a warning, never a failed deploy.
if sudo -u "$USER_NAME" "$VENV_DIR/bin/pip" install --quiet --only-binary=:all: "nlpo3==1.4.0"; then
    echo "  nlpo3     $(sudo -u "$USER_NAME" "$VENV_DIR"/bin/python -c 'import importlib.metadata as m; print(m.version("nlpo3"))')"
else
    echo "  WARNING: nlpo3 did not install (no wheel for this machine?) — the voice works, unsegmented"
fi

# -------------------------------------------------------------------- code
say "Application code"
rm -rf "$APP_DIR/kiosk_broker"
cp -r "$REPO_SERVER_DIR/kiosk_broker" "$APP_DIR/"
# Which commit this is, for /healthz. "Did the deploy happen?" was answered by
# guessing from behaviour once too often. safe.directory because this runs as
# root in a checkout owned by somebody else, which git otherwise refuses.
BUILD_ID="$(git -c safe.directory="$REPO_SERVER_DIR/.." -C "$REPO_SERVER_DIR/.." \
    rev-parse --short HEAD 2>/dev/null || echo unknown)"
printf '%s\n' "$BUILD_ID" > "$APP_DIR/kiosk_broker/BUILD"
echo "  build $BUILD_ID"
install -d -o "$USER_NAME" -g "$USER_NAME" -m 0700 "$CONF_DIR"
install -o "$USER_NAME" -g "$USER_NAME" -m 0644 "$REPO_SERVER_DIR/pricing.json" "$CONF_DIR/pricing.json"
# The transcriber hint words: written ONCE, then Poom's to edit — a deploy
# never overwrites them. Check an edit with `stt-hints-check`.
# The TTS respelling dictionary: the same rule, written once and then Poom's.
if [[ ! -f $CONF_DIR/pronunciation.json ]]; then
    install -o "$USER_NAME" -g "$USER_NAME" -m 0644 "$REPO_SERVER_DIR/pronunciation.json" "$CONF_DIR/pronunciation.json"
    echo "  wrote a default pronunciation.json"
fi
# The segmenter's project words: the same rule, written once and then Poom's.
if [[ ! -f $CONF_DIR/tts_words.txt ]]; then
    install -o "$USER_NAME" -g "$USER_NAME" -m 0644 "$REPO_SERVER_DIR/tts_words.txt" "$CONF_DIR/tts_words.txt"
    echo "  wrote a default tts_words.txt"
fi
if [[ ! -f $CONF_DIR/stt_hints.json ]]; then
    install -o "$USER_NAME" -g "$USER_NAME" -m 0644 "$REPO_SERVER_DIR/stt_hints.json"         "$CONF_DIR/stt_hints.json"
    echo "  wrote a default stt_hints.json"
fi
chown -R "$USER_NAME:$USER_NAME" "$APP_DIR"
find "$APP_DIR" -type d -exec chmod 0755 {} +
find "$APP_DIR" -type f -exec chmod 0644 {} +
echo "  code in $APP_DIR, pricing in $CONF_DIR"

# ------------------------------------------------------------------ config
if [[ ! -f $CONF_DIR/config.json ]]; then
    cat > "$CONF_DIR/config.json" <<'JSON'
{
  "port": 8770,
  "model": "claude-haiku-4-5",
  "monthly_budget_usd": 5.00,
  "rate_per_minute": 10,
  "rate_per_day": 300,
  "max_text_chars": 600,
  "log_prompts": false
}
JSON
    chown "$USER_NAME:$USER_NAME" "$CONF_DIR/config.json"
    chmod 0600 "$CONF_DIR/config.json"
    echo "  wrote a default config.json"
fi

# The key file is created empty and only readable by the broker. Poom pastes
# the key in; this script never sees it and neither does the journal.
if [[ ! -f $CONF_DIR/env ]]; then
    printf 'ANTHROPIC_API_KEY=\n' > "$CONF_DIR/env"
    chown "$USER_NAME:$USER_NAME" "$CONF_DIR/env"
    chmod 0600 "$CONF_DIR/env"
    echo "  created an EMPTY $CONF_DIR/env — put the key in it before starting"
fi

# ------------------------------------------------------------------ systemd
say "systemd unit"
install -o root -g root -m 0644 "$REPO_SERVER_DIR/install/kiosk-broker.service" \
    /etc/systemd/system/kiosk-broker.service
systemctl daemon-reload
systemctl enable kiosk-broker.service >/dev/null
echo "  installed and enabled (not started — the key has to go in first)"

# -------------------------------------------------------------------- nginx
say "nginx site file"
install -o root -g root -m 0644 "$REPO_SERVER_DIR/install/nginx-kiosk.conf" \
    /etc/nginx/sites-available/kiosk
echo "  written to /etc/nginx/sites-available/kiosk"

# First install: the file is written but not enabled, because the certificate it
# references does not exist yet and `nginx -t` would fail on it. Every later run:
# the site is already live, the file just changed, and a change nobody reloads is
# a change that does nothing — which is how /v1/stt spent a release being
# refused with 413 by a config that had already been fixed in git.
if [[ -L /etc/nginx/sites-enabled/kiosk ]]; then
    echo "  site is enabled; testing the configuration before reloading"
    if ! nginx -t; then
        echo >&2
        echo "  nginx -t FAILED. NOT reloading." >&2
        echo "  The running configuration is untouched and all three sites are still up." >&2
        echo "  Fix /etc/nginx/sites-available/kiosk, then: sudo nginx -t && sudo systemctl reload nginx" >&2
        exit 1
    fi
    systemctl reload nginx
    echo "  nginx reloaded"
else
    echo "  NOT enabled yet — the symlink and the first reload come after the"
    echo "  certificate exists. See INSTALL.md."
fi

# -------------------------------------------------------- certificate hook
say "certbot deploy hook"
# certbot runs everything in this directory once per renewed certificate. The
# hook guards on RENEWED_LINEAGE and no-ops for every lineage but ours, so
# thaitrack's and monthreport's renewals are untouched by it — they were issued
# with --nginx and reload themselves.
install -d -o root -g root -m 0755 /etc/letsencrypt/renewal-hooks/deploy
install -o root -g root -m 0755 "$REPO_SERVER_DIR/install/kiosk-reload-nginx" \
    /etc/letsencrypt/renewal-hooks/deploy/kiosk-reload-nginx
echo "  installed /etc/letsencrypt/renewal-hooks/deploy/kiosk-reload-nginx"
echo "  (reloads nginx only after OUR certificate renews, and only if nginx -t passes)"

# ----------------------------------------------------------------- recheck
say "Checking the existing sites again"
after_thaitrack=$(curl -s -o /dev/null -w '%{http_code}' -k -H 'Host: xn--l3cgts1b3bzcvf.com' https://127.0.0.1/ || echo 000)
after_monthreport=$(curl -s -o /dev/null -w '%{http_code}' -k -H 'Host: ubet89.house' https://127.0.0.1/ || echo 000)
echo "  thaitrack=$after_thaitrack (was $before_thaitrack)"
echo "  monthreport=$after_monthreport (was $before_monthreport)"
[[ $after_thaitrack == "$before_thaitrack" && $after_monthreport == "$before_monthreport" ]] \
    || { echo "  A site changed behaviour. Stop and investigate." >&2; exit 1; }

cat <<'NEXT'

Done with the parts that need root. Still to do, in order:

  1. Put the API keys in /home/kioskbroker/.config/kiosk-broker/env
     (ANTHROPIC_API_KEY, and for phase 3 also GROQ_API_KEY and GOOGLE_TTS_API_KEY)
  2. Add the DNS A record for kiosk.xn--l3cgts1b3bzcvf.com
  3. Issue the certificate (certbot certonly --webroot)
  4. Enable the nginx site, nginx -t, reload
  5. Issue a device token and test from outside

On a machine where the site is ALREADY enabled, this script has just tested and
reloaded nginx for you — steps 2 to 4 are only for a first install.

The certbot deploy hook is already in place, so renewals will reload nginx by
themselves. Test it with:

  sudo certbot renew --dry-run --run-deploy-hooks

(--dry-run alone does NOT run deploy hooks.)

INSTALL.md has the exact commands for each.
NEXT
