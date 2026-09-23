package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.surprise.SCAN_FRAME_MS
import io.github.scdouglas1999.tally.surprise.nextScanCandidate
import io.github.scdouglas1999.tally.ui.components.RollRestart
import io.github.scdouglas1999.tally.ui.components.countUpValue
import io.github.scdouglas1999.tally.ui.components.easeOutCubic
import io.github.scdouglas1999.tally.ui.components.rollIncomingOffset
import io.github.scdouglas1999.tally.ui.components.rollOutgoingOffset
import io.github.scdouglas1999.tally.ui.components.rollRestart
import io.github.scdouglas1999.tally.ui.components.scoreWentUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun incomingGlyphRisesFromOneCellBelow() {
        assertEquals(1f, rollIncomingOffset(0f), 0f)
        assertEquals(0.5f, rollIncomingOffset(0.5f), 0f)
        assertEquals(0f, rollIncomingOffset(1f), 0f)
        assertEquals(0f, rollIncomingOffset(2f), 0f)
    }

    @Test
    fun outgoingGlyphLeavesUpward() {
        assertEquals(0f, rollOutgoingOffset(from = 0f, progress = 0f), 0f)
        assertEquals(-1f, rollOutgoingOffset(from = 0f, progress = 1f), 0f)
    }

    @Test
    fun rollRestartedMidwayContinuesWithTheGlyphMostInView() {
        // At rest: the glyph in place leaves from 0.
        assertEquals(RollRestart(keepPrevious = false, from = 0f), rollRestart(previousFrom = 0f, progress = 1f))
        // Most of the way in (0.8): the incoming glyph sits 0.2 below and leaves from there.
        val late = rollRestart(previousFrom = 0f, progress = 0.8f)
        assertFalse(late.keepPrevious)
        assertEquals(0.2f, late.from, 1e-6f)
        // Barely started (0.1): the old glyph is still mostly in view (0.1 up); it keeps leaving on its
        // original path and the new glyph just replaces the incoming one.
        val early = rollRestart(previousFrom = 0f, progress = 0.1f)
        assertTrue(early.keepPrevious)
        assertEquals(0f, early.from, 0f)
    }

    @Test
    fun onlyARisingScoreHighlights() {
        assertTrue(scoreWentUp(3, 4))
        assertTrue(scoreWentUp(9, 10))
        assertFalse(scoreWentUp(4, 4))
        assertFalse(scoreWentUp(4, 3))
        assertFalse(scoreWentUp(null, 2))
        assertFalse(scoreWentUp(2, null))
    }

    @Test
    fun countUpEasesOutCubic() {
        assertEquals(0f, easeOutCubic(0f), 0f)
        assertEquals(0.875f, easeOutCubic(0.5f), 1e-6f)
        assertEquals(1f, easeOutCubic(1f), 0f)
        assertEquals(0L, countUpValue(57, 0f))
        assertEquals(50L, countUpValue(57, 0.875f))
        assertEquals(57L, countUpValue(57, 1f))
    }

    @Test
    fun channelScanSlowsDownAndSkipsPostersNotInMemory() {
        assertEquals(listOf(60, 70, 90, 120, 160), SCAN_FRAME_MS)
        val loaded = setOf("b", "d", "e")
        val candidates = listOf("a", "b", "c", "d", "e")
        assertEquals(1, nextScanCandidate(candidates, 0) { it in loaded })
        assertEquals(3, nextScanCandidate(candidates, 2) { it in loaded })
        assertNull(nextScanCandidate(candidates, 5) { it in loaded })
        assertNull(nextScanCandidate(candidates, 0) { false })
    }
}
