package com.github.damontecres.wholphin.jellytv.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors

/** Unlit amber glass. */
val LAMP_OFF = Color(0xFF28231A)

/** What the lamp is doing: dark, trying (and failing) to switch on, or on. */
enum class LampState { Off, Sputtering, Lit }

/**
 * The tally light: a square lamp that sputters like a failing bulb while something connects and catches when it
 * really goes live. Never rounded. With [glow], a soft amber halo is drawn behind it (it may spill outside the
 * square; nothing clips it).
 *
 * - [LampState.Off]: unlit glass.
 * - [LampState.Sputtering]: loops [TallyLampTimeline.SPUTTER].
 * - [LampState.Lit]: entered from Off or Sputtering while composed, runs the catch ([TallyLampTimeline.catchAt]) once,
 *   then steady lit; Lit from the first composition is steady lit with no animation.
 * - Any state to Off: dark immediately.
 *
 * [onFullBrightness] fires when the catch reaches full brightness and [onSettled] when it is complete (both at once
 * for a lamp that was lit from the start or with animations off), so callers can time what follows the catch.
 * With the system's animator duration scale at 0, Sputtering is dark and Lit is steady lit with no catch.
 */
@Composable
fun TallyLamp(
    state: LampState,
    size: Dp,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    onFullBrightness: () -> Unit = {},
    onSettled: () -> Unit = {},
) {
    val context = LocalContext.current
    val clock = remember { LampClock(litFromStart = state == LampState.Lit) }
    val fullBrightness by rememberUpdatedState(onFullBrightness)
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(state) {
        when (state) {
            LampState.Off -> {
                clock.reset()
            }

            LampState.Sputtering -> {
                clock.settled = false
                if (animationsOff(context)) {
                    clock.value = LampValue.OFF
                } else {
                    if (!clock.sputtering) clock.startSputter()
                    clock.lastFrame = null
                    while (true) {
                        withFrameMillis { now ->
                            clock.advance(now)
                            clock.value = TallyLampTimeline.sputterAt(clock.time)
                        }
                    }
                }
            }

            LampState.Lit -> {
                if (!clock.settled && !animationsOff(context)) {
                    val fromSputter = clock.sputtering
                    if (!fromSputter) clock.startSputter()
                    clock.lastFrame = null
                    var catchStart = -1L
                    var fired = false
                    var done = false
                    while (!done) {
                        withFrameMillis { now ->
                            clock.advance(now)
                            if (catchStart < 0) {
                                catchStart = if (fromSputter) TallyLampTimeline.catchStart(clock.time) else clock.time
                            }
                            val intoCatch = clock.time - catchStart
                            clock.value =
                                if (intoCatch < 0) {
                                    TallyLampTimeline.sputterAt(clock.time)
                                } else {
                                    TallyLampTimeline.catchAt(intoCatch)
                                }
                            if (!fired && intoCatch >= TallyLampTimeline.CATCH_FULL_MS) {
                                fired = true
                                fullBrightness()
                            }
                            done = intoCatch >= TallyLampTimeline.CATCH_TOTAL_MS
                        }
                    }
                }
                clock.value = LampValue.LIT
                clock.settled = true
                clock.sputtering = false
                fullBrightness()
                settled()
            }
        }
    }
    val value = clock.value
    Box(
        modifier
            .size(size)
            .drawBehind {
                if (glow && value.glow > 0f) {
                    val radius = this.size.minDimension * GLOW_RADIUS
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                0f to JtvColors.accent.copy(alpha = GLOW_ALPHA * value.glow),
                                1f to JtvColors.accent.copy(alpha = 0f),
                                center = center,
                                radius = radius,
                            ),
                        radius = radius,
                        center = center,
                    )
                }
                drawRect(lerp(LAMP_OFF, JtvColors.accent, value.brightness.coerceIn(0f, 1f)))
            },
    )
}

/**
 * The lamp's own time. It advances with the frames but never by more than [MAX_FRAME_STEP_MS] per frame, so when the
 * main thread stalls (a cold start composing the home page underneath) the lamp waits instead of skipping a blip.
 */
