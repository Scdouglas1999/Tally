#!/bin/bash
# make-test-library.sh <out-dir>
#
# Builds a small but realistic Jellyfin media library for developing and testing the app against a
# dev server: short test-pattern files named after real films, shows and albums, so Jellyfin's own
# metadata providers (TMDb, MusicBrainz) fill in real titles, artwork, ratings, cast and collections.
# Every API payload the app sees is then produced by Jellyfin itself; nothing is hand-written.
#
# Each video: 640x360 test pattern with the title burned in, two audio tracks (English, Spanish),
# an embedded English subtitle track, an external Spanish .srt, and a chapter every 30 s.
# Songs: short tones tagged with artist/album/track; one track has a synced .lrc (original text).
# Total size is roughly 60 MB. Needs ffmpeg with libx264, libmp3lame and drawtext.
set -euo pipefail
OUT=${1:?usage: make-test-library.sh <out-dir>}
FONT=${FONT:-/usr/share/fonts/TTF/DejaVuSans.ttf}
MOVIE_SECONDS=${MOVIE_SECONDS:-90}
EPISODE_SECONDS=${EPISODE_SECONDS:-60}
JOBS=${JOBS:-4}
mkdir -p "$OUT/movies" "$OUT/shows" "$OUT/music"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

MOVIES=(
  "Toy Story (1995)" "Toy Story 2 (1999)" "Toy Story 3 (2010)"
  "Back to the Future (1985)" "Back to the Future Part II (1989)" "Back to the Future Part III (1990)"
  "Finding Nemo (2003)" "Up (2009)" "Coco (2017)" "Paddington 2 (2017)" "Spirited Away (2001)"
  "Inception (2010)" "The Dark Knight (2008)" "Interstellar (2014)" "The Matrix (1999)"
  "Jurassic Park (1993)" "Mad Max Fury Road (2015)" "Arrival (2016)" "The Grand Budapest Hotel (2014)"
  "Dune (2021)" "Parasite (2019)" "Knives Out (2019)"
)
# "Show (Year)|season:episodes season:episodes ..."
SHOWS=(
  "Bluey (2018)|1:6"
  "The Office (2005)|1:6 2:4"
  "Breaking Bad (2008)|1:7 2:3"
  "Severance (2022)|1:5 2:3"
  "The Last of Us (2023)|1:4"
)
# "Artist|Album (Year)|Track 1;Track 2;..."
ALBUMS=(
  "Daft Punk|Discovery (2001)|One More Time;Aerodynamic;Digital Love;Harder, Better, Faster, Stronger;Crescendolls"
  "Miles Davis|Kind of Blue (1959)|So What;Freddie Freeloader;Blue in Green;All Blues;Flamenco Sketches"
)

# Subtitle cues every 10 s over the whole runtime. $1 seconds, $2 language line prefix.
write_srt() {
  local secs=$1 prefix=$2 n=0 t=0
  while [ $t -lt "$secs" ]; do
    n=$((n + 1))
    printf '%d\n00:%02d:%02d,000 --> 00:%02d:%02d,500\n%s %d\n\n' \
      $n $((t / 60)) $((t % 60)) $(((t + 4) / 60)) $(((t + 4) % 60)) "$prefix" $n
    t=$((t + 10))
  done
}

# Chapter metadata: a chapter every 30 s.
write_chapters() {
  local secs=$1 t=0 n=0
  echo ";FFMETADATA1"
  while [ $t -lt "$secs" ]; do
    n=$((n + 1))
    local end=$((t + 30)); [ $end -gt "$secs" ] && end=$secs
    printf '[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=Chapter %d\n' $((t * 1000)) $((end * 1000)) $n
    t=$end
  done
}

