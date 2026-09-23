package io.github.scdouglas1999.tally.ui.home

import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyGame
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * What the Tally home row shows, and in what order. Pure logic, no Android dependencies.
 *
 * The row is a glance at what is on right now, so it is much narrower than the board:
 *  - live games first, then games that start within [UPCOMING_WINDOW] (a game that was due up to
 *    [START_GRACE] ago still counts: scoreboards flip to "in" a few minutes late);
 *  - games with no channel yet ([TallyGame.watch] == null) still get a card: web-page sources only
 *    list a stream around game time, so hiding them left the row empty all day. Their card says
 *    "not on your channels";
 *  - inside each of those two groups: games you can watch first, then favorites, then league, then
 *    start time;
 *  - at most [MAX_GAMES] cards, and a game you can watch is never cut to make room for one you cannot.
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
        board: TallyBoard?,
        favorites: Set<String>,
        now: Instant,
    ): List<TallyGame> {
        val games = board?.games.orEmpty()
        if (games.isEmpty()) return emptyList()
        val order = order(favorites)
        val live = games.filter { it.isLive }.sortedWith(order)
        val soon = games.filter { it.isUpcoming && startsSoon(it.start, now) }.sortedWith(order)
        val candidates = live + soon
        val (watchable, dark) = candidates.partition { it.watch != null }
        val kept = (watchable + dark).take(MAX_GAMES).toSet()
        return candidates.filter { it in kept }
    }

    private fun order(favorites: Set<String>): Comparator<TallyGame> =
        compareByDescending<TallyGame> { it.watch != null }
            .thenByDescending { it.watch?.channelId in favorites }
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
