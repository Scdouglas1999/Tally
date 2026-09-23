package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.media.music.MusicFormat
import io.github.scdouglas1999.tally.media.music.MusicFormat.TrackListEntry.Disc
import io.github.scdouglas1999.tally.media.music.MusicFormat.TrackListEntry.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFormatTest {
    @Test
    fun trackNumberIsTwoDigits() {
        assertEquals("01", MusicFormat.trackNumber(1))
        assertEquals("12", MusicFormat.trackNumber(12))
        assertEquals("124", MusicFormat.trackNumber(124))
        assertEquals("", MusicFormat.trackNumber(null))
    }

    @Test
    fun durationIsAClock() {
        // The dev server's test tracks run 450351020 ticks (45.04 s).
        assertEquals("0:45", MusicFormat.duration(450_351_020L))
        assertEquals("4:05", MusicFormat.duration(245L * 10_000_000L))
        assertEquals("1:02:03", MusicFormat.duration(3723L * 10_000_000L))
        assertEquals("", MusicFormat.duration(null))
        assertEquals("", MusicFormat.duration(0L))
    }

    @Test
    fun singleDiscHasNoHeaders() {
        assertEquals(listOf(Track(0), Track(1), Track(2)), MusicFormat.tracklist(listOf(null, null, null)))
        assertEquals(listOf(Track(0), Track(1)), MusicFormat.tracklist(listOf(1, 1)))
    }

    @Test
    fun severalDiscsGetAHeaderEach() {
        assertEquals(
            listOf(Disc(1), Track(0), Track(1), Disc(2), Track(2)),
            MusicFormat.tracklist(listOf(1, 1, 2)),
        )
    }

    @Test
    fun artistOnlyWhenDifferent() {
        assertNull(MusicFormat.artistIfDifferent(listOf("Daft Punk"), "Daft Punk"))
        assertNull(MusicFormat.artistIfDifferent(null, "Daft Punk"))
        assertEquals("Romanthony", MusicFormat.artistIfDifferent(listOf("Romanthony"), "Daft Punk"))
        assertEquals("A, B", MusicFormat.artistIfDifferent(listOf("A", "B"), "Various Artists"))
    }

    @Test
    fun repeatModesMapToTags() {
        assertEquals(MusicFormat.RepeatTag.NONE, MusicFormat.repeatTag(0))
        assertEquals(MusicFormat.RepeatTag.ONE, MusicFormat.repeatTag(1))
        assertEquals(MusicFormat.RepeatTag.ALL, MusicFormat.repeatTag(2))
    }

    @Test
    fun syncedWhenAnyLineHasAStart() {
        assertTrue(MusicFormat.isSynced(listOf(0L, 50_000_000L)))
        assertFalse(MusicFormat.isSynced(listOf(null, null)))
    }

    @Test
    fun currentLineRestsAtFortyPercent() {
        // A 400px column: the line centered at 160px.
        assertEquals(140f, MusicFormat.lyricOffset(400f, lineTop = 0f, lineHeight = 40f), 0.01f)
        assertEquals(-340f, MusicFormat.lyricOffset(400f, lineTop = 480f, lineHeight = 40f), 0.01f)
    }
}
