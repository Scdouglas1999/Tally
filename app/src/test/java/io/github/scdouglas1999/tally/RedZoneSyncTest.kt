package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.api.TallyRedZone
import io.github.scdouglas1999.tally.api.TallyRedZoneCut
import io.github.scdouglas1999.tally.data.PlayerLatency
import io.github.scdouglas1999.tally.data.RedZoneSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** The RedZone overlay follows the picture: a cut shows once the player's own playback has reached it. */
class RedZoneSyncTest {
    private val t0 = Instant.parse("2026-09-27T21:00:00Z").toEpochMilli()

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun cut(
        game: String?,
        sinceMs: Long,
        reason: String = "hottest",
    ) = TallyRedZoneCut(
        active = game != null,
        gameId = game,
        title =
            game?.let {
                "$it title"
            },
        reason = game?.let { reason },
        since = iso(sinceMs),
    )

    /** An answer at [serverMs] (server clock): on now is the last of [recent], unless [on] says otherwise. */
    private fun status(
        serverMs: Long,
        vararg recent: TallyRedZoneCut,
        on: TallyRedZoneCut = recent.last(),
    ) = TallyRedZone(
        active = on.active,
        gameId = on.gameId,
        title = on.title,
        reason = on.reason,
        since = on.since,
        recent = recent.toList(),
        serverTime = iso(serverMs),
    )

    private val latency = 20_000L

