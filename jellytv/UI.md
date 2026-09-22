# JellyTV UI — the whole app in the JellyTV look

The JellyTV section (Games, Channels, Multiview), the player overlays and the plugin's web UI share one visual
language. As of 2026-09-22 the whole app uses it: the Wholphin look (pill buttons, round avatars, rounded posters,
full-page colour washes) is retired for the JELLYTV theme. This document is the design authority for that work.
Workers implement it; they do not redesign it.

## 1. The language ("control room, off duty")
Source of truth: the header comment and tokens in the plugin's `Web/app.css`, mirrored in
`jellytv/ui/theme/JtvTheme.kt` (`JtvColors`, `JtvType`, `JtvDimens`).
- Flat near-black ground (`ground #0E0F0E`), raised panels `groundRaised`, picture faces `screen`.
- Hairline structure: 1dp `rule` for structure, 1dp `ruleStrong` for control borders. Square corners everywhere.
- One accent, amber `#FFB000`: focus, selection, "now", the single most important action. Never decoration.
- Red (`live`, `liveText`) only for live and for failure.
- IBM Plex Sans for reading (titles, names, overviews). IBM Plex Mono for anything instrument-like: labels,
  kickers, metadata lines, times, counts, tabs, row headers, button labels — UPPERCASE with letter-spacing.
- Broadcast cues only where they do a job: indicator squares (state), black label bars under pictures (identity),
  segmented/thin progress bars (position), key hints (what the remote does).
- No gradients as decoration, no blur, no glow, no scale-on-focus, no shadows except under logos on imagery,
  no emoji. Image scrims (ground → transparent) are allowed where text sits on pictures.
- Focus: 3dp accent border (`JtvDimens.focusBorder`), drawn inside the element's bounds; nothing moves.
- A focus border is never clipped: every scrolling container (LazyRow, LazyColumn, verticalScroll) pads its content
  by at least `focusBorder + 1dp` on the sides where a focused item can touch its edge. Verify by measuring the
  border on all four sides of a focused first and last item at full resolution: they must be equal.
- Plain-language labels ("Resume", "From the start", "Mark watched"), not jargon.
- Text in a control is vertically centred: equal space above and below the cap height. tv-material3 `Surface`
  puts content top-left, so labels must fill the control's height and centre explicitly. Check at full resolution.

## 2. Architecture
- Upstream keeps the data: ViewModels, services, repositories, `BaseItem`, image URL helpers, navigation.
  JellyTV owns the composables. A JellyTV screen obtains the SAME ViewModel the upstream screen uses (same
  `hiltViewModel` call and factory arguments), reads the same state and calls the same actions. If a behaviour
  lives in an upstream composable (a dialog, a context menu, a picker), the JellyTV screen reuses that composable
  rather than re-implementing it, until a later task restyles it.
- Routing: one seam at the top of `ui/nav/DestinationContent.kt` asks `JellyTvRoutes.Content(destination, …)` to
  render the destination; it returns false for anything JellyTV does not (yet) own, and the upstream `when` runs.
  JellyTV routes are active only while the JELLYTV theme is selected: choosing a Wholphin theme gives Wholphin's
  screens back, whole.
- Package layout: `jellytv/media/kit/` (components), `jellytv/media/<screen>/` (pages), strings in
  `res/values/strings_jellytv_media*.xml`.
- Every JellyTV page wraps its content in `JtvScale { … }` (1080p = 960x540dp) on `JtvColors.ground`.
- Theme-level coverage for everything not yet owned: square theme shapes (`JellyTvShapes`), no backdrop colour wash
  (`JellyTvFirstRun`), IBM Plex type (`JellyTvTypography`).

## 3. The kit (`jellytv/media/kit/`)
Sizes are dp at the JellyTV canvas (960x540 at 1080p).

