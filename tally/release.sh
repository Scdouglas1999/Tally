#!/bin/bash
# Builds signed release APKs of Tally for Android TV; with --publish also creates the GitHub release that
# installed apps update themselves from.
# The signing key lives OUTSIDE the repository: ~/.config/tally/release.jks + release.env
# (KEY_ALIAS, KEY_PASSWORD, KEY_STORE_PASSWORD). Keep a backup of both: an APK signed with a
# different key cannot update an installed one.
set -euo pipefail
cd "$(dirname "$0")/.."
PUBLISH=0; [ "${1:-}" = "--publish" ] && PUBLISH=1
KEYDIR="${TALLY_KEYDIR:-$HOME/.config/tally}"
[ -f "$KEYDIR/release.jks" ] && [ -f "$KEYDIR/release.env" ] || { echo "missing $KEYDIR/release.jks or release.env" >&2; exit 1; }
if [ $PUBLISH = 1 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then echo "commit your changes first: the version is derived from git" >&2; exit 1; fi
set -a; . "$KEYDIR/release.env"; set +a
export CI=true   # upstream only signs "CI" builds; this is the only thing the flag changes
export SIGNING_KEY="$(base64 -w0 "$KEYDIR/release.jks")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# Tally's version line: a release is tagged tally-vMAJOR.MINOR.PATCH before it is built (git tag tally-v2.0.1), and
# the release is named vMAJOR.MINOR.PATCH, which is what installed apps compare against their own version.
# Without a tally-v tag this falls back to the old upstream numbering (v1.0.8-25-gabc1234).
TALLY_DESCRIBE="$(git describe --tags --long --match='tally-v*' 2>/dev/null || true)"
if [[ "$TALLY_DESCRIBE" =~ ^tally-(v[0-9]+\.[0-9]+\.[0-9]+)-([0-9]+)-g([0-9a-f]+)$ ]]; then
  if [ "${BASH_REMATCH[2]}" = 0 ]; then VERSION="${BASH_REMATCH[1]}"; TAG="tally-${BASH_REMATCH[1]}"
  else VERSION="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-g${BASH_REMATCH[3]}"; TAG=""; fi
else
  VERSION="$(git describe --tags --long --match='v*')"; TAG="tally-${VERSION#v}"
fi
if [ $PUBLISH = 1 ] && [ -z "$TAG" ]; then echo "tag the release first (git tag tally-vX.Y.Z): HEAD is $VERSION" >&2; exit 1; fi
# R8 on the release build no longer fits in the 2 GB upstream's gradle.properties gives the daemon
GRADLE_MEM="-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8"
./gradlew "$GRADLE_MEM" :app:assembleDefaultRelease
rm -f app/ci.keystore

OUT=tally/out; rm -rf "$OUT"; mkdir -p "$OUT"
SRC=app/build/outputs/apk/default/release
for abi in arm64-v8a armeabi-v7a x86_64; do cp "$SRC"/*-"$abi".apk "$OUT/Wholphin-release-$abi.apk"; done
# the universal APK is the one without an ABI suffix
UNIVERSAL="$(ls "$SRC"/*.apk | grep -v -E -- '-(arm64-v8a|armeabi-v7a|x86_64)\.apk$')"
cp "$UNIVERSAL" "$OUT/Wholphin-release.apk"
cp "$UNIVERSAL" "$OUT/Tally.apk"
# JellyTV.apk: the name older plugin installs and Downloader short codes point at; keep publishing it
cp "$UNIVERSAL" "$OUT/JellyTV.apk"
ls -lh "$OUT"

# Store builds (no self-update, TV-only): an app bundle for Google Play, an APK for the Amazon Appstore.
# Same signing key as the sideloaded build, so the three can update over one another.
if [ "${1:-}" = "--stores" ] || [ "${2:-}" = "--stores" ]; then
  # one variant at a time: compiling two at once exhausts the 2 GB Kotlin daemon upstream configures
  ./gradlew "$GRADLE_MEM" :app:bundleAppstoreRelease
  ./gradlew "$GRADLE_MEM" :app:assembleFiretvRelease
  rm -f app/ci.keystore
  cp app/build/outputs/bundle/appstoreRelease/*.aab "$OUT/Tally-play.aab"
  cp "$(ls app/build/outputs/apk/firetv/release/*.apk | grep -v -E -- '-(arm64-v8a|armeabi-v7a|x86_64)\.apk$')" "$OUT/Tally-amazon.apk"
  ls -lh "$OUT"/Tally-play.aab "$OUT"/Tally-amazon.apk
fi

if [ $PUBLISH = 1 ]; then
  git tag -f "$TAG" && git push -f origin "refs/tags/$TAG" && git push origin main
  NOTES="${TALLY_NOTES:-Tally for Android TV $VERSION}"
  gh release create "$TAG" "$OUT"/Tally.apk "$OUT"/JellyTV.apk "$OUT"/Wholphin-release*.apk --repo Scdouglas1999/Tally --title "$VERSION" --notes "$NOTES" --latest
  echo "published $VERSION"
fi
