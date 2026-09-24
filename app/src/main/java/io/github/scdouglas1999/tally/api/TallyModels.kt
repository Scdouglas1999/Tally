package io.github.scdouglas1999.tally.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The Tally Client API v1 contract (server: Jellyfin.Plugin.JellyTV, route /JellyTV/Client/v1).
 *
 * Rules every client follows:
 *  - decode leniently: unknown keys are ignored, missing keys take the defaults below;
 *  - never decide what the server already decided (which channel to watch is `watch`, full stop);
 *  - `heat` and `tags` exist in the payload for other clients and are deliberately NOT modeled here.
 */

val TallyJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

@Serializable
data class TallyInfo(
    val apiVersion: Int = 0,
    val pluginBuild: String? = null,
    val features: List<String> = emptyList(),
    val pollSeconds: Int = 15,
    val latestEventId: Long = 0,
)

@Serializable
data class TallyBoard(
    val serverTime: String = "",
    val games: List<TallyGame> = emptyList(),
    val channels: List<TallyChannel> = emptyList(),
    val events: List<TallyEvent> = emptyList(),
    /** One document per feature module, keyed by module name (e.g. "fantasy"). Opaque to the core app. */
    val modules: Map<String, JsonElement> = emptyMap(),
    /** league/module -> why its last refresh failed. Shown to the user; never treated as "no games". */
    val errors: Map<String, String> = emptyMap(),
)

@Serializable
data class TallyTeam(
    val id: String = "",
    val abbr: String = "",
    val name: String = "",
    val shortName: String = "",
    val location: String = "",
    val logo: String = "",
    val score: Int? = null,
    val record: String? = null,
    val possession: Boolean = false,
    val winner: Boolean = false,
    /** Points per period (quarter, inning, …) in order; empty before the game starts. */
    val periods: List<Int> = emptyList(),
    /** Team color, six hex digits without '#' ("132448"); empty when unknown. */
    val color: String = "",
    /** Alternate team color, same format. */
    val altColor: String = "",
)

/** Where to watch a game. Resolved on the server. */
@Serializable
data class TallyWatch(
    val channelId: String = "",
    val channelName: String = "",
    /** Jellyfin Live TV item id (32 hex chars, no dashes) for the app's own player; null for a minute or two after a channel first appears. */
    val liveTvItemId: String? = null,
    /** Root-relative, signed, anonymous HLS playlist. Prefix with the server base URL. Used by multiview only. */
    val hlsPath: String = "",
    /** Root-relative 16:9 PNG card. Prefix with the server base URL. */
    val cardPath: String = "",
    /** "teams" | "epg" | "network" (a broadcaster match may be a different regional game). */
    val confidence: String = "",
)

@Serializable
data class TallyGame(
    val id: String = "",
    /** football | baseball | basketball | hockey | soccer */
    val sport: String = "",
    val league: String = "",
    val name: String = "",
    /** ISO-8601 */
    val start: String = "",
    /** pre | in | post */
    val state: String = "pre",
    /** Human status: "7:58 - 4th", "Top 8th", "FT" */
    val detail: String = "",
    val period: Int = 0,
    val clock: String = "",
    val home: TallyTeam = TallyTeam(),
    val away: TallyTeam = TallyTeam(),
    val lastPlay: String? = null,
    val lastPlayType: String? = null,
    val lastPlayScore: Int = 0,
    /** Football: "2nd & 7 at CAR 36" */
    val downDistance: String? = null,
    val redZone: Boolean = false,
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
    val onFirst: Boolean = false,
    val onSecond: Boolean = false,
    val onThird: Boolean = false,
    val broadcasts: List<String> = emptyList(),
    val watch: TallyWatch? = null,
    /** Root-relative, anonymous 16:9 matchup art for backdrops (text-free, score-independent). */
    val backdropPath: String? = null,
    /** Per-module additions keyed by module name. Feature modules read their own key; everything else ignores it. */
    val extras: Map<String, JsonElement> = emptyMap(),
    /** The server DVR's job for this game (feature `dvr`); null when there is none. Never carries a score. */
    val recording: TallyGameRecording? = null,
) {
    val isLive: Boolean get() = state == "in"

    /** The settings key under which [team] is followed. */
    fun teamKey(team: TallyTeam): String = "${league.uppercase()}:${team.abbr.uppercase()}"

    val isUpcoming: Boolean get() = state == "pre"
    val isFinal: Boolean get() = state == "post"
}

/** A game's recording as the board shows it (the DVR's most relevant job for the game). */
@Serializable
data class TallyGameRecording(
    /** scheduled | waiting | recording | finishing | done | failed | canceled */
    val state: String = "",
    val jobId: String = "",
    /** Root-relative, signed HLS of everything recorded so far (an EVENT playlist that keeps growing), while recording. */
    val startOverPath: String? = null,
    /** The Jellyfin library item of the finished recording, once the server has scanned it. */
    val itemId: String? = null,
    /** Why it is waiting, why it failed, or why it stopped early. */
    val reason: String? = null,
)

@Serializable
data class TallyProgramme(
    val title: String = "",
    val start: String = "",
    val end: String = "",
)

@Serializable
data class TallyChannel(
    val id: String = "",
    val name: String = "",
    val group: String = "",
    val logo: String? = null,
    val liveTvItemId: String? = null,
    val hlsPath: String = "",
    val cardPath: String = "",
    val gameId: String? = null,
    val now: TallyProgramme? = null,
    val next: TallyProgramme? = null,
)

@Serializable
data class TallyEvent(
    val id: Long = 0,
    /** Producing module: "scores" today, "fantasy" later. Users opt in per source. */
    val source: String = "",
    val kind: String = "",
    val gameId: String? = null,
    val title: String = "",
    val text: String = "",
    val createdAt: String = "",
    val watch: TallyWatch? = null,
)

/** Per-user settings shared with the web UI (same server-side store). Unknown keys must round-trip. */
@Serializable
data class TallySettings(
    val favorites: List<String> = emptyList(),
    val hideScores: Boolean = false,
    val lastChannel: String? = null,
    /** "My channels only" on the Games board; null = never chosen, so the app decides from the board. */
    val onlyWatchable: Boolean? = null,
    /** Followed teams as "LEAGUE:ABBR" (e.g. "NFL:KC"); their games are pinned first and get start nudges. */
    val favoriteTeams: List<String> = emptyList(),
)
