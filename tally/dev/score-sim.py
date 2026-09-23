#!/usr/bin/env python3
"""A stand-in for ESPN's scoreboard feed, for testing Tally's live-score UI without waiting for a real game to score.

It captures ESPN's real scoreboard for each league once (the exact payload the plugin normally parses), serves it in
the same URL shape as ESPN (`/apis/site/v2/sports/<sport>/<league>/scoreboard`), and applies score changes on
command the way ESPN reports a real one: the team's `score` goes up, the current period's `linescores` entry goes up,
and `situation.lastPlay` becomes the scoring play (`text`, `scoreValue`, a new `id`). Everything else in the payload is
the captured real data, so the change travels the normal path: plugin parser -> Games board -> app polling.

Point the dev server's plugin at it (development only; the setting is not in the settings page):
  score-sim.py serve                          # serves on 0.0.0.0:8765, captures on first request
  score-sim.py point http://<sim address>:8765 # sets the plugin's ScoreboardSourceOverride on the dev server
The dev server runs in Docker and this host's firewall blocks containers from reaching host ports, so run the
simulator in a container on the same bridge (dev/score-sim-docker.sh does that and points the plugin at it).
Then, from any shell:
  score-sim.py list                           # games in the captured boards with their scores
  score-sim.py bump NYY                       # the Yankees score 1 (baseball); --points 7 for a touchdown
  score-sim.py reset                          # back to the captured payload
  score-sim.py point ""                       # back to ESPN
The plugin re-reads a live league every 12 s and the app polls its board, so a bump shows within ~15-20 s.
"""
import argparse
import copy
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ESPN = "https://site.web.api.espn.com/apis/site/v2/sports/{league}/scoreboard"
DEV_SERVER = os.environ.get("TALLY_DEV_SERVER", "http://127.0.0.1:18200")
TOKEN_FILE = os.environ.get(
    "TALLY_DEV_TOKEN",
    "/tmp/claude-1000/-home-scdouglas-Documents-JellyTV/cf6e86e0-f8fc-46b2-893a-05c6a53b7f24/scratchpad/dev-server.token",
)
PLUGIN_ID = "JellyTV"  # the plugin's page key; its configuration is found by name below

captured = {}  # league -> payload as ESPN sent it
state = {}  # league -> payload with bumps applied
lock = threading.Lock()


