package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.household.HOUSEHOLD_TICKS_PER_MS
import io.github.scdouglas1999.tally.household.mapHouseholdSessions
import io.github.scdouglas1999.tally.ui.household.progressFraction
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping of a captured `GET /Sessions` (`sessions-sample.json`) onto [io.github.scdouglas1999.tally.household.HouseholdSession].
 * The file is the server's payload, not a hand-built shape.
 */
class HouseholdSessionsTest {
    private val sessions: List<SessionInfoDto> by lazy {
        val text =
            javaClass
                .getResource("/tally/sessions-sample.json")!!
                .readText()
        ApiSerializer.json.decodeFromString<List<SessionInfoDto>>(text)
    }

    @Test
    fun dropsThisDeviceAndSessionsThatAreNotPlaying() {
        val playing = sessions.filter { it.nowPlayingItem != null }
        val idle = sessions.filter { it.nowPlayingItem == null }
        assertTrue("capture needs a session that is playing", playing.isNotEmpty())
        assertTrue("capture needs a session that is not playing", idle.isNotEmpty())

        val kept = mapHouseholdSessions(sessions, thisDeviceId = "no-such-device")
        assertEquals(playing.map { it.id }.toSet(), kept.map { it.sessionId }.toSet())
        assertTrue(kept.none { mapped -> idle.any { it.id == mapped.sessionId } })

        val thisDevice = playing.first().deviceId
        assertNotNull(thisDevice)
        val withoutThisDevice = mapHouseholdSessions(sessions, thisDeviceId = thisDevice!!)
        assertTrue(withoutThisDevice.none { it.sessionId == playing.first().id })
    }

    @Test
    fun mapsPositionRuntimePauseAndRemoteControlFromTheCapture() {
        val playing = sessions.filter { it.nowPlayingItem != null }
        val mapped = mapHouseholdSessions(sessions, thisDeviceId = "no-such-device")
        assertTrue(playing.any { (it.playState?.positionTicks ?: 0L) > 0L })

        playing.forEach { raw ->
            val one = mapped.single { it.sessionId == raw.id }
            val item = raw.nowPlayingItem!!
            assertEquals(raw.deviceName.orEmpty(), one.deviceName)
            assertEquals(raw.client.orEmpty(), one.client)
            assertEquals(raw.userName.orEmpty(), one.userName)
            assertEquals(item.id, one.itemId)
            assertEquals(item.name, one.itemName)
            assertEquals(item.seriesName, one.seriesName)
            assertEquals(raw.playState?.positionTicks?.div(HOUSEHOLD_TICKS_PER_MS), one.positionMs)
            assertEquals(item.runTimeTicks?.div(HOUSEHOLD_TICKS_PER_MS), one.runtimeMs)
            assertEquals(raw.playState?.isPaused == true, one.isPaused)
            assertEquals(raw.supportsRemoteControl, one.supportsRemoteControl)
        }

        // 6_000_000_000 ticks is ten minutes. Locks the divisor against a ticks/ms mix-up.
        val tenMinutes = playing.first { it.playState?.positionTicks == 6_000_000_000L }
        val tenMinutesMapped = mapped.single { it.sessionId == tenMinutes.id }
        assertEquals(600_000L, tenMinutesMapped.positionMs)
    }

    @Test
    fun progressFractionUsesPositionOverRuntime() {
        assertEquals(0.25f, progressFraction(25_000L, 100_000L))
        assertEquals(1f, progressFraction(150_000L, 100_000L))
        assertEquals(null, progressFraction(10_000L, null))
        assertEquals(null, progressFraction(null, 10_000L))
        assertEquals(null, progressFraction(10_000L, 0L))
    }
}
