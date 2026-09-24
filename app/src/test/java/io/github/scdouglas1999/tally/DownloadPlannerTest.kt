package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.downloads.Disallowed
import io.github.scdouglas1999.tally.downloads.DownloadFailure
import io.github.scdouglas1999.tally.downloads.DownloadPermissions
import io.github.scdouglas1999.tally.downloads.DownloadPlanner
import io.github.scdouglas1999.tally.downloads.DownloadPlanner.EpisodeRef
import io.github.scdouglas1999.tally.downloads.DownloadPlanner.LocalProgress
import io.github.scdouglas1999.tally.downloads.DownloadPlanner.ServerProgress
import io.github.scdouglas1999.tally.downloads.DownloadQuality
import io.github.scdouglas1999.tally.downloads.DownloadRung
import io.github.scdouglas1999.tally.downloads.DownloadSettingsStore
import io.github.scdouglas1999.tally.downloads.PlanItem
import io.github.scdouglas1999.tally.downloads.TallyOfflineStart
import io.github.scdouglas1999.tally.downloads.subtitlePathKey
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID

class DownloadPlannerTest {
    private val allowed = DownloadPermissions(download = true, transcode = true)

    /** Big Buck Bunny on the dev server: 1080p, 14.8 Mbps, 105 MB, 60 s. */
    private val bunny =
        PlanItem(
            isVideo = true,
            sourceHeight = 1080,
            sourceWidth = 1920,
            sourceBitrate = 14_772_533,
            sizeBytes = 105_044_508,
            runtimeTicks = 600_000_000,
            canDownload = true,
        )

    /** The dev server's films: 360p at 0.75 Mbps, 90 s. */
    private val small =
        PlanItem(
            isVideo = true,
            sourceHeight = 360,
            sourceWidth = 640,
            sourceBitrate = 755_000,
            sizeBytes = 4_150_000,
            runtimeTicks = 900_000_000,
            canDownload = true,
        )

    private val track =
        PlanItem(
            isVideo = false,
            sourceHeight = null,
            sourceWidth = null,
            sourceBitrate = 64_000,
            sizeBytes = 360_000,
            runtimeTicks = 450_000_000,
            canDownload = true,
        )

    @Test
    fun `a 1080p source offers Original and every rung, same height at a lower bitrate included`() {
        val options = DownloadPlanner.options(listOf(bunny), allowed)
        assertEquals(
            listOf(
                DownloadQuality.Original,
                DownloadQuality.Converted(DownloadRung.P1080),
                DownloadQuality.Converted(DownloadRung.P720),
                DownloadQuality.Converted(DownloadRung.P480),
                DownloadQuality.Converted(DownloadRung.P360),
            ),
            options.map { it.quality },
        )
        assertTrue(options.all { it.disallowed == null })
    }

    @Test
    fun `a source below every rung offers Original only`() {
        // 360p at 0.755 Mbps: the 360p rung (0.8 Mbps) is not a saving
        assertEquals(listOf(DownloadQuality.Original), DownloadPlanner.options(listOf(small), allowed).map { it.quality })
    }

    @Test
    fun `music offers Original only`() {
        assertEquals(listOf(DownloadQuality.Original), DownloadPlanner.options(listOf(track), allowed).map { it.quality })
    }

    @Test
    fun `a scope film counts by its width`() {
        val scope = bunny.copy(sourceHeight = 800, sourceWidth = 1920)
        assertEquals(1080, DownloadPlanner.effectiveHeight(800, 1920))
        assertTrue(DownloadPlanner.rungIsBelow(DownloadRung.P1080, scope))
    }

    @Test
    fun `unknown height or bitrate never excludes a rung`() {
        val unknown = bunny.copy(sourceHeight = null, sourceWidth = null, sourceBitrate = null)
        assertEquals(5, DownloadPlanner.options(listOf(unknown), allowed).size)
    }

    @Test
    fun `sizes are the file size for Original and bitrate times runtime converted`() {
        val options = DownloadPlanner.options(listOf(bunny), allowed).associate { it.quality to it.estimatedBytes }
        assertEquals(105_044_508L, options[DownloadQuality.Original])
        // (4 Mbps + 128 kbps) x 60 s / 8
        assertEquals(30_960_000L, options[DownloadQuality.Converted(DownloadRung.P720)])
        assertEquals(60_960_000L, options[DownloadQuality.Converted(DownloadRung.P1080)])
    }

