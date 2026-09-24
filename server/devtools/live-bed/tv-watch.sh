#!/bin/bash
# Plays a Live TV channel in the Tally app on an Android TV emulator and records the screen, then reports time to
# first frame and every freeze (a frozen picture > 0.5 s: a stall/spinner) from the recording.
#   ANDROID_SERIAL=emulator-5556 ./tv-watch.sh <app device id> <channel name> <seconds> <out prefix>
# The app's session is found by its Jellyfin device id — take it from the Devices table for the app's access token
# (debug builds: run-as <pkg> cat shared_prefs/<pkg>_server.xml), never from "the most recent session": other
# emulators may be signed in to the same server.
# Holds the emulator's lock for the whole run; the emulator is shared with other workers.
set -euo pipefail
DEVICE=$1; CHANNEL=$2; SECS=$3; OUT=$4
SERVER=${SERVER:-http://127.0.0.1:18200}
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=${PKG:-io.github.scoduglas1999.jellytv.debug}
TOK=${ADMIN_TOKEN:?set ADMIN_TOKEN to an API key of the test server}
# one lock file per emulator, shared with any other script that drives it
case "${ANDROID_SERIAL:-emulator-5554}" in
  emulator-5554) LOCK=/tmp/tally-emu.lock ;;
  *) LOCK="/tmp/tally-emu-${ANDROID_SERIAL}.lock" ;;
esac
(
flock -w 600 9 || { echo "emulator busy" >&2; exit 1; }
$ADB shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; sleep 8
SESSION=$(curl -s -H "X-Emby-Token: $TOK" "$SERVER/Sessions" | python3 -c "
import sys,json; ss=[s for s in json.load(sys.stdin) if s.get('DeviceId')==sys.argv[1]]; print(ss[0]['Id'] if ss else '')" "$DEVICE")
[ -n "$SESSION" ] || { echo "no session for device $DEVICE" >&2; exit 4; }
ITEM=$(curl -s -H "X-Emby-Token: $TOK" "$SERVER/LiveTv/Channels?Limit=500" | python3 -c "
import sys,json; print([c['Id'] for c in json.load(sys.stdin)['Items'] if c['Name']==sys.argv[1]][0])" "$CHANNEL")
# screenrecord stops at 180 s: longer runs are recorded as consecutive clips (a fraction of a second is lost between).
REC_T0=$(date +%s.%N)
CLIPS=()
record() { # clip index, seconds
  $ADB shell rm -f /sdcard/ladder$1.mp4
  echo "$(date +%s.%N)" > "$OUT.clip$1.start"; echo "$2" > "$OUT.clip$1.len"
  $ADB shell screenrecord --size 960x540 --bit-rate 3000000 --time-limit "$2" /sdcard/ladder$1.mp4
}
LEFT=$SECS; N=0
( sleep 1; date +%s.%N > "$OUT.command.tmp"; curl -s -X POST -H "X-Emby-Token: $TOK" "$SERVER/Sessions/$SESSION/Playing?playCommand=PlayNow&itemIds=$ITEM"; mv "$OUT.command.tmp" "$OUT.command" ) &
while [ "$LEFT" -gt 0 ]; do
  T=$(( LEFT > 175 ? 175 : LEFT )); record $N $T || true; CLIPS+=($N); LEFT=$(( LEFT - T )); N=$((N + 1))
done
wait
for i in "${CLIPS[@]}"; do $ADB pull /sdcard/ladder$i.mp4 "$OUT.clip$i.mp4" >/dev/null; done
echo "{\"command\": $(cat "$OUT.command"), \"clips\": [$(for i in "${CLIPS[@]}"; do printf '%s{"file": "%s", "start": %s, "seconds": %s}' "$([ $i -gt 0 ] && echo ,)" "$OUT.clip$i.mp4" "$(cat "$OUT.clip$i.start")" "$(cat "$OUT.clip$i.len")"; done)], \"session\": \"$SESSION\", \"item\": \"$ITEM\"}" > "$OUT.json"
[ "${KEEP_PLAYING:-0}" = 1 ] || { curl -s -X POST -H "X-Emby-Token: $TOK" "$SERVER/Sessions/$SESSION/Playing/Stop" || true; $ADB shell input keyevent 4 || true; }
) 9>"$LOCK"
# First frame: the first recorded frame whose picture is mostly the colorful test pattern; freezes via freezedetect.
python3 "$(dirname "$0")/analyze-tv.py" "$OUT"
