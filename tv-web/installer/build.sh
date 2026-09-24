#!/usr/bin/env bash
# Builds "Tally for Samsung", the program that installs Tally on a Samsung TV, for every desktop OS from this one
# source (a self-contained single-file .NET app: nothing to install first):
#   dist/Tally-Samsung-Installer-windows.exe      Windows 10/11, x64 (also runs on Arm64 Windows)
#   dist/Tally-Samsung-Installer-linux            Linux x64
#   dist/Tally-Samsung-Installer-macos-arm64      macOS on Apple silicon
#   dist/Tally-Samsung-Installer-macos-x64        macOS on Intel
# The tests run first. Usage: tv-web/installer/build.sh [win-x64|linux-x64|osx-arm64|osx-x64 ...]
# Environment: DOTNET (default ~/.dotnet/dotnet), TALLY_VERSION (x.y.z, the program's version; default 1.0.0).
set -euo pipefail
cd "$(dirname "$0")"
DOTNET="${DOTNET:-$HOME/.dotnet/dotnet}"; command -v "$DOTNET" >/dev/null || DOTNET=dotnet
VERSION="${TALLY_VERSION:-1.0.0}"
TARGETS=("$@"); [[ ${#TARGETS[@]} -gt 0 ]] || TARGETS=(win-x64 linux-x64 osx-arm64 osx-x64)

"$DOTNET" test tests -c Release --nologo -v q -p:UseSharedCompilation=false

mkdir -p dist
for rid in "${TARGETS[@]}"; do
  case "$rid" in
    win-x64) name=Tally-Samsung-Installer-windows.exe ;;
    linux-x64) name=Tally-Samsung-Installer-linux ;;
    osx-arm64) name=Tally-Samsung-Installer-macos-arm64 ;;
    osx-x64) name=Tally-Samsung-Installer-macos-x64 ;;
    *) echo "unknown target $rid" >&2; exit 2 ;;
  esac
  out="bin/publish/$rid"
  rm -rf "$out"
  "$DOTNET" publish src -c Release -r "$rid" -p:Version="$VERSION" -p:UseSharedCompilation=false -o "$out" --nologo -v q
  exe="$out/Tally-Samsung-Installer"; [[ "$rid" == win-* ]] && exe="$exe.exe"
  cp "$exe" "dist/$name"
  chmod +x "dist/$name"
done
ls -lh dist
