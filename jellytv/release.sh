#!/bin/bash
# Builds signed release APKs of JellyTV for Android TV.
# The signing key lives OUTSIDE the repository: ~/.config/jellytv/release.jks + release.env
# (KEY_ALIAS, KEY_PASSWORD, KEY_STORE_PASSWORD). Keep a backup of both: an APK signed with a
# different key cannot update an installed one.
set -euo pipefail
cd "$(dirname "$0")/.."
KEYDIR="${JELLYTV_KEYDIR:-$HOME/.config/jellytv}"
[ -f "$KEYDIR/release.jks" ] && [ -f "$KEYDIR/release.env" ] || { echo "missing $KEYDIR/release.jks or release.env" >&2; exit 1; }
set -a; . "$KEYDIR/release.env"; set +a
export SIGNING_KEY="$(base64 -w0 "$KEYDIR/release.jks")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew :app:assembleDefaultRelease "$@"
rm -f app/ci.keystore
ls -1 app/build/outputs/apk/default/release/*.apk
