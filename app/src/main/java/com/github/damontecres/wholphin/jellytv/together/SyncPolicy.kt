package com.github.damontecres.wholphin.jellytv.together

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * The pure decisions of keeping a local player in step with the group. Thresholds follow jellyfin-web's
 * SyncPlay defaults so a TV and a browser in the same group correct the same way.
 */
object SyncPolicy {
    const val TICKS_PER_MS = 10_000L

    /** Below this drift nothing is done. */
    const val MIN_DRIFT_MS = 60L

    /** Up to this drift the player catches up by playing slightly faster or slower; above it, it seeks. */
    const val MAX_SPEED_DRIFT_MS = 2_000L

    /** Speed used to close a small drift: 10% fast when behind, 10% slow when ahead. */
    const val CATCH_UP_SPEED = 1.1f
    const val SLOW_DOWN_SPEED = 0.9f

    /** How often drift is checked while playing. */
    const val CHECK_INTERVAL_MS = 1_000L

    /** Where the group is, for a running `Unpause` issued at [commandPositionTicks] effective at [commandWhen]. */
    fun expectedPositionMs(
        commandPositionTicks: Long,
        commandWhen: Instant,
        serverNow: Instant,
    ): Long = commandPositionTicks / TICKS_PER_MS + Duration.between(commandWhen, serverNow).toMillis().coerceAtLeast(0)

    sealed interface Correction {
        data object None : Correction

        /** Play at [speed] for [forMs], then return to 1.0. */
        data class Speed(
            val speed: Float,
            val forMs: Long,
        ) : Correction

        data class Seek(
            val toMs: Long,
        ) : Correction
    }

    /** [actualMs] is the local player's position, [expectedMs] the group's. */
    fun correction(
        actualMs: Long,
        expectedMs: Long,
    ): Correction {
        val drift = expectedMs - actualMs // > 0: we are behind
        val size = abs(drift)
        return when {
            size < MIN_DRIFT_MS -> {
                Correction.None
            }

            size <= MAX_SPEED_DRIFT_MS -> {
                val speed = if (drift > 0) CATCH_UP_SPEED else SLOW_DOWN_SPEED
                // Closing |drift| at |speed - 1| of real time.
                Correction.Speed(speed, (size / abs(speed - 1f)).toLong())
            }

            else -> {
                Correction.Seek(expectedMs)
            }
        }
    }

    /**
     * When to act on a timed command: the local instant for [commandWhen], or now if that has passed. A late
     * `Unpause` is honored by starting at once from [expectedPositionMs] rather than from the command's position.
     */
    fun localFireTime(
        commandWhen: Instant,
        clock: ServerClock,
        localNow: Instant,
    ): Instant = clock.toLocal(commandWhen).let { if (it.isBefore(localNow)) localNow else it }
}
