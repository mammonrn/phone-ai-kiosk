#!/usr/bin/env bash
# The STT comparison on the A07: every clip through each transcriber, through the
# broker's real /v1/stt (the debug build's TEST_STT_FILE), into RESULTS.tsv for
# tools/voice/score.py. Runs on the PC (Git Bash) with the phone on adb and the
# DEBUG APK from CI installed. Nothing here is sent anywhere but the broker.
#
#   tools/voice/run_stt_clips.sh CLIPS_DIR RESULTS.tsv [provider ...]
#       providers default: groq-hints qwen   (any of groq groq-hints google qwen)
#   GAP=3.5  seconds between requests (Groq's free plan allows 20 a minute)
#   KEEP=1   leave the clips on the phone afterwards (default: deleted one by one)
#
# Resumable: a file+provider already in RESULTS.tsv is skipped, so stop it with
# Ctrl-C and run the same line again to go on.
#
# WHAT EACH ROW IS
#   transcript  what the broker sent back (files/stt_probe.txt, read with run-as;
#               never in the log). EMPTY with a gate reason = the broker's speech
#               gate refused it (TEST_STT_FILE sends X-Wake "", so the gate treats
#               the clip as an older phone's turn: no wake score, Groq's own doubts
#               count). "ERROR <kind>" = the request failed.
#   ms          from just before the broadcast to the phone's "stt probe" log line,
#               both on the phone's clock when its `date` gives nanoseconds, else
#               on the PC's. Includes starting `am` (a few hundred ms, the same
#               for every provider) — compare providers with it, do not quote it
#               as the transcriber's own speed (the broker's journal has that:
#               `stt ok ... provider=... ms=`).
#   gate        empty when the transcript passed the gate, else the reason.
#
# QWEN SECONDS: with provider qwen every clip costs its length (rounded up to a
# second). With groq-hints, a clip whose transcript has a map word ALSO goes to
# Qwen (maps rescue, 0.51.0) and that transcript may be Qwen's — so the map rows
# under groq-hints are the production pipeline, not Groq alone.
set -u

if [ $# -lt 2 ]; then
  sed -n '2,12p' "$0"
  exit 2
fi
clips_dir=$1
results=$2
shift 2
if [ $# -gt 0 ]; then providers=("$@"); else providers=(groq-hints qwen); fi

PKG=com.mammonrn.phoneaikiosk.debug
ACT=com.mammonrn.phoneaikiosk
RECEIVER="$PKG/com.mammonrn.phoneaikiosk.TestTriggerReceiver"
REMOTE=/sdcard/Download/stt4
GAP=${GAP:-3.5}

adb get-state >/dev/null 2>&1 || { echo "no phone on adb"; exit 1; }
[ -s "$results" ] || printf 'file\tprovider\ttranscript\tms\tgate\n' > "$results"

shopt -s nullglob
files=("$clips_dir"/*.wav)
[ ${#files[@]} -gt 0 ] || { echo "no .wav in $clips_dir"; exit 1; }

echo "pushing ${#files[@]} clips to $REMOTE"
adb shell mkdir -p "$REMOTE"
adb push "${files[@]}" "$REMOTE/" >/dev/null || { echo "push failed"; exit 1; }

set_provider() {
  adb shell am broadcast -a "$ACT.TEST_STT_PROVIDER" -n "$RECEIVER" --es value "$1" >/dev/null
}

one() {  # file-name provider -> appends one row
  local name=$1 provider=$2 t0 now line t1 ms gate text i
  set_provider "$provider"
  adb logcat -c
  t0=$(adb shell "date +%s.%N; am broadcast -a $ACT.TEST_STT_FILE -n $RECEIVER --es path $REMOTE/$name >/dev/null" | head -1 | tr -d '\r')
  local pc0; pc0=$(date +%s.%N)
  line=""
  for i in $(seq 1 150); do
    line=$(adb logcat -d -v epoch -s KioskStats:I | grep -m1 "stt probe" | tr -d '\r')
    [ -n "$line" ] && break
    sleep 0.2
  done
  now=$(date +%s.%N)
  if [ -z "$line" ]; then
    text="ERROR timeout"; ms=""; gate=""
  else
    t1=$(echo "$line" | awk '{print $1}')
    if [[ "$t0" =~ ^[0-9]+\.[0-9]+$ ]] && [[ "$t1" =~ ^[0-9]+\.[0-9]+$ ]]; then
      ms=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%d", (b-a)*1000}')
    else
      ms=$(awk -v a="$pc0" -v b="$now" 'BEGIN{printf "%d", (b-a)*1000}')
    fi
    gate=$(echo "$line" | sed -n 's/.*gate=//p')
    if echo "$line" | grep -q "stt probe failed"; then
      text="ERROR $(echo "$line" | sed -n 's/.*stt probe failed //p')"; ms=""
    else
      text=$(adb exec-out run-as "$PKG" cat files/stt_probe.txt | tr '\t\r\n' '   ')
    fi
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$provider" "$text" "$ms" "$gate" >> "$results"
  echo "$name $provider ms=${ms:-?} gate=${gate:--} chars=${#text}"
}

index=0
for path in "${files[@]}"; do
  name=$(basename "$path")
  # Alternate which transcriber goes first, so neither always meets a cold line.
  if [ $((index % 2)) -eq 0 ]; then order=("${providers[@]}"); else
    order=(); for ((j=${#providers[@]}-1; j>=0; j--)); do order+=("${providers[$j]}"); done; fi
  for provider in "${order[@]}"; do
    if grep -q "^$name	$provider	" "$results"; then continue; fi
    one "$name" "$provider"
    sleep "$GAP"
  done
  index=$((index + 1))
done

set_provider default
adb shell run-as "$PKG" rm -f files/stt_probe.txt
if [ "${KEEP:-0}" != "1" ]; then
  for path in "${files[@]}"; do adb shell rm "$REMOTE/$(basename "$path")"; done
  adb shell rmdir "$REMOTE" 2>/dev/null
fi
echo "done: $(($(wc -l < "$results") - 1)) rows in $results"
