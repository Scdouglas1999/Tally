package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.api.TallyWatch
import io.github.scdouglas1999.tally.ui.home.HomeRowSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HomeRowSelectionTest {
    /** The captured payload: an NFL game live on a channel, an MLB game live on nothing, an NFL game tomorrow on ESPN. */
    private val sample: TallyBoard by lazy {
        val text =
            javaClass
                .getResource("/tally/board-sample.json")!!
                .readText()
        TallyJson.decodeFromString<TallyBoard>(text)
    }

    /** [TallyBoard.serverTime] of the sample: the NFL game kicked off 15 minutes ago. */
    private val sampleNow = Instant.parse("2026-09-21T00:35:12.481Z")

    private fun game(
        id: String,
        league: String = "NFL",
        state: String = "in",
        start: String = "2026-09-21T00:20:00+00:00",
        channelId: String? = "ch-$id",
        liveTvItemId: String? = "1fcfacdbc1fa4309f9ac372a218380ed",
    ) = TallyGame(
        id = id,
        league = league,
        state = state,
        start = start,
        watch =
            channelId?.let {
                TallyWatch(channelId = it, channelName = it.uppercase(), liveTvItemId = liveTvItemId)
            },
    )

    private fun select(
        games: List<TallyGame>,
        favorites: Set<String> = emptySet(),
        now: Instant = sampleNow,
    ) = HomeRowSelection.select(TallyBoard(games = games), favorites, now)

    // --- the real payload ---

    @Test
    fun `sample board shows the live game it can play first, then the live game on no channel`() {
        val selected = HomeRowSelection.select(sample, emptySet(), sampleNow)
        // 401817017 (MLB) is live but on no channel; 401872950 (NFL) is on ESPN but ~24h away.
        assertEquals(listOf("401872945", "401817017"), selected.map { it.id })
        assertEquals("Indianapolis Colts Kansas City Chiefs", selected.first().watch?.channelName)
        assertTrue(selected.all { it.isLive })
    }

    @Test
    fun `sample board adds the upcoming game once it is inside the window`() {
        // Six hours before the 2026-09-22T00:15Z kickoff.
        val selected = HomeRowSelection.select(sample, emptySet(), Instant.parse("2026-09-21T18:15:00Z"))
        assertEquals(listOf("401872945", "401817017", "401872950"), selected.map { it.id })
    }

    @Test
    fun `sample board favorite channel still sorts first`() {
        val favorites = setOf("aa11bb22cc33dd44")
        val selected = HomeRowSelection.select(sample, favorites, Instant.parse("2026-09-21T18:15:00Z"))
        // Favorites never jump the live group: the ESPN game is still only upcoming.
        assertEquals(listOf("401872945", "401817017", "401872950"), selected.map { it.id })
    }

    // --- edges ---

    @Test
    fun `no board is no row`() {
        assertEquals(emptyList<TallyGame>(), HomeRowSelection.select(null, emptySet(), sampleNow))
    }

    @Test
    fun `nothing on a channel still shows the live games, and an empty board is no row`() {
        val dark = sample.copy(games = sample.games.map { it.copy(watch = null) })
        assertEquals(listOf("401817017", "401872945"), HomeRowSelection.select(dark, emptySet(), sampleNow).map { it.id })
        assertTrue(HomeRowSelection.select(TallyBoard(), emptySet(), sampleNow).isEmpty())
    }

    @Test
    fun `final games never appear`() {
        val selected =
            select(
                listOf(
                    game("done", state = "post"),
                    game("live", state = "in"),
                ),
            )
        assertEquals(listOf("live"), selected.map { it.id })
    }

    @Test
    fun `at most ten cards, live before upcoming`() {
        val games =
            (1..12).map { game("live-$it", start = "2026-09-21T00:%02d:00+00:00".format(it)) } +
                game("soon", state = "pre", start = "2026-09-21T02:00:00+00:00")
        val selected = select(games)
        assertEquals(HomeRowSelection.MAX_GAMES, selected.size)
        assertEquals("live-1", selected.first().id)
        assertTrue(selected.none { it.id == "soon" })
    }

    // --- order ---

    @Test
    fun `live games come before upcoming ones whatever their start`() {
        val selected =
            select(
                listOf(
                    game("soon", state = "pre", start = "2026-09-21T01:00:00+00:00"),
                    game("live", state = "in", start = "2026-09-21T00:20:00+00:00"),
                ),
            )
        assertEquals(listOf("live", "soon"), selected.map { it.id })
    }

    @Test
    fun `favorites first, then league, then start time`() {
        val selected =
            select(
                listOf(
                    game("nhl", league = "NHL", start = "2026-09-21T00:10:00+00:00"),
                    game("mlb-late", league = "MLB", start = "2026-09-21T00:30:00+00:00"),
                    game("mlb-early", league = "MLB", start = "2026-09-21T00:05:00+00:00"),
                    game("fav", league = "NHL", channelId = "loved"),
                ),
                favorites = setOf("loved"),
            )
        assertEquals(listOf("fav", "mlb-early", "mlb-late", "nhl"), selected.map { it.id })
    }

    @Test
    fun `upcoming games are ordered by start time`() {
        val selected =
            select(
                listOf(
                    game("third", state = "pre", start = "2026-09-21T06:00:00+00:00"),
                    game("first", state = "pre", start = "2026-09-21T01:00:00+00:00"),
                    game("second", state = "pre", start = "2026-09-21T03:00:00+00:00"),
                ),
            )
        assertEquals(listOf("first", "second", "third"), selected.map { it.id })
    }

    // --- the upcoming window ---

    @Test
    fun `upcoming games beyond twelve hours are left off`() {
        val selected =
            select(
                listOf(
                    game("in-window", state = "pre", start = "2026-09-21T12:00:00+00:00"),
                    game("tomorrow", state = "pre", start = "2026-09-21T13:00:00+00:00"),
                ),
            )
        assertEquals(listOf("in-window"), selected.map { it.id })
    }

    @Test
    fun `a game that was due minutes ago is still upcoming, hours ago is not`() {
        val selected =
            select(
                listOf(
                    game("late-start", state = "pre", start = "2026-09-21T00:05:00+00:00"),
                    game("stale", state = "pre", start = "2026-09-20T20:00:00+00:00"),
                ),
            )
        assertEquals(listOf("late-start"), selected.map { it.id })
    }

    @Test
    fun `an unparseable start is never upcoming but a live game keeps its card`() {
        val selected =
            select(
                listOf(
                    game("junk-pre", state = "pre", start = "not-a-date"),
                    game("junk-live", state = "in", start = "not-a-date"),
                ),
            )
        assertEquals(listOf("junk-live"), selected.map { it.id })
    }

    @Test
    fun `games with no channel come after the ones you can watch`() {
        val selected =
            select(
                listOf(
                    game("dark", channelId = null),
                    game("watchable"),
                ),
            )
        assertEquals(listOf("watchable", "dark"), selected.map { it.id })
    }

    @Test
    fun `a game you can watch is never cut for one on no channel`() {
        val dark = (1..12).map { game("dark$it", channelId = null) }
        val soon = game("soon", state = "pre", start = "2026-09-21T02:00:00+00:00")
        val selected = select(dark + soon)
        assertEquals(10, selected.size)
        // live games still come first, so the upcoming game you can watch is last, but it is there
        assertEquals("soon", selected.last().id)
    }
}
