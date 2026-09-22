package com.github.damontecres.wholphin.jellytv.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The JellyTV Client API v1 contract (server: Jellyfin.Plugin.JellyTV, route /JellyTV/Client/v1).
 *
 * Rules every client follows:
 *  - decode leniently: unknown keys are ignored, missing keys take the defaults below;
 *  - never decide what the server already decided (which channel to watch is `watch`, full stop);
 *  - `heat` and `tags` exist in the payload for other clients and are deliberately NOT modelled here.
 */

val JellyTvJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

@Serializable
data class JtvInfo(
    val apiVersion: Int = 0,
    val pluginBuild: String? = null,
    val features: List<String> = emptyList(),
    val pollSeconds: Int = 15,
    val latestEventId: Long = 0,
)

@Serializable
data class JtvBoard(
    val serverTime: String = "",
    val games: List<JtvGame> = emptyList(),
    val channels: List<JtvChannel> = emptyList(),
    val events: List<JtvEvent> = emptyList(),
    /** One document per feature module, keyed by module name (e.g. "fantasy"). Opaque to the core app. */
    val modules: Map<String, JsonElement> = emptyMap(),
    /** league/module -> why its last refresh failed. Shown to the user; never treated as "no games". */
    val errors: Map<String, String> = emptyMap(),
)

@Serializable
data class JtvTeam(
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
)

/** Where to watch a game. Resolved on the server. */
@Serializable
data class JtvWatch(
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
data class JtvGame(
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
    val home: JtvTeam = JtvTeam(),
    val away: JtvTeam = JtvTeam(),
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
    val watch: JtvWatch? = null,
    /** Per-module additions keyed by module name. Feature modules read their own key; everything else ignores it. */
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    val isLive: Boolean get() = state == "in"

    /** The settings key under which [team] is followed. */
    fun teamKey(team: JtvTeam): String = "${league.uppercase()}:${team.abbr.uppercase()}"

    val isUpcoming: Boolean get() = state == "pre"
    val isFinal: Boolean get() = state == "post"
}

@Serializable
data class JtvProgramme(
    val title: String = "",
    val start: String = "",
    val end: String = "",
)

@Serializable
data class JtvChannel(
    val id: String = "",
    val name: String = "",
    val group: String = "",
    val logo: String? = null,
    val liveTvItemId: String? = null,
    val hlsPath: String = "",
    val cardPath: String = "",
    val gameId: String? = null,
    val now: JtvProgramme? = null,
    val next: JtvProgramme? = null,
)

@Serializable
data class JtvEvent(
    val id: Long = 0,
    /** Producing module: "scores" today, "fantasy" later. Users opt in per source. */
    val source: String = "",
    val kind: String = "",
    val gameId: String? = null,
    val title: String = "",
    val text: String = "",
    val createdAt: String = "",
    val watch: JtvWatch? = null,
)

/** Per-user settings shared with the web UI (same server-side store). Unknown keys must round-trip. */
@Serializable
data class JtvSettings(
    val favorites: List<String> = emptyList(),
    val hideScores: Boolean = false,
    val lastChannel: String? = null,
    /** "My channels only" on the Games board; null = never chosen, so the app decides from the board. */
    val onlyWatchable: Boolean? = null,
    /** Followed teams as "LEAGUE:ABBR" (e.g. "NFL:KC"); their games are pinned first and get start nudges. */
    val favoriteTeams: List<String> = emptyList(),
)
