package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.together.ClockSample
import com.github.damontecres.wholphin.jellytv.together.ServerClock
import com.github.damontecres.wholphin.jellytv.together.SyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class SyncPolicyTest {
    private val whenAt: Instant = Instant.parse("2026-09-22T17:00:00Z")

    @Test
    fun expectedPositionClampsAFutureCommandAndAdvancesAPastOne() {
        val ticks = 12_500L * SyncPolicy.TICKS_PER_MS
        assertEquals(12_500L, SyncPolicy.expectedPositionMs(ticks, whenAt, whenAt))
        assertEquals(17_500L, SyncPolicy.expectedPositionMs(ticks, whenAt, whenAt.plusMillis(5_000)))
        assertEquals(12_500L, SyncPolicy.expectedPositionMs(ticks, whenAt, whenAt.minusMillis(5_000)))
        assertEquals(2_999L, SyncPolicy.expectedPositionMs(29_997_775L, whenAt, whenAt))
    }

    @Test
    fun correctionBandsAndBoundaries() {
        assertEquals(SyncPolicy.Correction.None, SyncPolicy.correction(10_000, 10_000))
        assertEquals(SyncPolicy.Correction.None, SyncPolicy.correction(10_000, 10_059))
        assertEquals(SyncPolicy.Correction.None, SyncPolicy.correction(10_059, 10_000))

        assertSpeed(SyncPolicy.correction(10_000, 10_060), SyncPolicy.CATCH_UP_SPEED, 60)
        assertSpeed(SyncPolicy.correction(10_060, 10_000), SyncPolicy.SLOW_DOWN_SPEED, 60)
        assertSpeed(SyncPolicy.correction(0, SyncPolicy.MAX_SPEED_DRIFT_MS), SyncPolicy.CATCH_UP_SPEED, SyncPolicy.MAX_SPEED_DRIFT_MS)
        assertSpeed(
            SyncPolicy.correction(SyncPolicy.MAX_SPEED_DRIFT_MS, 0),
            SyncPolicy.SLOW_DOWN_SPEED,
            SyncPolicy.MAX_SPEED_DRIFT_MS,
        )

        assertEquals(
            SyncPolicy.Correction.Seek(SyncPolicy.MAX_SPEED_DRIFT_MS + 1),
            SyncPolicy.correction(0, SyncPolicy.MAX_SPEED_DRIFT_MS + 1),
        )
        assertEquals(SyncPolicy.Correction.Seek(0), SyncPolicy.correction(SyncPolicy.MAX_SPEED_DRIFT_MS + 1, 0))
    }

    @Test
    fun localFireTimeUsesNowWhenTheCommandIsAlreadyDue() {
        val t0 = Instant.parse("2026-09-22T17:00:00Z")
        val clock = ServerClock()
        clock.add(
            ClockSample(
                sentAt = t0,
                serverReceived = t0.plusMillis(1_000),
                serverSent = t0.plusMillis(1_000),
                receivedAt = t0,
            ),
        )
        assertEquals(1_000L, clock.offset.toMillis())

        val localNow = t0.plusSeconds(10)
        val future = localNow.plusMillis(clock.offset.toMillis()).plusMillis(500)
        assertEquals(localNow.plusMillis(500), SyncPolicy.localFireTime(future, clock, localNow))

        val past = localNow.plusMillis(clock.offset.toMillis()).minusMillis(500)
        assertEquals(localNow, SyncPolicy.localFireTime(past, clock, localNow))

        val due = localNow.plusMillis(clock.offset.toMillis())
        assertEquals(localNow, SyncPolicy.localFireTime(due, clock, localNow))
    }

    private fun assertSpeed(
        correction: SyncPolicy.Correction,
        speed: Float,
        driftMs: Long,
    ) {
        val applied = correction as SyncPolicy.Correction.Speed
        assertEquals(speed, applied.speed)
        val closing = (driftMs / abs(speed - 1f)).toLong()
        assertEquals(closing, applied.forMs)
        assertTrue(applied.forMs > 0)
    }
}
