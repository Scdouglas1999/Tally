#!/usr/bin/env bash
# One command from a Linux PC on the TV's network: build the Tally shell with the Jellyfin address stamped in, sign it,
# install it on a Samsung TV in Developer Mode and start it.
#
#   scripts/install-tizen.sh <tv-ip> --server http://<jellyfin-address>:8096 [--profile <name>]
#
# Before: on the TV, Apps → App Settings (or the Apps panel) → type 1 2 3 4 5 → Developer mode On → "Host PC IP" =
# this PC's LAN address → restart the TV (hold the power button; unplug it if Instant On is enabled).
#
# Signing (see ARCHITECTURE.md, Packaging): Tizen 5.5-6.5 TVs (2020-2022) accept the "tally" profile
# (scripts/tizen-certificate.sh, no Samsung account). Tizen 7+ (2023 on, and 2023 sets upgraded to Tizen 8/9) need a
# Samsung certificate that lists this TV's DUID: create it once with Tizen Studio's Certificate Manager (Samsung
# account, the DUID this script prints) as a profile, and pass --profile <its name>.
set -euo pipefail
cd "$(dirname "$0")/.."
TV="${1:-}"
[[ -n "$TV" && "$TV" != --* ]] || { sed -n '2,15p' "$0"; exit 2; }
shift
SERVER="" PROFILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
STUDIO="${TIZEN_STUDIO:-$HOME/tools/tizen-studio}"
SDB="$STUDIO/tools/sdb"
TIZEN="$STUDIO/tools/ide/bin/tizen"
APP_ID="TallyTVapp.Tally"
SERIAL="$TV:26101"

echo "Connecting to $TV (Developer Mode must point at this PC)…"
"$SDB" connect "$TV" >/dev/null
"$SDB" -s "$SERIAL" get-state >/dev/null 2>&1 || {
  echo "The TV did not accept the connection. Check Developer Mode is on with this PC's address, and restart the TV." >&2
  exit 1
}
VERSION="$("$SDB" -s "$SERIAL" capability 2>/dev/null | sed -n 's/^platform_version:\(.*\)$/\1/p' | tr -d '\r')"
DUID="$("$SDB" -s "$SERIAL" shell 0 getduid 2>/dev/null | tr -d '\r' | tail -1 || true)"
echo "Tizen ${VERSION:-unknown}; this TV's DUID: ${DUID:-unknown}"

if [[ -z "$PROFILE" ]]; then
  major="${VERSION%%.*}"
  if [[ "${major:-0}" =~ ^[0-9]+$ ]] && (( major >= 7 )); then
    echo "Tizen $VERSION needs a Samsung certificate listing DUID $DUID. Create one (Tizen Studio → Certificate Manager →" >&2
    echo "Samsung → TV, with that DUID), then run again with --profile <its profile name>." >&2
    exit 3
  fi
  PROFILE="tally"
  "$TIZEN" security-profiles list 2>/dev/null | grep -q "^$PROFILE " || scripts/tizen-certificate.sh "$PROFILE"
fi

if [[ -n "$SERVER" ]]; then
  scripts/package-tizen.sh --profile "$PROFILE" --server "$SERVER"
else
  scripts/package-tizen.sh --profile "$PROFILE"
fi
"$TIZEN" install -s "$SERIAL" -n Tally.wgt -- "$PWD/dist" || {
  echo "Install failed. 'Author certificate not match': uninstall Tally from the TV first (it was signed by another" >&2
  echo "certificate). 'Check certificate error': this TV needs a Samsung certificate (see above)." >&2
  exit 1
}
"$TIZEN" run -s "$SERIAL" -p "$APP_ID" || "$SDB" -s "$SERIAL" shell 0 was_execute "$APP_ID" || true
echo "Tally is installed. It opens on the TV now, and is in the Apps list from here on."
