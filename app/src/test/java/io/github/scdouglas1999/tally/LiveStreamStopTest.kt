package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import io.github.scdouglas1999.tally.playback.LiveStreamStop
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStreamStopTest {
    private fun playing(liveStreamId: String?): CurrentPlayback = mockk { every { this@mockk.liveStreamId } returns liveStreamId }

    @Test
    fun `only a playback with a live stream is stopped before it is opened again`() {
        assertTrue(LiveStreamStop.stopsFirst(playing("e2329f_af999c_7a5aa5")))
        assertFalse(LiveStreamStop.stopsFirst(playing(null)))
        assertFalse(LiveStreamStop.stopsFirst(null))
    }

    @Test
    fun `the stream is opened again only after the stop report is done`() =
        runTest {
            val report = CompletableDeferred<Unit>()
            val waiting = async { LiveStreamStop.awaitReports(listOf(report), LiveStreamStop.STOP_TIMEOUT_MS) }
            advanceTimeBy(1_500)
            runCurrent()
            assertFalse(waiting.isCompleted)
            report.complete(Unit)
            assertTrue(waiting.await())
        }

    @Test
    fun `a stop report that does not finish in time is canceled so it can never close the new stream`() =
        runTest {
            val report = CompletableDeferred<Unit>()
            val waiting = async { LiveStreamStop.awaitReports(listOf(report), LiveStreamStop.STOP_TIMEOUT_MS) }
            advanceTimeBy(LiveStreamStop.STOP_TIMEOUT_MS + 1)
            assertFalse(waiting.await())
            assertTrue(report.isCancelled)
        }

    @Test
    fun `nothing to wait for when the old playback sent no report`() =
        runTest {
            assertTrue(LiveStreamStop.awaitReports(emptyList(), LiveStreamStop.STOP_TIMEOUT_MS))
        }
}
