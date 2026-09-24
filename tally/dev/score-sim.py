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
                                              # (TALLY_DEV_SERVER, default http://127.0.0.1:18200; TALLY_DEV_TOKEN)
The dev server runs in Docker and this host's firewall blocks containers from reaching host ports, so run the
simulator in a container on the same bridge (dev/score-sim-docker.sh does that and points the plugin at it).
Then, from any shell:
  score-sim.py list                           # games in the captured boards with their scores
  score-sim.py bump NYY                       # the Yankees score 1 (baseball); --points 7 for a touchdown
  score-sim.py reset                          # back to the captured payload
  score-sim.py point ""                       # back to ESPN

Fictional games (for the DVR and anything else that needs a game to start and end on command). `serve --offline`
never contacts ESPN: every league starts empty and only has the games you add.
  score-sim.py add --id 900001 --away ROT:Riverton:Otters --home LKH:Lakeside:Herons --start +20
                                              # an MLB game 20 minutes from now (--league, --start ISO or +/-minutes)
  score-sim.py state 900001 in                # live; also: pre, post (final), postponed, canceled
  score-sim.py start 900001 -- -5             # move the listed start (ISO or +/-minutes from now)
  score-sim.py remove 900001
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
# `point` needs an API key of the dev server: TALLY_DEV_TOKEN is the key itself, or the path of a file holding it
# (Dashboard > API Keys on the dev server makes one).
DEV_TOKEN = os.environ.get("TALLY_DEV_TOKEN", "")
PLUGIN_ID = "JellyTV"  # the plugin's page key; its configuration is found by name below

OFFLINE = os.environ.get("SIM_OFFLINE") == "1"  # set by `serve --offline`: never fetch ESPN, leagues start empty
captured = {}  # league -> payload as ESPN sent it
state = {}  # league -> payload with bumps applied
lock = threading.Lock()


