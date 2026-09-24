#!/bin/bash
# Runs score-sim.py in its own container on Docker's default bridge (next to the dev server, which cannot reach
# ports on this host), then points the dev server's plugin at it. Control it from the host with
#   docker exec tally-score-sim python /sim.py bump NYY
# Stop with: docker rm -f tally-score-sim && python3 score-sim.py point ""
# Needs TALLY_DEV_TOKEN (an API key of the dev server, or a file holding one) to point the plugin.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
docker rm -f tally-score-sim >/dev/null 2>&1 || true
docker run -d --name tally-score-sim -v "$HERE/score-sim.py:/sim.py:ro" python:3-alpine python -u /sim.py serve >/dev/null
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' tally-score-sim)
python3 "$HERE/score-sim.py" point "http://$IP:8765"
echo "simulator at http://$IP:8765 (container tally-score-sim)"
