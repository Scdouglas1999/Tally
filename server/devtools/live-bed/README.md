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
| `:8081/J/index.m3u8` | 1080p60, ~8 Mbps | A's picture with its own timestamp base: the burst channel's first stream, through the control proxy |
| `:8081/K/master.m3u8` | 720p30, ~3 Mbps | B's picture with its own base: the burst channel's clean second stream, through the control proxy |
| `:8080/bed.m3u` | M3U source | "Bed Game HD" (A), "Bed Game" (B), "Bed Game BACKUP" (C) share `tvg-id="bed.game"`, so the plugin merges them into one channel "Bed Game" with three candidates; "Bed Burst HD" (J) and "Bed Burst" (K) become "Bed Burst" with two; "Bed Solo" stays on its own |

`/games.m3u` lists channels named after fictional games (see `GAMES` in `serve.py`), each only once it is switched on
with `./ctl.sh game otters` (`./ctl.sh game otters off` lists it no more), so a game's stream can "appear" late, as
web page sources do near game time. Pair them with games of the same names in the score simulator
(`tally/dev/score-sim.py serve --offline`, then `add` and `state`): that is how the DVR is tested end to end.
"otters" has three streams (A, B and C, so the ladder and its failover are in play), "hawks" and "cranes" one
rendition of SOLO (the plain pass-through path), "bears" B alone. `BED_NAME=<name>` runs a second bed (and steers
it with `ctl.sh`) next to one already running.

The proxy on `:8081` sits in front of A, C, J and K and can be told to misbehave, per source:

```
./ctl.sh slow 4000     # A's segments trickle at 4000 kbit/s (A needs ~8000): the ladder should step down
./ctl.sh stall         # A's playlist stops advancing
./ctl.sh fail          # every A segment answers 503
./ctl.sh down 0 C      # everything of C answers 503 (playlist too) — with C down, losing A means B (720p30, other PIDs)
./ctl.sh burst 0 J     # J's playlist publishes in bursts: new segments are held back and released all at once
                       # after pauses of 8, 12, 15, 6, 11 and 14 s (cycled), so they arrive 2-4 at a time
./ctl.sh burst 7,8.5,6 A   # bursts with these pauses (seconds): under 2 target durations + 1 s, like the live
                       # server's source on September 24, so the ladder's "no new segment" rule never fires
./ctl.sh normal        # (./ctl.sh normal 0 C for C)
./ctl.sh               # show the current modes
```

Every release of a burst is logged by the bed (`{"srv": "proxy", "burst": <segments>, "after": <seconds>}`).

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
- `GET /JellyTV/Ladder` (admin) shows every multi-stream channel's rungs, probes (with the cadence each probe's
  15-second playlist watch saw), the watched channels' current rung, switch history and diagnostics (per minute and
  in total: segments published, upstream update gaps, playlist fetch and segment download times, seconds held back,
  seconds ahead of the player and how long the player waited at the live edge); the server log has one
  `JellyTV ladder:` line per probe and per switch, with its reason, and one `[minute]` line a minute per watched
  channel.
- Bursts: `./scenario.sh /tmp/burst <tv device id> "Bed Burst" 180 0:burst:0:J` (J bursts from the start: the probes
  mark it bursty and the channel starts on K) or `... "Bed Game" 240 40:burst:7,8.5,6,8.5,8:A` (A turns bursty
  mid-game, with gaps the "no new segment" rule never catches: the session moves to a steadier stream). With both
  of a channel's streams bursty (`0:burst:0:J 0:burst:0:K`) there is nowhere to go and the channel rides the pauses
  on the cushion it started with.