    @Test
    fun `Original without a file size falls back to bitrate times runtime, else unknown`() {
        assertEquals(110_793_997L, DownloadPlanner.originalBytes(bunny.copy(sizeBytes = null)))
        assertNull(DownloadPlanner.originalBytes(bunny.copy(sizeBytes = null, runtimeTicks = null)))
        assertNull(DownloadPlanner.convertedBytes(DownloadRung.P720, null))
    }

    @Test
    fun `a group sums its items, each at the quality it really gets`() {
        val group = listOf(bunny, small)
        val options = DownloadPlanner.options(group, allowed).associate { it.quality to it.estimatedBytes }
        assertEquals(105_044_508L + 4_150_000L, options[DownloadQuality.Original])
        // the small film is not converted up: it goes as Original
        assertEquals(30_960_000L + 4_150_000L, options[DownloadQuality.Converted(DownloadRung.P720)])
        assertEquals(DownloadQuality.Original, DownloadPlanner.qualityFor(small, DownloadQuality.Converted(DownloadRung.P720)))
        assertEquals(
            DownloadQuality.Converted(DownloadRung.P720),
            DownloadPlanner.qualityFor(bunny, DownloadQuality.Converted(DownloadRung.P720)),
        )
    }

    @Test
    fun `a group with one unknown size has an unknown estimate`() {
        assertNull(DownloadPlanner.estimate(listOf(bunny, small.copy(sizeBytes = null, sourceBitrate = null)), DownloadQuality.Original))
    }

    @Test
    fun `permissions decide what is allowed`() {
        val noDownload = DownloadPlanner.options(listOf(bunny), DownloadPermissions(download = false, transcode = true))
        assertTrue(noDownload.all { it.disallowed == Disallowed.NO_DOWNLOAD_PERMISSION })

        val noTranscode = DownloadPlanner.options(listOf(bunny), DownloadPermissions(download = true, transcode = false))
        assertNull(noTranscode.first().disallowed)
        assertTrue(noTranscode.drop(1).all { it.disallowed == Disallowed.NO_TRANSCODE_PERMISSION })

        val refused = DownloadPlanner.options(listOf(bunny.copy(canDownload = false)), allowed)
        assertTrue(refused.all { it.disallowed == Disallowed.NOT_DOWNLOADABLE })
        assertTrue(DownloadPlanner.options(emptyList(), allowed).isEmpty())
    }

    private fun ep(
        season: Int?,
        episode: Int?,
        played: Boolean,
        playable: Boolean = true,
    ) = EpisodeRef(UUID.nameUUIDFromBytes("$season-$episode".toByteArray()), season, episode, played, playable)

    @Test
    fun `next unwatched starts after the last watched episode, in order, specials and missing left out`() {
        val s1e1 = ep(1, 1, true)
        val s1e2 = ep(1, 2, false)
        val s1e3 = ep(1, 3, true)
        val s1e4 = ep(1, 4, false)
        val s1e5 = ep(1, 5, false, playable = false)
        val s2e1 = ep(2, 1, false)
        val s2e2 = ep(2, 2, false)
        val special = ep(0, 1, false)
        val shuffled = listOf(s2e2, special, s1e4, s1e1, s2e1, s1e3, s1e5, s1e2)
        assertEquals(listOf(s1e4.id, s2e1.id), DownloadPlanner.nextUnwatched(shuffled, 2))
        assertEquals(listOf(s1e4.id, s2e1.id, s2e2.id), DownloadPlanner.nextUnwatched(shuffled, 10))
        assertEquals(emptyList<UUID>(), DownloadPlanner.nextUnwatched(shuffled, 0))
    }

    @Test
    fun `next unwatched with nothing watched starts at the first episode`() {
        val list = listOf(ep(1, 2, false), ep(1, 1, false), ep(1, 3, false))
        assertEquals(list.sortedBy { it.episode }.take(2).map { it.id }, DownloadPlanner.nextUnwatched(list, 2))
    }

    @Test
    fun `next unwatched after the last episode is nothing`() {
        assertEquals(emptyList<UUID>(), DownloadPlanner.nextUnwatched(listOf(ep(1, 1, false), ep(1, 2, true)), 3))
    }

