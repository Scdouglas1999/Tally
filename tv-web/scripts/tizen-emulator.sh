#!/usr/bin/env bash
# The Samsung TV emulator (Tizen Studio TV Extension, VM "tally-tv": 1080p, 1 GB) without a window: it runs on a
# private X display (Xvfb :99) so it never covers the desktop.
#   scripts/tizen-emulator.sh start     # boots the VM, waits until sdb sees it, prints its serial
#   scripts/tizen-emulator.sh shot out.png
#   scripts/tizen-emulator.sh stop
# Needs ≥ 7 GB free on this host (the VM takes 1 GB plus QEMU and the display).
set -euo pipefail
STUDIO="${TIZEN_STUDIO:-$HOME/tools/tizen-studio}"
EM="$STUDIO/tools/emulator/bin/em-cli"
SDB="$STUDIO/tools/sdb"
VM="${TALLY_TIZEN_VM:-tally-tv}"
DISPLAY_NUM="${TALLY_TIZEN_DISPLAY:-99}"
RUN="${XDG_RUNTIME_DIR:-/tmp}/tally-tizen-emulator"
mkdir -p "$RUN"

case "${1:-}" in
  start)
    avail="$(free -g | awk '/Mem:/{print $7}')"
    (( avail >= 7 )) || { echo "only ${avail} GB available; the emulator needs 7" >&2; exit 1; }
    if [[ ! -f "$RUN/xvfb.pid" ]] || ! kill -0 "$(cat "$RUN/xvfb.pid")" 2>/dev/null; then
      Xvfb ":$DISPLAY_NUM" -screen 0 1920x1080x24 -nolisten tcp >"$RUN/xvfb.log" 2>&1 &
      echo $! >"$RUN/xvfb.pid"
      sleep 1
    fi
    # GL stays on: with it off the TV image's tuner decoder fails ("winsys interface 'vigs_wsi' not found") and the VM
    # exits at once (seen on this host). Under Xvfb the GL is Mesa's software renderer.
    "$EM" modify -n "$VM" -g yes >/dev/null
    DISPLAY=":$DISPLAY_NUM" "$EM" launch -n "$VM" >"$RUN/emulator.log" 2>&1 &
    echo $! >"$RUN/emulator.pid"
    for _ in $(seq 1 90); do
      serial="$("$SDB" devices | awk '/emulator-/{print $1; exit}')"
      if [[ -n "$serial" ]] && "$SDB" -s "$serial" shell echo ok 2>/dev/null | grep -q ok; then
        echo "$serial"
        exit 0
      fi
      sleep 5
    done
    echo "the emulator did not come up; see $RUN/emulator.log" >&2
    exit 1
    ;;
  shot)
    DISPLAY=":$DISPLAY_NUM" import -window root "${2:-emulator.png}" 2>/dev/null \
      || python3 -c "import sys; from PIL import ImageGrab; ImageGrab.grab(xdisplay=':$DISPLAY_NUM').save(sys.argv[1])" "${2:-emulator.png}"
    ;;
  stop)
    pkill -f "emulator-x86_64.*$VM" 2>/dev/null || true
    [[ -f "$RUN/emulator.pid" ]] && kill "$(cat "$RUN/emulator.pid")" 2>/dev/null || true
    [[ -f "$RUN/xvfb.pid" ]] && kill "$(cat "$RUN/xvfb.pid")" 2>/dev/null || true
    rm -f "$RUN"/*.pid
    ;;
  *)
    sed -n '2,8p' "$0"
    exit 2
    ;;
esac