    @Test
    fun `the first answer shows at once`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 3_000)), t0)
        // the player is 20 s behind, so A is not on its screen yet by the numbers, but nothing earlier is known
        assertEquals("A", sync.at(t0, latency)?.gameId)
    }

    @Test
    fun `a first answer that knows the cut before shows the game the picture is still on`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 90_000), cut("B", t0 - 5_000)), t0)
        assertEquals("A", sync.at(t0, latency)?.gameId)
        assertEquals("A", sync.at(t0 + 14_000, latency)?.gameId)
        assertEquals("B", sync.at(t0 + 15_000, latency)?.gameId)
    }

    @Test
    fun `a cut waits until the player has reached it`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000)), t0)
        assertEquals("A", sync.at(t0, latency)?.gameId)
        // the server cuts to B at t0 + 2 s; the next poll hears of it at t0 + 10 s
        sync.offer(status(t0 + 10_000, cut("A", t0 - 60_000), cut("B", t0 + 2_000, "score")), t0 + 10_000)
        assertEquals("A", sync.at(t0 + 10_000, latency)?.gameId)
        assertEquals("A", sync.at(t0 + 21_999, latency)?.gameId)
        val b = sync.at(t0 + 22_000, latency)
        assertEquals("B", b?.gameId)
        assertEquals("score", b?.reason)
        assertEquals("B title", b?.title)
    }

    @Test
    fun `several cuts inside one latency window each get their turn`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000)), t0)
        sync.at(t0, latency)
        sync.offer(status(t0 + 10_000, cut("A", t0 - 60_000), cut("B", t0 + 1_000), cut("C", t0 + 6_000, "red zone")), t0 + 10_000)
        sync.offer(
            status(t0 + 20_000, cut("A", t0 - 60_000), cut("B", t0 + 1_000), cut("C", t0 + 6_000, "red zone"), cut(null, t0 + 15_000)),
            t0 + 20_000,
        )
        val seen = (0..40).map { sync.at(t0 + 10_000 + it * 1_000L, latency) }
        assertEquals("A", seen[0]?.gameId) // t0 + 10 s
        assertEquals("B", seen[11]?.gameId) // t0 + 21 s: B's cut reached
        assertEquals("C", seen[16]?.gameId) // t0 + 26 s
        assertEquals("red zone", seen[16]?.reason)
        assertFalse(seen[25]!!.active) // t0 + 35 s: the slate
        assertNull(seen[25]!!.gameId)
    }

    @Test
    fun `an older server without recent is followed by since alone`() {
        val sync = RedZoneSync()
        val a = TallyRedZone(active = true, gameId = "A", title = "A", reason = "hottest", since = iso(t0 - 60_000))
        sync.offer(a, t0)
        assertEquals("A", sync.at(t0, latency)?.gameId)
        sync.offer(a.copy(gameId = "B", title = "B", since = iso(t0 + 4_000)), t0 + 10_000)
        assertEquals("A", sync.at(t0 + 23_000, latency)?.gameId)
        assertEquals("B", sync.at(t0 + 24_000, latency)?.gameId)
    }

    @Test
    fun `a status with no since (the channel not running yet) shows at once`() {
        val sync = RedZoneSync()
        sync.offer(TallyRedZone(active = true, gameId = "A", title = "A", reason = "hottest"), t0)
        assertEquals("A", sync.at(t0, latency)?.gameId)
    }

    @Test
    fun `a cut decided but not in the playlist yet waits for its own time, then the playlist's`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000)), t0)
        sync.at(t0, latency)
        // the director picked B at t0 + 5 s (B's stream still starting): recent has only A
        sync.offer(status(t0 + 8_000, cut("A", t0 - 60_000), on = cut("B", t0 + 5_000)), t0 + 8_000)
        assertEquals("A", sync.at(t0 + 20_000, latency)?.gameId)
        // B reached the playlist at t0 + 9 s
        sync.offer(status(t0 + 18_000, cut("A", t0 - 60_000), cut("B", t0 + 9_000)), t0 + 18_000)
        assertEquals("A", sync.at(t0 + 28_999, latency)?.gameId)
        assertEquals("B", sync.at(t0 + 29_000, latency)?.gameId)
    }

    @Test
    fun `the overlay never steps back when the latency estimate jumps`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000), cut("B", t0 - 25_000)), t0)
        assertEquals("B", sync.at(t0, latency)?.gameId)
        assertEquals("B", sync.at(t0 + 1_000, 40_000)?.gameId)
    }

    @Test
    fun `server times are read on this device's clock`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000)), t0)
        sync.at(t0, latency)
        // the server's clock runs 30 s ahead of the device's: B's cut at server t0 + 32 s is device t0 + 2 s
        sync.offer(status(t0 + 40_000, cut("A", t0 - 30_000), cut("B", t0 + 32_000)), t0 + 10_000)
        assertEquals("A", sync.at(t0 + 21_999, latency)?.gameId)
        assertEquals("B", sync.at(t0 + 22_000, latency)?.gameId)
    }

    @Test
    fun `an unreadable answer changes nothing and a new reason for the same cut shows at once`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000)), t0)
        sync.offer(null, t0 + 10_000)
        assertEquals("hottest", sync.at(t0 + 10_000, latency)?.reason)
        sync.offer(status(t0 + 20_000, cut("A", t0 - 60_000), on = cut("A", t0 - 60_000, "red zone")), t0 + 20_000)
        assertEquals("red zone", sync.at(t0 + 20_000, latency)?.reason)
    }

    @Test
    fun `the same cut asked again is the same value (nothing on screen changes)`() {
        val sync = RedZoneSync()
        sync.offer(status(t0, cut("A", t0 - 60_000)), t0)
        val first = sync.at(t0, latency)
        sync.offer(status(t0 + 10_000, cut("A", t0 - 60_000)), t0 + 10_000)
        assertEquals(first, sync.at(t0 + 10_000, latency))
        assertEquals(emptyList<TallyRedZoneCut>(), first?.recent)
    }

    @Test
    fun `decodes recent and serverTime`() {
        val s =
            TallyJson.decodeFromString<TallyRedZone>(
                """{ "active": true, "gameId": "B", "title": "B", "reason": "score", "since": "2026-09-27T21:00:01.5+00:00",
                   "next": [], "serverTime": "2026-09-27T21:00:09.1234567+00:00",
                   "recent": [ { "active": true, "gameId": "A", "title": "A", "reason": "hottest", "since": "2026-09-27T20:58:00+00:00" },
                               { "active": true, "gameId": "B", "title": "B", "reason": "score", "since": "2026-09-27T21:00:02+00:00" } ] }""",
            )
        assertEquals(listOf("A", "B"), s.recent.map { it.gameId })
        assertEquals(t0 + 9_123, RedZoneSync.parse(s.serverTime))
        assertEquals(t0 + 2_000, RedZoneSync.parse(s.recent[1].since))
        assertNull(RedZoneSync.parse(null))
        assertNull(RedZoneSync.parse("soon"))
    }

    @Test
    fun `the player's latency`() {
        // ExoPlayer's own live offset wins
        assertEquals(12_000L, PlayerLatency.of(12_000, 60_000, 30_000, 2_000, remux = false))
        // else the distance to the listed edge plus the time since it moved (capped), plus Jellyfin's remux
        assertEquals(10_000L + 2_000, PlayerLatency.of(null, 70_000, 60_000, 2_000, remux = false))
        assertEquals(10_000L + PlayerLatency.MAX_EDGE_AGE_MS, PlayerLatency.of(null, 70_000, 60_000, 60_000, remux = false))
        assertEquals(10_000L + PlayerLatency.REMUX_MS, PlayerLatency.of(null, 70_000, 60_000, 0, remux = true))
        assertNull(PlayerLatency.of(null, null, 60_000, 0, remux = true))
    }
}
