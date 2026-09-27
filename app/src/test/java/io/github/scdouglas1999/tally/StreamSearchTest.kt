package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyFindResult
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.api.TallySearch
import io.github.scdouglas1999.tally.api.TallyWatch
import io.github.scdouglas1999.tally.ui.components.canWatch
import io.github.scdouglas1999.tally.ui.components.noStreamLabelRes
import io.github.scdouglas1999.tally.watch.FindReply
import io.github.scdouglas1999.tally.watch.StreamSearch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSearchTest {
    private val watch = TallyWatch(channelId = "c1", channelName = "Rays Phillies", hlsPath = "/JellyTV/Live/c1.m3u8")

    private class Calls {
        var finds = 0
        var boards = 0
    }

    private fun TestScope.search(
        calls: Calls,
        find: (Int) -> FindReply,
        board: (Int) -> TallyWatch? = { null },
    ) = StreamSearch(
        find = { find(++calls.finds) },
        boardWatch = { board(++calls.boards) },
        now = { currentTime },
    )

    @Test
    fun `found at once plays without waiting`() =
        runTest {
            val calls = Calls()
            val result = search(calls, { FindReply.Found(watch) }).round(keepLooking = false)
            assertEquals(watch, result)
            assertEquals(1, calls.finds)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `searching polls every 3 s until found`() =
        runTest {
            val calls = Calls()
            val result =
                search(calls, { n -> if (n < 4) FindReply.Searching else FindReply.Found(watch) })
                    .round(keepLooking = false)
            assertEquals(watch, result)
            assertEquals(4, calls.finds)
            assertEquals(9_000L, currentTime)
        }

    @Test
    fun `a round gives up after 45 s with the board as the last word`() =
        runTest {
            val calls = Calls()
            val result = search(calls, { FindReply.Searching }).round(keepLooking = false)
            assertNull(result)
            assertEquals(45_000L, currentTime)
            assertEquals(16, calls.finds)
            assertEquals(1, calls.boards)
        }

    @Test
    fun `none ends the first round after one board refresh`() =
        runTest {
            val calls = Calls()
            val result = search(calls, { FindReply.None }).round(keepLooking = false)
            assertNull(result)
            assertEquals(1, calls.finds)
            assertEquals(1, calls.boards)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `a server without find (404) behaves like none after one board refresh`() =
        runTest {
            val calls = Calls()
            val result = search(calls, { FindReply.Unsupported }).round(keepLooking = false)
            assertNull(result)
            assertEquals(1, calls.boards)
        }

    @Test
    fun `the board refresh after none can still find the stream`() =
        runTest {
            val calls = Calls()
            val result = search(calls, { FindReply.Unsupported }, { watch }).round(keepLooking = false)
            assertEquals(watch, result)
        }

    @Test
    fun `keep looking polls the board through none until a stream appears`() =
        runTest {
            val calls = Calls()
            val result =
                search(calls, { FindReply.Unsupported }, { n -> if (n == 5) watch else null })
                    .round(keepLooking = true)
            assertEquals(watch, result)
            assertEquals(12_000L, currentTime)
        }

    @Test
    fun `keep looking without a stream ends after 45 s`() =
        runTest {
            val calls = Calls()
            val result = search(calls, { FindReply.None }).round(keepLooking = true)
            assertNull(result)
            assertEquals(45_000L, currentTime)
        }

    @Test
    fun `failed calls keep polling`() =
        runTest {
            val calls = Calls()
            val result =
                search(calls, { n -> if (n < 3) FindReply.Failed else FindReply.Found(watch) })
                    .round(keepLooking = false)
            assertEquals(watch, result)
            assertEquals(2, calls.boards)
        }

    @Test
    fun `find replies decode as the plugin sends them`() {
        val found = TallyJson.decodeFromString<TallyFindResult>("""{"state":"found","watch":{"channelId":"c1","hlsPath":"/x.m3u8"}}""")
        assertEquals(FindReply.Found(TallyWatch(channelId = "c1", hlsPath = "/x.m3u8")), FindReply.of(found))
        assertEquals(FindReply.Searching, FindReply.of(TallyJson.decodeFromString("""{"state":"searching","watch":null}""")))
        assertEquals(FindReply.None, FindReply.of(TallyJson.decodeFromString("""{"state":"none","watch":null}""")))
    }

    @Test
    fun `board search field decodes and is absent by default`() {
        val game =
            TallyJson.decodeFromString<TallyGame>(
                """{"id":"1","state":"in","search":{"state":"searching","lastAt":null,"nextAt":null}}""",
            )
        assertEquals(TallySearch(state = "searching"), game.search)
        assertTrue(game.search!!.isSearching)
        assertNull(TallyJson.decodeFromString<TallyGame>("""{"id":"1"}""").search)
    }

    @Test
    fun `label says what the server is doing about a game without a stream`() {
        val live = TallyGame(id = "1", state = "in")
        assertEquals(R.string.tally_221_looking_for_stream, noStreamLabelRes(live.copy(search = TallySearch("searching"))))
        assertEquals(R.string.tally_not_on_your_channels, noStreamLabelRes(live.copy(search = TallySearch("waiting"))))
        assertEquals(R.string.tally_not_on_your_channels, noStreamLabelRes(TallyGame(id = "2", state = "pre")))
        assertEquals(R.string.tally_221_no_stream, noStreamLabelRes(TallyGame(id = "3", state = "post")))
    }

    @Test
    fun `every game that is not final can be watched`() {
        assertTrue(TallyGame(state = "in").canWatch)
        assertTrue(TallyGame(state = "pre").canWatch)
        assertFalse(TallyGame(state = "post").canWatch)
        assertTrue(TallyGame(state = "post", watch = watch).canWatch)
    }
}
