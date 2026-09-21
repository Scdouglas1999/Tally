package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvWatch
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardOrganizerTest {
    private fun game(
        id: String,
        league: String = "NFL",
        state: String = "in",
        start: String = "2026-09-21T00:00:00+00:00",
        channelId: String? = null,
    ) = JtvGame(
        id = id,
        league = league,
        state = state,
        start = start,
        watch = channelId?.let { JtvWatch(channelId = it) },
    )

    private fun rows(
        games: List<JtvGame>,
        favorites: Set<String> = emptySet(),
        onlyWatchable: Boolean = false,
    ) = BoardOrganizer.rows(games, favorites, onlyWatchable)

    @Test
    fun `rows are ordered live then upcoming then final`() {
        val rows =
            rows(
                listOf(
                    game("post", state = "post"),
                    game("pre", state = "pre"),
                    game("in", state = "in"),
                ),
            )
        assertEquals(listOf("in", "pre", "post"), rows.map { it.state })
        assertEquals(listOf("in", "pre", "post"), rows.map { it.games.single().id })
        assertEquals(listOf("NFL|in", "NFL|pre", "NFL|post"), rows.map { it.key })
    }

    @Test
    fun `leagues are alphabetical within the same state`() {
        val rows =
            rows(
                listOf(
                    game("1", league = "NFL"),
                    game("2", league = "MLB"),
                    game("3", league = "NBA"),
                ),
            )
        assertEquals(listOf("MLB", "NBA", "NFL"), rows.map { it.league })
    }

    @Test
    fun `favorite row sorts before non-favorite rows within a state`() {
        // Alphabetically MLB < NFL, but the NFL row has a favorite channel.
        val rows =
            rows(
                listOf(
                    game("nfl", league = "NFL", channelId = "fav"),
                    game("mlb", league = "MLB", channelId = "other"),
                ),
                favorites = setOf("fav"),
            )
        assertEquals(listOf("NFL", "MLB"), rows.map { it.league })
    }

    @Test
    fun `favorites do not cross state groups`() {
        val rows =
            rows(
                listOf(
                    game("nfl-post", league = "NFL", state = "post", channelId = "fav"),
                    game("mlb-in", league = "MLB", state = "in", channelId = "other"),
                ),
                favorites = setOf("fav"),
            )
        assertEquals(listOf("MLB|in", "NFL|post"), rows.map { it.key })
    }

    @Test
    fun `favorites come first inside a row`() {
        val row =
            rows(
                listOf(
                    game("a", channelId = "x"),
                    game("b", channelId = "fav"),
                    game("c", channelId = "y"),
                ),
                favorites = setOf("fav"),
            ).single()
        assertEquals("b", row.games.first().id)
    }

    @Test
    fun `games inside a row are ordered by start time`() {
        val row =
            rows(
                listOf(
                    game("late", start = "2026-09-21T03:00:00+00:00"),
                    game("early", start = "2026-09-21T01:00:00+00:00"),
                    game("mid", start = "2026-09-21T02:00:00+00:00"),
                ),
            ).single()
        assertEquals(listOf("early", "mid", "late"), row.games.map { it.id })
    }

    @Test
    fun `start ordering handles offsets and bad strings`() {
        val row =
            rows(
                listOf(
                    game("z", start = "not-a-date"),
                    game("b", start = "2026-09-21T02:30:00+00:00"),
                    // 01:30 at +02:00 is 23:30Z, earlier than both
                    game("a", start = "2026-09-21T01:30:00+02:00"),
                ),
            ).single()
        // "a" parses and is earliest; "not-a-date" fails to parse so string compare is used
        assertEquals("a", row.games.first().id)
    }

    @Test
    fun `post rows are newest first`() {
        val row =
            rows(
                listOf(
                    game("early", state = "post", start = "2026-09-21T01:00:00+00:00"),
                    game("late", state = "post", start = "2026-09-21T03:00:00+00:00"),
                ),
            ).single()
        assertEquals(listOf("late", "early"), row.games.map { it.id })
    }

    @Test
    fun `onlyWatchable drops games with no watch`() {
        val rows =
            rows(
                listOf(
                    game("watchable", channelId = "x"),
                    game("dark"),
                ),
                onlyWatchable = true,
            )
        assertEquals(listOf("watchable"), rows.single().games.map { it.id })
    }

    @Test
    fun `empty rows are never returned`() {
        assertTrue(rows(listOf(game("dark")), onlyWatchable = true).isEmpty())
        assertTrue(rows(emptyList()).isEmpty())
    }

    @Test
    fun `gameFor returns the live game on a channel`() {
        val games =
            listOf(
                game("pre", state = "pre", channelId = "ch"),
                game("live", state = "in", channelId = "ch"),
                game("other", state = "in", channelId = "nope"),
            )
        assertEquals("live", BoardOrganizer.gameFor("ch", games)?.id)
        assertNull(BoardOrganizer.gameFor("missing", games))
    }
}
