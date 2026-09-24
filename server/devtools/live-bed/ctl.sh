#!/bin/bash
# Steers the proxy in front of sources A, C, J and K: ctl.sh [mode] [kbps] [src]
#   modes: normal | slow <kbps> | stall | fail | down | burst [pauses]      src: A (default), C, J or K
#   burst: the playlist releases new segments only after each pause (seconds, cycled; default 8,12,15,6,11,14),
#          e.g. ctl.sh burst 7,8.5,6,8.5 A
#   ctl.sh game <name> [on|off]   list or unlist a fictional game's channels in /games.m3u (serve.py GAMES)
set -euo pipefail
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${BED_NAME:-tally-live-bed}")
mode=${1:-}; kbps=${2:-4000}; src=${3:-A}
if [ "$mode" = game ]; then curl -s "http://$IP:8081/ctl?game=$2&on=$([ "${3:-on}" = off ] && echo 0 || echo 1)"; echo; exit 0; fi
if [ -z "$mode" ]; then curl -s "http://$IP:8081/ctl"
elif [ "$mode" = burst ] && [[ "$kbps" == *[,.]* ]]; then curl -s "http://$IP:8081/ctl?mode=burst&pauses=$kbps&src=$src"
else curl -s "http://$IP:8081/ctl?mode=$mode&kbps=$kbps&src=$src"; fi
echo
