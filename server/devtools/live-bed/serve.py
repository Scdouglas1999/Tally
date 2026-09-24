#!/usr/bin/env python3
"""Live HLS test bed for the Tally plugin's live ladder.

Serves the pre-encoded loops from make-media.sh as endless live streams (sliding window, continuous timestamps:
every lap of the loop is shifted by the loop's length, and each source has its own timestamp base, like
independent real encoders), plus an M3U that lists them as one channel with several candidates, and a proxy in
front of the best source (A) that can be told to slow down, stall or fail.

  :8080  /A/index.m3u8                 1080p60 ~8 Mbps, plain media playlist (no master: ffprobe must tell)
         /B/master.m3u8                720p30 ~3 Mbps, master with BANDWIDTH only; PIDs 480/481/482
         /C/master.m3u8                1080p30 5 Mbps + 540p30 1.2 Mbps, full master attributes
         /SOLO/index.m3u8              720p30, a channel with one candidate
         /bed.m3u                      the M3U source (A via the proxy)
         /games.m3u                    channels named after fictional games, listed once switched on (the DVR tests:
                                       a game whose stream "appears" late)
  :8081  /A/..., /C/...                proxy to :8080 under control of /ctl (the M3U routes A and C through it)
         /ctl?mode=normal|slow|stall|fail|down[&kbps=N][&src=A|C]   (src defaults to A)
         /ctl                          current modes (JSON)
         /ctl?game=<name>&on=1|0       list or unlist a game's channels in /games.m3u (see GAMES)

Every request is logged to stdout as JSON lines (time, path, status, bytes, seconds), which is what the
measurements in the README are computed from.
"""
import json
import os
import re
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MEDIA = os.environ.get("MEDIA", "/media")
WINDOW = 6
T0 = time.time()
WRAP = 1 << 33

# name -> (media dir, timestamp base in 90 kHz ticks)
SOURCES = {
    "A": ("A", 10 * 90000),
    "B": ("B", 7_000_000_000),   # far from A's base, like an unrelated encoder
    "C_hi": ("C_hi", 3_000_000_000),
    "C_lo": ("C_lo", 3_000_000_000),  # renditions of one master share a timeline
    "SOLO": ("SOLO", 123 * 90000),
}


def load(name):
    d = os.path.join(MEDIA, SOURCES[name][0])
    segs = []
    dur = None
    for line in open(os.path.join(d, "index.m3u8")):
        line = line.strip()
        if line.startswith("#EXTINF:"):
            dur = float(line[8:].split(",")[0])
        elif line and not line.startswith("#"):
            segs.append((os.path.join(d, line), dur))
    total = sum(s[1] for s in segs)
    loop = round(total * 10) / 10  # the encode is exactly N seconds; EXTINF sums carry rounding
    starts = []
    acc = 0.0
    for _, du in segs:
        starts.append(acc)
        acc += du
    return {"segs": segs, "starts": starts, "loop": loop, "ticks": int(round(loop * 90000)), "base": SOURCES[name][1]}


LIB = {name: load(name) for name in SOURCES}