def fetch_espn(league):
    request = urllib.request.Request(ESPN.format(league=league), headers={"User-Agent": "Tally-dev/score-sim"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def empty_board(league):
    return {"leagues": [{"abbreviation": league.split("/")[-1].upper()}], "events": []}


def board(league):
    with lock:
        if league not in captured:
            captured[league] = empty_board(league) if OFFLINE else fetch_espn(league)
            state[league] = copy.deepcopy(captured[league])
        return state[league]


STATUS = {
    # state -> (ESPN state, status name, short detail, completed)
    "pre": ("pre", "STATUS_SCHEDULED", None, False),
    "in": ("in", "STATUS_IN_PROGRESS", "Top 1st", False),
    "post": ("post", "STATUS_FINAL", "Final", True),
    "postponed": ("post", "STATUS_POSTPONED", "Postponed", False),
    "canceled": ("post", "STATUS_CANCELED", "Canceled", False),
}


def when(value):
    """ISO time, or +/-minutes from now."""
    if value.startswith(("+", "-")) or value.lstrip("-").isdigit():
        return time.strftime("%Y-%m-%dT%H:%MZ", time.gmtime(time.time() + float(value) * 60))
    return value


def set_status(event, name):
    espn_state, status_name, detail, completed = STATUS[name]
    if detail is None:
        detail = event["date"]
    event["status"] = {
        "clock": 0, "displayClock": "0:00", "period": 1 if espn_state != "pre" else 0,
        "type": {"id": "1", "name": status_name, "state": espn_state, "completed": completed,
                 "description": detail, "detail": detail, "shortDetail": detail},
    }


def team(spec, home_away):
    abbr, location, name = (spec.split(":") + ["", ""])[:3]
    team_id = str(900000 + sum(ord(c) * (i + 1) for i, c in enumerate(abbr)))  # stable per team, like ESPN's ids
    return {
        "id": team_id, "homeAway": home_away, "score": "0",
        "team": {"id": team_id, "abbreviation": abbr, "location": location, "name": name,
                 "displayName": f"{location} {name}".strip(), "shortDisplayName": name or abbr,
                 "color": "1d4f91" if home_away == "home" else "b3282d", "alternateColor": "ffffff"},
    }


def add_game(league, event_id, away, home, start, status="pre", broadcast=None):
    payload = board(league)
    with lock:
        payload["events"] = [e for e in payload.get("events", []) if e.get("id") != event_id]
        away_c, home_c = team(away, "away"), team(home, "home")
        event = {
            "id": event_id, "date": when(start),
            "name": f'{away_c["team"]["displayName"]} at {home_c["team"]["displayName"]}',
            "shortName": f'{away_c["team"]["abbreviation"]} @ {home_c["team"]["abbreviation"]}',
            "competitions": [{"id": event_id, "competitors": [home_c, away_c],
                              "broadcasts": [{"market": "national", "names": [broadcast]}] if broadcast else []}],
        }
        set_status(event, status)
        payload["events"].append(event)
        return f'{league}: {event["name"]} ({event_id}) at {event["date"]}, {status}'


def find_event(event_id):
    for lg, payload in state.items():
        for e in payload.get("events", []):
            if e.get("id") == event_id:
                return lg, e
    return None, None


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
        elif url.path == "/sim/add":
            q = {k: v[0] for k, v in query.items()}
            try:
                result = add_game(q.get("league", "baseball/mlb"), q["id"], q["away"], q["home"], q.get("start", "+30"),
                                  q.get("state", "pre"), q.get("broadcast"))
                self.send_json(200, {"result": result})
            except (KeyError, ValueError) as error:
                self.send_json(400, {"error": f"bad request: {error}"})
        elif url.path in ("/sim/state", "/sim/start", "/sim/remove"):
            event_id = query.get("id", [""])[0]
            with lock:
                lg, event = find_event(event_id)
                if event is None:
                    self.send_json(404, {"result": "no such game"})
                    return
                if url.path == "/sim/state":
                    set_status(event, query["state"][0])
                    result = f'{event["name"]}: {event["status"]["type"]["name"]}'
                elif url.path == "/sim/start":
                    event["date"] = when(query["start"][0])
                    result = f'{event["name"]}: starts {event["date"]}'
                else:
                    state[lg]["events"].remove(event)
                    result = f'{event["name"]}: removed'
            self.send_json(200, {"result": result})
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
    if not DEV_TOKEN:
        sys.exit("set TALLY_DEV_TOKEN to an API key of the dev server (or the path of a file holding one)")
    token = open(DEV_TOKEN).read().strip() if os.path.isfile(DEV_TOKEN) else DEV_TOKEN.strip()
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
    serve_parser = sub.add_parser("serve")
    serve_parser.add_argument("--offline", action="store_true", help="never fetch ESPN; leagues hold only added games")
    add_parser = sub.add_parser("add")
    add_parser.add_argument("--id", required=True)
    add_parser.add_argument("--away", required=True, help="ABBR:Location:Name")
    add_parser.add_argument("--home", required=True, help="ABBR:Location:Name")
    add_parser.add_argument("--league", default="baseball/mlb")
    add_parser.add_argument("--start", default="+30", help="ISO time or +/-minutes from now")
    add_parser.add_argument("--state", default="pre", choices=list(STATUS))
    add_parser.add_argument("--broadcast")
    state_parser = sub.add_parser("state")
    state_parser.add_argument("id")
    state_parser.add_argument("state", choices=list(STATUS))
    start_parser = sub.add_parser("start")
    start_parser.add_argument("id")
    start_parser.add_argument("start", help="ISO time or +/-minutes from now")
    remove_parser = sub.add_parser("remove")
    remove_parser.add_argument("id")
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
        global OFFLINE
        OFFLINE = OFFLINE or args.offline
        print(f"score-sim serving on 0.0.0.0:{args.port}{' (offline)' if OFFLINE else ''}", flush=True)
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
    elif args.command == "add":
        query = urllib.parse.urlencode({k: v for k, v in {
            "id": args.id, "away": args.away, "home": args.home, "league": args.league, "start": args.start,
            "state": args.state, "broadcast": args.broadcast}.items() if v})
        print(control(args.port, "POST", f"/sim/add?{query}").get("result"))
    elif args.command == "state":
        print(control(args.port, "POST", f"/sim/state?{urllib.parse.urlencode({'id': args.id, 'state': args.state})}")["result"])
    elif args.command == "start":
        print(control(args.port, "POST", f"/sim/start?{urllib.parse.urlencode({'id': args.id, 'start': args.start})}")["result"])
    elif args.command == "remove":
        print(control(args.port, "POST", f"/sim/remove?{urllib.parse.urlencode({'id': args.id})}")["result"])
    elif args.command == "point":
        point(args.base)


if __name__ == "__main__":
    main()
