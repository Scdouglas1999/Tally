package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.together.ClockSample
import com.github.damontecres.wholphin.jellytv.together.ServerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ServerClockTest {
    private val t0: Instant = Instant.parse("2026-09-22T17:00:00Z")

    @Test
    fun offsetAndRoundTripOfAKnownSample() {
        val sample =
            ClockSample(
                sentAt = t0,
                serverReceived = t0.plusMillis(100),
                serverSent = t0.plusMillis(110),
                receivedAt = t0.plusMillis(150),
            )
        assertEquals(140L, sample.roundTrip.toMillis())
        assertEquals(30L, sample.offset.toMillis())
    }

    @Test
    fun offsetUsesTheSmallestRoundTripInTheWindow() {
        val clock = ServerClock()
        assertFalse(clock.hasSample)
        assertEquals(0L, clock.offset.toMillis())
        assertEquals(0L, clock.pingMs)

        val best =
            ClockSample(
                sentAt = t0,
                serverReceived = t0.plusMillis(20),
                serverSent = t0.plusMillis(25),
                receivedAt = t0.plusMillis(40),
            )
        // round trip 35 ms, offset (20 + (25 - 40)) / 2 = 2 ms (integer division of 5 ms).
        assertEquals(35L, best.roundTrip.toMillis())
        assertEquals(2L, best.offset.toMillis())
        clock.add(best)
        repeat(ServerClock.WINDOW - 1) { clock.add(worse()) }
        assertEquals(2L, clock.offset.toMillis())
        assertEquals(35L, clock.pingMs)

        clock.add(worse())
        assertEquals(-45L, clock.offset.toMillis())
        assertEquals(490L, clock.pingMs)
    }

    @Test
    fun serverNowAndToLocalRoundTrip() {
        val empty = ServerClock()
        val now = t0.plusSeconds(30)
        assertEquals(now, empty.serverNow(now))
        assertEquals(now, empty.toLocal(now))

        val clock = ServerClock()
        clock.add(
            ClockSample(
                sentAt = t0,
                serverReceived = t0.plusMillis(100),
                serverSent = t0.plusMillis(110),
                receivedAt = t0.plusMillis(150),
            ),
        )
        val server = Instant.parse("2026-09-22T18:00:00Z")
        val local = clock.toLocal(server)
        assertEquals(server.minusMillis(30), local)
        assertEquals(server, clock.serverNow(local))
    }

    private fun worse(): ClockSample =
        ClockSample(
            sentAt = t0,
            serverReceived = t0.plusMillis(200),
            serverSent = t0.plusMillis(210),
            receivedAt = t0.plusMillis(500),
        )
}
