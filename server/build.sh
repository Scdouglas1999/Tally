#!/bin/bash
# Builds and tests the plugin for both supported servers and lays out installable folders and zips:
#   dist/jf10.10/  + dist/Tally-server-<version>-jf10.10.zip   Jellyfin 10.10  (.NET 8)
#   dist/jf10.11/  + dist/Tally-server-<version>-jf10.11.zip   Jellyfin 10.11  (.NET 9)
#   dist/jf12/     + dist/Tally-server-<version>-jf12.zip      Jellyfin 12     (.NET 10)
# Each build has its own plugin version: <version>.10, <version>.11 and <version>.12 (the Jellyfin line), so Jellyfin's
# updater replaces a build with the right one after a Jellyfin upgrade. Each zip is what Jellyfin's plugin catalog
# downloads and what the installers put in plugins/Tally_<plugin version>/.
# Usage: ./build.sh [10|11|12]   (default: all three)
# Environment: TALLY_VERSION (x.y.z, default from Directory.Build.props), TALLY_CHANGELOG (one line for meta.json).
set -euo pipefail
cd "$(dirname "$0")"
DOTNET="${DOTNET:-$HOME/.dotnet/dotnet}"; command -v "$DOTNET" >/dev/null || DOTNET=dotnet
VERSION="${TALLY_VERSION:-$(sed -n 's:.*<TallyVersion[^>]*>\(.*\)</TallyVersion>.*:\1:p' Directory.Build.props)}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "TALLY_VERSION must look like 2.0.0, not '$VERSION'" >&2; exit 1; }
STAMP="$(date -u +%Y-%m-%dT%H:%M:%S.0000000Z)"
export CHANGELOG="${TALLY_CHANGELOG:-Tally $VERSION}"

# line: the -p:JellyfinLine value; abi: the name in the zip and folder; target: the lowest Jellyfin it installs on;
# rev: the fourth part of the plugin version
build() {
  local line=$1 abi=$2 target=$3 rev=$4; shift 4
  "$DOTNET" test Tally.Tests -c Release -p:JellyfinLine=$line -p:TallyVersion=$VERSION --nologo -v q
  local pub="Jellyfin.Plugin.Tally/bin/jf$line/publish"
  rm -rf "$pub"
  "$DOTNET" publish Jellyfin.Plugin.Tally -c Release -p:JellyfinLine=$line -p:TallyVersion=$VERSION -o "$pub" --nologo -v q
  local out="dist/jf$abi" zip="dist/Tally-server-$VERSION-jf$abi.zip"
  rm -rf "$out" "$zip"; mkdir -p "$out"
  # Only what the server does not already ship. 10.11 and 12 dropped Microsoft.Bcl.AsyncInterfaces, which Playwright
  # needs.
  for f in Jellyfin.Plugin.JellyTV.dll AngleSharp.dll Microsoft.Playwright.dll "$@"; do cp "$pub/$f" "$out/"; done
  # licenses of the third-party files inside the plugin (hls.js is served to browsers; IBM Plex is in the DLL)
  cp Jellyfin.Plugin.Tally/Web/hls.js-LICENSE.txt Jellyfin.Plugin.Tally/Web/plex-OFL.txt "$out/"
  python3 - "$out/meta.json" "$VERSION.$rev" "$target" "$STAMP" <<'PY'
import json, os, sys
meta = json.load(open("meta.template.json"))
meta.update(version=sys.argv[2], targetAbi=sys.argv[3], timestamp=sys.argv[4], changelog=os.environ["CHANGELOG"])
json.dump(meta, open(sys.argv[1], "w"), indent=2)
PY
  (cd "$out" && python3 -m zipfile -c "../$(basename "$zip")" *)
  # the zips never carry the Playwright driver (.playwright/, platform specific): the plugin downloads its host's
  TALLY_REQUIRE_DIST=1 "$DOTNET" test Tally.Tests -c Release -p:JellyfinLine=$line -p:TallyVersion=$VERSION --no-build \
    --filter "FullyQualifiedName~ReleaseZipTests" --nologo -v q
  echo "built $zip  ($(strings -e l "$out/Jellyfin.Plugin.JellyTV.dll" | grep -m1 -E "^$VERSION\+b[0-9]+"), md5 $(md5sum "$zip" | cut -d' ' -f1))"
}

want="${1:-all}"
case "$want" in all|10) build 10 10.10 10.10.0.0 10 ;; esac
case "$want" in all|11) build 11 10.11 10.11.0.0 11 Microsoft.Bcl.AsyncInterfaces.dll ;; esac
case "$want" in all|12) build 12 12 12.1.0.0 12 Microsoft.Bcl.AsyncInterfaces.dll ;; esac
