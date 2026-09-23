#!/bin/bash
# seed-dev-server.sh <server-url> <admin-token> [media-root-inside-container]
#
# Points a dev Jellyfin at the library built by make-test-library.sh (Movies, Shows, Music with
# automatic TMDb collections and trickplay), waits for the scan, then gives the admin user a
# believable history so home rows are populated: a few films watched, two in progress, Breaking Bad
# S01E01-E03 watched (Next Up = E04), Bluey half-way through season 1, and some favorites.
set -euo pipefail
B=${1:?server url}; TOK=${2:?admin token}; ROOT=${3:-/media}
H="Authorization: MediaBrowser Token=\"$TOK\""; J='Content-Type: application/json'
api() { curl -sf -H "$H" -H "$J" "$@"; }

existing=$(api "$B/Library/VirtualFolders" | python3 -c "import sys,json;print('|'.join(f['Name'] for f in json.load(sys.stdin)))")
add_library() { # name type path
  case "|$existing|" in *"|$1|"*) echo "library $1 exists"; return;; esac
  api -X POST "$B/Library/VirtualFolders?name=$1&collectionType=$2&refreshLibrary=false" -d "{
    \"LibraryOptions\": {
      \"PathInfos\": [{\"Path\": \"$3\"}],
      \"AutomaticallyAddToCollection\": true,
      \"EnableTrickplayImageExtraction\": true,
      \"ExtractTrickplayImagesDuringLibraryScan\": true,
      \"EnableChapterImageExtraction\": true,
      \"ExtractChapterImagesDuringLibraryScan\": true,
      \"SaveLyricsWithMedia\": false
    }}" > /dev/null
  echo "library $1 added"
}
add_library Movies movies "$ROOT/movies"
add_library Shows tvshows "$ROOT/shows"
add_library Music music "$ROOT/music"
api -X POST "$B/Library/Refresh" > /dev/null

echo -n "scanning"
sleep 5
for _ in $(seq 1 120); do
  running=$(api "$B/ScheduledTasks?isHidden=false" | python3 -c "
import sys,json
print(any(t['State']!='Idle' and t['Key'] in ('RefreshLibrary',) for t in json.load(sys.stdin)))")
  [ "$running" = "False" ] && break; echo -n .; sleep 5
done
echo
USER=$(api "$B/Users/Me" | python3 -c "import sys,json;print(json.load(sys.stdin)['Id'])")
id_of() { # name type
  api "$B/Items?userId=$USER&recursive=true&includeItemTypes=$2&searchTerm=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1")&limit=1" |
    python3 -c "import sys,json;i=json.load(sys.stdin)['Items'];print(i[0]['Id'] if i else '')"
}
played() { local id; id=$(id_of "$1" "$2"); [ -n "$id" ] && api -X POST "$B/UserPlayedItems/$id?userId=$USER" > /dev/null && echo "played: $1"; }
favorite() { local id; id=$(id_of "$1" "$2"); [ -n "$id" ] && api -X POST "$B/UserFavoriteItems/$id?userId=$USER" > /dev/null && echo "favorite: $1"; }
progress() { # name type percent
  local id ticks; id=$(id_of "$1" "$2"); [ -n "$id" ] || return 0
  ticks=$(api "$B/Items/$id?userId=$USER" | python3 -c "import sys,json;print(int(json.load(sys.stdin).get('RunTimeTicks',0)*$3/100))")
  api -X POST "$B/UserItems/$id/UserData?userId=$USER" -d "{\"PlaybackPositionTicks\": $ticks, \"Played\": false, \"LastPlayedDate\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /dev/null
  echo "in progress ($3%): $1"
}
episode_id() { # series-name season episode
  local sid; sid=$(id_of "$1" Series); [ -n "$sid" ] || return 0
  api "$B/Shows/$sid/Episodes?userId=$USER&season=$2" | python3 -c "
import sys,json
for e in json.load(sys.stdin)['Items']:
    if e.get('IndexNumber')==$3: print(e['Id'])"
}
for f in "Toy Story" "The Matrix" "Jurassic Park" "Back to the Future" "Spirited Away" "Up"; do played "$f" Movie; done
progress "Inception" Movie 40
progress "Dune" Movie 65
for e in 1 2 3; do id=$(episode_id "Breaking Bad" 1 $e); [ -n "$id" ] && api -X POST "$B/UserPlayedItems/$id?userId=$USER" > /dev/null && echo "played: Breaking Bad S01E0$e"; done
for e in 1 2 3; do id=$(episode_id "Bluey" 1 $e); [ -n "$id" ] && api -X POST "$B/UserPlayedItems/$id?userId=$USER" > /dev/null && echo "played: Bluey S01E0$e"; done
for f in "Interstellar" "Paddington 2" "Arrival"; do favorite "$f" Movie; done
favorite "Severance" Series
api "$B/Items/Counts" && echo

# A year of history for "Your Year": more titles watched, spread over 2025 and this year, a few rewatched.
python3 - "$B" "$TOK" "$USER" <<'PY'
import json, random, sys, urllib.request
base, tok, user = sys.argv[1:4]
hdr = {"Authorization": f'MediaBrowser Token="{tok}"', "Content-Type": "application/json"}
def call(path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=hdr, method="GET" if body is None else "POST")
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else None
rnd = random.Random(2026)
items = call(f"/Items?userId={user}&recursive=true&includeItemTypes=Movie,Episode&sortBy=SortName&enableUserData=true")["Items"]
year = __import__("datetime").date.today().year
for it in items:
    if it["UserData"].get("PlaybackPositionTicks"):
        continue  # keep the in-progress ones for Continue Watching
    if it["Type"] == "Episode" and it.get("SeriesName") in ("Severance", "The Last of Us"):
        continue  # leave something unwatched to find
    if rnd.random() < 0.35 and not it["UserData"].get("Played"):
        continue
    when_year = year if rnd.random() < 0.8 else year - 1
    month = rnd.randint(1, 12 if when_year < year else __import__("datetime").date.today().month)
    today = __import__("datetime").date.today()
    day = rnd.randint(1, 28 if (when_year, month) != (today.year, today.month) else max(1, today.day - 1))
    plays = 1 + (rnd.random() < 0.2) + (rnd.random() < 0.05)
    call(f"/UserItems/{it['Id']}/UserData?userId={user}", {"Played": True, "PlayCount": plays,
         "LastPlayedDate": f"{when_year}-{month:02d}-{day:02d}T{rnd.randint(17,23):02d}:{rnd.randint(0,59):02d}:00Z"})
print("history spread over", year - 1, "and", year)
PY
