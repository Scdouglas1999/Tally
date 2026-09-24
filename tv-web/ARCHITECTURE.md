# Tally TV (web): architecture

One TypeScript app for Samsung (Tizen), LG (webOS) and desktop browsers, with Tally's own screens (not
jellyfin-web), talking to Jellyfin and to the Tally plugin's Client API. Target: **2020+ TVs, Tizen 5.5+ (Chromium 69)
and webOS 5+ (Chromium 68)**. Samsung comes first (a friend's 2020+ Samsung needs it now); webOS is built from the same
code afterwards.

Status of this document: decisions made in tvweb-0 (September 24, 2026), with what was measured and what still
needs a real TV marked as such.

## 1. The decisions in one table

| Area | Decision | Why, in one line |
|---|---|---|
| Delivery | A small **installed shell** per platform loads the app **bundle from the Tally plugin** (`/JellyTV/TV/`) | One install; every plugin update updates every TV |
| UI | **Preact 10** + **TypeScript** (strict) | 4 KB runtime, React's model, fast enough on TV SoCs; DOM text and hairlines are the design |
| Focus | **Norigin spatial navigation core 4** (framework-free) + a 150-line Preact binding | Proven on TVs; geometry-based D-pad focus with focus groups, boundaries, saved focus |
| Build | **Vite 8** (Rolldown), one **classic IIFE script** + one stylesheet, syntax lowered to **chrome68**, core-js for built-ins | Module scripts need CORS on a `file://` TV page; Chromium 68 is the floor |
| Jellyfin | **@jellyfin/sdk 1.0** (MPL-2.0) over axios | Official, typed, Jellyfin 10.10 to 12 |
| Playback | `PlayerEngine` interface, three engines: **AVPlay** (Tizen), **webOS <video>**, **HTML5 + hls.js** (browsers) | Each platform's native pipeline where it has one |
| Design | Tally tokens on a **1920x1080 canvas**, IBM Plex from the app's `res/font`, focus = the amber frame | Same look as the Android app, measured against its screenshots |
| Tests | Vitest (logic), Playwright in Chromium 1920x1080 against the dev server (flows), Tizen emulator (AVPlay, shell) | Logic and flows run anywhere; platform parts need the emulator or a TV |

## 2. Delivery: installed shell + server-served bundle

```
 TV (installed once, ~75 KB)                     Jellyfin + Tally plugin (updated with the plugin)
 ┌──────────────────────────────┐   GET          ┌─────────────────────────────────────────────┐
 │ index.html  (local, file://) │── manifest ───▶│ /JellyTV/TV/manifest.json  (no-cache)        │
 │   webapis.js  (Tizen only)   │                │   { js: app.<hash>.js, css: app.<hash>.css,  │
 │   config.js   (stamped addr) │◀── app.*.js ───│     minShell: 1, version, revision }         │
 │   shell.js    (ES5)          │◀── app.*.css ──│ /JellyTV/TV/app.<hash>.js   (immutable, 1 y) │
 │ window.TallyShell ───────────┼─▶ bundle runs  │ /JellyTV/TV/assets/*.ttf    (immutable)      │
 └──────────────────────────────┘   in this page │ /JellyTV/TV/hls-1.7.3.min.js (browsers only) │
                                                 └─────────────────────────────────────────────┘
```

- **The shell** (`shell/`: `index.html`, `shell.js`, `shell.css`, two woff2 fonts, the platform manifest) is what gets
  installed. It remembers the server address (localStorage), or uses the one the installer stamped into
  `config.js`, or asks for one. It checks `/System/Info/Public`, fetches `/JellyTV/TV/manifest.json`, then adds the
  bundle's `<link>` and `<script>` to its own page and sets `window.TallyShell`. Errors get Tally-styled screens:
  server not answering (Try again / Change server), no TV app on the server ("install or update the Tally plugin"),
  a server that needs a newer shell ("reinstall"). BACK on those screens leaves the app.
- **The bundle runs inside the local page**, so on Tizen it keeps `tizen` and `webapis` (AVPlay, remote keys).
  Samsung documents that hosted apps (a remote *page*) lose the Tizen APIs, and allows external scripts in a packaged
  app; a remote *script* in the local page is the same page. To verify on the Tizen emulator/TV (section 12).
- **Contract** (`src/shell-contract/shell.ts`): `shellVersion`, `platform`, `serverUrl`, `bundleBase`,
  `changeServer(url|null)`, `reload()`, `exit()`, `started()`. Only additive changes; the bundle's manifest carries
  `minShell` and an old shell refuses a bundle that needs more (shows "reinstall").
- **Caching**: `manifest.json` and `index.html` are `no-cache`; everything else has a content hash (or the hls.js
  version) in its name and is `public, max-age=31536000, immutable`. A plugin update changes the manifest, the TV
  fetches the new files once.
- **Plugin side**: `server/build.sh` builds tv-web (`npm ci && npm test && npm run build`) and embeds
  `dist/bundle/` in the plugin DLL (`TvWeb/**` → `TallyTvWeb/<path>` resources); `Api/TvAppController.cs` serves them
  anonymously (the shell needs them before sign-in; they hold only code) with an explicit
  `Access-Control-Allow-Origin: *` (fonts are fetched cross-origin from the TV's `file://` page).
  `TALLY_SKIP_TV_WEB=1` builds a plugin without the TV app (never for a release). Verified on a throwaway Jellyfin
  10.10.6 container: manifest `no-cache`, bundle `immutable`, traversal 404, `/JellyTV/TV` redirects to the browser
  version, and the shell loaded the bundle from it and reached Quick Connect.
- **The same bundle in a browser**: `/JellyTV/TV/index.html` on any Tally server is a working desktop version
  (it defaults to the server that served it).
- **Offline**: nothing to do without the server (no downloads on TVs), so the shell shows the error screen with
  Try again. A cached copy of the bundle is not kept.
- **Security**: the bundle travels like every other request of the app (http on a LAN, https when the server has
  it). Someone who can tamper with that traffic can also read the Jellyfin token; nothing new. The endpoint is
  read-only static files.

## 3. UI stack

**Preact + TypeScript, DOM rendering, Norigin core for focus.**

- Considered **Lightning.js** (WebGL canvas, very fast on weak SoCs): rejected. Tally's design is text, hairlines,
  mono type and posters; in Lightning every one of those becomes custom texture work, text wrapping and fonts are
  harder, and the plugin's web UI (the same design) is DOM already. DOM on a 2020 Tizen/webOS set is fine when the
  app avoids the expensive things (below).
- Considered **React**: same model, 10x the runtime. **Solid/Svelte**: fine, but Preact keeps the React idioms most
  workers know and costs 4 KB.
- **Norigin spatial navigation core** (MIT, the engine behind their React package) instead of writing one: focus
  groups (`FocusGroup`), boundaries (pages, dialogs), saved last focus per group, preferred child. The binding is
  `src/focus/focus.tsx`. Keys do not go to Norigin directly: `platform/keyRouter.ts` owns every key.

Performance rules (the reason a DOM app is quick on a TV):
- Focus changes do **not** re-render: Norigin's adapter sets `data-focused` on the element and CSS draws the amber
  frame from it. Components re-render on focus only when they draw something new (the Home header).
- Scroll containers move with `scrollLeft`/`scrollTop` (never transforms: the focus system measures through scroll
  offsets), instantly, without smooth scrolling.
- No blur, glow, shadows (the design forbids them anyway), no animated layout. The lamp is the only animation and
  writes one element's styles from `requestAnimationFrame`.
- Images are requested at the size they are drawn (`fillWidth/fillHeight` at the 1080p canvas).
- Hidden pages stay mounted (`display: none`) instead of re-rendering on BACK.

## 4. Build and the Chromium 68 floor

- `vite build` → `dist/bundle/`: `app.<hash>.js` (IIFE, 390 KB, 103 KB gzipped), `app.<hash>.css` (21 KB), fonts
  (IBM Plex 7 files + Font Awesome Solid, 1.7 MB, straight from `app/src/main/res/font/`), `hls-<ver>.min.js`,
  license files, `manifest.json`, `index.html` (the browser version). Vite plugin `tally-bundle` in `vite.config.ts`.
- **Syntax**: lowered to `chrome68`. **Built-ins** newer than 68: `src/polyfills.ts` (core-js: `globalThis`,
  `Array#flat/flatMap/at/findLast`, `Object.fromEntries/hasOwn`, `Promise.allSettled/any`, `String#matchAll/
  replaceAll/at`, `queueMicrotask`). `Array.prototype.sort` is stable only from 70: sort through `stableSort`.
- **Guards** (`npm run lint`, `npm run build`):
  - `eslint-plugin-compat` with `chrome >= 68` flags web APIs and built-ins the TVs lack (polyfilled ones allowed);
  - `scripts/check-legacy.mjs` parses every built script as ES2019 (anything newer was not lowered) and fails on CSS
    Chromium 68 ignores: flexbox `gap`, `aspect-ratio`, `inset`, `clamp()/min()/max()`, `:is/:where`,
    `:focus-visible`. Space flex children with margins.
- **Why one classic script**: module scripts (`type="module"`) are fetched in CORS mode; from a TV app's `file://`
  page that is fragile. `index.html` is rewritten to a `defer` classic script without `crossorigin`.

## 5. Jellyfin access

- **SDK**: `@jellyfin/sdk` 1.0.0 (API 13, minimum server 10.10.0). Every endpoint the app uses was exercised against
  the 10.10.6 dev server: `/Users/AuthenticateByName`, `/QuickConnect/Initiate|Connect`,
  `/Users/AuthenticateWithQuickConnect`, `/UserViews`, `/UserItems/Resume`, `/Shows/NextUp`, `/Items/Latest`,
  `/Items/{id}`, `/Items/{id}/PlaybackInfo`, `/Sessions/Playing*`, images. Requests carry the SDK's
  `Authorization: MediaBrowser Client="Tally TV", Device=…, DeviceId=…, Version=…, Token=…` (Jellyfin 12 rejects
  the old `X-Emby-Token`).
- **Server address**: the shell's (stamped or typed); in a browser the SDK's discovery (`http/https`, `8096/8920`
  candidates, best by `/System/Info/Public`).
- **Sign-in**: Quick Connect first (the 6-digit code, split `831 550`, the kicker lamp sputtering, catching on
  approval and held 600 ms before Home, as on Android), or name + password. Quick Connect off → the password step.
- **Storage**: `localStorage` (a TV app's storage survives restarts; per app origin). `tally.session.v1` = server
  id/name/version/address, user id/name/image tag, token. `tally.deviceId` = a random id per install (Jellyfin binds
  tokens to it). The shell keeps `tally.shell.server`.
- **Tally plugin API**: `src/api/tally.ts` + `tallyModels.ts` (a lenient TypeScript port of `TallyModels.kt`: `heat`
  and `tags` are not modeled; unknown keys ignored; the settings document is read-modify-write whole). 404 on
  `/info` = no plugin: the Tally sections do not appear. One board poll for the app (`state/sportsData.ts`, every
  `pollSeconds`, only while a screen that shows games is open).

## 6. Playback

`src/player/`: `engine.ts` (interface), `avplayEngine.ts`, `html5Engine.ts` (also the webOS engine),
`createEngine.ts`, `deviceProfile.ts`, `playback.ts` (PlaybackInfo, stream URL, reporting), `qualityLadder.ts`,
`subtitles.ts`.

| | Tizen: AVPlay | webOS: <video> | Browser: <video> + hls.js |
|---|---|---|---|
| Surface | hardware plane under the page (`<object type="application/avplayer">`, page transparent) | element in the page | element in the page |
| HLS | native | native | hls.js (loaded only here), native where the browser has it |
| Files played directly | MP4, MKV, TS/M2TS, MOV, AVI, WebM, MPEG, FLV, ASF/WMV | MP4, MKV, TS, MOV, AVI, WebM, MPEG | MP4, WebM (MKV is converted) |
| Video | H.264, HEVC (Main/Main10), VP9, MPEG-2/4, VC-1; AV1 where probed | H.264, HEVC, VP9, MPEG-2/4; AV1 where probed | what `MediaSource.isTypeSupported` says |
| Audio | AAC, MP3, AC-3, E-AC-3, FLAC, Opus, Vorbis, PCM; DTS only if probed | same list | AAC, MP3, Opus, FLAC, Vorbis (+AC-3 where probed) |
| Server conversion | HLS TS, HEVC or H.264 + AAC/AC-3/E-AC-3, 6 channels | same | HLS TS, H.264 + AAC, 2 channels |
| State | written, **not yet run on a TV or the emulator** | stub (the HTML5 engine named `webos`) | **verified** in Chromium |

- **Device profiles** (`deviceProfile.ts`): tables for what each native pipeline plays from files (web engines
  under-report: `canPlayType` knows nothing of MKV or AC-3 in AVPlay), merged with probes; conservative for 2020 sets.
  DTS: Samsung dropped it 2018-2022, LG 2020-2021, so only when probed. UHD panels (`productinfo.
  isUdPanelSupported`) allow 3840x2160 and HDR10/HLG. Anything not listed is converted by the server, never refused.
  Exact per-model tables are refined on real TVs.
- **Subtitles**: text subtitles (SRT, ASS/SSA, embedded or external) are requested as WebVTT
  (`/Videos/{id}/{source}/Subtitles/{index}/0/Stream.vtt`) and **drawn by the app** (`SubtitleLayer`): the same
  look on every engine, and AVPlay has no `<track>`. Picture subtitles (PGS, DVD, DVB) are **burned in** by the
  server (the menu marks them). Verified: Spanish external and English embedded tracks on the dev films.
- **Audio tracks**: a choice restarts the stream at the current position with `AudioStreamIndex` (works on every
  engine; the server remuxes or converts). AVPlay can also switch tracks itself on a direct-played file
  (`nativeAudioTracks`/`selectNativeAudio`), to be used once verified. Jellyfin 10.10 ignores `AudioStreamIndex`
  unless the request also names the `MediaSourceId` (measured), so restarts always send it. Verified in Chromium: the
  server's new transcoding URL carries the chosen track and playback continues from 11.7 s at 12.4 s.
- **Quality**: the Android ladder exactly (Original, 4K 120/80/60/40, 1080p 30/20/15/10/8, 720p 5/3, 480p 2,
  360p 1 Mbps); a rung sets `MaxStreamingBitrate`, disables direct play/stream and video copy, and adds
  `MaxWidth`/`MaxHeight` (16:9 box) to the transcoding URL, because live channels report ~0 video bitrate and a
  bitrate cap alone left them at full size (measured on Android).
- **Live**: the plugin's continuous playlist (`/JellyTV/Live/{id}.m3u8?s=…`, signed, anonymous), the same address
  multiview uses; the live ladder keeps it going across source switches. hls.js holds ~15 s (5 segments) behind the
  edge like Android's `TallyLivePlayback`; AVPlay gets a 6 s start buffer. Fallback (not built): the channel's
  Jellyfin Live TV item through PlaybackInfo when a TV cannot decode the source (e.g. 1080p60 HEVC on an old set).
  Verified in Chromium: playlist requests, playback, score bug, CH+/CH- switching.
- **Live overlays** (`pages/player/LivePage.tsx`, `liveOverlays.tsx`; tvweb-sports), as on the Android TV live player
  (TallyPlaybackPage.kt): the **score bug** (back on open, on every score/period/situation change, while the
  switcher is up and for 8 s after a key; its digits roll), **UP** = the box score (line score, situation, last
  play; closes on the next key or after 12 s), **DOWN** = the "also on now" switcher (other live games on channels
  in board order, or the looping channels when none is live; OK switches in place, HOLD opens the game's actions),
  **event banners** for scoring plays in *other* games (the board poll's `since` events, 8 s, a lower third; never
  while scores are hidden), CH+/CH- step through the channels, REWIND = watch from the start while the game is being
  recorded. Verified in Chromium with the score simulator (bump → bug roll + amber flash; bump in another game →
  banner).
- **Reporting**: `/Sessions/Playing` on start, `/Progress` every 10 s, `/Stopped` on leave: resume points and
  Continue Watching stay right (verified in Chromium: start, progress and stopped reports with the real position, all
  answered 204; the dev films are 90 s, under Jellyfin's 5-minute minimum for a resume point).
- **Trickplay** (done, tvweb-player): the item's `Trickplay` info (width, tile size, interval) and
  `/Videos/{id}/Trickplay/{width}/{index}.jpg` tiles, drawn as a background-position crop above the seek bar.
- **Queue**: an episode plays on through its series (upstream's PlaylistCreator); a library's Play all / Shuffle
  passes its list in the route (`player.queue`, at most 100 ids, the grid's order or a random one).
- **Multiview** (`pages/multiview/`, tvweb-sports): the Android page (TallyMultiviewPage.kt) as it is: up to four
  tiles (equal grid / focus layout with the large tile at 68%, the same slot math and D-pad map, unit-tested), the
  swap-in rail, audio follows focus, OK toggles the layout, HOLD opens the tile's actions. Every tile is its own
  `<video>` engine (`createHtml5Engine`: hls.js in browsers, the TV's native HLS in `<video>` on Tizen/webOS), never
  AVPlay: AVPlay is one instance drawn on a full-screen plane, and a tile needs a positioned picture. Decoders are the
  limit, so it degrades instead of disappearing: `multiviewDecoders()` (multiviewLayout.ts) says how many tiles may
  play at once, the audio (focused) tile first (`playingTiles`); the others show the plugin's live **card**
  (`/JellyTV/Card/{id}.png`) until focus reaches them, when the picture moves there (a channel change on the one
  decoder). Tiles stop when the page is covered (full screen from a tile) and restart on return.
  - Browser: **4** (software decoders; verified in Chromium: four tiles playing, one unmuted).
  - Tizen and webOS: **1** until probed. The probe (to write and run on real sets, per model): open tiles one at a
    time on the dev channels; a tile counts when its `<video>` reaches `playing` within 8 s and every earlier tile
    keeps advancing `currentTime` for 10 s more; stop at the first failure (a `MEDIA_ERR_DECODE`, a stall, an earlier
    tile freezing). Record the count per `productinfo.getRealModel()` (Tizen) / `webOS.deviceInfo` model name (LG)
    and make `multiviewDecoders()` read it. Expectation to check: 2021+ Samsung sets decode two HD streams (the same
    hardware that gives `webapis.avplaystore` a second player); LG webOS 5 sets vary by SoC.
- **Screensaver / lifecycle**: Tizen `appcommon.setScreenSaver(OFF)` while a player is open; AVPlay is suspended on
  `visibilitychange` (hidden) and restored at the same position.

## 7. Design: tokens, type, components

- **Canvas**: every screen is designed at 1920x1080 CSS px (`#tally-stage`), scaled to the window (`platform/
  stage.ts`); a TV app gets a 1920x1080 viewport, so scale is 1 there. Android sizes convert as **1 Tally dp =
  1.6 px** (Android lays Tally out on a 1200x675 canvas) and **1 rail dp = 2 px** (the rail is unscaled upstream).
  Hairline 2 px and focus frame 4 px, as measured on Android captures at 1080p.
- **Tokens**: `src/styles/tokens.css` (ground, groundRaised, screen, labelBar, rule, ruleStrong, text,
  textSecondary, muted, accent, onAccent, live, liveText, lamp off), margins, card sizes, motion.
- **Type**: IBM Plex Sans / Mono and Font Awesome Solid from `app/src/main/res/font/` (one source of truth; Vite
  copies them). Labels are uppercased in code with `tallyUppercase()` (units keep their case: `14.8 Mbps`, `1080p`,
  `2h 35m`), never with CSS.
- **Focus** = 4 px amber inside the element (pseudo-element border), `groundRaised` fill where the Android component
  has it; nothing moves or scales. Scroll containers pad their content by focus + 1 dp so frames are never clipped.
- **Motion**: color/focus changes instant or ≤120 ms; the lamp (the Android `TallyLampTimeline`, same numbers,
  unit-tested) for launch-like moments: sign-in kicker, tune-in; `prefers-reduced-motion` skips it.
- **Kit** (`src/kit/`, mirrors `tally/media/kit/` and `tally/ui/components/`): `Button` (TallyButton), `Field`,
  `MediaRow` (+ `useRowReveal`), `ScrollPage`, `ItemCard` (PosterCard/LandscapeCard with SEEN / N NEW tags,
  favorite square, progress, kicker), `Bits` (`IndicatorSquare`, `RowHeader`, `LabelBar`, `GlyphIcon`), `Lamp`;
  sports: `GameCard`, `TeamMark`. Next to add (with their screens): DetailHeader, EpisodeRow, PersonCard, Tabs, Chip,
  SearchField, TrackRow, dialogs.

## 8. Navigation, keys, lifecycle

- **Routes** (`router/router.ts`) are data, not URLs: `home`, `search`, `library`, `item`, `player`, `live`,
  `sports`, `settings`, `placeholder`. A stack; pages below the top stay mounted and hidden; the uncovered page gets
  its last focus back. Drawer destinations reset the stack to Home + destination (as Android).
- **Pages** (`app/routes.tsx`) are registered with a chrome: `rail` (beside the navigation rail) or `full`
  (players). Each page is a focus group with boundaries (LEFT from a rail page reaches the rail).
- **Rail / drawer** (`app/Rail.tsx`): collapsed 64 dp rail with the tally light on the current page, opening to the
  224 dp drawer while it has focus (page pushed right under a scrim), order as on Android: Search, Home; Movies and
  TV libraries, Sports; LIBRARIES; Surprise me, Favorites; Settings pinned last. Live TV hidden while Sports exists.
- **Keys** (`platform/keys.ts`, `keyRouter.ts`): one listener maps each platform's codes to app keys (Tizen: BACK
  10009, media and color keys registered through `tizen.tvinputdevice` with the codes the TV reports; webOS: BACK
  461 with `disableBackHistoryAPI`, CH± 33/34; browsers: Escape/Backspace, media keys). Order: a text field being
  edited, then `useKeyHandler` handlers newest first (dialogs, players, a page's own BACK), then arrows/OK to the
  focus system and BACK to the router.
- **BACK**: dialog/overlay → page → previous page → on Home the drawer opens → BACK in the open drawer leaves the app
  (Tizen `application.exit()`, webOS `window.close()`). Samsung's guideline (BACK at the top level exits) is met.
- **Text fields** do not take DOM focus while the D-pad passes (the TV keyboard would pop up): OK starts editing,
  OK/Done submits, UP/DOWN leave, BACK stops editing. Tizen IME Done/Cancel (65376/65385) handled in the shell.
- **Lifecycle**: `visibilitychange` (both platforms): AVPlay suspend/restore; the board poll stops with its screens.
  webOS relaunch (`webOSRelaunch`) and Tizen deep links: later (Play-on-TV arrives through Jellyfin's websocket
  instead, see parity).
- **HOLD OK** (the Android TV long press: a game's actions, a channel into multiview, a card's item menu on Home and
  in the libraries; `pages/sports/useOkHold.ts`):
  on screens that have holds, OK is delivered on key-up; held for 500 ms it is a hold. The remote's auto-repeat while
  the key is down is swallowed (a key-down within 700 ms of the last counts as a repeat: Tizen does not flag repeats
  reliably), so a menu opening under the finger does not pick its first row; menus also ignore OK for their first
  400 ms, as on Android. MENU/INFO (where the remote has them; ContextMenu on a keyboard) open the same actions. To
  verify on a TV: that OK's key-up arrives (without it a short press only acts after the hold time).

## 9. Code layout and parallel work

```
tv-web/
  shell/              the installed shell (ES5, never transpiled) + tizen/config.xml, webos/appinfo.json, icons
  src/main.tsx        boot: polyfills, shell, platform, SDK, focus, keys, stage, <App/>
  src/shell-contract/ the shell ↔ bundle contract
  src/platform/       keys, key router, platform adapters, Tizen API types, the stage
  src/focus/          Norigin binding
  src/router/         route stack
  src/app/            App frame, rail/drawer, page registry, arrival focus
  src/api/            Jellyfin session (SDK), images, Tally plugin client and models
  src/state/          app-wide stores (libraries, plugin availability, board poll)
  src/kit/            shared components and their CSS
  src/sports/         game card, team mark, home row selection
  src/player/         engines, device profile, PlaybackInfo, subtitles, quality
  src/pages/<screen>/ one folder per screen (page component + its CSS + its data module)
  tests/              Vitest (pure logic, real captured fixtures)
  e2e/                Playwright flows against a real server
  scripts/            legacy check, packaging, certificates, installer, icons
```

How several workers build screens at once:
- **One worker, one `pages/<screen>/` folder** (page, CSS, data module). Registering the page is a one-line change
  in `app/routes.tsx` (+ its `Route` variant in `router/router.ts`), replacing a `PlaceholderPage`.
- **Kit changes are additive** (new components, new optional props with defaults); a worker who needs a kit change
  that alters existing behavior asks for it.
- **API access in modules**, not in components: a screen's `…Data.ts` calls the SDK / `api/tally.ts`; models are
  never invented (use the SDK's types, `tallyModels.ts`, and fixtures captured from the dev server).
- **Every screen**: `PageProps`, `useArrivalFocus` for its first focus, `useBack`/`useKeyHandler` for its own keys,
  focus keys prefixed by the screen name, labels through `tallyUppercase`, no CSS the legacy check rejects.
- **Definition of done** per screen: `npm run lint && npm test && npm run build` clean, an e2e flow with screenshots
  opened and judged at 1920x1080, compared with the Android screen's captures.

## 10. Samsung 1 (the first release for Samsung TVs)

Scope, in this order; everything else follows through server updates (no reinstall):

1. **Sign-in**: server address (stamped by the installer, or typed) and Quick Connect; password as the fallback.
   *Done in tvweb-0* (Quick Connect with the lamp, password with errors; verified in Chromium).
2. **Home**: games row (live / today) and the library rows (Continue Watching, Next Up, Recently added per library),
   the header describing the focused card, the backdrop. *Done* (the games row is the Sports card with its game menu
   on HOLD OK; the item menu on HOLD OK / MENU on any card; PLAY plays the focused item. Watch-live channels row,
   household and watch-party
   rows: later).
3. **Libraries**: grid with tabs (Recommended, Library, Collections, Genres), sort/filter, the alphabet jump.
   *Done in tvweb-library* (`pages/library/`: Movies, TV, music and other libraries, genre/studio pages, folders,
   Recommended rows with Suggestions and VIEW ALL, the sort/filter/view dialogs, a windowed grid paged from the
   server; verified in Chromium against the dev server).
4. **Film, series (season tabs + episode list), season, episode pages**: DetailHeader, action row (Resume/Play, From
   the start, Watched, Favorite, More), cast row, extras. *Done in tvweb-details* (`pages/details/`, `pages/person/`):
   the `item` route opens films/videos, series, episodes and people by type, `season` is the rundown (season tabs,
   numbered episodes with NEXT UP / progress / WATCHED, guest stars, season extras); rows: cast & crew (person page),
   chapters, extras, the collection's next film (TMDb collection order, as Android's CollectionNext), more like this,
   more from the season. Item menu (MORE, or MENU / INFO on a card), trailer list, full overview, series-watched
   confirmation and Add to playlist are Tally panels (`kit/Panel`). New kit: DetailHeader, EpisodeRow, FrameCard /
   PersonCard, Panel. Home and library cards open these pages (`pages/details/navigate.ts`, Android's
   `destination()`; a box set opens the library grid of its items until tv-web has a collection page).
5. **Player**: Tally controls (seek bar with trickplay, transport, chapters, next up, skip intro), subtitles, audio,
   quality. *Done in tvweb-player* (`pages/player/`, `pages/postplay/`): the Android TV controls, chapters and queue
   rows, the settings panel (audio, subtitles, speed, scale, subtitle delay, quality, sleep timer), media segments,
   next up with its countdown, the post-play page.
6. **Sports**: Games board (focused game panel + rows per league/state), Channels grid, and the **live player** with
   the score bug, event banners and the game switcher. *Done in tvweb-sports*: the Sports section (GAMES, CHANNELS,
   MULTIVIEW, RECORDINGS when the server records, SETTINGS), the game actions menu (watch, multiview, follow, hide
   scores, record, record every game of a team), multiview, the Recordings tab and watch-from-the-start, the live
   overlays (verified in Chromium against the dev server with the score simulator).
7. **Tizen verification**: AVPlay engine, remote keys, screensaver, suspend/resume, device profile on a real set.

Proposed parallel tasks after tvweb-0: `tvweb-details` (4), `tvweb-library` (3), `tvweb-player` (5),
`tvweb-sports` (6), `tvweb-tizen` (7, needs a TV or the emulator with a Samsung certificate), `tvweb-installer`
(section 11).

## 11. Packaging and install

### Samsung (Tizen)
- **Package**: `scripts/package-tizen.sh [--server URL] [--profile NAME]` → `dist/Tally.wgt` (≈75 KB). `config.xml`:
  package `TallyTVapp`, app `TallyTVapp.Tally`, `required_version` 5.5, profile `tv-samsung`, privileges `internet`,
  `tv.inputdevice`, `productinfo` (all public level), `<access origin="*" subdomains="true"/>`, `hwkey-event=
  "enable"`. **No** `<tizen:content-security-policy>` or `<tizen:allow-navigation>`: either one turns on the runtime's
  CSP mode, whose default (`script-src 'self'`) blocks the server's bundle. AVPlay needs no privilege (since 2015)
  but needs `$WEBAPIS/webapis/webapis.js` in the page (the package script adds it). Icon: `shell/icons/icon-512.png`
  drawn from the Android launcher art (`scripts/make-icons.py`).
- **Certificates** (research of September 24, 2026; sources in the tvweb-0 report):

  | TV | Tizen | Signing that installs in Developer Mode |
  |---|---|---|
  | 2020 | 5.5 | Tizen author certificate + Tizen's default distributor (no Samsung account) |
  | 2021 | 6.0 | same |
  | 2022 | 6.5 | same |
  | 2023 (original firmware) | 7.0 | unclear: treat as Samsung certificate needed |
  | 2023 upgraded, 2024, 2025 | 8.0, 9.0 | **Samsung certificate**: Samsung account, distributor certificate listing the TV's DUID (valid ~1 year) |

  The Tizen author certificate chains to "Tizen Developers CA", which **expires 2027-01-01**. What that means,
  from Tizen's open validator (platform/core/security/cert-svc, identical on the tizen_5.5 and tizen_6.5 branches;
  Samsung's TV firmware is closed, so this is the reference behavior, not a measurement on a TV):
  `BaseValidator::preStep()` checks only the *signing* certificate's dates against the clock; when it is outside
  them (expired or not yet valid) and its root is not in the strict test stores, the whole chain is checked at the
  **middle of the signing certificate's validity** instead (xmlsec `certsVerificationTime`). That is why TVs still
  take Tizen's public distributor signer, which expired in 2012. Consequences: an author certificate that **ends
  when the CA ends** (as Tizen Studio makes them: notAfter hard-coded to 2027-01-01) keeps installing after 2027 (it
  is then expired, the chain is checked at its midpoint, where the CA was valid); one that ends later would fail
  from 2027 on (still in its dates, so the chain is checked now, against an expired CA). Tizen Studio itself
  cannot make new author certificates after 2027 (it checks their validity); Tally for Samsung makes them for the
  CA's last year then. Updates compare only the author's **public key** (app-installers `IsSameAuthor`), so the key
  must be kept, a re-issued certificate for the same key updates fine. The emulator cannot confirm any of this: it
  refuses the Tizen chain outright (section 12). `scripts/tizen-certificate.sh` creates the development author
  certificate in `~/.tally/tizen` (with its password) and the `tally` security profile; Tally for Samsung keeps its
  own per computer (below).
- **Developer Mode** (the owner or friend does this on the TV once): Apps panel → App Settings (or the Apps screen) →
  type **12345** → Developer mode **On** → Host PC IP = the installing PC → restart the TV fully (hold power; unplug
  with Instant On). It stays on; major firmware upgrades have reset it (and removed sideloaded apps).
- **Development installer** (`scripts/install-tizen.sh <tv-ip> --server <jellyfin>`, needs Tizen Studio):
  `sdb connect`, reads the Tizen version and DUID, picks the signing (the `tally` profile on Tizen < 7, else asks
  for a Samsung profile), packages with the server stamped in, `tizen install`, `tizen run`.
- **Tally for Samsung** (`installer/`, for everyone else; the steps for people are in
  [INSTALL-SAMSUNG.md](INSTALL-SAMSUNG.md)): one self-contained program per desktop OS, published by
  `tally/release.sh` as `Tally-Samsung-Installer-windows.exe`, `-linux`, `-macos-arm64`, `-macos-x64`
  (`installer/build.sh`). No Tizen Studio, Java, Docker or Samsung binaries.
  - **Stack**: .NET 10, self-contained single file, trimmed (12-15 MB), a console flow (four numbered steps, plain
    sentences, every error says what to do). The server setup is already C#/.NET and cross-built from Linux the same
    way; a console instead of a window keeps one UI for Windows, macOS and Linux in this pass (WinForms is Windows
    only; a window can sit on the same `InstallerFlow` later).
  - **Find the TV**: shows this PC's address (what Developer Mode's Host PC IP must be), then asks every address of
    the PC's home networks (/24) at once for Samsung's TV information (`http://<ip>:8001/api/v2/`: name, model code
    with the year, and on TVs that report them `developerMode`/`developerIP`) and whether the sdb port 26101 takes a
    connection; or the IP is typed. Connection failures are explained with that information ("Developer Mode is on,
    but for another computer (192.168.1.33; this PC is 192.168.1.20)") and the Developer Mode steps.
  - **sdb, reimplemented** (`Sdb/`): the device protocol straight to the TV's sdbd, no sdb server: CNXN (the same
    version, payload size and banner Tizen Studio's sdb 4.2 sends), OPEN/OKAY/WRTE/CLSE streams with flow control,
    `capability:` (2-byte length + key:value lines), `shell:0 getduid` (or `0 getduidgadget` / `duid-gadget` on older
    sdbd, as Samsung's tools choose), `sync:` push (SEND `path,33261`, DATA ≤ 64 KiB, DONE, OKAY/FAIL, QUIT),
    `shell:0 vd_appinstall TallyTVapp.Tally <sdk_toolpath>/tmp/Tally.wgt` (its progress lines are shown and parsed:
    completed, or `install failed[118, -12], reason: …` sorted into certificate not trusted / author mismatch /
    another TV's DUID / not yet valid / expired / Tizen too old), `shell:0 was_execute TallyTVapp.Tally`,
    `shell:0 vd_appuninstall` (only after asking, on "author certificate not match"). Recorded against the emulator
    through a logging proxy between Tizen Studio's sdb and sdbd (`installer/tests/fixtures/sdb-trace-emulator.txt`);
    the TV's sdbd accepts only these fixed "0 …" commands. Samsung's sdb binaries are not redistributable (Tizen SDK
    license, §3.1); the sdb 3.x source is Apache-2.0 but was not needed.
  - **Signing, reimplemented** (`Signing/WidgetSigner.cs`): author-signature.xml and signature1.xml exactly as
    `tizen package` writes them: Exclusive C14N over SignedInfo, RSA-SHA512, SHA-512 digests, references sorted
    ordinally with Tizen Studio's URI escaping, the `#prop` object (C14N 1.1) with profile/role/identifier, KeyInfo =
    signer + CA, base64 at 76 columns. Byte-for-byte equal to three golden packages from `tizen package`
    (`installer/tests/fixtures/golden/`: the shell, odd file names, every punctuation character).
  - **Certificates** (kept in the app-data folder, `%APPDATA%\Tally\Samsung` / `~/.config/Tally/Samsung`, reused for
    every update; losing them means uninstalling Tally from the TV before the next install):
    - Tizen 5.5-6.5 (2020-2022): an author certificate made on first use exactly as Tizen Studio's generator makes one
      (issued by the Tizen Developers CA with its public key from Tizen's Apache-2.0 certificate-generator: SHA-512,
      CA:FALSE critical, digitalSignature, codeSigning, notAfter = the CA's end; differences: 2048-bit key, random
      serial, start one day back) + Tizen's public distributor ("Tizen Public Distributor Signer", Tizen Studio's
      default, which its own wizard says is for Tizen ≤ 7; the 2022 "Tizen Studio Public Signer" is for 8+ and not in
      the Apache sources).
    - Tizen 7+ (2023 on): the Samsung certificate flow of Tizen Studio's Samsung Certificate Extension 2.0.75, as
      Apps2Samsung (MIT) and Samsung's tizen-agent-skills (Apache-2.0, read as a specification) implement it: the
      browser opens `account.samsung.com/accounts/…/signInGate?clientId=v285zxnl3h&tokenType=TOKEN&redirect_uri=
      http://localhost:4794/signin/callback` (the only registered redirect, so port 4794 is fixed; the program listens
      on IPv4 and IPv6 loopback), Samsung's page POSTs `code` = JSON (access_token, userId, inputEmailID); then
      multipart POSTs to `https://svdca.samsungqbe.com/apis/v3/authors` (access_token, user_id, platform=VD,
      csr=author.csr) and `apis/v1/distributors` + `apis/v3/distributors` (… privilege_level=Public,
      developer_type=Individual, csr=distributor.csr with subjectAltName `URN:tizen:packageid=` +
      `URN:tizen:deviceid=<DUID>` per TV). Answers are PEM; the chain is completed with Samsung's CA certificates
      (bundled, issuer matched by signature). The author key is kept across renewals; the distributor certificate
      lists every TV this PC has installed on. **Untested against Samsung** (no account): unit-tested against the
      published request/response formats only.
    - A Tally.wgt someone else signed for this TV (`--wgt`, dragging it onto the program, or choice 2 when the TV
      needs a Samsung certificate): checked before copying (Tally's app id, signed, its distributor certificate lists
      this TV's DUID). The owner makes one for another person's TV with `--make-wgt <DUID> --server <address>`.
  - **Server**: the typed address is tried as the shell would use it (https first, then http with :8096), must
    answer `/System/Info/Public` as Jellyfin, and `/JellyTV/TV/manifest.json` tells whether the plugin carries the TV
    app (a warning if not). It is stamped into `config.js` of the package made on the user's PC; the program and the
    release contain no server address.
  - Every run writes `Tally-Samsung-Installer.log` in the temp folder.
- **LG (not built)**: the same program would need, for webOS 5+: the LG **Developer Mode** app state (Dev Mode Status
  on, **Key Server** on); fetching the TV's SSH private key from its key server (`http://<tv>:9991/webos_rsa`) and
  decrypting it with the **passphrase** the Developer Mode app shows (typed by the person); an SSH/SFTP client
  (port 9922, user `prisoner`; e.g. SSH.NET, MIT) to copy the `.ipk` to `/media/developer/temp` and run
  `luna-send -n 1 luna://com.webos.appInstallService/dev/install '{"id":…,"ipkUrl":…,"subscribe":true}'` and
  `luna://com.webos.applicationManager/launch`, which is what `ares-install`/`ares-launch` do; building the `.ipk`
  itself (an `ar` archive of `debian-binary`, `control.tar.gz`, `data.tar.gz`, as `ares-package` writes; no
  signing); and the 50-hour Developer Mode session, which community tools extend by calling LG's
  `ResetDevModeSession` with the token stored on the TV. Not cheap (SSH, key handling, the session timer), so it is
  left for the LG task.
- **Store**: Samsung Seller Office distribution is possible later; its review of an app that loads its code from a
  server is an open question (Samsung's hosted-app rules allow external scripts with registration).

### LG (webOS)
- **Package**: `scripts/package-webos.sh [--server URL]` → `dist/io.github.scdouglas1999.tally_<ver>_all.ipk`
  (≈70 KB) with `ares-package` (`@webos-tools/cli` 3.2.6 in `~/tools/webos-cli`). `appinfo.json`: id
  `io.github.scdouglas1999.tally`, `disableBackHistoryAPI: true` (BACK arrives as key 461), resolution 1920x1080,
  icons 80/130 px.
- **Install**: the LG **Developer Mode** app (LG account, from the LG Content Store) on the TV → Dev Mode Status On,
  Key Server On; `ares-setup-device` with the TV's IP and passphrase, `ares-install`, `ares-launch`. The Dev Mode
  session **expires after 50 hours** unless extended in the app (community tools extend it automatically).
- **Store**: LG Seller Lounge / Content Store review; same open question about server-loaded code.

## 12. Testing

- **Unit** (`npm test`, Vitest): lamp timeline (Android numbers), quality ladder, key maps, formats, home-row
  selection on real boards (the Android fixture and one captured from the dev server), device profiles, subtitles,
  scroll math, drawer order, and the AVPlay engine against a recording fake of `webapis.avplay` (call order,
  suspend/restore, tracks), and for Sports: the board rows (the Android BoardOrganizer cases), line score labels and
  column fitting, the score roll's offsets and restarts, the followed-team countdown, DVR models and words (the
  Android DvrModelsTest payload), multiview slots, D-pad map and decoder allotment; the player's formats, the Home
  header's meta line. 123 tests.
- **Lint** (`npm run lint`): ESLint (typescript-eslint + compat for Chromium 68), `tsc --noEmit` strict, CSS legacy
  check. **Build** adds the ES2019 parse of the bundle.
- **End-to-end** (`npm run e2e`, Playwright 1.63, Chromium at 1920x1080, the production bundle through
  `vite preview` on `TALLY_PREVIEW_PORT`, never a preview already running there, the dev server at
  `127.0.0.1:18200`; the board's games change through the day, so the Sports flows find the games they need on it): Quick Connect sign-in (approved with the admin token),
  password error, Home (games row, library rows, header, drawer open/close; the game menu and the item menu on HOLD OK
  / MENU, PLAY on a card), library cards (item menu, a box set's items, an episode to its rundown, Play all's queue),
  film playback with a subtitle and an
  audio switch, live channel from the continuous playlist with the score bug; Sports (`e2e/sports.spec.ts`): the
  board and its tabs, HOLD menus, channels, the multiview queue, settings, recordings, four-tile multiview, the live
  overlays, a team recording rule end to end, the start-over page. Live states come from the score simulator
  (`tally/dev/score-sim.py`, run with `TALLY_SIM=1`; its parts are skipped without it). Screenshots in
  `test-results/shots/`.
- **Tizen emulator**: Tizen Studio 6.1 CLI + TV Extension 10.0 in `~/tools/tizen-studio` (installed without root on
  this Arch host: a `dpkg` shim answers the package manager's Ubuntu prerequisite check; all emulator libraries
  resolve). Only the **Tizen 10.0 (2026) TV image** is published now (Chromium M130), not 5.5/6.x: it verifies the
  shell, AVPlay and keys, not the Chromium 68 floor (that is what the legacy checks are for). VM `tally-tv`
  (1080p, 1 GB) boots headless on Xvfb (`scripts/tizen-emulator.sh`; GL must stay on, the TV image's tuner device
  needs it) to Smart Hub, reports `platform_version:10.0` over sdb, and **refuses Tally signed with the plain Tizen
  certificate**: `install failed[118, -12], reason: Check certificate error : :Invalid certificate chain with
  certificate in signature.` (the same error Tizen 8+ TVs give). Since TV Extension 7.0.1 the emulator installs only
  Samsung-certificate-signed apps, so running Tally in it (and verifying AVPlay and `webapis` from the server-loaded
  script) needs the owner's Samsung account (open question). Run only with ≥ 7 GB free on this host. It also
  refuses the same package with Tizen Studio's 2022 public distributor ("Tizen Studio Public Signer") and with its
  partner and platform distributors (tried September 24, 2026): only a Samsung chain installs. The emulator reaches
  the host at `10.0.2.2` (QEMU user networking) and the host's LAN address; its sdbd is `127.0.0.1:26101` and takes
  one client at a time (stop Tizen Studio's `sdb` server before another client connects).
  **A 2020-2022 TV, simulated** (tvweb-installer, September 24, 2026): the refusal is the TV image's trust list,
  `/usr/share/ca-certificates/fingerprint/fingerprint_list.xml`, whose `tizen-public` distributor domain lists only
  Samsung's roots, not Tizen's "Tizen Public Distributor Root CA" (the Tizen Developers roots for authors are
  there). On a *copy* of the image (`qemu-img` from Tizen Studio to raw, `debugfs` to add that root's SHA-1
  `04:C5:A6:1D:…:44:AE` to `tizen-public`; the shared `tally-tv` VM untouched), Tally for Samsung's Linux build
  installed Tally signed on this PC ("install completed"), started it, and the shell loaded the bundle and showed
  Quick Connect against the dev server; the update path (same author) and another computer's author ("Author
  certificate not match", uninstall, install) behaved as on a TV. So the signatures, the author chain, the
  install and launch commands and the server-loaded bundle all work on a real Tizen web runtime (10.0); what
  2020-2022 firmware trusts is taken from Tizen's upstream list, not measured.
- **Tally for Samsung** (`installer/`, `dotnet test installer/tests`, xUnit): the signer against three golden
  packages from `tizen package` (byte for byte), the author certificate against Tizen Studio's (fields, the 2027
  rule), the sdb client against a fake sdbd that answers as the emulator's did (handshake, capability, DUID, push
  across payload and sync-chunk boundaries, install/launch/uninstall, hang-up, silence, nothing listening) and the
  install answers (the emulator's real ones and the documented others), the TV scan (Samsung's `/api/v2/` answer, a
  fake TV among silent addresses), the server check, the Samsung sign-in callback (served over HTTP) and certificate
  requests (field names, CSR subjectAltName, error answers, CA matching) against a fake service, and the whole flow
  (a 2021 TV, the update path with the same author, replacing a Tally from another computer, a TV that refuses this
  PC, a 2024 TV with a file for another TV, a file for this TV, no TV app on the server).
- **webOS**: the webOS TV emulator is a VirtualBox image (needs VirtualBox, i.e. root on this host). The webOS TV
  Simulator (a Chromium shell, not LG's media pipeline) is offered for webOS 6.0 and 22-26 on LG's developer site
  behind a license dialog (no webOS 5 build); neither is installed yet: webOS follows Samsung.

## 13. Feature parity with the Android TV app

Status: **done** (in tvweb-0), **planned** (same feature, later task), **adapted** (different on a TV web app, and
how), **not possible** (and why).

| Android TV feature | TV web |
|---|---|
| Tally look everywhere (tokens, Plex, amber focus, square, no glow) | done (kit + tokens) |
| Launch lamp | adapted: the shell's static lit lamp; the bundle's lamp on sign-in and tune-in |
| Server picker, discovery | adapted: address typed or stamped by the installer; UDP discovery is not available to web apps |
| User picker, PIN | planned |
| Quick Connect sign-in (lamp catches on approval) | done |
| Username/password sign-in | done |
| Self-update from GitHub releases | adapted: the bundle updates with the plugin; the shell rarely changes |
| Navigation rail and drawer (Search, Home, libraries, Sports, Surprise me, Favorites, Settings) | done (destinations beyond Home/Settings are placeholders) |
| Home: games row, header, library rows, backdrop, clock | done |
| Home: Watch live channels row, household row, watch party row, row customization (Settings → Home) | planned |
| Film / series / season / episode pages | done (tvweb-details); adapted: remote (YouTube) trailers open only in a browser (TVs: local trailers), extras of one kind are listed one by one (no grid page), no VERSION / audio / subtitle choice before playing (chosen in the player), no Delete (Android's media-management setting is off by default) |
| Library grid, tabs, filter/sort, alphabet, genres, recommended | done (play all / shuffle queue the grid's first 100; the item menu on HOLD OK / MENU) |
| Search (text) | planned; voice: adapted (the TV's own voice/IME input into the field) |
| Collections, person, favorites, playlists | person done (tvweb-details); a collection opens as a grid of its items (the Android collection page: planned); favorites, playlists planned |
| Music: albums, artists, now playing, lyrics | planned; background music: not possible (web apps stop when hidden) |
| Player: transport, seek bar, chapters, queue, next up, skip intro/credits (media segments) | done (tvweb-player) |
| Player: subtitles (text + burned-in), audio tracks | done |
| Player: quality ladder with MaxWidth/MaxHeight | done |
| Player: trickplay | done |
| Player: subtitle style settings | planned |
| Sleep timer | done (in the player's settings) |
| Post-play page, CollectionNext | done (post-play: tvweb-player; the collection's next film: on the film page) |
| Surprise me | planned |
| Sports: Games board, focused game panel (line score, situation, broadcasts), league/state rows incl. POSTPONED, hidden scores, followed teams, score roll | done |
| Sports: Channels grid | done |
| Sports: game actions menu (HOLD OK): watch, multiview, follow, hide scores, record | done |
| Live player: plugin continuous playlist, score bug | done |
| Live player: tune-in lamp | done |
| Live player: event banners, game switcher, box score overlay | done |
| Corner view (picture in picture) | adapted: needs a second decoder (Tizen avplaystore / webOS dual <video> where the set has it), else the live card image |
| Multiview 4-up | done in browsers; adapted on TVs: one decoder until probed, the other tiles show live cards (section 6) |
| Follow teams, hide scores, "My channels only" (shared settings) | done |
| Favorite channels | planned (the board reads them; no screen sets them on Android TV either) |
| DVR: record a game, record every team game (keep last N), Recordings tab, watch from the start, stop/cancel/delete | done (recording itself unverified on the dev server: it keeps 10 GB free and has less) |
| Watch parties (SyncPlay) | planned (Jellyfin SyncPlay over the SDK's websocket) |
| Household row, Send to another screen | planned (Jellyfin sessions API) |
| Play-on-TV target (phone pushes Play) | planned (Jellyfin websocket `Play` messages: same channel as Android) |
| Remote track switching from a phone | planned (websocket general commands) |
| Live-scores screensaver | adapted: in-app idle screensaver while the app is open; the TV's own screensaver outside |
| Downloads and offline playback | not possible: TV web apps have no storage for video files |
| Phone and tablet layouts | not applicable |
| Wholphin themes (Purple etc.) | not applicable: the Tally look only |
| Settings (app preferences, subtitle style, home rows) | planned (first cut: sign out, change server, reload, versions) |

## 14. Licenses

Shipped in the bundle: Preact (MIT), Norigin spatial navigation core (MIT, with lodash-es MIT), @jellyfin/sdk
(MPL-2.0, compatible with GPL-2.0 through its secondary-license clause), axios (MIT, with its MIT dependencies),
core-js (MIT). Fonts: IBM Plex and Font Awesome 6 Free Solid, both SIL OFL 1.1 (license files shipped next to them).
**hls.js (Apache-2.0)** is not linked into the bundle: it is a separate file that only desktop browsers load, never
the TVs (the plugin already serves it to browsers the same way). Apache-2.0 is not compatible with GPL-2.0-only, so
keeping it separate matters; the owner may prefer to state the bundle as GPL-2.0-or-later instead (open question).
Build/test only (not shipped): Vite, Rolldown, TypeScript, ESLint and plugins, Vitest, Playwright, acorn, the Tizen
Studio CLI and the webOS CLI.

Tally for Samsung (`installer/`) ships the .NET runtime (MIT), the shell, Tizen's public Developers CA certificate
and key and public distributor (Apache-2.0, git.tizen.org `sdk/tools/certificate-generator`) and Samsung's public
TV developer CA certificates (published by Samsung under Apache-2.0); `installer/src/certificates/NOTICE.txt` has the
details and the program prints them with `--licenses`. The key material is data the program reads, not code linked
into it, and the Apache-2.0 text travels with it; if the owner prefers not to redistribute it, the program can
download Tizen's `certificate-generator_0.1.4` package from download.tizen.org on first run instead (as Samsung's own
`@tizentv/tools` does). Tests only: xUnit, System.Security.Cryptography.Xml (an independent canonicalizer).

## 15. Open questions for the owner

- **Samsung account** for Tizen 7+ TVs and for the Tizen emulator (Samsung certificates are issued per account and
  list the TV's DUID; valid about a year). Which TV does the friend have (model year / Tizen version)?
- **Author certificate custody**: `~/.tally/tizen` on this machine holds the key every future install must use; where
  should the owner keep a copy?
- **2027-01-01** Tizen Developers CA expiry: by Tizen's open validator, author certificates that end with the CA
  (Tizen Studio's and Tally for Samsung's) keep installing afterwards (section 11); Samsung's TV firmware is closed,
  so a 2020-2022 TV has to confirm it after that date. The fallback is a Samsung certificate (`--samsung`), which
  those TVs also take.
- **Tally for Samsung**: the Samsung sign-in and certificate requests are untested (no account); the first 2023+ TV
  install is their first real run. Code signing for the Windows/macOS programs (SmartScreen and Gatekeeper warn about
  unsigned downloads; INSTALL-SAMSUNG.md explains the clicks) is not set up.
- **Stores** (Samsung Seller Office, LG Content Store): pursue, or sideload only? Both review apps that load code
  from a server.
- **hls.js licensing** for the browser version (separate file today), or GPL-2.0-or-later for tv-web.
- **LG**: a Developer Mode app session expires every 50 hours; acceptable for friends, or aim for the store?
