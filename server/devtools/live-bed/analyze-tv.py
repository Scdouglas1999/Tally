#!/usr/bin/env python3
"""Time to first frame and freezes from a tv-watch.sh recording: analyze-tv.py <out prefix>."""
import json, subprocess, sys, re
out = sys.argv[1]
meta = json.load(open(out + ".json"))
cmd = meta["command"]
first = None
freezes = []
for clip in meta["clips"]:
    lead = cmd - clip["start"]  # command time on this clip's clock (negative for later clips)
    stats = subprocess.run(["ffmpeg", "-hide_banner", "-i", clip["file"], "-vf", "fps=20,signalstats,metadata=print:key=lavfi.signalstats.SATAVG", "-f", "null", "-"],
                           capture_output=True, text=True).stderr
    sat = [(float(t), float(v)) for t, v in re.findall(r"pts_time:([\d.]+).*?SATAVG=([\d.]+)", stats, re.S)]
    if first is None:
        # first moment the picture is the colorful test pattern for half a second running (not a flash of a poster)
        run = 0
        for t, v in sat:
            run = run + 1 if t > lead and v > 25 else 0
            if run == 10:
                first = t - 0.45 - lead
                break
    fr = subprocess.run(["ffmpeg", "-hide_banner", "-i", clip["file"], "-vf", "freezedetect=n=0.003:d=0.5", "-map", "0:v", "-f", "null", "-"],
                        capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r"freeze_start: ([\d.]+)", fr)]
    durs = [float(x) for x in re.findall(r"freeze_duration: ([\d.]+)", fr)]
    if len(starts) > len(durs):  # still frozen when the clip ended (screenrecord also stops writing frames then)
        end = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", clip["file"]],
                                   capture_output=True, text=True).stdout.strip() or 0)
        clip_len = clip.get("seconds", end)
        durs.append(max(end, clip_len) - starts[-1])
    freezes += [{"at": round(s - lead, 2), "seconds": round(d, 2), "clip": clip["file"]} for s, d in zip(starts, durs) if first is not None and s - lead > first]
print(json.dumps({"firstFrameSeconds": round(first, 2) if first is not None else None, "freezesAfterStart": freezes, "commandAt": cmd}))