# make_video <path-without-extension> <label> <seconds>
make_video() {
  local base=$1 label=$2 secs=$3 key
  [ -f "$base.mkv" ] && return 0
  key=$(echo "$base" | md5sum | cut -c1-8)
  write_srt "$secs" "Subtitle" > "$TMP/$key.en.srt"
  write_srt "$secs" "Subtítulo" > "$base.es.srt"
  write_chapters "$secs" > "$TMP/$key.chapters"
  local text=${label//:/\\:}; text=${text//\'/}
  ffmpeg -nostdin -loglevel error -y \
    -f lavfi -i "testsrc2=size=640x360:rate=24:duration=$secs" \
    -f lavfi -i "sine=frequency=440:duration=$secs" \
    -f lavfi -i "sine=frequency=660:duration=$secs" \
    -i "$TMP/$key.en.srt" -i "$TMP/$key.chapters" \
    -map 0:v -map 1:a -map 2:a -map 3:s -map_metadata 4 -map_chapters 4 \
    -vf "drawtext=fontfile=$FONT:text='$text':fontsize=28:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=12:x=(w-tw)/2:y=h-60" \
    -c:v libx264 -preset veryfast -crf 34 -pix_fmt yuv420p \
    -c:a aac -b:a 48k -ac 2 -c:s srt \
    -metadata:s:a:0 language=eng -metadata:s:a:0 title="English Stereo" \
    -metadata:s:a:1 language=spa -metadata:s:a:1 title="Español Stereo" \
    -metadata:s:s:0 language=eng -metadata:s:s:0 title="English" \
    "$base.mkv"
}
export -f make_video write_srt write_chapters
export TMP FONT

jobs_file=$TMP/jobs
: > "$jobs_file"
for m in "${MOVIES[@]}"; do
  mkdir -p "$OUT/movies/$m"
  printf '%s\t%s\t%s\n' "$OUT/movies/$m/$m" "$m" "$MOVIE_SECONDS" >> "$jobs_file"
done
for entry in "${SHOWS[@]}"; do
  show=${entry%%|*}; seasons=${entry#*|}; name=${show% (*}
  for s in $seasons; do
    season=${s%%:*}; count=${s#*:}
    dir=$(printf '%s/shows/%s/Season %02d' "$OUT" "$show" "$season"); mkdir -p "$dir"
    for e in $(seq 1 "$count"); do
      ep=$(printf 'S%02dE%02d' "$season" "$e")
      printf '%s\t%s\t%s\n' "$dir/$name $ep" "$name $ep" "$EPISODE_SECONDS" >> "$jobs_file"
    done
  done
done
tr '\n' '\0' < "$jobs_file" | xargs -0 -P "$JOBS" -I{} bash -c 'IFS=$'"'"'\t'"'"' read -r b l s <<< "$1"; make_video "$b" "$l" "$s"' _ {}

for entry in "${ALBUMS[@]}"; do
  IFS='|' read -r artist album tracks <<< "$entry"
  year=${album##*(}; year=${year%)}; title=${album% (*}
  dir="$OUT/music/$artist/$album"; mkdir -p "$dir"
  n=0
  IFS=';' read -ra list <<< "$tracks"
  for t in "${list[@]}"; do
    n=$((n + 1)); file=$(printf '%s/%02d - %s.mp3' "$dir" $n "${t//\//-}")
    [ -f "$file" ] && continue
    ffmpeg -nostdin -loglevel error -y -f lavfi -i "sine=frequency=$((220 + n * 55)):duration=45" \
      -c:a libmp3lame -b:a 64k -ac 2 \
      -metadata artist="$artist" -metadata album_artist="$artist" -metadata album="$title" \
      -metadata title="$t" -metadata track="$n/${#list[@]}" -metadata date="$year" "$file"
  done
  # Synced lyrics for the first track (original placeholder text, timed every 5 s).
  first=$(printf '%s/01 - %s.lrc' "$dir" "${list[0]//\//-}")
  if [ ! -f "$first" ]; then
    {
      printf '[ar:%s]\n[al:%s]\n[ti:%s]\n' "$artist" "$title" "${list[0]}"
      i=0
      for line in "Test lyric line one" "The second line arrives on time" "Line three keeps the beat" \
        "Four lines in and still in sync" "Halfway through the test tone" "Six, and the highlight moves" \
        "Seven lines of placeholder text" "Last line, back to the top"; do
        printf '[00:%02d.00]%s\n' $((i * 5)) "$line"; i=$((i + 1))
      done
    } > "$first"
  fi
done
# One real-bitrate film for quality testing: 1080p at ~14 Mbps with Dolby Digital 5.1 and a commentary track.
BIG="$OUT/movies/Big Buck Bunny (2008)"; mkdir -p "$BIG"
[ -f "$BIG/Big Buck Bunny (2008).mkv" ] || ffmpeg -nostdin -loglevel error -y \
  -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=60" -f lavfi -i "sine=frequency=440:duration=60" \
  -f lavfi -i "sine=frequency=880:duration=60" -map 0:v -map 1:a -map 2:a \
  -c:v libx264 -preset veryfast -b:v 14M -maxrate 16M -bufsize 16M -pix_fmt yuv420p -c:a ac3 -b:a 384k -ac 6 \
  -metadata:s:a:0 language=eng -metadata:s:a:0 title="English 5.1" \
  -metadata:s:a:1 language=eng -metadata:s:a:1 title="Commentary" "$BIG/Big Buck Bunny (2008).mkv"
du -sh "$OUT"
