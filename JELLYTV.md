# JellyTV for Android TV — engineering rules

This repository is a fork of **Wholphin** (GPL-2.0). It is Wholphin, unchanged, plus one native section
called **JellyTV**: a live-sports front end backed by the `Jellyfin.Plugin.JellyTV` server plugin.
Full design: `docs/native-apps-architecture.md` in the plugin repo. This file is the short version that
every change must obey.

## The one rule

Upstream ships often and this fork is rebased onto every release. **Every line you change in an upstream
file is a future merge conflict.** Therefore:

1. All JellyTV code lives under `app/src/main/java/com/github/damontecres/wholphin/jellytv/`
   (tests: `app/src/test/java/com/github/damontecres/wholphin/jellytv/`, fixtures
   `app/src/test/resources/jellytv/`, strings `app/src/main/res/values/strings_jellytv.xml`,
   fonts `app/src/main/res/font/ibm_plex_*`).
2. Upstream files may be edited ONLY at the seams listed below, only by the task that owns that seam, and
   every edited region is wrapped in `// JELLYTV: begin` … `// JELLYTV: end`.
3. Never reformat, reorder imports in, or "tidy" an upstream file. Never touch `strings.xml`
   (Weblate rewrites it) — new strings go in `strings_jellytv.xml`.
4. Never add a Room entity/migration (the DB is versioned upstream). Persist with `KeyValueService`.
5. Never add a Gradle dependency without being told to. Everything needed is already there:
   OkHttp, kotlinx.serialization, Coil 3, Hilt, Compose for TV (`androidx.tv.material3`), Media3.

### Seams (the complete list)

| Seam | File | What |
|---|---|---|
| W1 | `ui/nav/Destination.kt` | new `Destination` subclasses |
| W2 | `ui/nav/DestinationContent.kt` | their `when` branches |
| W3 | `ui/nav/NavDrawer.kt` | `NavDrawerItem.JellyTv` + the three `when` branches |
| W4 | `services/NavDrawerService.kt` | add the item to `builtins` |
| W5 | `services/ServerEventListener.kt` | handle server-pushed `PlayMessage` |
| W6 | `app/build.gradle.kts`, `res/values/strings.xml` (`app_name`, `app_name_long` ONLY), launcher art | identity |
| W7 | `MainActivity.kt` | inject `JellyTvUpdatePrompt`, call it after upstream's update check |
| W8 | `preferences/AppPreference.kt` (`UpdateUrl.defaultValue` ONLY) | self-update from this fork's releases |

## Releases and self-update

Upstream's updater is kept and pointed at this fork (W8). It reads the GitHub release **name** as the version
(`v<git describe>`, e.g. `v1.0.8-25-gabc1234`) and downloads the asset `Wholphin-release-<abi>.apk`, so release
assets keep upstream's names; `JellyTV.apk` (universal) is an extra copy for the permanent install link
`https://github.com/Scdouglas1999/jellytv-android/releases/latest/download/JellyTV.apk`.
Release git tags are `jtv-*`: they must NOT match `v*`/`p*`, which `app/build.gradle.kts` uses for the version.
`jellytv/release.sh` builds and signs; `jellytv/release.sh --publish` also creates the GitHub release.

## Conventions (match the surrounding code)

