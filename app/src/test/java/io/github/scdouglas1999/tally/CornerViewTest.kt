package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.ui.player.cornerChannelName
import io.github.scdouglas1999.tally.ui.player.cornerLiveGame
import io.github.scdouglas1999.tally.ui.player.cornerScoreLine
import io.github.scdouglas1999.tally.ui.player.cornerStreamUrl
import io.github.scdouglas1999.tally.ui.player.gameToCornerAfterSwap
import io.github.scdouglas1999.tally.ui.player.gamelessChannelGames
import io.github.scdouglas1999.tally.ui.player.shouldShowCornerRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerViewTest {
    private val board: TallyBoard by lazy {
        val text =
            javaClass
                .getResource("/tally/board-sample.json")!!
                .readText()
        TallyJson.decodeFromString<TallyBoard>(text)
    }

    @Test
    fun `live score line uses the sample board`() {
        val nfl = board.games.first { it.id == "401872945" }
        assertEquals("IND 7 · KC 0 · 8:25 1ST", cornerScoreLine(nfl, hideScores = false))
        val mlb = board.games.first { it.id == "401817017" }
        assertEquals("PHI 4 · NYM 2 · TOP 8TH", cornerScoreLine(mlb, hideScores = false))
    }

    @Test
    fun `score line is omitted when scores are hidden or the game is not live`() {
        val live = board.games.first { it.isLive }
        assertNull(cornerScoreLine(live, hideScores = true))
        val upcoming = board.games.first { it.isUpcoming }
        assertNull(cornerScoreLine(upcoming, hideScores = false))
        assertNull(cornerScoreLine(null, hideScores = false))
    }

    @Test
    fun `a missing score is an en dash`() {
        val live = board.games.first { it.id == "401872945" }
        val noAwayScore = live.copy(away = live.away.copy(score = null))
        assertEquals("IND \u2013 · KC 0 · 8:25 1ST", cornerScoreLine(noAwayScore, hideScores = false))
    }

    @Test
    fun `stream url is the channel hls path, not a shorter watch path`() {
        val channelId = "dea2bdfac2d6739c"
        val channelPath = board.channels.first { it.id == channelId }.hlsPath
        val game = board.games.first { it.watch?.channelId == channelId }
        val diverged =
            board.copy(
                games =
                    listOf(
                        game.copy(
                            watch =
                                game.watch!!.copy(
                                    hlsPath = "/JellyTV/Live/dea2bdfac2d6739c.m3u8?s=95c3",
                                ),
                        ),
                    ),
            )
        val url = cornerStreamUrl(diverged.channels, channelId) { "http://server$it" }
        assertEquals("http://server$channelPath", url)
        assertTrue(url!!.contains("s=95c3847533b71d15"))
    }

    @Test
    fun `same-channel requests are left for the next page`() {
        assertTrue(shouldShowCornerRequest("aa11bb22cc33dd44", "dea2bdfac2d6739c"))
        assertTrue(!shouldShowCornerRequest("dea2bdfac2d6739c", "dea2bdfac2d6739c"))
        assertTrue(!shouldShowCornerRequest(null, "dea2bdfac2d6739c"))
        assertTrue(!shouldShowCornerRequest("", "dea2bdfac2d6739c"))
    }

    @Test
    fun `swap keeps the live game when the channel has one`() {
        val swapped = gameToCornerAfterSwap(board, "dea2bdfac2d6739c")
        assertEquals("401872945", swapped?.id)
        assertEquals(
            "Indianapolis Colts Kansas City Chiefs",
            cornerChannelName(board, "dea2bdfac2d6739c", null),
        )
        assertEquals("401872945", cornerLiveGame(board, "dea2bdfac2d6739c")?.id)
    }

    @Test
    fun `a channel with no live tv item cannot move into the corner`() {
        assertNull(gameToCornerAfterSwap(board, "aa11bb22cc33dd44"))
        assertTrue(gamelessChannelGames(board, "dea2bdfac2d6739c").isEmpty())
    }

    @Test
    fun `gameless channels become switcher cards`() {
        val channel = board.channels.first { it.id == "dea2bdfac2d6739c" }
        val looping =
            board.copy(
                channels =
                    listOf(
                        channel.copy(id = "plain", name = "Plain Sports Channel", gameId = null),
                        channel.copy(
                            id = "sim",
                            name = "Real Time Sim",
                            gameId = null,
                            liveTvItemId = channel.liveTvItemId,
                            hlsPath = "/JellyTV/Live/sim.m3u8?s=1",
                        ),
                    ),
                games = emptyList(),
            )
        val cards = gamelessChannelGames(looping, "sim")
        assertEquals(listOf("plain"), cards.map { it.watch?.channelId })
        assertEquals("Plain Sports Channel", cards.single().watch?.channelName)
        assertEquals(channel.liveTvItemId, cards.single().watch?.liveTvItemId)
    }
}
