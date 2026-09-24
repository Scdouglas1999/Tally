# Tally: maintainer notes

Tally is a fork of [Wholphin](https://github.com/damontecres/Wholphin) (GPL-2.0). The user-facing description is
the [root README](../README.md); the licensing record is [NOTICE.md](../NOTICE.md). This file is for whoever builds,
releases and rebases the fork. Tally's code is the package `io.github.scdouglas1999.tally`
(`app/src/main/java/io/github/scdouglas1999/tally/`), with `strings_tally*.xml`, the `TALLY` theme and the
`TALLY: begin/end` markers in Wholphin's files. A few names keep the earlier working name, JellyTV, because
changing them would break installed apps or servers: the application id `io.github.scoduglas1999.jellytv`, stored
preference keys and the plugin's `/JellyTV/` web paths.

## Keeping close to Wholphin

The rules that keep rebases onto upstream releases cheap are in [`TALLY.md`](../TALLY.md): all Tally code
lives under one package, and Wholphin's files are edited only at a short list of marked seams, each listed there.
Each modified Wholphin file also carries a "Modified for Tally" notice at its top (required by GPL-2.0 §2a); a
new seam in a file that has no notice yet must add one. `app/src/main/res/values/strings.xml` cannot carry one
(Weblate rewrites it) and is listed in NOTICE.md instead. Locally, `upstream-main` tracks Wholphin's `main`.

Wholphin's project documents are Tally's own here: `README.md`, `CONTRIBUTING.md` and the `.github/` issue and pull
request templates are rewritten, and Wholphin's `DEVELOPMENT.md` and `Intents.md` live in `docs/` (as
`WHOLPHIN-DEVELOPMENT.md` and `INTENTS.md`). An upstream merge that touches those files conflicts; keep Tally's
version, and carry any real change to Wholphin's two guides over to their copies in `docs/`.

The UI is redrawn screen by screen while the `TALLY` theme is selected: `io/github/scdouglas1999/tally/media/TallyRoutes.kt` decides
which destinations Tally draws, reusing Wholphin's view models. Design rules are in [`UI.md`](UI.md).

## Building

- Debug: `./gradlew :app:assembleDefaultDebug`.
- Release: `tally/release.sh` builds the signed APKs and the server plugin release into `tally/out/` and prints what
  `--publish` would run. `--publish` (only on a `tally-vX.Y.Z` tag) also pushes the tag, creates the GitHub release and
  then commits the plugin repository entry to `main`; `--stores` adds the Play bundle and Amazon APK (see
  [`store/`](store/)).
- Signing key: `~/.config/tally/release.jks` + `release.env`, never in the repository. Keep a backup: without
  it nobody can update their installed app.

## Release assets

| Asset | Used by |
|---|---|
| `Tally.apk` | what people download: the README's install link (`releases/latest/download/Tally.apk`); the updater's fallback |
| `Tally-arm64-v8a.apk`, `-armeabi-v7a.apk`, `-x86_64.apk` | the in-app updater from 2.0.2 on (smaller per-chip builds) |
| `Wholphin-release-<abi>.apk`, `Wholphin-release.apk` | TRANSITIONAL: the updater in installs from before 2.0.2, which knows only Wholphin's names. Drop them from `release.sh` once no device on the owner's server reports an older version (Dashboard → Devices, or `/Devices`: `AppVersion`) |
| `Tally-server-<version>-jf10.10.zip`, `-jf10.11.zip`, `-jf12.zip` | the server plugin: Jellyfin's plugin catalog (through `server/manifest.json`), the Docker Compose file and the Linux script download these |
| `Tally-Server-Setup.exe` | the Windows installer (the three zips are inside it) |
| `docker-compose.yml`, `install-linux.sh` | the Docker and Linux installs, with this release as their default version |

The updater reads the release *name* as the version; release tags are `tally-*` (earlier `jtv-*`) so they never collide
with the `v*` tags Gradle derives `versionName`/`versionCode` from.

## The server plugin

The plugin's source is in [`server/`](../server/). It was a separate, unpublished repository until Tally 2.0 and was
brought in as a single commit (its earlier history held private server details and was not carried over). It is built
with the .NET SDK, not Gradle:

- `server/build.sh` runs the tests and builds one zip per Jellyfin line (10.10, 10.11, 12) into `server/dist/`.
  Each zip is the plugin DLL, the libraries the server does not ship, and a `meta.json`.
- The plugin is versioned with the app. `release.sh` takes the version from the `tally-vX.Y.Z` tag (plugin
  `X.Y.Z.10`, `.11` and `.12`, one per Jellyfin line, so Jellyfin's updater moves a server to the right build after a
  Jellyfin upgrade); local builds use `TallyVersion` in `server/Directory.Build.props`. The changelog Jellyfin shows is the
  first line of `TALLY_NOTES`, or `TALLY_SERVER_CHANGELOG`.
- `server/install/windows/` is `Tally-Server-Setup.exe`, a WinForms program cross-built from Linux
  (`dotnet publish server/install/windows -c Release`, self-contained, win-x64). It embeds the three zips, so build
  the plugin first.
- `server/manifest.json` is the Jellyfin plugin repository, served from `main` through raw.githubusercontent.com.
  Every published version has one entry per build, pointing at that release's zips with their MD5. `release.sh`
  writes the new entries with `server/manifest.py` into `tally/out/manifest.json`; `--publish` copies it back and
  commits it, together with the new default version in `server/install/docker-compose.yml` and `install-linux.sh`,
  only after the GitHub release exists, so the catalog never points at a missing zip.
- On its first start the plugin adds that repository to the server's list (once; an admin who removes it is not
  overridden), so every install gets updates through Jellyfin.
- Test a plugin change on a real Jellyfin before releasing: the `jellyfin/jellyfin` Docker images (10.10.x, 10.11.x,
  12.1) with the zip unpacked into `config/plugins/Tally_<version>/`. `server/devtools/live-bed/` has local live
  streams for testing the live ladder.

## Development helpers

- `dev/make-test-library.sh` builds a small media library of test-pattern files named after real titles, so a
  dev Jellyfin server fills in real metadata; `dev/seed-dev-server.sh` sets that server up.
- `dev/syncplay-peer.py` is a scripted second SyncPlay member for testing Watch together.
- `art/` holds the scripts that generate the TV banner and the README art.
