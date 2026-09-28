package io.github.scdouglas1999.tally

import androidx.compose.ui.unit.sp
import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.ui.components.rankLabel
import io.github.scdouglas1999.tally.ui.components.rankedName
import io.github.scdouglas1999.tally.ui.components.rankedText
import io.github.scdouglas1999.tally.ui.components.showsUpsetAlert
import io.github.scdouglas1999.tally.ui.player.cornerScoreLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** College football on the board: AP Top 25 ranks before team names and the server's UPSET ALERT tag. */
class CollegeRankTest {
    private fun text(name: String): String = javaClass.getResource("/tally/$name")!!.readText()

    private val board: TallyBoard by lazy { TallyJson.decodeFromString<TallyBoard>(text("board-233-college.json")) }
    private val oldBoard: TallyBoard by lazy { TallyJson.decodeFromString<TallyBoard>(text("board-sample.json")) }

    private fun game(id: String): TallyGame = board.games.first { it.id == id }

    @Test
    fun `decodes the poll rank of each team`() {
        val upset = game("401752101")
        assertEquals(5, upset.home.rank)
        assertEquals(5, upset.home.pollRank)
        assertNull(upset.away.rank)
        assertNull(upset.away.pollRank)
        val top = game("401752102")
        assertEquals(1, top.away.pollRank)
        assertEquals(7, top.home.pollRank)
    }

    @Test
    fun `a rank of zero is no rank`() {
        val g = game("401752103")
        assertEquals(0, g.home.rank)
        assertNull(g.home.pollRank)
        assertNull(rankLabel(g.home))
        assertEquals("#12", rankLabel(g.away))
    }

    @Test
    fun `a board without ranks or tags decodes as unranked and untagged`() {
        assertTrue(oldBoard.games.isNotEmpty())
        oldBoard.games.forEach { g ->
            assertNull(g.home.pollRank)
            assertNull(g.away.pollRank)
            assertFalse(g.isUpsetAlert)
        }
        val bare = TallyJson.decodeFromString<TallyGame>("""{ "id": "1", "home": { "abbr": "A" }, "away": { "abbr": "B" } }""")
        assertNull(bare.home.rank)
        assertTrue(bare.tags.isEmpty())
        assertFalse(bare.isUpsetAlert)
    }

    @Test
    fun `rank labels lead the team name`() {
        val g = game("401752101")
        assertEquals("#5", rankLabel(g.home))
        assertNull(rankLabel(g.away))
        assertEquals("#5 Alabama", rankedName(g.home, "Alabama", 20.sp).text)
        assertEquals("Vanderbilt", rankedName(g.away, "Vanderbilt", 20.sp).text)
        assertEquals("#5 ALA", rankedText(g.home, "ALA"))
        assertEquals("VAN", rankedText(g.away, "VAN"))
    }

    @Test
    fun `the corner score line carries ranks`() {
        assertEquals("VAN 24 · #5 ALA 17 · 6:41 4TH", cornerScoreLine(game("401752101"), hideScores = false))
        assertEquals("#12 UGA 14 · AUB 10 · 12:02 3RD", cornerScoreLine(game("401752103"), hideScores = false))
    }

    @Test
    fun `reads the UPSET ALERT tag in any case`() {
        assertTrue(game("401752101").isUpsetAlert)
        assertTrue(game("401752103").isUpsetAlert)
        assertFalse(game("401752102").isUpsetAlert)
    }

    @Test
    fun `hidden scores hide the upset alert`() {
        val upset = game("401752101")
        assertTrue(showsUpsetAlert(upset, hideScores = false))
        assertFalse(showsUpsetAlert(upset, hideScores = true))
        assertFalse(showsUpsetAlert(game("401752102"), hideScores = false))
        assertFalse(showsUpsetAlert(null, hideScores = false))
    }
}