def live_window(name, now=None):
    """Absolute segment numbers currently listed: the newest WINDOW that have fully 'happened'."""
    src = LIB[name]
    n_per = len(src["segs"])
    t = (now or time.time()) - T0 + 60  # start one minute "into" the event so the window is full at once
    lap = int(t // src["loop"])
    pos = t - lap * src["loop"]
    idx = max(i for i, s in enumerate(src["starts"]) if s <= pos)
    last_done = lap * n_per + idx - 1  # the one before the segment in progress
    first = max(0, last_done - WINDOW + 1)
    return list(range(first, last_done + 1))


def media_playlist(name):
    src = LIB[name]
    n_per = len(src["segs"])
    nums = live_window(name)
    target = int(max(d for _, d in src["segs"]) + 0.999)
    out = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{target}", f"#EXT-X-MEDIA-SEQUENCE:{nums[0]}"]
    for n in nums:
        out.append(f"#EXTINF:{src['segs'][n % n_per][1]:.6f},")
        out.append(f"seg_{n}.ts")
    return "\n".join(out) + "\n"


def shift_ts(data, offset):
    """Adds offset to every PTS/DTS/PCR of an MPEG-TS buffer (mod 2^33)."""
    b = bytearray(data)
    for i in range(0, len(b) - 187, 188):
        if b[i] != 0x47:
            continue
        afc = (b[i + 3] >> 4) & 3
        p = i + 4
        if afc in (2, 3):
            alen = b[i + 4]
            if alen >= 7 and b[i + 5] & 0x10:
                q = i + 6
                base = (b[q] << 25) | (b[q + 1] << 17) | (b[q + 2] << 9) | (b[q + 3] << 1) | (b[q + 4] >> 7)
                base = (base + offset) % WRAP
                b[q] = (base >> 25) & 0xFF
                b[q + 1] = (base >> 17) & 0xFF
                b[q + 2] = (base >> 9) & 0xFF
                b[q + 3] = (base >> 1) & 0xFF
                b[q + 4] = ((base & 1) << 7) | (b[q + 4] & 0x7F)
            p += 1 + alen
        if afc in (1, 3) and b[i + 1] & 0x40 and p + 14 <= i + 188 and b[p] == 0 and b[p + 1] == 0 and b[p + 2] == 1:
            flags = b[p + 7] >> 6
            for present, at in ((flags in (2, 3), p + 9), (flags == 3, p + 14)):
                if present and at + 5 <= i + 188:
                    v = ((b[at] & 0x0E) << 29) | (b[at + 1] << 22) | ((b[at + 2] & 0xFE) << 14) | (b[at + 3] << 7) | (b[at + 4] >> 1)
                    v = (v + offset) % WRAP
                    b[at] = (b[at] & 0xF0) | ((v >> 29) & 0x0E) | 1
                    b[at + 1] = (v >> 22) & 0xFF
                    b[at + 2] = ((v >> 14) & 0xFE) | 1
                    b[at + 3] = (v >> 7) & 0xFF
                    b[at + 4] = ((v << 1) & 0xFE) | 1
    return bytes(b)


def segment(name, n):
    src = LIB[name]
    n_per = len(src["segs"])
    path = src["segs"][n % n_per][0]
    with open(path, "rb") as f:
        data = f.read()
    return shift_ts(data, (n // n_per) * src["ticks"] + src["base"])


MASTERS = {
    # B: only BANDWIDTH, like many scraped streams; C: everything
    "B": "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3300000\nindex.m3u8\n",
    "C": ("#EXTM3U\n"
          "#EXT-X-STREAM-INF:BANDWIDTH=5500000,AVERAGE-BANDWIDTH=5200000,RESOLUTION=1920x1080,FRAME-RATE=30.000,CODECS=\"avc1.640028,mp4a.40.2\"\n"
          "hi/index.m3u8\n"
          "#EXT-X-STREAM-INF:BANDWIDTH=1400000,AVERAGE-BANDWIDTH=1300000,RESOLUTION=960x540,FRAME-RATE=30.000,CODECS=\"avc1.64001f,mp4a.40.2\"\n"
          "lo/index.m3u8\n"),
}

# Fictional games for /games.m3u: channel names that the plugin's matcher ties to the score simulator's games
# (tally/dev/score-sim.py add ...). Entries sharing a tvg-id become one channel with several streams.
GAMES = {
    # three streams: the ladder, with the proxy in front of A and C for failovers
    "otters": [("Riverton Otters at Lakeside Herons HD", "game.otters", 8081, "A/index.m3u8"),
               ("Riverton Otters at Lakeside Herons", "game.otters", 8080, "B/master.m3u8"),
               ("Riverton Otters at Lakeside Herons BACKUP", "game.otters", 8081, "C/master.m3u8")],
    # one stream, one rendition: the plain pass-through path
    "hawks": [("Harbor Hawks at Mesa Owls", "game.hawks", 8080, "SOLO/index.m3u8")],
    "bears": [("Pine Bears at Dune Foxes", "game.bears", 8080, "B/master.m3u8?g=bears")],
    "cranes": [("Delta Cranes at Summit Elks", "game.cranes", 8080, "SOLO/index.m3u8?g=cranes")],
}
GAMES_ON = set()

ROUTE = re.compile(r"^/(A|B|C/hi|C/lo|SOLO)/(index\.m3u8|seg_(\d+)\.ts)$")
LOG_LOCK = threading.Lock()


def log(**kw):
    kw["t"] = round(time.time(), 3)
    with LOG_LOCK:
        print(json.dumps(kw), flush=True)


class Origin(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, code, body=b"", ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        start = time.time()
        path = self.path.split("?")[0]
        code, body, ctype = 404, b"not found", "text/plain"
        try:
            if path == "/bed.m3u":
                host = self.headers.get("Host", "127.0.0.1:8080").split(":")[0]
                code, ctype = 200, "audio/x-mpegurl"
                body = (f'#EXTM3U\n'
                        f'#EXTINF:-1 tvg-id="bed.game" group-title="Live Bed",Bed Game HD\nhttp://{host}:8081/A/index.m3u8\n'
                        f'#EXTINF:-1 tvg-id="bed.game" group-title="Live Bed",Bed Game\nhttp://{host}:8080/B/master.m3u8\n'
                        f'#EXTINF:-1 tvg-id="bed.game" group-title="Live Bed",Bed Game BACKUP\nhttp://{host}:8081/C/master.m3u8\n'
                        f'#EXTINF:-1 tvg-id="bed.solo" group-title="Live Bed",Bed Solo\nhttp://{host}:8080/SOLO/index.m3u8\n').encode()
            elif path == "/games.m3u":
                host = self.headers.get("Host", "127.0.0.1:8080").split(":")[0]
                code, ctype = 200, "audio/x-mpegurl"
                lines = ["#EXTM3U"]
                for game in sorted(GAMES_ON):
                    for name, tvg, port, rel in GAMES[game]:
                        lines.append(f'#EXTINF:-1 tvg-id="{tvg}" group-title="Games",{name}')
                        lines.append(f"http://{host}:{port}/{rel}")
                body = ("\n".join(lines) + "\n").encode()
            elif path in ("/B/master.m3u8", "/C/master.m3u8"):
                code, body, ctype = 200, MASTERS[path[1]].encode(), "application/vnd.apple.mpegurl"
            else:
                m = ROUTE.match(path)
                if m:
                    name = m.group(1).replace("/", "_")
                    if m.group(3) is None:
                        code, body, ctype = 200, media_playlist(name).encode(), "application/vnd.apple.mpegurl"
                    else:
                        n = int(m.group(3))
                        if n in live_window(name) or n + WINDOW in live_window(name):
                            code, body, ctype = 200, segment(name, n), "video/mp2t"
        except Exception as e:  # noqa: BLE001 - a test server reports, never dies
            code, body = 500, str(e).encode()
        self.send(code, body, ctype)
        log(srv="origin", path=path, status=code, bytes=len(body), s=round(time.time() - start, 3))


class Control:
    """Per-source misbehavior, keyed by the first path element (A or C)."""
    modes = {"A": ("normal", 4000), "C": ("normal", 4000)}
    frozen = {}  # playlist path -> body served while stalled


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send_simple(self, code, body=b"", ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        start = time.time()
        path, _, query = self.path.partition("?")
        if path == "/ctl":
            q = dict(kv.split("=", 1) for kv in query.split("&") if "=" in kv)
            if q.get("game") in GAMES:
                (GAMES_ON.add if q.get("on", "1") == "1" else GAMES_ON.discard)(q["game"])
                log(srv="proxy", ctl="game", game=q["game"], on=q["game"] in GAMES_ON)
            if "mode" in q:
                src = q.get("src", "A")
                Control.modes[src] = (q["mode"], int(q.get("kbps", Control.modes.get(src, ("", 4000))[1])))
                for k in [k for k in Control.frozen if k.startswith("/" + src + "/")]:
                    del Control.frozen[k]
                log(srv="proxy", ctl=q["mode"], src=src, kbps=Control.modes[src][1])
            status = {k: {"mode": m, "kbps": b} for k, (m, b) in Control.modes.items()}
            status["games"] = sorted(GAMES_ON)
            self.send_simple(200, json.dumps(status).encode(), "application/json")
            return
        src = path.split("/")[1] if path.count("/") >= 2 else ""
        mode, kbps = Control.modes.get(src, ("normal", 4000))
        if mode == "down":
            self.send_simple(503, b"down")
            log(srv="proxy", path=path, status=503, mode=mode, s=round(time.time() - start, 3))
            return
        playlist = path.endswith(".m3u8")
        if playlist and mode == "stall" and path in Control.frozen:
            self.send_simple(200, Control.frozen[path], "application/vnd.apple.mpegurl")
            log(srv="proxy", path=path, status=200, mode=mode, frozen=True, s=round(time.time() - start, 3))
            return
        if not playlist and mode == "fail":
            self.send_simple(503, b"segment unavailable")
            log(srv="proxy", path=path, status=503, mode=mode, s=round(time.time() - start, 3))
            return
        try:
            with urllib.request.urlopen("http://127.0.0.1:8080" + path, timeout=30) as r:
                body = r.read()
                code, ctype = r.status, r.headers.get("Content-Type", "application/octet-stream")
        except urllib.error.HTTPError as e:
            body, code, ctype = e.read(), e.code, "text/plain"
        if playlist and mode == "stall" and path not in Control.frozen and code == 200:
            Control.frozen[path] = body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if mode == "slow" and not playlist:
            rate = kbps * 1000 / 8  # bytes per second
            chunk = 16384
            for i in range(0, len(body), chunk):
                t = time.time()
                self.wfile.write(body[i:i + chunk])
                spent = time.time() - t
                time.sleep(max(0, len(body[i:i + chunk]) / rate - spent))
        else:
            self.wfile.write(body)
        log(srv="proxy", path=path, status=code, bytes=len(body), mode=mode, s=round(time.time() - start, 3))


def main():
    for name, src in LIB.items():
        log(source=name, segments=len(src["segs"]), loop=src["loop"])
    origin = ThreadingHTTPServer(("0.0.0.0", 8080), Origin)
    proxy = ThreadingHTTPServer(("0.0.0.0", 8081), Proxy)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    origin.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