private class LampClock(
    litFromStart: Boolean,
) {
    var value by mutableStateOf(if (litFromStart) LampValue.LIT else LampValue.OFF)

    /** Milliseconds of lamp time since the current sputter (or catch from dark) began. */
    var time = 0L

    var lastFrame: Long? = null

    /** A sputter is running (kept across Sputtering → Lit so the catch follows it). */
    var sputtering = false

    /** Steady lit; a Lit state change finds nothing to do. */
    var settled = litFromStart

    fun startSputter() {
        time = 0L
        lastFrame = null
        sputtering = true
    }

    fun advance(frameMillis: Long) {
        lastFrame?.let { time += (frameMillis - it).coerceIn(0L, MAX_FRAME_STEP_MS) }
        lastFrame = frameMillis
    }

    fun reset() {
        value = LampValue.OFF
        sputtering = false
        settled = false
    }
}

private const val MAX_FRAME_STEP_MS = 34L

private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Halo radius as a multiple of the lamp size, and its peak alpha at full glow. */
private const val GLOW_RADIUS = 1.4f
private const val GLOW_ALPHA = 0.30f

/** Brightness (0 unlit glass .. 1 full amber) and halo strength (0..1) at one moment. */
data class LampValue(
    val brightness: Float,
    val glow: Float,
) {
    val on: Boolean get() = brightness > 0f

    companion object {
        val OFF = LampValue(0f, 0f)
        val LIT = LampValue(1f, 1f)
    }
}

/** A held level: [brightness]/[glow] for [durationMs]. */
data class LampStep(
    val brightness: Float,
    val glow: Float,
    val durationMs: Long,
) {
    val on: Boolean get() = brightness > 0f
}

/**
 * The lamp's timing as data, shared by every use (launch, tune-in, watch party) so they cannot drift apart.
 * Times are milliseconds since the lamp began sputtering; everything here is pure and unit-tested.
 */
object TallyLampTimeline {
    /** One failing-bulb cycle, looped while Sputtering: three blips, a near-catch, and long dark gaps. */
    val SPUTTER: List<LampStep> =
        listOf(
            LampStep(0f, 0f, 400),
            LampStep(0.35f, 0f, 50),
            LampStep(0f, 0f, 120),
            LampStep(0.5f, 0.05f, 40),
            LampStep(0f, 0f, 700),
            LampStep(0.25f, 0f, 60),
            LampStep(0f, 0f, 300),
            LampStep(0.7f, 0.2f, 110),
            LampStep(0.15f, 0f, 40),
            LampStep(0f, 0f, 900),
        )

    val SPUTTER_CYCLE_MS: Long = SPUTTER.sumOf { it.durationMs }

    /** The catch opens with one more flicker, then goes dark before the ramp. */
    val CATCH_FLICKER: List<LampStep> =
        listOf(
            LampStep(0.6f, 0.1f, 50),
            LampStep(0f, 0f, 60),
        )
    const val CATCH_RAMP_MS = 200L
    const val CATCH_RAMP_GLOW = 0.7f
    const val CATCH_GLOW_MS = 260L
    private val CATCH_FLICKER_MS: Long = CATCH_FLICKER.sumOf { it.durationMs }

    /** From the catch's start to full brightness. */
    val CATCH_FULL_MS: Long = CATCH_FLICKER_MS + CATCH_RAMP_MS

    /** From the catch's start to steady lit with full glow. */
    val CATCH_TOTAL_MS: Long = CATCH_FULL_MS + CATCH_GLOW_MS

    /** At least this much sputtering is shown before a catch... */
    const val MIN_SPUTTER_MS = 350L

    /** ...and at least the first blip, so even a fast start shows one failed try. */
    val FIRST_BLIP_END_MS: Long =
        run {
            var t = 0L
            for (step in SPUTTER) {
                t += step.durationMs
                if (step.on) break
            }
            t
        }

    /** Photosensitivity: never more than this many off → on switches in any one-second window. */
    const val MAX_ONSETS_PER_SECOND = 3
    private const val WINDOW_MS = 1_000L

    /** The looping sputter at [ms] after it began. */
    fun sputterAt(ms: Long): LampValue {
        if (ms < 0) return LampValue.OFF
        val step = SPUTTER[stepIndexAt(ms)]
        return LampValue(step.brightness, step.glow)
    }

