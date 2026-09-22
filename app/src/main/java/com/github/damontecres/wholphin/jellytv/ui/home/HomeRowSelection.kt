package com.github.damontecres.wholphin.jellytv.ui.home

import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * What the JellyTV home row shows, and in what order. Pure logic, no Android dependencies.
 *
 * The row is a glance at what is on right now, so it is much narrower than the board:
 *  - only games the server resolved a channel for ([JtvGame.watch] != null) — a card you cannot
 *    press does not belong on someone else's home screen;
 *  - live games first, then games that start within [UPCOMING_WINDOW] (a game that was due up to
 *    [START_GRACE] ago still counts: scoreboards flip to "in" a few minutes late);
 *  - inside each of those two groups: favorites first, then league, then start time;
 *  - at most [MAX_GAMES] cards.
 *
 * This is a per-game order, not `BoardOrganizer`'s per-row order: the home row is one flat row, so
 * a favorite sorts ahead of every other game rather than ahead of every other league.
 */
object HomeRowSelection {
    /** How far ahead an upcoming game may start and still earn a card. */
    val UPCOMING_WINDOW: Duration = Duration.ofHours(12)

    /** How long after its listed start a game may still be "upcoming" (feed lag at kickoff). */
    val START_GRACE: Duration = Duration.ofHours(1)

    /** The row is a glance, not the board. */
    const val MAX_GAMES = 10

    fun select(
        board: JtvBoard?,
        favorites: Set<String>,
        now: Instant,
    ): List<JtvGame> {
        val watchable = board?.games?.filter { it.watch != null }.orEmpty()
        if (watchable.isEmpty()) return emptyList()
        val order = order(favorites)
        val live = watchable.filter { it.isLive }.sortedWith(order)
        val soon = watchable.filter { it.isUpcoming && startsSoon(it.start, now) }.sortedWith(order)
        return (live + soon).take(MAX_GAMES)
    }

    private fun order(favorites: Set<String>): Comparator<JtvGame> =
        compareByDescending<JtvGame> { it.watch?.channelId in favorites }
            .thenBy { it.league }
            .thenComparator { a, b -> compareStart(a.start, b.start) }

    /** True when [start] lands inside the window around [now]. An unparseable start is never "soon". */
    private fun startsSoon(
        start: String,
        now: Instant,
    ): Boolean {
        val instant = parseStart(start) ?: return false
        return !instant.isAfter(now.plus(UPCOMING_WINDOW)) && !instant.isBefore(now.minus(START_GRACE))
    }

    private fun compareStart(
        a: String,
        b: String,
    ): Int {
        val left = parseStart(a)
        val right = parseStart(b)
        return if (left != null && right != null) left.compareTo(right) else a.compareTo(b)
    }

    private fun parseStart(value: String): Instant? =
        try {
            OffsetDateTime.parse(value).toInstant()
        } catch (e: Exception) {
            null
        }
}
