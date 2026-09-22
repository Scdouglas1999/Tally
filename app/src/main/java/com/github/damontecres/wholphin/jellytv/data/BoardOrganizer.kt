package com.github.damontecres.wholphin.jellytv.data

import com.github.damontecres.wholphin.jellytv.api.JtvGame
import java.time.OffsetDateTime

/**
 * One horizontally scrolling row on the games board: a league + state group
 * ("NFL / LIVE"), keyed as `"$league|$state"`.
 */
data class BoardRow(
    val key: String,
    val league: String,
    val state: String,
    val games: List<JtvGame>,
)

/**
 * Groups and orders board games. Pure logic, no Android dependencies.
 *
 * Board order: live before upcoming before final; within a state, rows that
 * contain a favorite game first, then leagues alphabetically. Within a row:
 * favorites first, then by start time (newest first for final games).
 */
object BoardOrganizer {
    private fun stateOrder(state: String): Int =
        when (state) {
            "in" -> 0
            "pre" -> 1
            "post" -> 2
            else -> 3
        }

    private fun compareStart(
        a: String,
        b: String,
    ): Int =
        try {
            OffsetDateTime.parse(a).compareTo(OffsetDateTime.parse(b))
        } catch (e: Exception) {
            a.compareTo(b)
        }

    fun rows(
        games: List<JtvGame>,
        favoriteChannelIds: Set<String>,
        onlyWatchable: Boolean,
        favoriteTeams: Set<String> = emptySet(),
    ): List<BoardRow> {
        fun JtvGame.isFavorite() = isFollowed(favoriteTeams) || watch?.channelId?.let { it in favoriteChannelIds } == true

        val visible = if (onlyWatchable) games.filter { it.watch != null } else games
        return visible
            .groupBy { it.league to it.state }
            .map { (leagueState, rowGames) ->
                val (league, state) = leagueState
                val sorted =
                    rowGames.sortedWith(
                        compareByDescending<JtvGame> { it.isFavorite() }.thenComparator { a, b ->
                            val byStart = compareStart(a.start, b.start)
                            if (state == "post") -byStart else byStart
                        },
                    )
                BoardRow(
                    key = "$league|$state",
                    league = league,
                    state = state,
                    games = sorted,
                )
            }.sortedWith(
                compareBy<BoardRow> { stateOrder(it.state) }
                    .thenByDescending { row -> row.games.any { it.isFavorite() } }
                    .thenBy { it.league },
            )
    }

    /** The first live game shown on the given channel, if any. */
    fun gameFor(
        channelId: String,
        games: List<JtvGame>,
    ): JtvGame? = games.firstOrNull { it.isLive && it.watch?.channelId == channelId }
}

/** True when either team of this game is followed. */
fun JtvGame.isFollowed(favoriteTeams: Set<String>): Boolean = teamKey(away) in favoriteTeams || teamKey(home) in favoriteTeams
