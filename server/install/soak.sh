#!/usr/bin/env bash
# Phase 6 soak test, one command each (run as root, from the repo):
#
#   sudo bash server/install/soak.sh start    # begin: VPS sampler every 15 min + keep the phone's samples
#   sudo bash server/install/soak.sh report   # any time, mid-way or after
#   sudo bash server/install/soak.sh stop     # end: sampler removed, nothing deleted
#
# The VPS sampler writes one CSV line per 15 minutes to $CSV: numbers only
# (restarts, memory, warning COUNT, sizes). It never copies a log line.
# It is a oneshot: it runs for well under a second and is gone.
set -euo pipefail

CSV_DIR=/var/lib/kiosk-soak
CSV=$CSV_DIR/vps.csv
BIN=/usr/local/sbin/kiosk-soak
UNIT=kiosk-soak
DB=/home/kioskbroker/.config/kiosk-broker/broker.db
NGINX_LOG=/var/log/nginx/kiosk-timing.log
KB="sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker PYTHONPATH=/home/kioskbroker/app /home/kioskbroker/venv/bin/python -m kiosk_broker"

[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }

size_kb() { if [[ -e $1 ]]; then echo $(( $(stat -c %s "$1") / 1024 )); else echo 0; fi; }

sample() {
    install -d -m 0755 "$CSV_DIR"
    if [[ ! -s $CSV ]]; then
        echo "time,broker_active,broker_auto_restarts,broker_mem_mb,warnings_15m,requests_15m,db_kb,db_wal_kb,journal_mb,nginx_log_kb,load1,disk_used_pct" > "$CSV"
    fi
    local active restarts mem warnings requests journal load disk
    active=$(systemctl is-active kiosk-broker || true)
    restarts=$(systemctl show kiosk-broker -p NRestarts --value)
    mem=$(systemctl show kiosk-broker -p MemoryCurrent --value)
    [[ $mem =~ ^[0-9]+$ ]] && mem=$(( mem / 1048576 )) || mem=
    warnings=$(journalctl -u kiosk-broker -p warning --since "-15min" -q -o cat 2>/dev/null | wc -l)
    # Read-only, and a count only: no row of the database is copied.
    requests=$(python3 - "$DB" <<'PY' 2>/dev/null || echo
import sqlite3, sys, time
c = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True, timeout=5)
print(c.execute("SELECT COUNT(*) FROM requests WHERE ts > ?", (time.time() - 900,)).fetchone()[0])
PY
)
    journal=$(journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]' | head -1 | numfmt --from=iec --to-unit=1048576 2>/dev/null || echo)
    load=$(cut -d' ' -f1 /proc/loadavg)
    disk=$(df --output=pcent / | tail -1 | tr -dc '0-9')
    echo "$(date '+%Y-%m-%d %H:%M'),$active,$restarts,$mem,$warnings,$requests,$(size_kb "$DB"),$(size_kb "$DB-wal"),$journal,$(size_kb "$NGINX_LOG"),$load,$disk" >> "$CSV"
}

case "${1:-}" in
    start)
        install -m 0755 "$0" "$BIN"
        cat > /etc/systemd/system/$UNIT.service <<EOF
[Unit]
Description=kiosk soak test: one VPS sample (numbers only)

[Service]
Type=oneshot
ExecStart=$BIN sample
Nice=10
EOF
        cat > /etc/systemd/system/$UNIT.timer <<EOF
[Unit]
Description=kiosk soak test: sample the VPS every 15 minutes

[Timer]
OnActiveSec=10s
OnUnitActiveSec=15min
AccuracySec=30s

[Install]
WantedBy=timers.target
EOF
        if [[ -s $CSV ]]; then
            mv "$CSV" "$CSV_DIR/vps-$(date +%Y%m%d-%H%M).csv"
            echo "the previous soak's CSV was kept beside it"
        fi
        systemctl daemon-reload
        systemctl enable --now $UNIT.timer
        $KB soak-start
        echo "VPS samples: $CSV (every 15 min). Phone samples: in the broker, from its next 15-minute send."
        echo "Mid-way: sudo bash server/install/soak.sh report    End: sudo bash server/install/soak.sh stop"
        ;;
    sample)
        sample
        ;;
    stop)
        systemctl disable --now $UNIT.timer 2>/dev/null || true
        rm -f /etc/systemd/system/$UNIT.timer /etc/systemd/system/$UNIT.service "$BIN"
        systemctl daemon-reload
        $KB soak-stop
        echo "the VPS sampler is removed; $CSV and the phone samples are kept for the report"
        ;;
    report)
        $KB soak-report --vps-csv "$CSV"
        ;;
    *)
        echo "usage: sudo bash server/install/soak.sh start|report|stop" >&2
        exit 2
        ;;
esac
