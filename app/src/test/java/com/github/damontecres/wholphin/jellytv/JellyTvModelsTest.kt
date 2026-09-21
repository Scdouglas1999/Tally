package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.api.JellyTvJson
import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyTvModelsTest {
    private val board: JtvBoard by lazy {
        val text =
            javaClass
                .getResource("/jellytv/board-sample.json")!!
                .readText()
        JellyTvJson.decodeFromString<JtvBoard>(text)
    }

    @Test
    fun `decodes the sample board`() {
        assertEquals("2026-09-21T00:35:12.481Z", board.serverTime)
        assertEquals(3, board.games.size)
    }

    @Test
    fun `first game is live with watch info`() {
        val game = board.games[0]
        assertTrue(game.isLive)
        assertEquals("in", game.state)
        assertEquals("NFL", game.league)
        val watch = game.watch
        assertNotNull(watch)
        assertEquals("1fcfacdbc1fa4309f9ac372a218380ed", watch!!.liveTvItemId)
        assertEquals("dea2bdfac2d6739c", watch.channelId)
        assertEquals("teams", watch.confidence)
        assertTrue(watch.hlsPath.startsWith("/JellyTV/Live/"))
    }

    @Test
    fun `baseball game decodes count and runners`() {
        val game = board.games[1]
        assertEquals("baseball", game.sport)
        assertEquals("MLB", game.league)
        assertEquals(3, game.balls)
        assertEquals(1, game.strikes)
        assertEquals(1, game.outs)
        assertTrue(game.onFirst)
        assertTrue(game.onSecond)
        assertFalse(game.onThird)
        assertNull(game.watch)
        assertTrue(game.extras.containsKey("fantasy"))
    }

    @Test
    fun `unknown keys are ignored`() {
        // heat, tags, channels, nickname, homeWinPct, clockSeconds are not modelled
        val game = board.games[1]
        assertEquals("PHI @ NYM", game.name)
        assertEquals("Top 8th", game.detail)
        assertEquals("Pitch 4 : Ball 3", game.lastPlay)
    }

    @Test
    fun `channels and events decode`() {
        assertEquals(2, board.channels.size)
        assertEquals("dea2bdfac2d6739c", board.channels[0].id)
        assertEquals("SportsCenter", board.channels[1].now?.title)

        assertEquals(1, board.events.size)
        val event = board.events[0]
        assertEquals("scores", event.source)
        assertEquals("score", event.kind)
        assertEquals("401872945", event.gameId)
        assertEquals(1789950912001L, event.id)
        assertEquals("dea2bdfac2d6739c", event.watch?.channelId)
    }

    @Test
    fun `empty object decodes to all defaults`() {
        val empty = JellyTvJson.decodeFromString<JtvBoard>("{}")
        assertEquals("", empty.serverTime)
        assertTrue(empty.games.isEmpty())
        assertTrue(empty.channels.isEmpty())
        assertTrue(empty.events.isEmpty())
        assertTrue(empty.modules.isEmpty())
        assertTrue(empty.errors.isEmpty())
    }
}
