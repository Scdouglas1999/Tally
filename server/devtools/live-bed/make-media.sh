#!/bin/bash
# Pre-encodes the looping test sources the live bed serves (once; ~1.3 GB, a few minutes of CPU).
# Every source is the same 10-minute "event": testsrc2 motion, a 1 kHz beep every second, and a burned-in label
# with the event clock, so frames from different sources at the same wall time show the same clock.
#   A      1080p60  ~8 Mbps   PIDs 256/257 (ffmpeg default)
#   B       720p30  ~3 Mbps   PIDs 481/482, PMT 480 (a different muxer layout, like many real CDNs)
#   C      master:  1080p30 ~5 Mbps + 540p30 ~1.2 Mbps, segment-aligned renditions
#   SOLO    720p30  ~3 Mbps   (a channel with a single candidate)
# Usage: make-media.sh [output dir]   (default ~/.cache/tally-live-bed)
set -euo pipefail
OUT="${1:-$HOME/.cache/tally-live-bed}"
SECS="${SECS:-600}"
SEG=4
mkdir -p "$OUT"

enc() { # name size rate vbitrate label [extra ts options]
  local name=$1 size=$2 rate=$3 vb=$4 label=$5 ts=${6:-}
  local dir="$OUT/$name"
  if [ -f "$dir/index.m3u8" ]; then echo "have $name"; return; fi
  rm -rf "$dir"; mkdir -p "$dir"
  local fs=$(( ${size#*x} / 14 ))
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=$size:rate=$rate" \
    -f lavfi -i "sine=frequency=1000:beep_factor=4:sample_rate=48000" \
    -t "$SECS" \
    -vf "drawtext=text='$label  %{pts\\:hms}':fontsize=$fs:x=$fs/2:y=$fs/2:box=1:boxborderw=12:boxcolor=black@0.7:fontcolor=white" \
    -c:v libx264 -preset veryfast -profile:v high -pix_fmt yuv420p \
    -b:v "$vb" -maxrate "$vb" -bufsize "$vb" -g $(( rate * 2 )) -keyint_min $(( rate * 2 )) -sc_threshold 0 \
    -c:a aac -b:a 128k -ac 2 \
    -f segment -segment_time $SEG -segment_format mpegts ${ts:+-segment_format_options "$ts"} \
    -segment_list "$dir/index.m3u8" -segment_list_type m3u8 "$dir/seg_%04d.ts"
  echo "encoded $name: $(ls "$dir" | grep -c '\.ts$') segments, $(du -sh "$dir" | cut -f1)"
}

enc A 1920x1080 60 8M "A 1080p60" &
enc B 1280x720 30 3M "B 720p30" "mpegts_pmt_start_pid=480:mpegts_start_pid=481" &
enc C_hi 1920x1080 30 5M "C 1080p30" &
enc C_lo 960x540 30 1200k "C 540p30" &
enc SOLO 1280x720 30 3M "SOLO 720p30" &
wait
echo "media in $OUT"
