#!/usr/bin/env python3
"""A made-up listing site for trying the plugin's stream search on a dev server (no real site involved). It counts
every request it answers, so a run shows how much the plugin reads.

The front page lists 160 event links over five leagues. Most event pages have no stream yet; every tenth one carries
a player config with a stream. The wanted games are linked from the 121st link on (121st, 131st, 141st, ...), past the
96 pages a full-site scan reads, as /<league>/<away abbr>-<home abbr>/<id> with the link text "Watch" (the hardest
shape: only abbreviations name the game). Each game's page embeds a player (/embed/7700, 7710, ...) with a "Link 2"
button that switches to the next id (7701, 7711, ...); every player points at STREAM (a live HLS playlist the dev
server can play).

  stream-search-fixture.py serve --game ROT:LKH [--game DVL:SMT ...] [--league mlb] [--port 8000] [--stream URL]
                                 [--hidden] [--busy Riverton,Lakeside,...] [--spanish]
      (--away ROT --home LKH still works for a single game)
      --hidden   start with the wanted games unlisted
      --busy     a busy day: every fourth filler link names one of these cities in another sport ("Riverton Rockets
                 12 vs ..."), the way a listing names a wanted team's city many times over
      --spanish  each game's page also offers its Spanish feed: an "ESPN Deportes" button switching to the player
                 after Link 2 (/embed/7702, 7712, ...), which Tally should list as its own "… (Español)" channel
  curl -X POST http://<fixture>:8000/_ctl/list      # the front page lists the wanted games
  curl -X POST http://<fixture>:8000/_ctl/unlist    # it does not (a site that adds a game's link near game time)
  curl -X POST 'http://<fixture>:8000/_ctl/slow?s=3' # each page takes 3 s to answer (0: back to normal), to watch
                                                    # the board say "searching"
  curl -X POST 'http://<fixture>:8000/_ctl/fault?code=429&match=/embed/'
                                                    # answer requests whose path contains MATCH with CODE (the site
                                                    # pushing back); code=0 clears it
  curl http://<fixture>:8000/_ctl/log               # the pages the plugin read, in order, with times
  curl http://<fixture>:8000/_ctl/stats             # request counts: all, pages, playlists, listing reads, the most
                                                    # under way at once, and per minute
  curl -X POST http://<fixture>:8000/_ctl/reset     # zero the counts and the log

The dev server runs in Docker and this host's firewall blocks containers from reaching host ports, so run it in a
container on the same bridge:
  docker run -d --name tally-search-fixture -v "$PWD/stream-search-fixture.py:/site.py:ro" python:3-alpine \\
      python -u /site.py serve --game ROT:LKH --stream http://172.17.0.2/live.m3u8
and add http://<its address>:8000/ as a web page source.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LEAGUES = ["mlb", "nba", "nhl", "nfl", "soccer"]
FILLER_AWAY = ["Harbor City Gulls", "Pine Hollow Badgers", "Cedar Falls Comets", "North Ridge Foxes"]
FILLER_HOME = ["Stonebridge Rams", "Bayview Pelicans", "Elm Grove Owls", "Red Mesa Coyotes"]
BUSY_NICKNAMES = ["Rockets", "Mariners", "Lancers", "Stallions"]
WANTED_AT = 120
EVENT_BASE = 5000000
EMBED_BASE = 7700

opts = None
games = []  # (away, home) abbreviations
listed = True
slow = 0.0
fault = (0, "")
log = []
stats = {}
in_flight = 0
lock = threading.Lock()


def reset_stats():
    global stats
    stats = {"since": time.strftime("%H:%M:%S"), "requests": 0, "pages": 0, "playlists": 0, "listing": 0,
             "maxInFlight": 0, "faults": 0, "perMinute": {}}


def filler(i):
    away = f"{FILLER_AWAY[i % len(FILLER_AWAY)]} {i}"
    if opts.busy and i % 4 == 0:
        cities = opts.busy.split(",")
        away = f"{cities[(i // 4) % len(cities)].strip()} {BUSY_NICKNAMES[(i // 4) % len(BUSY_NICKNAMES)]} {i}"
    home = f"{FILLER_HOME[i % len(FILLER_HOME)]} {i}"
    slug = f"{away} {home}".lower().replace(" ", "-")
    return away, home, f"/{LEAGUES[i % len(LEAGUES)]}/{slug}/{EVENT_BASE + i}"


def wanted_slot(k):
    return WANTED_AT + 10 * k


def wanted_href(k):
    away, home = games[k]
    return f"/{opts.league}/{away.lower()}-{home.lower()}/{EVENT_BASE + wanted_slot(k)}"


def stream(tag):
    sep = "&" if "?" in opts.stream else "?"
    return f"{opts.stream}{sep}fixture={tag}"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, kind="text/html; charset=utf-8"):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        global listed, slow, fault
        url = urlparse(self.path)
        query = parse_qs(url.query)
        if url.path == "/_ctl/slow":
            slow = float(query.get("s", ["0"])[0] or 0)
            return self.send(200, json.dumps({"slow": slow}), "application/json")
        if url.path == "/_ctl/fault":
            fault = (int(query.get("code", ["0"])[0] or 0), query.get("match", [""])[0])
            return self.send(200, json.dumps({"code": fault[0], "match": fault[1]}), "application/json")
        if url.path == "/_ctl/reset":
            with lock:
                reset_stats()
                log.clear()
            return self.send(200, json.dumps(stats), "application/json")
        if url.path == "/_ctl/list":
            listed = True
        elif url.path == "/_ctl/unlist":
            listed = False
        else:
            return self.send(404, '{"error": "not found"}', "application/json")
        self.send(200, json.dumps({"listed": listed, "wanted": [wanted_href(k) for k in range(len(games))]}), "application/json")

    def do_GET(self):
        global in_flight
        path = self.path.split("?")[0]
        if path == "/_ctl/log":
            with lock:
                return self.send(200, json.dumps({"listed": listed, "wanted": [wanted_href(k) for k in range(len(games))],
                                                  "pages": log}), "application/json")
        if path == "/_ctl/stats":
            with lock:
                return self.send(200, json.dumps(stats), "application/json")

        with lock:
            in_flight += 1
            stats["requests"] += 1
            stats["maxInFlight"] = max(stats["maxInFlight"], in_flight)
            kind = "playlists" if path.endswith(".m3u8") else "pages"
            stats[kind] += 1
            if path == "/":
                stats["listing"] += 1
            minute = time.strftime("%H:%M")
            stats["perMinute"][minute] = stats["perMinute"].get(minute, 0) + 1
            log.append(f"{time.strftime('%H:%M:%S')} {path}")
            del log[:-4000]
        try:
            self.answer(path)
        finally:
            with lock:
                in_flight -= 1

    def answer(self, path):
        if fault[0] and fault[1] and fault[1] in path:
            with lock:
                stats["faults"] += 1
            return self.send(fault[0], "pushed back", "text/plain")
        if slow and not path.endswith(".m3u8"):
            time.sleep(slow)

        if path.endswith(".m3u8"):
            return self.send(404, "not found", "text/plain")  # players point at STREAM, on another host

        if path == "/":
            items = []
            for i in range(160):
                k = next((k for k in range(len(games)) if wanted_slot(k) == i), None)
                if k is not None:
                    if listed:
                        items.append(f'<li><a href="{wanted_href(k)}">Watch</a></li>')
                    continue
                away, home, href = filler(i)
                items.append(f'<li><a href="{href}">{away} vs {home}</a></li>')
            return self.send(200, "<html><head><title>Listing Example - every game</title></head><body><ul>"
                             + "".join(items) + "</ul></body></html>")

        for k in range(len(games)):
            if path == wanted_href(k):
                embed = EMBED_BASE + 10 * k
                return self.send(200, '<html><head><title>Listing Example</title></head><body>'
                                 f'<iframe id="player" src="/embed/{embed}"></iframe>'
                                 f'<button onclick="changeStream({embed})">Link 1</button>'
                                 f'<button onclick="changeStream({embed + 1})">Link 2</button>'
                                 + (f'<button onclick="changeStream({embed + 2})">ESPN Deportes</button>' if opts.spanish else '')
                                 + '</body></html>')

        if path.startswith("/embed/"):
            tag = path[len("/embed/"):]
            return self.send(200, f'<html><body><script>var source = "{stream("wanted-" + tag)}";</script></body></html>')

        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[2].isdigit():
            i = int(parts[2]) - EVENT_BASE
            player = (f'<script>var player = {{ file: "{stream("filler-" + str(i))}" }};</script>'
                      if i % 10 == 0 else "<p>Stream starts soon</p>")
            return self.send(200, f"<html><head><title>Listing Example</title></head><body>{player}</body></html>")

        self.send(404, "not found", "text/plain")


def main():
    global opts, listed, games
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--game", action="append", default=[], help="a wanted game as AWAY:HOME abbreviations, e.g. ROT:LKH (repeatable)")
    serve.add_argument("--away", help="a single wanted game's away abbreviation, e.g. ROT")
    serve.add_argument("--home", help="its home abbreviation, e.g. LKH")
    serve.add_argument("--league", default="mlb")
    serve.add_argument("--port", type=int, default=8000)
    serve.add_argument("--stream", default="http://172.17.0.2/live.m3u8", help="a live HLS playlist every player points at")
    serve.add_argument("--hidden", action="store_true", help="start with the wanted games unlisted")
    serve.add_argument("--busy", default="", help="comma-separated cities other filler links name (a busy day)")
    serve.add_argument("--spanish", action="store_true", help="each game's page also offers an \"ESPN Deportes\" (Spanish) player")
    opts = parser.parse_args()
    games = [tuple(g.split(":", 1)) for g in opts.game]
    if opts.away and opts.home:
        games.insert(0, (opts.away, opts.home))
    if not games:
        parser.error("name at least one wanted game (--game AWAY:HOME)")
    listed = not opts.hidden
    reset_stats()
    print(f"fixture site on :{opts.port}; wanted games at {', '.join(wanted_href(k) for k in range(len(games)))} "
          f"({'listed' if listed else 'unlisted'})")
    ThreadingHTTPServer(("0.0.0.0", opts.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
