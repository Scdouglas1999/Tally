#!/usr/bin/env bash
# Builds the Tizen package (dist/Tally.wgt): the installed shell, signed with a Tizen Studio security profile.
#   scripts/package-tizen.sh [--server http://192.168.1.50:8096] [--profile tally]
# --server stamps a Jellyfin address into the package, so the TV opens straight to Quick Connect on first start.
# The profile must exist (scripts/tizen-certificate.sh creates the default "tally" one). TIZEN_CLI overrides the path
# of the `tizen` command (default ~/tools/tizen-studio/tools/ide/bin/tizen).
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER="" PROFILE="tally" BUNDLE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --bundle) BUNDLE="$2"; shift 2 ;;  # development: bundle from a dev machine (vite preview)
    --profile) PROFILE="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
TIZEN="${TIZEN_CLI:-$HOME/tools/tizen-studio/tools/ide/bin/tizen}"
[[ -x "$TIZEN" ]] || { echo "Tizen CLI not found at $TIZEN (install Tizen Studio CLI, see README.md)" >&2; exit 1; }
VERSION="$(node -p "require('./package.json').version")"
STAGE="dist/tizen"
rm -rf "$STAGE" && mkdir -p "$STAGE/fonts"
cp shell/shell.js shell/shell.css "$STAGE/"
cp shell/fonts/*.woff2 "$STAGE/fonts/"
cp ../IBM-PLEX-OFL.txt "$STAGE/fonts/OFL.txt"
cp shell/icons/icon-512.png "$STAGE/icon.png"
sed "s/@VERSION@/$VERSION/" shell/tizen/config.xml > "$STAGE/config.xml"
# AVPlay and the other Samsung product APIs come from webapis.js, which the TV provides at $WEBAPIS
sed 's#<!-- PLATFORM:.*-->#<script src="$WEBAPIS/webapis/webapis.js"></script>#' shell/index.html > "$STAGE/index.html"
node -e '
const [server, bundle] = process.argv.slice(1);
process.stdout.write("window.TALLY_SHELL_CONFIG = " + JSON.stringify({ platform: "tizen", server, bundle }, null, 2) + ";\n");
' "$SERVER" "$BUNDLE" > "$STAGE/config.js"
"$TIZEN" package -t wgt -s "$PROFILE" -o "$PWD/dist" -- "$PWD/$STAGE"
ls -la dist/*.wgt
