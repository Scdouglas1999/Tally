#!/bin/bash
# Steers the proxy in front of sources A and C: ctl.sh [mode] [kbps] [src]
#   modes: normal | slow <kbps> | stall | fail | down        src: A (default) or C
set -euo pipefail
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' tally-live-bed)
mode=${1:-}; kbps=${2:-4000}; src=${3:-A}
if [ -z "$mode" ]; then curl -s "http://$IP:8081/ctl"; else curl -s "http://$IP:8081/ctl?mode=$mode&kbps=$kbps&src=$src"; fi
echo
