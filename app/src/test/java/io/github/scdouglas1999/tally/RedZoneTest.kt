package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.api.TallyRedZone
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.RedZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RedZone channel: decoding its channel and status, and when the Games board shows its tile. */
class RedZoneTest {
    private fun text(name: String): String = javaClass.getResource("/tally/$name")!!.readText()

    private val board: TallyBoard by lazy { TallyJson.decodeFromString<TallyBoard>(text("board-23-language-redzone.json")) }
    private val oldBoard: TallyBoard by lazy { TallyJson.decodeFromString<TallyBoard>(text("board-sample.json")) }
    private val status: TallyRedZone by lazy { TallyJson.decodeFromString<TallyRedZone>(text("redzone-status.json")) }

    @Test
    fun `decodes the RedZone channel`() {
        val channel = RedZone.channel(board)
        assertNotNull(channel)
        assertEquals("redzone", channel!!.id)
        assertEquals("Tally RedZone", channel.name)
        assertTrue(channel.isRedZone)
        assertEquals(1, board.channels.count { it.isRedZone })
    }

    @Test
    fun `decodes the status`() {
        assertTrue(status.active)
        assertEquals("401900101", status.gameId)
        assertEquals("Colts at Texans", status.title)
        assertEquals("red zone", status.reason)
        assertEquals(listOf("401900102", "401900103"), status.next)
    }

    @Test
    fun `an idle status decodes with nulls`() {
        val idle =
            TallyJson.decodeFromString<TallyRedZone>(
                """{ "active": false, "gameId": null, "title": null, "reason": null, "since": null, "next": [] }""",
            )
        assertFalse(idle.active)
        assertNull(idle.gameId)
        assertNull(RedZone.tile(board, idle))
    }

    @Test
    fun `the tile shows when the board has the channel and RedZone is on`() {
        val tile = RedZone.tile(board, status)
        assertNotNull(tile)
        assertEquals("redzone", tile!!.channel.id)
        assertEquals("401900101", tile.game?.id)
    }

    @Test
    fun `no tile without the channel, even when a status says active`() {
        assertNull(RedZone.tile(oldBoard, status))
        assertNull(RedZone.tile(null, status))
    }

    @Test
    fun `no tile while the status is unknown or inactive`() {
        assertNull(RedZone.tile(board, null))
        assertNull(RedZone.tile(board, status.copy(active = false)))
    }

    @Test
    fun `a game the board does not have leaves the tile without a game`() {
        val tile = RedZone.tile(board, status.copy(gameId = "999"))
        assertNotNull(tile)
        assertNull(tile!!.game)
    }

    @Test
    fun `the tile leads the first live row`() {
        val rows = BoardOrganizer.rows(board.games, emptySet(), onlyWatchable = false)
        val index = RedZone.rowIndex(rows)
        assertEquals("in", rows[index].state)
        assertEquals(0, index)
        val noLive = BoardOrganizer.rows(board.games.map { it.copy(state = "post") }, emptySet(), onlyWatchable = false)
        assertEquals(-1, RedZone.rowIndex(noLive))
    }

    @Test
    fun `the player knows RedZone by its kind or its fixed id`() {
        assertTrue(RedZone.isRedZone("redzone", board))
        assertTrue(RedZone.isRedZone("redzone", null))
        assertFalse(RedZone.isRedZone("c0115a7e0a000001", board))
        assertFalse(RedZone.isRedZone(null, board))
    }

    @Test
    fun `the player follows the game RedZone is on`() {
        assertEquals("401900101", RedZone.gameOn(status, board.games)?.id)
        assertEquals("401900102", RedZone.gameOn(status.copy(gameId = "401900102"), board.games)?.id)
        assertNull(RedZone.gameOn(status.copy(active = false), board.games))
        assertNull(RedZone.gameOn(null, board.games))
    }

    @Test
    fun `ordinary channels are not RedZone and the RedZone channel carries no game`() {
        assertNull(BoardOrganizer.gameFor("redzone", board.games))
        assertFalse(board.channels.first { it.id == "c0115a7e0a000001" }.isRedZone)
    }
}
