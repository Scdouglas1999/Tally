#!/bin/bash
# Steers the proxy in front of sources A and C: ctl.sh [mode] [kbps] [src]
#   modes: normal | slow <kbps> | stall | fail | down        src: A (default) or C
#   ctl.sh game <name> [on|off]   list or unlist a fictional game's channels in /games.m3u (serve.py GAMES)
set -euo pipefail
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${BED_NAME:-tally-live-bed}")
mode=${1:-}; kbps=${2:-4000}; src=${3:-A}
if [ "$mode" = game ]; then curl -s "http://$IP:8081/ctl?game=$2&on=$([ "${3:-on}" = off ] && echo 0 || echo 1)"; echo; exit 0; fi
if [ -z "$mode" ]; then curl -s "http://$IP:8081/ctl"; else curl -s "http://$IP:8081/ctl?mode=$mode&kbps=$kbps&src=$src"; fi
echo
