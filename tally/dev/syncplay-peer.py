#!/usr/bin/env python3
"""A scriptable Jellyfin SyncPlay participant, for developing and testing Watch Together against a dev server.

It signs in as its own device, keeps the websocket alive, keeps a clock offset to the server (NTP-style, best of
the recent samples), prints every SyncPlay message as one JSON line, and behaves like a well-mannered client:
when the group's queue changes it "loads" the item and reports Ready after --load-ms; when told to unpause, pause
or seek it tracks the position it would be at. Actions run from --do, a comma-separated script:

  list                       print the server's groups
  create:<name>              create a group (and join it)
  join:<groupId|first>       join a group ("first" = the first listed)
  queue:<itemId>             set a new queue with this item (starts paused at 0)
  unpause | pause | stop     group playback requests
  seek:<seconds>             group seek
  buffering | ready          report our state explicitly
  wait:<seconds>             sleep, while still printing events
  leave                      leave the group

Example:
  syncplay-peer.py --server http://127.0.0.1:18200 --user friend --password Fr1end-test-pass \\
      --do "list,join:first,wait:30,pause,wait:5,unpause,wait:30,leave"
"""
import argparse
import datetime as dt
import json
import sys
import threading
import time
import urllib.request
import uuid

import websocket  # websocket-client

TICKS_PER_SECOND = 10_000_000


def now_utc() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def parse_time(value: str) -> dt.datetime:
    # Jellyfin sends 7 fractional digits; Python takes 6.
    value = value.rstrip("Z")
    if "." in value:
        head, frac = value.split(".", 1)
        value = f"{head}.{frac[:6]}"
    return dt.datetime.fromisoformat(value).replace(tzinfo=dt.timezone.utc)


