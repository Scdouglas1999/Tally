#!/bin/bash
# Runs one ladder scenario on both players at once: the Tally app on the TV emulator (tv-watch.sh) and Jellyfin's
# web client (web-watch.mjs) watch the same channel while the proxy in front of source A is steered on a schedule.
#   ANDROID_SERIAL=emulator-5556 ./scenario.sh <out dir> <tv device id> <channel> <seconds> [<t>:<mode>[:<kbps>] ...]
# e.g. ./scenario.sh /tmp/slow aeb1c36e768e47ca "Bed Game" 360 0:down:0:C 40:slow:4000 100:normal
# (events are <t>:<mode>[:<kbps>[:<source A|C|J|K>]]; every source starts "normal")
# e.g. ./scenario.sh /tmp/burst aeb1c36e768e47ca "Bed Burst" 180 0:burst:0:J    (J publishes in bursts from the start)
# Times are seconds after the TV's play command. Needs WEB_USER / WEB_PASSWORD for the web client.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=$1; DEVICE=$2; CHANNEL=$3; SECS=$4; shift 4
mkdir -p "$OUT"; rm -f "$OUT"/tv.command "$OUT"/web.jsonl
for src in A C J K; do "$HERE/ctl.sh" normal 4000 $src >/dev/null; done
"$HERE/tv-watch.sh" "$DEVICE" "$CHANNEL" "$SECS" "$OUT/tv" > "$OUT/tv.result" 2>&1 &
TV=$!
until [ -s "$OUT/tv.command" ]; do sleep 0.2; kill -0 $TV 2>/dev/null || { cat "$OUT/tv.result"; exit 1; }; done
T0=$(cat "$OUT/tv.command")
node "$HERE/web-watch.mjs" --user "$WEB_USER" --password "$WEB_PASSWORD" --channel "$CHANNEL" --seconds $((SECS - 10)) --events "$OUT/web.jsonl" > /dev/null 2>&1 &
WEB=$!
for ev in "$@"; do
  IFS=: read -r at mode kbps src <<< "$ev"; kbps=${kbps:-4000}; src=${src:-A}
  now=$(date +%s.%N); wait_s=$(python3 -c "print(max(0, $T0 + $at - $now))"); sleep "$wait_s"
  echo "{\"t\": $(date +%s.%N), \"ctl\": \"$mode\", \"kbps\": \"$kbps\", \"src\": \"$src\"}" >> "$OUT/ctl.jsonl"; "$HERE/ctl.sh" "$mode" "$kbps" "$src" >/dev/null
done
wait $WEB || true; wait $TV || true
echo "TV:  $(tail -1 "$OUT/tv.result")"
echo "web: $(tail -1 "$OUT/web.jsonl")"
