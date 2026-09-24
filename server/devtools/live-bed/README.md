# Live bed

A controllable set of live HLS sources for developing and measuring the plugin's live ladder (several streams per
channel, server-side switching, one continuous playlist per channel). Everything runs locally; nothing here talks
to a real stream source.

## What it serves

`make-media.sh` pre-encodes a 10-minute "event" once (testsrc2 motion, a beep every second, a burned-in label with
the event clock) as four sources. `serve.py` plays them as endless live streams: a sliding six-segment window,
timestamps that keep increasing across laps of the loop, and a different timestamp base per source, like unrelated
real encoders. All sources show the same event clock at the same wall time, so a switch that lost or repeated time
is visible in the picture.

| Path | Stream | Notes |
|---|---|---|
| `:8081/A/index.m3u8` | 1080p60, ~8 Mbps | plain media playlist (no master: resolution and frame rate must come from ffprobe); served **through the control proxy** |
| `:8080/B/master.m3u8` | 720p30, ~3 Mbps | master with BANDWIDTH only; different PIDs (PMT 480, video 481, audio 482) |
| `:8081/C/master.m3u8` | 1080p30 5 Mbps + 540p30 1.2 Mbps | full master attributes, segment-aligned renditions; also **through the control proxy** |
| `:8080/SOLO/index.m3u8` | 720p30, ~3 Mbps | a channel with a single candidate |
| `:8080/bed.m3u` | M3U source | "Bed Game HD" (A), "Bed Game" (B), "Bed Game BACKUP" (C) share `tvg-id="bed.game"`, so the plugin merges them into one channel "Bed Game" with three candidates; "Bed Solo" stays on its own |

The proxy on `:8081` sits in front of A and C and can be told to misbehave, per source:

```
./ctl.sh slow 4000     # A's segments trickle at 4000 kbit/s (A needs ~8000): the ladder should step down
./ctl.sh stall         # A's playlist stops advancing
./ctl.sh fail          # every A segment answers 503
./ctl.sh down 0 C      # everything of C answers 503 (playlist too) — with C down, losing A means B (720p30, other PIDs)
./ctl.sh normal        # (./ctl.sh normal 0 C for C)
./ctl.sh               # show the current modes
```

## Running it

```
./make-media.sh                 # once: ~1.3 GB into ~/.cache/tally-live-bed (SECS=600 by default)
./run.sh                        # starts container tally-live-bed (python:3-alpine, 384 MB cap) on the Docker bridge
./run.sh stop
```

The dev Jellyfin runs in Docker and the host firewall blocks containers from reaching host ports, so the bed runs
in its own container on the default bridge; `run.sh` prints its address. Add it to the dev server's plugin config as
an M3U source with that `bed.m3u` URL (back up the config first and restore it afterwards), then run Jellyfin's
"Refresh Guide" task so the Live TV channels appear. Every request the bed serves is logged as a JSON line
(`docker logs tally-live-bed`): segment download times on the proxy are the upstream side of every measurement.

## Watching

- `web-watch.mjs` plays a channel in Jellyfin's own web client (headless Chromium over CDP; playback is started with
  `POST /Sessions/{id}/Playing?playCommand=PlayNow` against the browser's session) and reports time to first frame,
  stalls (currentTime standing still), errors, resolution changes and whether the player reloaded its source.
- `tv-watch.sh` does the same in the Tally app on an Android TV emulator, from a screen recording (first colorful
  frame; `freezedetect` for frozen pictures). It holds the emulator's lock for the whole run.
- `scenario.sh` runs both at once on one channel and steers the proxy on a schedule, e.g.
  `./scenario.sh /tmp/kill <tv device id> "Bed Game" 300 0:down:0:C 40:down:0:A 100:normal:0:A 290:normal:0:C`.
- `GET /JellyTV/Ladder` (admin) shows every multi-stream channel's rungs, probes, the watched channels' current rung
  and their switch history; the server log has one `JellyTV ladder:` line per probe and per switch, with its reason.
