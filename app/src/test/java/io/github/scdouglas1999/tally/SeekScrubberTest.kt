package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.ui.player.controls.scrubberCenter
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekScrubberTest {
    private val width = 1766f
    private val square = 24f

    @Test
    fun startKeepsTheSquareInsideTheTrack() {
        assertEquals(square / 2f, scrubberCenter(0f, width, square), 0.001f)
    }

    @Test
    fun endKeepsTheSquareInsideTheTrack() {
        assertEquals(width - square / 2f, scrubberCenter(1f, width, square), 0.001f)
    }

    @Test
    fun middleIsTheTrackMiddle() {
        assertEquals(width / 2f, scrubberCenter(0.5f, width, square), 0.001f)
    }

    @Test
    fun progressOutsideTheRangeIsClamped() {
        assertEquals(width - square / 2f, scrubberCenter(1.4f, width, square), 0.001f)
        assertEquals(square / 2f, scrubberCenter(-0.2f, width, square), 0.001f)
    }
}
