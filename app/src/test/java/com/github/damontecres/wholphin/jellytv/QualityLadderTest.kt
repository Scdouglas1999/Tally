package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytv.quality.QualityLadder
import com.github.damontecres.wholphin.jellytv.quality.QualityStatus
import com.github.damontecres.wholphin.jellytv.quality.maxBitratePreferenceIndex
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodeReason
import org.jellyfin.sdk.model.api.TranscodingInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QualityLadderTest {
    private fun mbit(megabits: Int): Int = (megabits * AppPreference.MEGA_BIT).toInt()

    @Test
    fun `big buck bunny offers original with its bitrate then the rungs under 1080p and 14_8 mbps`() {
        val options = QualityLadder.options(1080, 14_772_533)
        assertEquals(
            listOf(
                "ORIGINAL · 14.8 MBPS",
                "1080P · 10 MBPS",
                "1080P · 8 MBPS",
                "720P · 5 MBPS",
                "720P · 3 MBPS",
                "480P · 2 MBPS",
                "360P · 1 MBPS",
            ),
            options.map { it.label },
        )
        assertEquals(
            listOf(null, mbit(10), mbit(8), mbit(5), mbit(3), mbit(2), mbit(1)),
            options.map { it.bitsPerSecond },
        )
        assertEquals(listOf(null, 10, 8, 5, 3, 2, 1), options.map { it.megabits })
    }

    @Test
    fun `an unknown height or bitrate never drops a rung on that axis`() {
        val all = QualityLadder.options(null, null)
        assertEquals(14, all.size)
        assertEquals("ORIGINAL", all.first().label)
        assertEquals("4K · 120 MBPS", all[1].label)
        assertEquals(
            listOf(
                "ORIGINAL · 15 MBPS",
                "1080P · 10 MBPS",
                "1080P · 8 MBPS",
                "720P · 5 MBPS",
                "720P · 3 MBPS",
                "480P · 2 MBPS",
                "360P · 1 MBPS",
            ),
            QualityLadder.options(null, 15_000_000).map { it.label },
        )
        assertEquals(
            listOf("ORIGINAL", "720P · 5 MBPS", "720P · 3 MBPS", "480P · 2 MBPS", "360P · 1 MBPS"),
            QualityLadder.options(720, null).map { it.label },
        )
    }

    @Test
    fun `a tiny 360p file only offers original`() {
        assertEquals(listOf("ORIGINAL · 0.8 MBPS"), QualityLadder.options(360, 800_000).map { it.label })
    }

    @Test
    fun `original shows one decimal under 20 mbps and whole numbers from 20 up`() {
        assertEquals("ORIGINAL", QualityLadder.originalLabel(null))
        assertEquals("ORIGINAL · 14.8 MBPS", QualityLadder.originalLabel(14_772_533))
        assertEquals("ORIGINAL · 19.9 MBPS", QualityLadder.originalLabel(19_900_000))
        assertEquals("ORIGINAL · 20 MBPS", QualityLadder.originalLabel(20_000_000))
        assertEquals("ORIGINAL · 81 MBPS", QualityLadder.originalLabel(80_600_000))
    }

    @Test
    fun `equal bitrate is not under the source and the same height at a lower bitrate is`() {
        assertFalse(QualityLadder.options(1080, mbit(10)).any { it.bitsPerSecond == mbit(10) })
        assertTrue(QualityLadder.options(1080, mbit(10) + 1).any { it.bitsPerSecond == mbit(10) })
        assertFalse(QualityLadder.options(1079, 50_000_000).any { it.label.startsWith("1080P") })
    }

    @Test
    fun `a 2160p 80 mbps source offers 4k 60 down to 360p 1`() {
        assertEquals(
            listOf(
                "ORIGINAL · 80 MBPS",
                "4K · 60 MBPS",
                "4K · 40 MBPS",
                "1080P · 30 MBPS",
                "1080P · 20 MBPS",
                "1080P · 15 MBPS",
                "1080P · 10 MBPS",
                "1080P · 8 MBPS",
                "720P · 5 MBPS",
                "720P · 3 MBPS",
                "480P · 2 MBPS",
                "360P · 1 MBPS",
            ),
            QualityLadder.options(2160, 80_000_000).map { it.label },
        )
        // 80 decimal Mbps is under upstream's 80 Mbps step (80 x 1024 x 1024), above 4K 60.
        assertEquals(14, QualityLadder.options(2160, 130_000_000).size)
    }

    @Test
    fun `now line names the resolution and rounds bitrate to one decimal`() {
        assertEquals("1080P", QualityStatus.resolutionLabel(1080, 1920))
        assertEquals("4K", QualityStatus.resolutionLabel(2160, 3840))
        assertEquals("4K", QualityStatus.resolutionLabel(1600, 3840))
        assertEquals("720P", QualityStatus.resolutionLabel(720, 1280))
        assertEquals("480P", QualityStatus.resolutionLabel(480, 854))
        assertEquals("360P", QualityStatus.resolutionLabel(360, 640))
        assertEquals("14.8 MBPS", QualityStatus.bitrateLabel(14_772_533))
        assertEquals("4 MBPS", QualityStatus.bitrateLabel(4_000_000))
        assertNull(QualityStatus.bitrateLabel(0))
        assertEquals(
            "DIRECT PLAY · 1080P · 14.8 MBPS",
            QualityStatus.formatNowLine("DIRECT PLAY", "1080P", "14.8 MBPS"),
        )
        assertEquals(
            "TRANSCODING · 720P · 4 MBPS",
            QualityStatus.formatNowLine("TRANSCODING", "720P", "4 MBPS"),
        )
    }

    @Test
    fun `transcode now line uses transcode info and the ladder keeps the file height`() {
        val file =
            mediaSource(
                bitrate = 14_772_533,
                height = 1080,
                width = 1920,
            )
        val transcoded =
            mediaSource(
                bitrate = 4_000_000,
                height = 720,
                width = 1280,
            )
        val playback =
            CurrentPlayback(
                item =
                    BaseItem(
                        data =
                            BaseItemDto(
                                id = UUID.randomUUID(),
                                type = BaseItemKind.MOVIE,
                                height = 1080,
                                mediaSources = listOf(file),
                            ),
                    ),
                tracks = emptyList(),
                backend = PlayerBackend.EXO_PLAYER,
                playMethod = PlayMethod.TRANSCODE,
                playSessionId = "session",
                liveStreamId = null,
                mediaSourceInfo = transcoded,
                transcodeInfo =
                    TranscodingInfo(
                        audioCodec = "aac",
                        videoCodec = "h264",
                        container = "ts",
                        isVideoDirect = false,
                        isAudioDirect = false,
                        bitrate = 4_000_000,
                        framerate = null,
                        completionPercentage = null,
                        width = 1280,
                        height = 720,
                        audioChannels = 2,
                        hardwareAccelerationType = null,
                        transcodeReasons =
                            listOf(
                                TranscodeReason.AUDIO_CODEC_NOT_SUPPORTED,
                                TranscodeReason.CONTAINER_BITRATE_EXCEEDS_LIMIT,
                            ),
                    ),
            )
        assertEquals(1080, QualityStatus.sourceHeight(playback))
        assertEquals(14_772_533, QualityStatus.sourceBitrate(playback))
        val now = QualityStatus.now(playback)
        assertEquals(QualityStatus.Method.TRANSCODING, now?.method)
        assertEquals("720P", now?.resolution)
        assertEquals("4 MBPS", now?.bitrateLabel)
        assertEquals(
            listOf(
                TranscodeReason.AUDIO_CODEC_NOT_SUPPORTED,
                TranscodeReason.CONTAINER_BITRATE_EXCEEDS_LIMIT,
            ),
            now?.reasons,
        )
        assertEquals(
            listOf(
                "ORIGINAL · 14.8 MBPS",
                "1080P · 10 MBPS",
                "1080P · 8 MBPS",
                "720P · 5 MBPS",
                "720P · 3 MBPS",
                "480P · 2 MBPS",
                "360P · 1 MBPS",
            ),
            QualityLadder.options(QualityStatus.sourceHeight(playback), QualityStatus.sourceBitrate(playback)).map { it.label },
        )
    }

    @Test
    fun `no playback yet is loading and direct play reads the file`() {
        assertNull(QualityStatus.now(null))
        val source = mediaSource(bitrate = 14_772_533, height = 1080, width = 1920)
        val playback =
            CurrentPlayback(
                item = BaseItem(data = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)),
                tracks = emptyList(),
                backend = PlayerBackend.EXO_PLAYER,
                playMethod = PlayMethod.DIRECT_PLAY,
                playSessionId = null,
                liveStreamId = null,
                mediaSourceInfo = source,
            )
        val now = QualityStatus.now(playback)
        assertEquals(QualityStatus.Method.DIRECT_PLAY, now?.method)
        assertEquals("1080P", now?.resolution)
        assertEquals("14.8 MBPS", now?.bitrateLabel)
        assertTrue(now?.reasons.orEmpty().isEmpty())
    }

    @Test
    fun `a live stream's reported bitrate is ignored so the ladder follows its height`() {
        // Captured from the dev server's PlaybackInfo for a JellyTV channel: IsInfiniteStream, Bitrate 192018,
        // one 720p h264 video stream.
        val live = mediaSource(bitrate = 192_018, height = 720, width = 1280, infinite = true)
        val playback =
            CurrentPlayback(
                item = BaseItem(data = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL)),
                tracks = emptyList(),
                backend = PlayerBackend.EXO_PLAYER,
                playMethod = PlayMethod.TRANSCODE,
                playSessionId = "session",
                liveStreamId = "live",
                mediaSourceInfo = live,
            )
        assertNull(QualityStatus.sourceBitrate(playback))
        assertEquals(720, QualityStatus.sourceHeight(playback))
        assertEquals(
            listOf("ORIGINAL", "720P · 5 MBPS", "720P · 3 MBPS", "480P · 2 MBPS", "360P · 1 MBPS"),
            QualityLadder.options(QualityStatus.sourceHeight(playback), QualityStatus.sourceBitrate(playback)).map { it.label },
        )
    }

    @Test
    fun `every transcode reason maps to a phrase and an unknown name becomes words`() {
        TranscodeReason.entries.forEach { reason ->
            assertTrue(reason.name, QualityStatus.reasonRes(reason) != null)
        }
        assertEquals(
            R.string.jtv_quality_reason_audio_codec,
            QualityStatus.reasonRes(TranscodeReason.AUDIO_CODEC_NOT_SUPPORTED),
        )
        assertEquals(
            R.string.jtv_quality_reason_quality_limit,
            QualityStatus.reasonRes(TranscodeReason.CONTAINER_BITRATE_EXCEEDS_LIMIT),
        )
        assertEquals(
            R.string.jtv_quality_reason_video_codec,
            QualityStatus.reasonRes(TranscodeReason.VIDEO_CODEC_NOT_SUPPORTED),
        )
        assertEquals(
            R.string.jtv_quality_reason_subtitles,
            QualityStatus.reasonRes(TranscodeReason.SUBTITLE_CODEC_NOT_SUPPORTED),
        )
        assertEquals("Something new", QualityStatus.enumWords("SOMETHING_NEW"))
    }

    @Test
    fun `every rung saved as the default lands exactly on its settings step`() {
        val preference = AppPreference.MaxBitrate
        assertEquals(preference.defaultValue, maxBitratePreferenceIndex(null))
        assertEquals("100 Mbps", preference.summarizer?.invoke(maxBitratePreferenceIndex(null)))
        val expected =
            mapOf(
                120 to 18L,
                80 to 15L,
                60 to 13L,
                40 to 11L,
                30 to 10L,
                20 to 9L,
                15 to 8L,
                10 to 7L,
                8 to 6L,
                5 to 5L,
                3 to 4L,
                2 to 3L,
                1 to 2L,
            )
        val rungs = QualityLadder.options(null, null).mapNotNull { it.megabits }
        assertEquals(expected.keys.toList(), rungs)
        expected.forEach { (megabits, index) ->
            assertEquals("$megabits Mbps", index, maxBitratePreferenceIndex(megabits))
            assertEquals("$megabits Mbps", preference.summarizer?.invoke(index))
        }
        // Safety net only: a value that is not a step lands on the nearest one, the lower on a tie.
        assertEquals("3 Mbps", preference.summarizer?.invoke(maxBitratePreferenceIndex(4)))
    }

    private fun mediaSource(
        bitrate: Int,
        height: Int,
        width: Int,
        infinite: Boolean = false,
    ): MediaSourceInfo =
        MediaSourceInfo(
            protocol = MediaProtocol.HTTP,
            type = MediaSourceType.DEFAULT,
            isRemote = false,
            readAtNativeFramerate = true,
            ignoreDts = true,
            ignoreIndex = true,
            genPtsInput = false,
            supportsTranscoding = true,
            supportsDirectStream = true,
            supportsDirectPlay = true,
            isInfiniteStream = infinite,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = true,
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            hasSegments = false,
            bitrate = bitrate,
            mediaStreams =
                listOf(
                    MediaStream(
                        type = MediaStreamType.VIDEO,
                        width = width,
                        height = height,
                        bitRate = bitrate,
                        index = 0,
                        isInterlaced = false,
                        isDefault = true,
                        isForced = false,
                        isHearingImpaired = false,
                        isExternal = false,
                        isTextSubtitleStream = false,
                        supportsExternalStream = false,
                    ),
                ),
        )

    @Test
    fun `every rung knows its height and other bitrates do not`() {
        val options = QualityLadder.options(2160, 200_000_000).drop(1)
        assertEquals(13, options.size)
        for (option in options) {
            val height = QualityLadder.heightFor(option.bitsPerSecond!!)
            val expected = if (option.label.startsWith("4K")) 2160 else option.label.substringBefore('P').toInt()
            assertEquals(option.label, expected, height)
        }
        assertNull(QualityLadder.heightFor(4_000_000))
    }
}
