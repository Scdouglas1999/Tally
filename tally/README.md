# Tally: maintainer notes

Tally is a fork of [Wholphin](https://github.com/damontecres/Wholphin) (GPL-2.0). The user-facing description is
the [root README](../README.md); the licensing record is [NOTICE.md](../NOTICE.md). This file is for whoever builds,
releases and rebases the fork. Tally's code is the package `io.github.scdouglas1999.tally`
(`app/src/main/java/io/github/scdouglas1999/tally/`), with `strings_tally*.xml`, the `TALLY` theme and the
`TALLY: begin/end` markers in Wholphin's files. A few names keep the earlier working name, JellyTV, because
changing them would break installed apps or servers: the application id `io.github.scoduglas1999.jellytv`, stored
preference keys, the plugin's `/JellyTV/` web paths and the `JellyTV.apk` release asset.

## Keeping close to Wholphin

The rules that keep rebases onto upstream releases cheap are in [`TALLY.md`](../TALLY.md): all Tally code
lives under one package, and Wholphin's files are edited only at a short list of marked seams, each listed there.
Each modified Wholphin file also carries a "Modified for Tally" notice at its top (required by GPL-2.0 §2a); a
new seam in a file that has no notice yet must add one. `app/src/main/res/values/strings.xml` cannot carry one
(Weblate rewrites it) and is listed in NOTICE.md instead. Locally, `upstream-main` tracks Wholphin's `main`.

The UI is redrawn screen by screen while the `TALLY` theme is selected: `io/github/scdouglas1999/tally/media/TallyRoutes.kt` decides
which destinations Tally draws, reusing Wholphin's view models. Design rules are in [`UI.md`](UI.md).

## Building

- Debug: `./gradlew :app:assembleDefaultDebug`.
- Release: `tally/release.sh` builds signed APKs into `tally/out/`; `--publish` also tags `jtv-<version>`, pushes
  and creates the GitHub release; `--stores` adds the Play bundle and Amazon APK (see [`store/`](store/)).
- Signing key: `~/.config/tally/release.jks` + `release.env`, never in the repository. Keep a backup: without
  it nobody can update their installed app.

## Release assets

| Asset | Used by |
|---|---|
| `Tally.apk` | the README's install link (`releases/latest/download/Tally.apk`) |
| `JellyTV.apk` | the same file under its old name, for plugin installs and Downloader short codes made before the rename |
| `Wholphin-release-<abi>.apk`, `Wholphin-release.apk` | the in-app updater, which Wholphin wrote to look for these names |

The updater reads the release *name* as the version; release tags are `jtv-*` so they never collide with the
`v*` tags Gradle derives `versionName`/`versionCode` from.

## Development helpers

- `dev/make-test-library.sh` builds a small media library of test-pattern files named after real titles, so a
  dev Jellyfin server fills in real metadata; `dev/seed-dev-server.sh` sets that server up.
- `dev/syncplay-peer.py` is a scripted second SyncPlay member for testing Watch together.
- `art/` holds the scripts that generate the TV banner and the README art.
