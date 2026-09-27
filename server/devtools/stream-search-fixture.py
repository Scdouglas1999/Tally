#!/usr/bin/env python3
"""A made-up listing site for trying the plugin's game-aware stream search on a dev server (no real site involved).

The front page lists 160 event links over five leagues. Most event pages have no stream yet; every tenth one carries
a player config with a stream. The wanted game is linked 121st, past the 96 pages the general crawl reads, as
/<league>/<away abbr>-<home abbr>/5000120 with the link text "Watch" (the hardest shape: only abbreviations name it).
Its page embeds a player (/embed/7700) with a "Link 2" button that switches to /embed/7701; both players point at
STREAM (a live HLS playlist the dev server can play).

  stream-search-fixture.py serve --away ROT --home LKH [--league mlb] [--port 8000] [--stream URL] [--hidden]
  curl -X POST http://<fixture>:8000/_ctl/list      # the front page lists the wanted game
  curl -X POST http://<fixture>:8000/_ctl/unlist    # it does not (a site that adds a game's link near game time)
  curl -X POST 'http://<fixture>:8000/_ctl/slow?s=3' # each page takes 3 s to answer (0: back to normal), to watch
                                                    # the board say "searching"
  curl http://<fixture>:8000/_ctl/log               # the pages the plugin read, in order

The dev server runs in Docker and this host's firewall blocks containers from reaching host ports, so run it in a
container on the same bridge:
  docker run -d --name tally-search-fixture -v "$PWD/stream-search-fixture.py:/site.py:ro" python:3-alpine \
      python -u /site.py serve --away ROT --home LKH --stream http://172.17.0.2/live.m3u8
and add http://<its address>:8000/ as a web page source.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LEAGUES = ["mlb", "nba", "nhl", "nfl", "soccer"]
FILLER_AWAY = ["Harbor City Gulls", "Pine Hollow Badgers", "Cedar Falls Comets", "North Ridge Foxes"]
FILLER_HOME = ["Stonebridge Rams", "Bayview Pelicans", "Elm Grove Owls", "Red Mesa Coyotes"]
WANTED_AT = 120
EVENT_BASE = 5000000

opts = None
listed = True
slow = 0.0
log = []
lock = threading.Lock()


def filler(i):
    away = f"{FILLER_AWAY[i % len(FILLER_AWAY)]} {i}"
    home = f"{FILLER_HOME[i % len(FILLER_HOME)]} {i}"
    slug = f"{away} {home}".lower().replace(" ", "-")
    return away, home, f"/{LEAGUES[i % len(LEAGUES)]}/{slug}/{EVENT_BASE + i}"


def wanted_href():
    return f"/{opts.league}/{opts.away.lower()}-{opts.home.lower()}/{EVENT_BASE + WANTED_AT}"


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
        global listed, slow
        if self.path.startswith("/_ctl/slow"):
            slow = float(self.path.partition("s=")[2] or 0)
            return self.send(200, json.dumps({"slow": slow}), "application/json")
        if self.path == "/_ctl/list":
            listed = True
        elif self.path == "/_ctl/unlist":
            listed = False
        else:
            return self.send(404, '{"error": "not found"}', "application/json")
        self.send(200, json.dumps({"listed": listed, "wanted": wanted_href()}), "application/json")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/_ctl/log":
            with lock:
                return self.send(200, json.dumps({"listed": listed, "wanted": wanted_href(), "pages": log}), "application/json")
        with lock:
            log.append(path)
            del log[:-2000]
        if slow and not path.endswith(".m3u8"):
            time.sleep(slow)

        if path == "/":
            items = []
            for i in range(160):
                if i == WANTED_AT:
                    if listed:
                        items.append(f'<li><a href="{wanted_href()}">Watch</a></li>')
                    continue
                away, home, href = filler(i)
                items.append(f'<li><a href="{href}">{away} vs {home}</a></li>')
            return self.send(200, "<html><head><title>Listing Example - every game</title></head><body><ul>"
                             + "".join(items) + "</ul></body></html>")

        if path == wanted_href():
            return self.send(200, '<html><head><title>Listing Example</title></head><body>'
                             '<iframe id="player" src="/embed/7700"></iframe>'
                             '<button onclick="changeStream(7700)">Link 1</button>'
                             '<button onclick="changeStream(7701)">Link 2</button></body></html>')

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
    global opts, listed
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--away", required=True, help="the wanted game's away abbreviation, e.g. ROT")
    serve.add_argument("--home", required=True, help="its home abbreviation, e.g. LKH")
    serve.add_argument("--league", default="mlb")
    serve.add_argument("--port", type=int, default=8000)
    serve.add_argument("--stream", default="http://172.17.0.2/live.m3u8", help="a live HLS playlist every player points at")
    serve.add_argument("--hidden", action="store_true", help="start with the wanted game unlisted")
    opts = parser.parse_args()
    listed = not opts.hidden
    print(f"fixture site on :{opts.port}; wanted game at {wanted_href()} ({'listed' if listed else 'unlisted'})")
    ThreadingHTTPServer(("0.0.0.0", opts.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
