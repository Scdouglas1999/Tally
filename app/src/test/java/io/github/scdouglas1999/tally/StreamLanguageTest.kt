package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyFeed
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.api.TallyLanguage
import io.github.scdouglas1999.tally.api.TallyWatch
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.SettingsJson
import io.github.scdouglas1999.tally.ui.components.gameActions
import io.github.scdouglas1999.tally.watch.carries
import io.github.scdouglas1999.tally.watch.commentary
import io.github.scdouglas1999.tally.watch.commentaryFeeds
import io.github.scdouglas1999.tally.watch.commentaryOn
import io.github.scdouglas1999.tally.watch.onFeed
import io.github.scdouglas1999.tally.watch.otherFeed
import io.github.scdouglas1999.tally.watch.spanishWatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tally 2.3's commentary language: decoding `feeds` / `language`, and which feed each action plays. */
class StreamLanguageTest {
    private fun load(name: String): TallyBoard =
        TallyJson.decodeFromString<TallyBoard>(javaClass.getResource("/tally/$name")!!.readText())

    private val board: TallyBoard by lazy { load("board-23-language-redzone.json") }
    private val oldBoard: TallyBoard by lazy { load("board-sample.json") }

    private fun game(id: String): TallyGame = board.games.first { it.id == id }

    private val english get() = game("401900101")
    private val spanish get() = game("401900102")
    private val single get() = game("401900103")

    @Test
    fun `decodes feeds, watch language and channel language`() {
        assertEquals(2, english.feeds.size)
        assertEquals("en", english.feeds[0].language)
        assertEquals("English", english.feeds[0].label)
        assertEquals("Español", english.feeds[1].label)
        assertEquals("c0115a7e0a000e51", english.feeds[1].watch?.channelId)
        assertEquals("es", english.feeds[1].watch?.language)
        assertEquals("en", english.watch?.language)
        assertEquals("es", spanish.watch?.language)
        assertEquals("es", board.channels.first { it.id == "c0115a7e0a000e51" }.language)
        assertEquals("", board.channels.first { it.id == "d00d000000000001" }.language)
    }

    @Test
    fun `an older server has no feeds and no languages, which reads as English`() {
        val game = oldBoard.games[0]
        assertTrue(game.feeds.isEmpty())
        assertEquals("", game.watch?.language)
        assertEquals(TallyLanguage.ENGLISH, game.watch?.commentary)
        assertFalse(game.spanishWatch)
        assertNull(game.otherFeed())
        assertTrue(game.commentaryFeeds().isEmpty())
        assertTrue(oldBoard.channels.all { it.language.isEmpty() && it.kind.isEmpty() })
    }

    @Test
    fun `a game offered in English offers Spanish beside WATCH`() {
        val other = english.otherFeed()
        assertNotNull(other)
        assertEquals("es", other!!.commentary)
        assertEquals("c0115a7e0a000e51", other.watch?.channelId)
        assertFalse(english.spanishWatch)
    }

    @Test
    fun `a game offered in Spanish offers English beside WATCH and wears the ES chip`() {
        val other = spanish.otherFeed()
        assertEquals("en", other?.commentary)
        assertEquals("b0ffa10000000001", other?.watch?.channelId)
        assertTrue(spanish.spanishWatch)
    }

    @Test
    fun `a game with one commentary offers nothing more`() {
        assertNull(single.otherFeed())
        assertTrue(single.commentaryFeeds().isEmpty())
    }

    @Test
    fun `a feed without a stream is never offered`() {
        val game =
            english.copy(
                feeds = listOf(english.feeds[0], TallyFeed(language = "es", label = "Español", watch = null)),
            )
        assertNull(game.otherFeed())
        assertTrue(game.commentaryFeeds().isEmpty())
    }

    @Test
    fun `a game with no stream offers no other feed`() {
        assertNull(english.copy(watch = null).otherFeed())
    }

    @Test
    fun `onFeed points WATCH at the feed's stream and keeps the rest`() {
        val switched = english.onFeed(english.otherFeed()!!)
        assertEquals("c0115a7e0a000e51", switched.watch?.channelId)
        assertEquals(english.id, switched.id)
        assertEquals(english.feeds, switched.feeds)
        // and back: from the Spanish watch the other feed is English again
        assertEquals("en", switched.otherFeed()?.commentary)
    }

    @Test
    fun `the Spanish channel carries its game for the player`() {
        assertTrue(english.carries("c0115a7e0a000e51"))
        assertTrue(english.carries("c0115a7e0a000001"))
        assertFalse(english.carries("d00d000000000001"))
        assertEquals("es", english.commentaryOn("c0115a7e0a000e51"))
        assertEquals("en", english.commentaryOn("c0115a7e0a000001"))
        assertNull(english.commentaryOn("d00d000000000001"))
        assertEquals("en", single.commentaryOn("d00d000000000001"))
        assertSame(english, BoardOrganizer.gameFor("c0115a7e0a000e51", board.games))
    }

    @Test
    fun `game actions add Watch in Espanol, which plays that feed through WATCH`() {
        var watched: TallyWatch? = null
        val actions =
            gameActions(
                game = english,
                favoriteTeams = emptySet(),
                hideScores = false,
                onWatch = { watched = it.watch },
                onAddToMultiview = {},
                onWatchInCorner = null,
                onToggleFollow = {},
                onToggleHideScores = {},
            )
        assertEquals("es", actions.otherFeed?.commentary)
        actions.watchFeed!!.invoke()
        assertEquals("c0115a7e0a000e51", watched?.channelId)
        actions.watch!!.invoke()
        assertEquals("c0115a7e0a000001", watched?.channelId)
    }

    @Test
    fun `game actions leave the line out for a game with one commentary`() {
        val actions =
            gameActions(
                game = single,
                favoriteTeams = emptySet(),
                hideScores = false,
                onWatch = {},
                onAddToMultiview = {},
                onWatchInCorner = null,
                onToggleFollow = {},
                onToggleHideScores = {},
            )
        assertNull(actions.otherFeed)
        assertNull(actions.watchFeed)
    }

    @Test
    fun `streamLanguage round-trips and keeps unknown keys`() {
        val before = JsonObject(mapOf("alerts" to JsonPrimitive(false), "hideScores" to JsonPrimitive(true)))
        assertNull(SettingsJson.parse(before).streamLanguage)
        val after = SettingsJson.setStreamLanguage(before, "es")
        assertEquals("es", SettingsJson.parse(after).streamLanguage)
        assertEquals(JsonPrimitive(false), after["alerts"])
        assertTrue(SettingsJson.parse(after).hideScores)
        assertEquals("en", SettingsJson.parse(SettingsJson.setStreamLanguage(after, "en")).streamLanguage)
    }
}
