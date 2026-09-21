# JellyTV for Android TV

[Wholphin](https://github.com/damontecres/Wholphin) with one addition: a native **JellyTV** section for
live sports, backed by the `Jellyfin.Plugin.JellyTV` server plugin. Everything else (movies, shows, music,
playback settings) is Wholphin, unchanged. GPL-2.0, same as upstream.

The JellyTV entry appears in the navigation drawer only when the signed-in server has the plugin. Against any
other Jellyfin server this app behaves exactly like Wholphin.

## What it adds

- **Games**: every live and upcoming game as a card, with a large panel for the focused game (score, clock,
  situation, last play). OK watches it.
- **Player**: the normal Wholphin player plus a score bug and an "also on now" switcher.
- **Multiview**: up to four games at once; audio follows focus.
- **Play on TV**: the JellyTV web UI on a phone can start a game on this app.

## Install (sideload)

1. Download the APK for your device from Releases: `arm64-v8a` for almost every modern Android TV, Fire TV and
   Shield; `armeabi-v7a` for older 32-bit sticks.
2. Install it with your usual sideloading tool (Downloader, `adb install`, Send Files to TV).
3. It installs next to Wholphin and the official Jellyfin app; it does not replace either.

In-app updating is switched off in this fork. Update by installing a newer APK over the old one.

## Building

`./gradlew :app:assembleDefaultDebug` for a debug build. `jellytv/release.sh` builds signed release APKs; it
expects the signing key in `~/.config/jellytv/` (never in the repository).

## Maintaining the fork

The rules that keep rebases onto upstream releases cheap are in [`JELLYTV.md`](../JELLYTV.md): all JellyTV code
lives under one package, and upstream files are edited only at a short list of marked seams.