    @Test
    fun `progress follows Jellyfin's resume rules`() {
        val now = 1_000L
        assertEquals(LocalProgress(30_000, false, now), DownloadPlanner.progressAt(30_000, 60_000, false, false, now))
        // under 5 %: not started
        assertEquals(LocalProgress(0, false, now), DownloadPlanner.progressAt(2_000, 60_000, false, false, now))
        // over 90 % or ended: played
        assertEquals(LocalProgress(0, true, now), DownloadPlanner.progressAt(55_000, 60_000, false, false, now))
        assertEquals(LocalProgress(0, true, now), DownloadPlanner.progressAt(10_000, 60_000, true, false, now))
        // played stays played when resumed
        assertEquals(LocalProgress(30_000, true, now), DownloadPlanner.progressAt(30_000, 60_000, false, true, now))
        // unknown duration keeps the position
        assertEquals(LocalProgress(1_000, false, now), DownloadPlanner.progressAt(1_000, null, false, false, now))
    }

    @Test
    fun `offline progress is pushed only when newer than the server's last play`() {
        val local = LocalProgress(30_000, false, 2_000)
        assertTrue(DownloadPlanner.shouldPush(local, ServerProgress(0, false, null)))
        assertTrue(DownloadPlanner.shouldPush(local, ServerProgress(10_000, false, 1_000)))
        assertFalse(DownloadPlanner.shouldPush(local, ServerProgress(50_000, false, 3_000)))
        assertFalse(DownloadPlanner.shouldPush(local, ServerProgress(50_000, false, 2_000)))
    }

    @Test
    fun `the newest progress wins for resuming`() {
        val local = LocalProgress(30_000, false, 2_000)
        assertEquals(30_000L to false, DownloadPlanner.newest(local, ServerProgress(10_000, false, 1_000)))
        assertEquals(50_000L to true, DownloadPlanner.newest(local, ServerProgress(50_000, true, 3_000)))
        assertEquals(10_000L to false, DownloadPlanner.newest(null, ServerProgress(10_000, false, 1_000)))
        assertEquals(0L to false, DownloadPlanner.newest(null, null))
    }

    @Test
    fun `failures are classified and retried with backoff`() {
        assertEquals(DownloadFailure.SERVER_UNREACHABLE, DownloadFailure.of(IOException("x", UnknownHostException("h"))))
        assertEquals(DownloadFailure.DISK_FULL, DownloadFailure.of(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(DownloadFailure.UNKNOWN, DownloadFailure.of(null))
        assertEquals(30_000L, DownloadFailure.retryDelayMs(1))
        assertEquals(60_000L, DownloadFailure.retryDelayMs(2))
        assertEquals(3_600_000L, DownloadFailure.retryDelayMs(20))
    }

    @Test
    fun `subtitle URLs map to the item and stream`() {
        val item = "1a27aba7d8639d762d762bf9f64218fa"
        assertEquals(item to 3, subtitlePathKey("/Videos/$item/$item/Subtitles/3/0/Stream.vtt"))
        assertEquals(item to 12, subtitlePathKey("/jellyfin/Videos/1a27aba7-d863-9d76-2d76-2bf9f64218fa/src/Subtitles/12/0/Stream.ass"))
        assertNull(subtitlePathKey("/Videos/$item/stream.mkv"))
    }

    @Test
    fun `default quality setting round-trips`() {
        listOf(null, DownloadQuality.Original, DownloadQuality.Converted(DownloadRung.P480)).forEach {
            assertEquals(it, DownloadSettingsStore.decodeQuality(DownloadSettingsStore.encodeQuality(it)))
        }
        assertNull(DownloadSettingsStore.decodeQuality("NOPE"))
    }

    @Test
    fun `offline start happens only when the server did not answer`() {
        assertTrue(TallyOfflineStart.isUnreachable(ApiClientException("timeout", IOException())))
        assertTrue(TallyOfflineStart.isUnreachable(UnknownHostException()))
        assertTrue(TallyOfflineStart.isUnreachable(InvalidStatusException(502)))
        assertFalse(TallyOfflineStart.isUnreachable(InvalidStatusException(401)))
        assertFalse(TallyOfflineStart.isUnreachable(IllegalStateException()))
    }
}
