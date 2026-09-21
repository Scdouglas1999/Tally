#!/bin/bash
# Builds signed release APKs of JellyTV for Android TV; with --publish also creates the GitHub release that
# installed apps update themselves from.
# The signing key lives OUTSIDE the repository: ~/.config/jellytv/release.jks + release.env
# (KEY_ALIAS, KEY_PASSWORD, KEY_STORE_PASSWORD). Keep a backup of both: an APK signed with a
# different key cannot update an installed one.
set -euo pipefail
cd "$(dirname "$0")/.."
PUBLISH=0; [ "${1:-}" = "--publish" ] && PUBLISH=1
KEYDIR="${JELLYTV_KEYDIR:-$HOME/.config/jellytv}"
[ -f "$KEYDIR/release.jks" ] && [ -f "$KEYDIR/release.env" ] || { echo "missing $KEYDIR/release.jks or release.env" >&2; exit 1; }
if [ $PUBLISH = 1 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then echo "commit your changes first: the version is derived from git" >&2; exit 1; fi
set -a; . "$KEYDIR/release.env"; set +a
export SIGNING_KEY="$(base64 -w0 "$KEYDIR/release.jks")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

VERSION="$(git describe --tags --long --match='v*')"          # v1.0.8-25-gabc1234  (what the app calls itself)
./gradlew :app:assembleDefaultRelease
rm -f app/ci.keystore

OUT=jellytv/out; rm -rf "$OUT"; mkdir -p "$OUT"
SRC=app/build/outputs/apk/default/release
for abi in arm64-v8a armeabi-v7a x86_64; do cp "$SRC"/*-"$abi".apk "$OUT/Wholphin-release-$abi.apk"; done
cp "$SRC"/*-universal.apk "$OUT/Wholphin-release.apk"
cp "$SRC"/*-universal.apk "$OUT/JellyTV.apk"
ls -lh "$OUT"

if [ $PUBLISH = 1 ]; then
  TAG="jtv-${VERSION#v}"
  git tag -f "$TAG" && git push -f origin "$TAG" jellytv
  NOTES="${JELLYTV_NOTES:-JellyTV for Android TV $VERSION}"
  gh release create "$TAG" "$OUT"/*.apk --repo Scdouglas1999/jellytv-android --title "$VERSION" --notes "$NOTES" --latest
  echo "published $VERSION"
fi
