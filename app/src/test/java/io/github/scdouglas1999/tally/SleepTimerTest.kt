package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SleepTimerTest {
    @Test
    fun `countdown subtracts elapsed time and fires at zero`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.start(15.minutes.inWholeMilliseconds, nowMs = 1_000, player = player)

        assertEquals(SleepTimerEffect.Running, logic.tick(1_000, player, false, false, null))
        assertEquals(15.minutes.inWholeMilliseconds, logic.remainingMs)

        assertEquals(SleepTimerEffect.Running, logic.tick(2_000, player, false, false, null))
        assertEquals(15.minutes.inWholeMilliseconds - 1_000, logic.remainingMs)

        assertEquals(
            SleepTimerEffect.Running,
            logic.tick(1_000 + 14.minutes.inWholeMilliseconds, player, false, false, null),
        )
        assertEquals(60_000L, logic.remainingMs)

        assertEquals(
            SleepTimerEffect.Fire,
            logic.tick(1_000 + 15.minutes.inWholeMilliseconds, player, false, false, null),
        )
        assertEquals(0L, logic.remainingMs)
        assertFalse(logic.active)
        // A later tick must not fire again.
        assertEquals(
            SleepTimerEffect.None,
            logic.tick(1_000 + 16.minutes.inWholeMilliseconds, player, false, false, null),
        )
    }

    @Test
    fun `an overdue tick still fires once with zero remaining`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.start(5_000, nowMs = 0, player = player)
        assertEquals(SleepTimerEffect.Fire, logic.tick(9_000, player, false, false, null))
        assertEquals(0L, logic.remainingMs)
        assertEquals(SleepTimerEffect.None, logic.tick(12_000, player, false, false, null))
    }

    @Test
    fun `starting at zero fires immediately`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.start(0, nowMs = 50, player = player)
        assertEquals(SleepTimerEffect.Fire, logic.tick(50, player, false, false, null))
        assertFalse(logic.active)
    }

    @Test
    fun `cancel stops the countdown`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.start(30.minutes.inWholeMilliseconds, nowMs = 0, player = player)
        logic.tick(5_000, player, false, false, null)
        logic.cancel()
        assertNull(logic.remainingMs)
        assertFalse(logic.active)
        assertFalse(logic.untilEnd)
        assertEquals(SleepTimerEffect.None, logic.tick(30.minutes.inWholeMilliseconds, player, false, false, null))
    }

    @Test
    fun `a new start replaces the previous deadline`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.start(10_000, nowMs = 0, player = player)
        logic.tick(4_000, player, false, false, null)
        logic.start(20_000, nowMs = 4_000, player = player)
        assertTrue(logic.active)
        assertFalse(logic.untilEnd)
        assertEquals(20_000L, logic.remainingMs)
        assertEquals(SleepTimerEffect.Running, logic.tick(4_000, player, false, false, null))
        assertEquals(20_000L, logic.remainingMs)
    }

    @Test
    fun `countdown ignores the media ending`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.start(10_000, nowMs = 0, player = player)
        assertEquals(SleepTimerEffect.Running, logic.tick(1_000, player, false, ended = true, mediaRemainingMs = 0))
        assertEquals(9_000L, logic.remainingMs)
        assertTrue(logic.active)
    }

    @Test
    fun `replacing or releasing the player clears without firing`() {
        val original = Any()
        val replacement = Any()
        val logic = SleepTimerLogic()
        logic.start(10_000, nowMs = 0, player = original)
        assertEquals(
            SleepTimerEffect.Clear,
            logic.tick(1_000, replacement, playerReleased = false, ended = true, mediaRemainingMs = 0),
        )
        assertNull(logic.remainingMs)
        assertFalse(logic.active)

        logic.startUntilEnd(original, mediaRemainingMs = 5_000)
        assertEquals(
            SleepTimerEffect.Clear,
            logic.tick(1_000, original, playerReleased = true, ended = true, mediaRemainingMs = 0),
        )
        assertFalse(logic.active)
        assertNull(logic.remainingMs)
    }

    @Test
    fun `until end follows media time and fires at zero or when playback ends`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.startUntilEnd(player, mediaRemainingMs = 5_000)
        assertTrue(logic.untilEnd)
        // Wall clock advancing does not consume a media timer.
        assertEquals(
            SleepTimerEffect.Running,
            logic.tick(nowMs = 60_000, player, false, ended = false, mediaRemainingMs = 4_000),
        )
        assertEquals(4_000L, logic.remainingMs)

        assertEquals(
            SleepTimerEffect.Fire,
            logic.tick(nowMs = 61_000, player, false, ended = false, mediaRemainingMs = 0),
        )
        assertFalse(logic.active)

        val ended = SleepTimerLogic()
        ended.startUntilEnd(player, mediaRemainingMs = 40_000)
        assertEquals(
            SleepTimerEffect.Fire,
            ended.tick(nowMs = 1_000, player, false, ended = true, mediaRemainingMs = 40_000),
        )
    }

    @Test
    fun `until end with an unknown length keeps waiting`() {
        val player = Any()
        val logic = SleepTimerLogic()
        logic.startUntilEnd(player, mediaRemainingMs = null)
        assertNull(logic.remainingMs)
        assertEquals(SleepTimerEffect.Running, logic.tick(5_000, player, false, ended = false, mediaRemainingMs = null))
        assertNull(logic.remainingMs)
        assertTrue(logic.active)
        logic.cancel()
        assertEquals(SleepTimerEffect.None, logic.tick(9_000, player, false, ended = true, mediaRemainingMs = 0))
    }

    @Test
    fun `media remaining treats live and unknown durations as unknown`() {
        assertNull(SleepTimerLogic.mediaRemainingMs(durationMs = -1, positionMs = 0, live = false))
        assertNull(SleepTimerLogic.mediaRemainingMs(durationMs = 0, positionMs = 0, live = false))
        assertNull(SleepTimerLogic.mediaRemainingMs(durationMs = 10_000, positionMs = 10_000, live = true))
        assertEquals(7_500L, SleepTimerLogic.mediaRemainingMs(10_000, 2_500, live = false))
        assertEquals(0L, SleepTimerLogic.mediaRemainingMs(10_000, 10_000, live = false))
        assertEquals(0L, SleepTimerLogic.mediaRemainingMs(10_000, 11_000, live = false))
    }

    @Test
    fun `displayed remaining uses infinite for an unknown until-end timer`() {
        assertNull(displayedSleepRemaining(active = false, untilEnd = true, remainingMs = null))
        assertEquals(
            Duration.INFINITE,
            displayedSleepRemaining(active = true, untilEnd = true, remainingMs = null),
        )
        assertEquals(
            5.seconds,
            displayedSleepRemaining(active = true, untilEnd = true, remainingMs = 5_000),
        )
        assertEquals(
            15.minutes,
            displayedSleepRemaining(active = true, untilEnd = false, remainingMs = 15.minutes.inWholeMilliseconds),
        )
        assertNull(displayedSleepRemaining(active = true, untilEnd = false, remainingMs = null))
    }

    @Test
    fun `only playback destinations are left when the timer fires`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        assertTrue(sleepTimerShouldLeave(Destination.Playback(itemId = id, positionMs = 0)))
        assertTrue(sleepTimerShouldLeave(Destination.TallyPlayback(itemId = id, channelId = "sim")))
        assertFalse(sleepTimerShouldLeave(Destination.Home()))
        assertFalse(sleepTimerShouldLeave(Destination.Search()))
        assertFalse(sleepTimerShouldLeave(null))
    }

    @Test
    fun `clock format matches the chip and rounds up`() {
        assertEquals("23:10", formatSleepClock(23.minutes + 10.seconds))
        assertEquals("15:00", formatSleepClock(15.minutes))
        assertEquals("0:45", formatSleepClock(45.seconds))
        assertEquals("59:59", formatSleepClock(59.minutes + 59.seconds))
        assertEquals("1:00:00", formatSleepClock(60.minutes))
        assertEquals("1:30:00", formatSleepClock(90.minutes))
        assertEquals("0:00", formatSleepClock(Duration.ZERO))
        assertEquals("0:01", formatSleepClock(1.milliseconds))
        assertEquals("0:01", formatSleepClock(1_000.milliseconds))
        assertEquals("0:02", formatSleepClock(1_001.milliseconds))
        assertEquals("14:10", formatSleepClock(850_000.milliseconds))
        assertEquals("", formatSleepClock(Duration.INFINITE))
    }

    @Test
    fun `the chip is urgent only under one minute`() {
        assertFalse(sleepChipUrgent(60.seconds))
        assertTrue(sleepChipUrgent(59.seconds))
        assertTrue(sleepChipUrgent(23.seconds))
        assertFalse(sleepChipUrgent(23.minutes + 10.seconds))
        assertFalse(sleepChipUrgent(Duration.INFINITE))
        assertFalse(sleepChipUrgent(15.minutes))
    }
}
