#!/usr/bin/env bash
# Creates the Tizen author certificate and the "tally" security profile that scripts/package-tizen.sh signs with
# (a Tizen author certificate + Tizen's default public distributor certificate: what Samsung TVs on Tizen 5.5-6.5,
# i.e. 2020-2022 models, accept for Developer Mode installs; Tizen 7+ needs a Samsung certificate, see README.md).
#
# KEEP THE AUTHOR CERTIFICATE: a TV only accepts an update signed with the same author certificate as the installed
# app ("Author certificate not match" otherwise, and the app must be uninstalled first). It is written to
# $TALLY_TIZEN_KEYS (default ~/.tally/tizen) with its password; back that folder up.
set -euo pipefail
TIZEN="${TIZEN_CLI:-$HOME/tools/tizen-studio/tools/ide/bin/tizen}"
KEYS="${TALLY_TIZEN_KEYS:-$HOME/.tally/tizen}"
PROFILE="${1:-tally}"
mkdir -p "$KEYS" && chmod 700 "$KEYS"
if [[ ! -f "$KEYS/author.p12" ]]; then
  PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20 || true)"
  printf '%s\n' "$PASSWORD" > "$KEYS/author.password" && chmod 600 "$KEYS/author.password"
  "$TIZEN" certificate -a tally -p "$PASSWORD" -c US -n "Tally" -o "Tally" -f tally-author -- "$KEYS"
  found="$(find "$KEYS" -name tally-author.p12 2>/dev/null | head -1 || true)"
  [[ -n "$found" ]] || { echo "tizen certificate did not write tally-author.p12" >&2; exit 1; }
  [[ "$found" == "$KEYS/author.p12" ]] || mv "$found" "$KEYS/author.p12"
  chmod 600 "$KEYS/author.p12"
  echo "created $KEYS/author.p12 (password in $KEYS/author.password)"
fi
"$TIZEN" security-profiles add -n "$PROFILE" -a "$KEYS/author.p12" -p "$(cat "$KEYS/author.password")" -f
"$TIZEN" security-profiles list