    /** The catch at [ms] after it began: flicker, dark, a cubic ease-out ramp to full, then the glow settles. */
    fun catchAt(ms: Long): LampValue {
        if (ms < 0) return LampValue.OFF
        var t = ms
        for (step in CATCH_FLICKER) {
            if (t < step.durationMs) return LampValue(step.brightness, step.glow)
            t -= step.durationMs
        }
        if (t < CATCH_RAMP_MS) {
            val e = easeOutCubic(t.toFloat() / CATCH_RAMP_MS)
            return LampValue(e, CATCH_RAMP_GLOW * e)
        }
        t -= CATCH_RAMP_MS
        if (t < CATCH_GLOW_MS) {
            return LampValue(1f, CATCH_RAMP_GLOW + (1f - CATCH_RAMP_GLOW) * easeOutCubic(t.toFloat() / CATCH_GLOW_MS))
        }
        return LampValue.LIT
    }

    /**
     * When the catch begins if Lit arrives at [litAtMs] into the sputter: not before [MIN_SPUTTER_MS] nor before the
     * first blip has ended, never cutting a blip short (the current one finishes first), and never so soon that the
     * catch's own switches would break [MAX_ONSETS_PER_SECOND].
     */
    fun catchStart(litAtMs: Long): Long {
        var t = maxOf(litAtMs, MIN_SPUTTER_MS, FIRST_BLIP_END_MS)
        val limit = t + 2 * SPUTTER_CYCLE_MS
        while (t < limit && !canCatchAt(t)) t++
        return t
    }

    /** The whole lamp: sputtering since 0, Lit arriving at [litAtMs] (null: still sputtering). */
    fun valueAt(
        ms: Long,
        litAtMs: Long?,
    ): LampValue {
        if (litAtMs == null) return sputterAt(ms)
        val start = catchStart(litAtMs)
        return if (ms < start) sputterAt(ms) else catchAt(ms - start)
    }

    fun easeOutCubic(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun canCatchAt(t: Long): Boolean {
        // Mid-blip: the step holding t started before t and is lit.
        val index = stepIndexAt(t)
        if (SPUTTER[index].on && stepStart(t) < t) return false
        val onsets = sputterOnsetsBefore(t).toMutableList()
        if (!sputterAt(t - 1).on) onsets.add(t)
        var offset = 0L
        var previousOn = true
        for (step in CATCH_FLICKER) {
            if (step.on && !previousOn) onsets.add(t + offset)
            previousOn = step.on
            offset += step.durationMs
        }
        if (!previousOn) onsets.add(t + offset) // the ramp rises from dark
        return withinOnsetLimit(onsets)
    }

    /** True when no window of [WINDOW_MS] holds more than [MAX_ONSETS_PER_SECOND] of the sorted [onsets]. */
    fun withinOnsetLimit(onsets: List<Long>): Boolean {
        val sorted = onsets.sorted()
        for (i in 0 until sorted.size - MAX_ONSETS_PER_SECOND) {
            if (sorted[i + MAX_ONSETS_PER_SECOND] - sorted[i] < WINDOW_MS) return false
        }
        return true
    }

    private fun sputterOnsetsBefore(t: Long): List<Long> {
        val result = mutableListOf<Long>()
        var at = 0L
        var previousOn = false
        while (at < t) {
            for (step in SPUTTER) {
                if (at >= t) break
                if (step.on && !previousOn) result.add(at)
                previousOn = step.on
                at += step.durationMs
            }
        }
        return result
    }

    private fun stepIndexAt(ms: Long): Int {
        var t = ms.mod(SPUTTER_CYCLE_MS)
        SPUTTER.forEachIndexed { i, step ->
            if (t < step.durationMs) return i
            t -= step.durationMs
        }
        return SPUTTER.lastIndex
    }

    private fun stepStart(ms: Long): Long {
        val cycleStart = ms - ms.mod(SPUTTER_CYCLE_MS)
        var at = cycleStart
        for (i in 0 until stepIndexAt(ms)) at += SPUTTER[i].durationMs
        return at
    }
}
