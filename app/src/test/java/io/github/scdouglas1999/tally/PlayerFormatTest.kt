package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.ui.player.controls.PlayerFormat
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Player control labels against payloads captured from the dev server: `movies-provider-ids.json` (the films),
 * `series-episodes-bb-s1.json` (Breaking Bad season 1) and the media sources `embedded_subs.json` /
 * `external_subs.json` (audio and subtitle streams).
 */
class PlayerFormatTest {
    @Test
    fun `a film's kicker and meta line`() {
        val inception = films.single { it.name == "Inception" }
        assertEquals(PlayerFormat.Kind.FILM, PlayerFormat.kind(inception))
        assertEquals("FILM", PlayerFormat.kicker(inception, film = "Film", live = "Live"))
        assertEquals("Inception", PlayerFormat.title(inception))
        assertEquals("2010 · PG-13", PlayerFormat.meta(inception))
    }

    @Test
    fun `an episode's kicker names the series and the episode, the meta line its air date`() {
        val e3 = bbSeason1[2]
        assertEquals("BREAKING BAD · S1 E3", PlayerFormat.kicker(e3, film = "Film", live = "Live"))
        assertEquals("...And the Bag's in the River", PlayerFormat.title(e3))
        assertEquals("FEB 10, 2008", PlayerFormat.meta(e3))
    }

    @Test
    fun `a live stream reads live whatever the item is`() {
        assertEquals("LIVE", PlayerFormat.kicker(bbSeason1[0], film = "Film", live = "Live", isLive = true))
    }

    @Test
    fun `clock times`() {
        assertEquals("0:00", PlayerFormat.clock(0))
        assertEquals("0:45", PlayerFormat.clock(45_000))
        assertEquals("12:34", PlayerFormat.clock(754_000))
        assertEquals("1:02:03", PlayerFormat.clock(3_723_000))
        assertEquals("0:00", PlayerFormat.clock(-5))
        assertEquals("-34:28", PlayerFormat.remaining(positionMs = 60_000, durationMs = 60_000 + 2_068_000))
        assertEquals("-0:00", PlayerFormat.remaining(positionMs = 90_000, durationMs = 60_000))
    }

    @Test
    fun `the end time follows the playback speed`() {
        assertEquals(60_000L, PlayerFormat.remainingRealMs(0, 60_000, 1f))
        assertEquals(30_000L, PlayerFormat.remainingRealMs(0, 60_000, 2f))
        assertEquals(0L, PlayerFormat.remainingRealMs(90_000, 60_000, 1f))
    }

    @Test
    fun `speeds`() {
        assertEquals("1×", PlayerFormat.speed(1.0f))
        assertEquals("0.25×", PlayerFormat.speed(.25f))
        assertEquals("1.5×", PlayerFormat.speed(1.5f))
        assertEquals("2×", PlayerFormat.speed(2.0f))
    }

    @Test
    fun `audio tracks in one line`() {
        val audio = embedded.mediaStreams!!.filter { it.type == MediaStreamType.AUDIO }
        assertEquals(listOf("JA · OPUS STEREO", "PT · AAC STEREO", "PT · EAC3 5.1"), audio.map { PlayerFormat.audioTrack(it) })
        val external = external.mediaStreams!!.single { it.type == MediaStreamType.AUDIO }
        assertEquals("EN · EAC3 5.1", PlayerFormat.audioTrack(external))
    }

    @Test
    fun `subtitle tracks in one line`() {
        val embeddedSubs = embedded.mediaStreams!!.filter { it.type == MediaStreamType.SUBTITLE }
        assertEquals(listOf("PT", "PT · FORCED", "EN"), embeddedSubs.map { PlayerFormat.subtitleTrack(it) })
        val externalSubs = external.mediaStreams!!.filter { it.type == MediaStreamType.SUBTITLE }
        assertEquals(
            listOf("EN · EXTERNAL", "EN · EXTERNAL", "EN · EXTERNAL", "SUBRIP"),
            externalSubs.map { PlayerFormat.subtitleTrack(it) },
        )
    }

    @Test
    fun `subtitle delays`() {
        assertEquals("0s", PlayerFormat.subtitleDelay(Duration.ZERO))
        assertEquals("+0.25s", PlayerFormat.subtitleDelay(250.milliseconds))
        assertEquals("-1s", PlayerFormat.subtitleDelay((-1).seconds))
        assertEquals("+0.05s", PlayerFormat.subtitleDelay(50.milliseconds))
    }

    @Test
    fun `chapter kickers and the chapter playing`() {
        assertEquals("CHAPTER 1 · 00:00", PlayerFormat.chapterKicker(0, 0, "Chapter"))
        assertEquals("CHAPTER 3 · 12:30", PlayerFormat.chapterKicker(2, 750_000, "Chapter"))
        val starts = listOf(0L, 30_000L, 60_000L)
        assertEquals(0, PlayerFormat.chapterAt(starts, 0))
        assertEquals(1, PlayerFormat.chapterAt(starts, 45_000))
        assertEquals(2, PlayerFormat.chapterAt(starts, 90_000))
        assertNull(PlayerFormat.chapterAt(listOf(10_000L), 5_000))
        assertNull(PlayerFormat.chapterAt(emptyList(), 5_000))
    }

    @Test
    fun `queue kickers`() {
        assertEquals("NEXT", PlayerFormat.queueKicker(0, bbSeason1[3], "Next"))
        // Episodes on the dev server run a minute.
        assertEquals("E05 · 1m", PlayerFormat.queueKicker(1, bbSeason1[4], "Next"))
    }

    @Test
    fun `next up line`() {
        assertEquals("S1 E4 · Cancer Man", PlayerFormat.nextUpLine(bbSeason1[3]))
        assertEquals("Inception", PlayerFormat.nextUpLine(films.single { it.name == "Inception" }))
    }

    @Test
    fun `countdown bar`() {
        assertEquals(1f, PlayerFormat.countdownFraction(15, 15), 0f)
        assertEquals(0.5f, PlayerFormat.countdownFraction(5, 10), 0f)
        assertEquals(0f, PlayerFormat.countdownFraction(0, 10), 0f)
        assertEquals(0f, PlayerFormat.countdownFraction(-1, 10), 0f)
        assertEquals(0f, PlayerFormat.countdownFraction(5, 0), 0f)
    }

    private companion object {
        fun load(name: String): List<BaseItemDto> {
            val text =
                PlayerFormatTest::class.java
                    .getResource("/tally/$name")!!
                    .readText()
            return ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(text).items
        }

        fun source(name: String): MediaSourceInfo {
            val text =
                PlayerFormatTest::class.java
                    .getResource("/$name")!!
                    .readText()
            return ApiSerializer.json.decodeFromString<MediaSourceInfo>(text)
        }

        val films by lazy { load("movies-provider-ids.json") }
        val bbSeason1 by lazy { load("series-episodes-bb-s1.json") }
        val embedded by lazy { source("embedded_subs.json") }
        val external by lazy { source("external_subs.json") }
    }
}
