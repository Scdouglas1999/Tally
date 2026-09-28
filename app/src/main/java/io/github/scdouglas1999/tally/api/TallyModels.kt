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
 *  - `heat` exists in the payload for other clients and is deliberately NOT modeled here; `tags` is read only for the
 *    UPSET ALERT tag ([TallyGame.isUpsetAlert]), which the app shows as the web app does.
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
    /**
     * Poll rank (college football: the AP Top 25, 1–25); null when unranked, for a league with no poll, or from a
     * server that predates it. Read [pollRank], which drops a value that is not a rank.
     */
    val rank: Int? = null,
    val possession: Boolean = false,
    val winner: Boolean = false,
    /** Points per period (quarter, inning, …) in order; empty before the game starts. */
    val periods: List<Int> = emptyList(),
    /** Team color, six hex digits without '#' ("132448"); empty when unknown. */
    val color: String = "",
    /** Alternate team color, same format. */
    val altColor: String = "",
) {
    /** [rank] when it is one (1 or more); null otherwise. */
    val pollRank: Int? get() = rank?.takeIf { it > 0 }
}

/** Where to watch a game. Resolved on the server. */
@Serializable
data class TallyWatch(
    val channelId: String = "",
    val channelName: String = "",
    /** Jellyfin Live TV item id (32 hex chars, no dashes) for the app's own player; null for a minute or two after a channel first appears. */
    val liveTvItemId: String? = null,
    /**
     * Root-relative, signed, anonymous HLS playlist. Prefix with the server base URL. Multiview plays it, and so does
     * WATCH while [liveTvItemId] is not ready yet.
     */
    val hlsPath: String = "",
    /** Root-relative 16:9 PNG card. Prefix with the server base URL. */
    val cardPath: String = "",
    /** "teams" | "epg" | "network" (a broadcaster match may be a different regional game). */
    val confidence: String = "",
    /** The commentary's language, "en" or "es"; empty from a server that predates it (which means English). */
    val language: String = "",
) {
    /** The commentary's language, English when the server does not say. */
    val commentary: String get() = language.ifBlank { TallyLanguage.ENGLISH }
}

/** Commentary languages as the server names them (ISO 639-1). */
object TallyLanguage {
    const val ENGLISH = "en"
    const val SPANISH = "es"
}

/**
 * One commentary of a game that has more than one (`GameInfo.feeds`, English first). [TallyGame.watch] is already the
 * viewer's preferred one; the others are offered as "Watch in Español" / "Watch in English".
 */
@Serializable
data class TallyFeed(
    /** "en" | "es" */
    val language: String = "",
    /** "English" | "Español", as the server names it. */
    val label: String = "",
    val watch: TallyWatch? = null,
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
    /**
     * The server's search for a stream, on a game that is live or starts within 30 minutes and has no [watch].
     * Null when the game has a [watch], is final, or the server predates it.
     */
    val search: TallySearch? = null,
    /**
     * Every playable commentary, English first, when the game has more than one; empty otherwise and from a server
     * that predates it.
     */
    val feeds: List<TallyFeed> = emptyList(),
    /**
     * The server's short reasons the game is worth watching, most important first ("RED ZONE", "UPSET ALERT", …).
     * Only [isUpsetAlert] reads it; empty from a server that predates it.
     */
    val tags: List<String> = emptyList(),
) {
    val isLive: Boolean get() = state == "in"

    /** A ranked college team is losing late to a lower-ranked or unranked one (the server's UPSET ALERT tag). */
    val isUpsetAlert: Boolean get() = tags.any { it.equals(TAG_UPSET_ALERT, ignoreCase = true) }

    /** The settings key under which [team] is followed. */
    fun teamKey(team: TallyTeam): String = "${league.uppercase()}:${team.abbr.uppercase()}"

    val isUpcoming: Boolean get() = state == "pre"
    val isFinal: Boolean get() = state == "post"

    companion object {
        /** The server's tag for a likely upset (GameHeat). */
        const val TAG_UPSET_ALERT = "UPSET ALERT"
    }
}

/** Where the server's search for a game's stream stands. */
@Serializable
data class TallySearch(
    /** "searching": a search that covers this game is running now; "waiting": none is, the next is due at [nextAt]. */
    val state: String = "",
    /** ISO-8601; null before the first search. */
    val lastAt: String? = null,
    /** ISO-8601; null when none is scheduled. */
    val nextAt: String? = null,
) {
    val isSearching: Boolean get() = state == "searching"
}

/** The answer to `POST games/{id}/find`. */
@Serializable
data class TallyFindResult(
    /** "found" (with [watch]) | "searching" | "none" (a search finished within the last minute and found nothing). */
    val state: String = "",
    val watch: TallyWatch? = null,
)

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
    /** "en" | "es"; empty from a server that predates it. */
    val language: String = "",
    /** "redzone" for the server's RedZone channel; empty for every ordinary channel. */
    val kind: String = "",
) {
    val isRedZone: Boolean get() = kind == KIND_REDZONE

    companion object {
        const val KIND_REDZONE = "redzone"
    }
}

/** The answer to `GET redzone`: what the RedZone channel is showing right now. */
@Serializable
data class TallyRedZone(
    /** False while no live game has a stream (the channel shows its slate). */
    val active: Boolean = false,
    val gameId: String? = null,
    /** "Chiefs at Bills" */
    val title: String? = null,
    /** "red zone" | "score" | "two-minute drill" | "overtime" | "close" | "hottest" */
    val reason: String? = null,
    /** ISO-8601: when it cut to this game. */
    val since: String? = null,
    /** Game ids it would cut to next, hottest first. */
    val next: List<String> = emptyList(),
    /**
     * The channel's last cuts as its players got them, oldest first (2.3; empty from a server that predates it, and
     * while nobody watches): each `since` is when that cut entered the channel's playlist.
     */
    val recent: List<TallyRedZoneCut> = emptyList(),
    /** The server's clock when it answered (ISO-8601; empty from an older server). */
    val serverTime: String? = null,
)

/** One of [TallyRedZone.recent]: from [since] on the channel carries [gameId] (inactive: the "No games live" slate). */
@Serializable
data class TallyRedZoneCut(
    val active: Boolean = false,
    val gameId: String? = null,
    val title: String? = null,
    val reason: String? = null,
    val since: String? = null,
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
    /** "Only games with a stream" on the Games board; null = never chosen, which means off. */
    val onlyWatchable: Boolean? = null,
    /** Followed teams as "LEAGUE:ABBR" (e.g. "NFL:KC"); their games are pinned first and get start nudges. */
    val favoriteTeams: List<String> = emptyList(),
    /** Commentary language WATCH prefers: "en" (also when unset) or "es". The server reads it to pick `watch`. */
    val streamLanguage: String? = null,
)