def fetch_espn(league):
    request = urllib.request.Request(ESPN.format(league=league), headers={"User-Agent": "Tally-dev/score-sim"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def board(league):
    with lock:
        if league not in captured:
            captured[league] = fetch_espn(league)
            state[league] = copy.deepcopy(captured[league])
        return state[league]


def competitors(event):
    return event.get("competitions", [{}])[0].get("competitors", [])


def bump(abbr, points, league=None):
    """Applies a scoring play for team [abbr] in the first matching event. Returns a description."""
    with lock:
        leagues = [league] if league else list(state)
        for lg in leagues:
            events = state.get(lg, {}).get("events", [])
            # a doubleheader lists the same team twice: prefer the game in progress, then one about to start
            rank = {"in": 0, "pre": 1}
            for event in sorted(events, key=lambda e: rank.get(e.get("status", {}).get("type", {}).get("state"), 2)):
                for team in competitors(event):
                    if team.get("team", {}).get("abbreviation", "").upper() != abbr.upper():
                        continue
                    score = int(team.get("score") or 0) + points
                    team["score"] = str(score)
                    lines = team.setdefault("linescores", [])
                    if not lines:
                        lines.append({"value": 0.0, "displayValue": "0", "period": 1})
                    lines[-1]["value"] = float(lines[-1].get("value", 0)) + points
                    lines[-1]["displayValue"] = str(int(lines[-1]["value"]))
                    competition = event["competitions"][0]
                    situation = competition.setdefault("situation", {})
                    play = copy.deepcopy(situation.get("lastPlay") or {})
                    name = team.get("team", {}).get("shortDisplayName") or abbr
                    unit = "run" if lg.startswith("baseball") else "point"
                    play.update(
                        {
                            "id": str(int(time.time() * 1000)),
                            "text": f"{name} score {points} {unit}{'' if points == 1 else 's'}.",
                            "scoreValue": points,
                            "team": {"id": team.get("id")},
                        }
                    )
                    play.setdefault("type", {"text": "Play Result"})
                    situation["lastPlay"] = play
                    names = " at ".join(c.get("team", {}).get("abbreviation", "?") for c in reversed(competitors(event)))
                    return f"{lg}: {names}: {abbr.upper()} now {score}"
    return None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("score-sim: " + fmt % args + "\n")

    def send_json(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        prefix, suffix = "/apis/site/v2/sports/", "/scoreboard"
        if url.path.startswith(prefix) and url.path.endswith(suffix):
            league = url.path[len(prefix) : -len(suffix)]
            try:
                self.send_json(200, board(league))
            except Exception as error:  # ESPN unreachable on first capture
                self.send_json(502, {"error": str(error)})
        elif url.path == "/sim/list":
            with lock:
                games = [
                    {
                        "league": lg,
                        "status": e.get("status", {}).get("type", {}).get("state"),
                        "teams": {c["team"]["abbreviation"]: c.get("score") for c in competitors(e)},
                    }
                    for lg, payload in state.items()
                    for e in payload.get("events", [])
                ]
            self.send_json(200, games)
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)
        if url.path == "/sim/bump":
            result = bump(query["team"][0], int(query.get("points", ["1"])[0]), query.get("league", [None])[0])
            self.send_json(200 if result else 404, {"result": result or "no such team in the captured boards"})
        elif url.path == "/sim/reset":
            with lock:
                for lg in captured:
                    state[lg] = copy.deepcopy(captured[lg])
            self.send_json(200, {"result": "reset"})
        else:
            self.send_json(404, {"error": "not found"})


def control(port, method, path):
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return json.load(error)


def point(base):
    """Sets (or clears, with "") the plugin's ScoreboardSourceOverride on the dev server."""
    token = open(TOKEN_FILE).read().strip()
    headers = {"Authorization": f'MediaBrowser Token="{token}"', "Content-Type": "application/json"}

    def call(method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(DEV_SERVER + path, data=data, method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    plugin = next(p for p in call("GET", "/Plugins") if p["Name"] in ("Tally", "JellyTV"))
    config = call("GET", f"/Plugins/{plugin['Id']}/Configuration")
    config["ScoreboardSourceOverride"] = base
    call("POST", f"/Plugins/{plugin['Id']}/Configuration", config)
    print(f"{plugin['Name']} scoreboard source: {base or 'ESPN'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8765)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("serve")
    sub.add_parser("list")
    sub.add_parser("reset")
    bump_parser = sub.add_parser("bump")
    bump_parser.add_argument("team", help="team abbreviation, e.g. NYY")
    bump_parser.add_argument("--points", type=int, default=1)
    bump_parser.add_argument("--league", help="e.g. baseball/mlb (default: any captured league)")
    point_parser = sub.add_parser("point")
    point_parser.add_argument("base", help='simulator base URL as the server sees it, or "" for ESPN')
    args = parser.parse_args()

    if args.command == "serve":
        print(f"score-sim serving on 0.0.0.0:{args.port}", flush=True)
        ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
    elif args.command == "list":
        for game in control(args.port, "GET", "/sim/list"):
            print(game)
    elif args.command == "reset":
        print(control(args.port, "POST", "/sim/reset")["result"])
    elif args.command == "bump":
        query = urllib.parse.urlencode(
            {k: v for k, v in {"team": args.team, "points": args.points, "league": args.league}.items() if v}
        )
        print(control(args.port, "POST", f"/sim/bump?{query}")["result"])
    elif args.command == "point":
        point(args.base)


if __name__ == "__main__":
    main()