- Kotlin, 4-space indent, trailing commas, ktlint 1.8 style as in the rest of the repo.
- DI: constructor injection. `@Singleton class Foo @Inject constructor(...)`; view models are
  `@HiltViewModel class X @Inject constructor(...) : ViewModel()`; no new Hilt modules.
  Qualifiers already exist: `@AuthOkHttpClient OkHttpClient` (adds the user's Authorization header),
  `@IoDispatcher`, `@DefaultCoroutineScope`. Use-site targets are written `@param:AuthOkHttpClient`.
- The server base URL is `ApiClient.baseUrl` (inject `org.jellyfin.sdk.api.client.ApiClient`). It can be null
  when signed out.
- Logging: `Timber`. No `println`, no `Log`.
- Coroutines: never block; IO on the injected IO dispatcher.
- Compose: **Compose for TV** (`androidx.tv.material3.*`) for focusable surfaces, exactly as upstream pages do.
  Every interactive element must be reachable and operable with a D-pad. No touch-only affordances.
- No emoji in UI. No hard-coded user-visible strings: use `strings_jellytv.xml`.

## The server contract

`jellytv/api/JellyTvModels.kt` is the contract and is **read-only for you** — if you think it is wrong, stop
and say so instead of editing it. Decode with the provided `JellyTvJson`. Endpoints (all relative to
`ApiClient.baseUrl`, all need the auth header, all JSON):

| Call | Returns |
|---|---|
| `GET /JellyTV/Client/v1/info` | `JtvInfo`. **HTTP 404 = the server has no JellyTV plugin → the JellyTV section must not appear at all.** 401 = signed out. |
| `GET /JellyTV/Client/v1/board?since={lastEventId}` | `JtvBoard`. Omit `since` on the first call (returns no events); afterwards pass the highest event id seen. |
| `GET /JellyTV/Client/v1/channels/{id}` | `JtvChannel`, fresh. |
| `GET` / `PUT /JellyTV/Client/v1/settings` | free-form JSON object shared with the web UI. Read-modify-write: unknown keys MUST be preserved. Known keys: `favorites: [channelId]`, `hideScores: bool`, `lastChannel: string`. |

`hlsPath` and `cardPath` are root-relative: prefix with `ApiClient.baseUrl`. Images load through the app's
existing Coil loader (already authenticated) — a plain `AsyncImage(model = url)` works.
A realistic payload is in `app/src/test/resources/jellytv/board-sample.json`.

**There is no "heat" in this app.** Ignore `heat`/`tags` if you see them in JSON. Board order is:
favorites first, then by league, live before upcoming before final, then by start time.

## Design system (same as the JellyTV web UI; TV-sized)

Flat, near-black, hairline rules, square corners (radius 0 everywhere), one accent. No gradients as
decoration, no blur, no glow, no drop shadows, no rounded cards.

| Token | Value | Use |
|---|---|---|
| `ground` | `#0E0F0E` | screen background |
| `groundRaised` | `#1B1C1A` | focused card ground |
| `screen` | `#050505` | video / monitor faces |
| `labelBar` | `#000000` | channel label bars |
| `rule` | `#2A2C2A` | structural hairlines (1dp) |
| `ruleStrong` | `#3A3D38` | control / card borders (1dp), idle indicator squares |
| `text` | `#E3E5DE` | primary text |
| `textSecondary` | `#A9ADA3` | secondary text |
| `muted` | `#8B9084` | captions, labels |
| `accent` | `#FFB000` | focus frame, "now", active tab. Text on accent is `#0E0F0E` |
| `live` | `#FF3B30` | LIVE only (and failure). Small red text uses `#FF6A61` |

Type: **IBM Plex Sans** (`R.font.ibm_plex_sans_regular/medium/semibold/bold`) for reading; **IBM Plex Mono**
(`R.font.ibm_plex_mono_regular/medium/semibold`) for clocks, scores, league/channel labels and anything
instrument-like. Mono labels are UPPERCASE with 1.5–2.5sp letter spacing. Minimum text size 14sp; body 18sp+.

Focus: exactly one element is focused. Focused = 3dp `accent` border + `groundRaised` ground; NO scale
animation, no glow. Unfocused = 1dp `ruleStrong` border. Safe margins: 48dp left/right, 27dp top/bottom.
Motion: focus/colour changes ≤120ms, nothing bounces.

Reference mockups (1920×1080): Games = top bar, a large "focused game" panel (teams, big mono scores, clock,
situation, last play, channel label bar, key hints), then horizontally scrolling rows of game cards, one row
per league+state ("NFL / LIVE"). Card = league + clock strip, two team lines (logo, short name, mono score),
black label bar with an indicator square + channel name.

## Definition of done for any task

- Only files you were told to create/edit are touched (`git status` shows nothing else).
- It compiles: `./gradlew :app:compileDefaultDebugKotlin` — **do not run Gradle unless your task says you may**;
  builds are run centrally because several tasks share this machine.
- Unit tests you add pass under `./gradlew :app:testDefaultDebugUnitTest --tests "…jellytv…"` (same caveat).
- You end by printing a short report: files created/changed, anything you were unsure about, anything you
  deliberately left as TODO. Never claim something works that you did not run.