def fmt_time(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class Peer:
    def __init__(self, server: str, user: str, password: str, device: str, load_ms: int, raw=None):
        self.raw = raw
        self.server = server.rstrip("/")
        self.device = device
        self.load_ms = load_ms
        self.offset = dt.timedelta(0)  # server time = local time + offset
        self.best_rtt = None
        self.lock = threading.Lock()
        self.group_id = None
        self.playlist_item_id = None
        self.position_ticks = 0
        self.playing_since = None  # server time when playback (virtually) started from position_ticks
        auth = f'MediaBrowser Client="syncplay-peer", Device="{device}", DeviceId="{device}", Version="1"'
        body = self.http("POST", "/Users/AuthenticateByName", {"Username": user, "Pw": password}, auth)
        self.token = body["AccessToken"]
        self.user_id = body["User"]["Id"]
        self.auth = f'{auth}, Token="{self.token}"'

    # -- plumbing ---------------------------------------------------------------------------------
    def http(self, method, path, body=None, auth=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.server + path, data=data, method=method)
        req.add_header("Authorization", auth or self.auth)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=10) as res:
            raw = res.read()
            return json.loads(raw) if raw else None

    def emit(self, kind, **fields):
        line = {"t": fmt_time(now_utc()), "peer": self.device, "event": kind, **fields}
        print(json.dumps(line), flush=True)

    def server_now(self) -> dt.datetime:
        return now_utc() + self.offset

    # -- clock --------------------------------------------------------------------------------------
    def sync_clock(self):
        t0 = now_utc()
        res = self.http("GET", "/GetUtcTime")
        t3 = now_utc()
        t1 = parse_time(res["RequestReceptionTime"])
        t2 = parse_time(res["ResponseTransmissionTime"])
        rtt = (t3 - t0) - (t2 - t1)
        offset = ((t1 - t0) + (t2 - t3)) / 2
        with self.lock:
            if self.best_rtt is None or rtt <= self.best_rtt * 1.5:
                self.offset = offset
                self.best_rtt = rtt if self.best_rtt is None else min(rtt, self.best_rtt)
        if self.group_id:
            self.http("POST", "/SyncPlay/Ping", {"Ping": int(rtt.total_seconds() * 1000)})
        return rtt, offset

    def clock_loop(self):
        while True:
            try:
                self.sync_clock()
            except Exception as ex:  # keep going; a missed sample is harmless
                self.emit("clock-error", error=str(ex))
            time.sleep(5)

    # -- websocket ----------------------------------------------------------------------------------
    def socket_loop(self):
        url = self.server.replace("http", "ws", 1) + f"/socket?api_key={self.token}&deviceId={self.device}"

        def on_message(ws, raw):
            msg = json.loads(raw)
            kind = msg.get("MessageType")
            if self.raw and kind in ("SyncPlayGroupUpdate", "SyncPlayCommand"):
                self.raw.write(raw.strip() + "\n")
                self.raw.flush()
            if kind == "ForceKeepAlive":
                ws.send(json.dumps({"MessageType": "KeepAlive"}))
                return
            if kind == "KeepAlive":
                return
            if kind == "SyncPlayGroupUpdate":
                self.on_group_update(msg.get("Data") or {})
            elif kind == "SyncPlayCommand":
                self.on_command(msg.get("Data") or {})
            else:
                self.emit("socket", messageType=kind)

        def keepalive(ws):
            while True:
                time.sleep(20)
                try:
                    ws.send(json.dumps({"MessageType": "KeepAlive"}))
                except Exception:
                    return

        def on_open(ws):
            self.emit("socket-open")
            threading.Thread(target=keepalive, args=(ws,), daemon=True).start()

        ws = websocket.WebSocketApp(url, on_message=on_message, on_open=on_open)
        ws.run_forever()

    # -- protocol -----------------------------------------------------------------------------------
    def on_group_update(self, data):
        kind = data.get("Type")
        payload = data.get("Data")
        self.emit("group-update", type=kind, data=payload)
        if kind == "GroupJoined":
            self.group_id = data.get("GroupId")
        elif kind in ("GroupLeft", "NotInGroup"):
            self.group_id = None
        elif kind == "PlayQueue" and payload:
            items = payload.get("Playlist") or []
            index = payload.get("PlayingItemIndex", -1)
            if 0 <= index < len(items):
                self.playlist_item_id = items[index]["PlaylistItemId"]
                self.position_ticks = payload.get("StartPositionTicks", 0)
                self.playing_since = None
                reason = payload.get("Reason")
                if reason in ("NewPlaylist", "SetCurrentItem", "NextItem", "PreviousItem"):
                    threading.Thread(target=self.load_then_ready, daemon=True).start()

    def load_then_ready(self):
        self.request("buffering")
        time.sleep(self.load_ms / 1000)
        self.request("ready")

    def position_at(self, when: dt.datetime) -> int:
        if self.playing_since is None:
            return self.position_ticks
        return self.position_ticks + int((when - self.playing_since).total_seconds() * TICKS_PER_SECOND)

    def on_command(self, data):
        command = data.get("Command")
        when = parse_time(data["When"]) if data.get("When") else self.server_now()
        ticks = data.get("PositionTicks", 0)
        lead_ms = int((when - self.server_now()).total_seconds() * 1000)
        self.emit("command", command=command, when=data.get("When"), positionTicks=ticks, leadMs=lead_ms,
                  playlistItemId=data.get("PlaylistItemId"))
        if command == "Unpause":
            self.position_ticks = ticks
            self.playing_since = when
        elif command in ("Pause", "Stop"):
            self.position_ticks = ticks
            self.playing_since = None
        elif command == "Seek":
            self.position_ticks = ticks
            self.playing_since = None
            threading.Thread(target=self.load_then_ready, daemon=True).start()

    def request(self, action, arg=None):
        now = self.server_now()
        if action == "list":
            groups = self.http("GET", "/SyncPlay/List") or []
            self.emit("groups", groups=groups)
            return groups
        if action == "create":
            return self.http("POST", "/SyncPlay/New", {"GroupName": arg})
        if action == "join":
            if arg == "first":
                groups = self.http("GET", "/SyncPlay/List") or []
                if not groups:
                    self.emit("error", error="no groups to join")
                    return None
                arg = groups[0]["GroupId"]
            return self.http("POST", "/SyncPlay/Join", {"GroupId": arg})
        if action == "leave":
            return self.http("POST", "/SyncPlay/Leave")
        if action == "queue":
            return self.http("POST", "/SyncPlay/SetNewQueue",
                             {"PlayingQueue": [arg], "PlayingItemPosition": 0, "StartPositionTicks": 0})
        if action == "unpause":
            return self.http("POST", "/SyncPlay/Unpause")
        if action == "pause":
            return self.http("POST", "/SyncPlay/Pause")
        if action == "stop":
            return self.http("POST", "/SyncPlay/Stop")
        if action == "seek":
            return self.http("POST", "/SyncPlay/Seek", {"PositionTicks": int(float(arg) * TICKS_PER_SECOND)})
        body = {"When": fmt_time(now), "PositionTicks": self.position_at(now),
                "IsPlaying": self.playing_since is not None, "PlaylistItemId": self.playlist_item_id}
        if action == "buffering":
            return self.http("POST", "/SyncPlay/Buffering", body)
        if action == "ready":
            return self.http("POST", "/SyncPlay/Ready", body)
        raise ValueError(f"unknown action {action}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", required=True)
    ap.add_argument("--user", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--device", default=f"syncplay-peer-{uuid.uuid4().hex[:6]}")
    ap.add_argument("--load-ms", type=int, default=1500, help="simulated time to load an item before Ready")
    ap.add_argument("--do", default="list", help="comma-separated script (see above)")
    ap.add_argument("--raw", help="also append every SyncPlay websocket frame, verbatim, to this file")
    args = ap.parse_args()

    raw = open(args.raw, "a") if args.raw else None
    peer = Peer(args.server, args.user, args.password, args.device, args.load_ms, raw)
    rtt, offset = peer.sync_clock()
    peer.emit("clock", rttMs=int(rtt.total_seconds() * 1000), offsetMs=int(offset.total_seconds() * 1000))
    threading.Thread(target=peer.socket_loop, daemon=True).start()
    threading.Thread(target=peer.clock_loop, daemon=True).start()
    time.sleep(1.0)  # let the socket connect before acting

    for step in [s.strip() for s in args.do.split(",") if s.strip()]:
        action, _, arg = step.partition(":")
        if action == "wait":
            time.sleep(float(arg))
            continue
        try:
            peer.emit("do", action=action, arg=arg or None)
            peer.request(action, arg or None)
        except Exception as ex:
            peer.emit("error", action=action, error=str(ex))
    time.sleep(1.0)


if __name__ == "__main__":
    sys.exit(main())