**PosterCard** — 2:3 artwork. Default width 132 (height 198). `ContentScale.Crop` on `screen`; 1dp `ruleStrong`
border; focused 3dp accent. Under the image a black label bar (height 40): title in Sans Medium 14sp `text` (one
line, ellipsis) and, on a second line, a mono 11sp `muted` detail (year for films; "3 SEASONS" / "S2 · 8 LEFT" for
series). `showLabel = false` hides the bar (dense grids). Overlays on the image:
- progress (resumable): 4dp bar along the image bottom, accent on `ruleStrong`, full image width.
- played: a black tag top-left with mono 11sp `SEEN` in `muted` (JellyTV tag like multiview's MUTED).
- unplayed episodes (series/season): the same tag with `4 NEW` in accent instead.
- favourite: an 8dp accent `IndicatorSquare` top-right, 8dp in from the corner.
**LandscapeCard** — 16:9 artwork (thumb → backdrop → primary). Default width 232. Same frame, focus and progress.
Label bar: mono 11sp accent kicker (`S2 · E4`, `CHAPTER 3 · 30:00`, `TRAILER`) then Sans Medium 14sp title.
**PersonCard** — square 1:1 portrait, width 104, `Crop`, same frame; label bar: name (Sans Medium 13sp, one line)
and role (mono 11sp muted, one line). No circles.
**MediaRow** — `RowHeader` (mono uppercase + count, as on the JellyTV home row) above a `LazyRow` of cards with
`JtvDimens.cardGap`/2 = 9dp spacing, first card aligned to the page's left margin; remembers and restores the
focused index when focus re-enters the row.
**JtvButton** — square, height 40, horizontal padding 18, mono 14sp Medium UPPERCASE letter-spaced label with an
optional FontAwesome glyph (16sp) before it. `primary`: accent fill, `onAccent` text; focused primary: a 3dp `text`
border inside. Secondary: transparent, 1dp `ruleStrong` border, `text` label; focused: 3dp accent border.
**IconButton** — 40x40 square, glyph 18sp, 1dp `ruleStrong` border; focused 3dp accent border and its label
appears under the row as a mono 12sp `muted` caption ("FAVOURITE"), so icon-only rows stay legible.
**Tabs** — the JellyTV top-bar tab style (indicator square + mono uppercase, selected = accent + underline),
generic over labels: `JtvTabs(labels, selected, onSelect)`.
**Chip** — filter/sort chip: height 32, 1dp `ruleStrong`, mono 13sp; toggles carry a leading `IndicatorSquare`
(accent when on); focused 3dp accent.
**SearchField** — square, height 48, 1dp `ruleStrong` (focused: 3dp accent), mono placeholder in `muted`
(`SEARCH MOVIES, SHOWS, PEOPLE`), Sans 20sp input; an IconButton for voice sits to its left.
**DetailHeader** — the top of every detail page:
- backdrop: the app-wide backdrop (upstream `BackdropService`, drawn once by `ApplicationContent`, image-only in
  the JellyTV look: top-right, fading out to the left and bottom). A page submits its item, exactly as upstream's
  VM does; it never draws a second backdrop of its own, so moving between pages crossfades;
- left column (max 480): kicker (mono `label`, `muted`: `FILM`, `SERIES · 2 SEASONS`, `S1 · E4`), logo (max
  360x96, Fit) or title (Sans SemiBold 40sp/46sp, 2 lines), meta line (mono `label` `textSecondary`: year ·
  official rating in a 1dp `ruleStrong` box · runtime `2H 28M` · `★ 8.4` · critic score `RT 87%` · `ENDS 9:41 PM`),
  genres (Sans 15sp `muted`, " / "), tagline (Sans 17sp `textSecondary`), overview (`body`, 4 lines), then a
  tech line of small boxes (mono 11sp, 1dp `rule` border, `muted`): `1080P`, `HEVC`, `HDR10`, `EN · AAC 5.1`,
  `CC · EN ES`;
- action row (16dp above the next section): JtvButtons, primary first.
**EpisodeRow** — list row for episodes: 16:9 thumb (200 wide) with progress; right of it: mono `label` accent
`E4 · 52M` (plus `SEEN` muted when played), title Sans SemiBold 20sp, overview `body` `muted` 2 lines. Focused: 3dp
accent border around the whole row, `groundRaised` fill.
**ItemDialogsHost** — one reusable host for the dialogs every item page needs, reusing upstream's composables
as they are (restyled later at the theme level): the context menu (`ContextMenuDialog` with `ContextMenu.ForBaseItem`
— it carries the version/audio/subtitle pickers, add to queue/playlist, delete), `PlaylistDialog` via
`AddPlaylistViewModel`, `ItemDetailsDialog` (full overview) and `ConfirmDialog`. Pages open them through a small
state object instead of wiring each dialog themselves.
**TrackRow** — music: number mono muted (right-aligned, 32 wide), title Sans 18sp, artist Sans 15sp muted, duration
mono right; playing = accent indicator square before the number. Focus like EpisodeRow.

## 4. Screens, in order
1. Film details (pilot: sets the bar for the rest). 2. Series details with seasons as tabs and an episode list.
3. Episode details. 4. Home. 5. Library (grid, tabs, filter/sort). 6. Search. 7. Collection, Person, Favourites,
Playlist. 8. Music (album, artist, now playing). 9. Player controls. 10. Settings and upstream dialogs (theme level
first; bespoke later).
Each screen's spec lists: the upstream VM and actions it reuses, layout, focus, and the screens to look at.
