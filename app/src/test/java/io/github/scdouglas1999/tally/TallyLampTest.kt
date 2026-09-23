package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.ui.components.LampValue
import io.github.scdouglas1999.tally.ui.components.TallyLampTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TallyLampTest {
    private val t = TallyLampTimeline

    /** Times (ms) at which the lamp goes from dark to any light, sampled every millisecond over [0, endMs). */
    private fun onsets(
        endMs: Long,
        value: (Long) -> LampValue,
    ): List<Long> {
        val result = mutableListOf<Long>()
        var previousOn = false
        for (ms in 0 until endMs) {
            val on = value(ms).on
            if (on && !previousOn) result.add(ms)
            previousOn = on
        }
        return result
    }

    /** Slides a one-second window across [onsets] and returns the most switches seen in any window. */
    private fun maxOnsetsInAnySecond(onsets: List<Long>): Int {
        if (onsets.isEmpty()) return 0
        var most = 0
        for (start in (onsets.first() - 1_000)..onsets.last()) {
            most = maxOf(most, onsets.count { it >= start && it < start + 1_000 })
        }
        return most
    }

    @Test
    fun `sputter cycle is the specified pattern and about 2_7 s long`() {
        assertEquals(2_720L, t.SPUTTER_CYCLE_MS)
        assertEquals(LampValue.OFF, t.sputterAt(0))
        assertEquals(LampValue.OFF, t.sputterAt(399))
        assertEquals(LampValue(0.35f, 0f), t.sputterAt(400))
        assertEquals(LampValue.OFF, t.sputterAt(450))
        assertEquals(LampValue(0.5f, 0.05f), t.sputterAt(570))
        assertEquals(LampValue(0.25f, 0f), t.sputterAt(1_310))
        assertEquals(LampValue(0.7f, 0.2f), t.sputterAt(1_670))
        assertEquals(LampValue(0.15f, 0f), t.sputterAt(1_780))
        assertEquals(LampValue.OFF, t.sputterAt(1_820))
        // It loops.
        assertEquals(t.sputterAt(400), t.sputterAt(400 + t.SPUTTER_CYCLE_MS))
        assertEquals(t.sputterAt(1_700), t.sputterAt(1_700 + 5 * t.SPUTTER_CYCLE_MS))
    }

    @Test
    fun `sputter never switches on more than three times in any second over two cycles`() {
        val onsets = onsets(2 * t.SPUTTER_CYCLE_MS) { t.sputterAt(it) }
        assertEquals(listOf(400L, 570L, 1_310L, 1_670L, 3_120L, 3_290L, 4_030L, 4_390L), onsets)
        assertTrue(maxOnsetsInAnySecond(onsets) <= t.MAX_ONSETS_PER_SECOND)
    }

    @Test
    fun `catch flickers, goes dark, ramps with ease-out and then settles its glow`() {
        assertEquals(LampValue(0.6f, 0.1f), t.catchAt(0))
        assertEquals(LampValue.OFF, t.catchAt(50))
        assertEquals(LampValue.OFF, t.catchAt(109))
        assertEquals(0f, t.catchAt(110).brightness, 0.0001f)
        // Ease-out: past halfway after a quarter of the ramp.
        assertTrue(t.catchAt(160).brightness > 0.5f)
        assertEquals(310L, t.CATCH_FULL_MS)
        assertEquals(1f, t.catchAt(310).brightness, 0.0001f)
        assertEquals(0.7f, t.catchAt(310).glow, 0.0001f)
        assertEquals(570L, t.CATCH_TOTAL_MS)
        assertEquals(LampValue.LIT, t.catchAt(570))
        assertEquals(LampValue.LIT, t.catchAt(10_000))
    }

    @Test
    fun `a fast start still shows at least 350 ms of sputter and the first failed blip`() {
        assertEquals(450L, t.catchStart(0))
        assertEquals(450L, t.catchStart(120))
        assertTrue(t.catchStart(0) >= t.MIN_SPUTTER_MS)
        assertEquals(1, onsets(t.catchStart(0)) { t.sputterAt(it) }.size)
    }

    @Test
    fun `a blip in progress finishes before the catch`() {
        // The near-catch runs 1670..1780; Lit at 1700 waits for its end.
        assertEquals(1_780L, t.catchStart(1_700))
        // Lit during a dark gap starts at once when the switch budget allows it...
        assertEquals(2_500L, t.catchStart(2_500))
        // ...and otherwise waits until the blips at 1310 and 1670 leave the one-second window.
        assertEquals(2_200L, t.catchStart(2_000))
    }

    @Test
    fun `catch never breaks the three switches per second rule, wherever Lit arrives`() {
        for (litAt in 0 until 2 * t.SPUTTER_CYCLE_MS step 3) {
            val start = t.catchStart(litAt)
            assertTrue("catch starts before Lit ($litAt)", start >= litAt)
            // Never waits more than one blip plus the budget: well under a cycle.
            assertTrue("catch waits too long for Lit at $litAt: $start", start - litAt < 1_000)
            val onsets = onsets(start + t.CATCH_TOTAL_MS + 10) { t.valueAt(it, litAt) }
            assertTrue(
                "Lit at $litAt (catch at $start): $onsets",
                maxOnsetsInAnySecond(onsets) <= t.MAX_ONSETS_PER_SECOND,
            )
            // Never cut a blip short: the moment before the catch is dark or the end of a whole step.
            val before = t.sputterAt(start - 1)
            if (before.on) assertFalse(t.sputterAt(start) == before && t.sputterAt(start).on)
            assertEquals(LampValue.LIT, t.valueAt(start + t.CATCH_TOTAL_MS, litAt))
        }
    }

    @Test
    fun `still sputtering without Lit`() {
        assertEquals(t.sputterAt(1_234), t.valueAt(1_234, null))
    }
}
