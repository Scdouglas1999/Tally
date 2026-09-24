#!/bin/bash
# Starts the live bed in a small container on Docker's default bridge (the dev Jellyfin cannot reach ports on this
# host, but it can reach other containers). Prints the addresses to use.
#   ./run.sh [media dir]          start (media from make-media.sh, default ~/.cache/tally-live-bed)
#   ./run.sh stop                 stop and remove the container
#   ./ctl.sh slow 4000 | stall | fail | down | normal     steer the proxy in front of source A
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NAME=${BED_NAME:-tally-live-bed}   # BED_NAME: run a second bed next to another one
if [ "${1:-}" = stop ]; then docker rm -f $NAME >/dev/null 2>&1 || true; echo stopped; exit 0; fi
MEDIA="${1:-$HOME/.cache/tally-live-bed}"
[ -d "$MEDIA/A" ] || { echo "no media in $MEDIA - run make-media.sh first" >&2; exit 1; }
docker rm -f $NAME >/dev/null 2>&1 || true
docker run -d --name $NAME --memory 384m -v "$HERE/serve.py:/serve.py:ro" -v "$MEDIA:/media:ro" \
  python:3-alpine python -u /serve.py >/dev/null
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $NAME)
for _ in $(seq 20); do curl -sf "http://$IP:8080/bed.m3u" >/dev/null && break; sleep 0.5; done
echo "live bed at http://$IP:8080 (M3U: http://$IP:8080/bed.m3u, control: http://$IP:8081/ctl)"
