package io.github.scdouglas1999.tally.data

import io.github.scdouglas1999.tally.api.TallyGame
import java.time.OffsetDateTime

/**
 * One horizontally scrolling row on the games board: a league + state group
 * ("NFL / LIVE"), keyed as `"$league|$state"`.
 */
data class BoardRow(
    val key: String,
    val league: String,
    val state: String,
    val games: List<TallyGame>,
)

/**
 * Groups and orders board games. Pure logic, no Android dependencies.
 *
 * Board order: live before upcoming before final before postponed; within a state, rows that
 * contain a favorite game first, then leagues alphabetically. Within a row:
 * favorites first, then by start time (newest first for final and postponed games).
 * Postponed and canceled games (the feed reports them as `post`) get their own rows,
 * state [POSTPONED] ("MLB / POSTPONED"), after the finals: they are not results.
 */
object BoardOrganizer {
    /** The row state of postponed and canceled games. */
    const val POSTPONED = "postponed"

    private val POSTPONED_DETAIL = Regex("postponed|canceled|cancelled", RegexOption.IGNORE_CASE)

    /** The row [game] is listed in: its own state, or [POSTPONED] for a postponed or canceled game. */
    fun rowState(game: TallyGame): String =
        if (game.state == "post" && POSTPONED_DETAIL.containsMatchIn(game.detail)) POSTPONED else game.state

    private fun stateOrder(state: String): Int =
        when (state) {
            "in" -> 0
            "pre" -> 1
            "post" -> 2
            POSTPONED -> 3
            else -> 4
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
        games: List<TallyGame>,
        favoriteChannelIds: Set<String>,
        onlyWatchable: Boolean,
        favoriteTeams: Set<String> = emptySet(),
    ): List<BoardRow> {
        fun TallyGame.isFavorite() = isFollowed(favoriteTeams) || watch?.channelId?.let { it in favoriteChannelIds } == true

        val visible = if (onlyWatchable) games.filter { it.watch != null } else games
        return visible
            .groupBy { it.league to rowState(it) }
            .map { (leagueState, rowGames) ->
                val (league, state) = leagueState
                val sorted =
                    rowGames.sortedWith(
                        compareByDescending<TallyGame> { it.isFavorite() }.thenComparator { a, b ->
                            val byStart = compareStart(a.start, b.start)
                            if (state == "post" || state == POSTPONED) -byStart else byStart
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
        games: List<TallyGame>,
    ): TallyGame? = games.firstOrNull { it.isLive && it.watch?.channelId == channelId }
}

/** True when either team of this game is followed. */
fun TallyGame.isFollowed(favoriteTeams: Set<String>): Boolean = teamKey(away) in favoriteTeams || teamKey(home) in favoriteTeams
