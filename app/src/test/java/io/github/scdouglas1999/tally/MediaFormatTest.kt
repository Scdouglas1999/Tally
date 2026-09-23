package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.media.kit.formatEndsAt
import io.github.scdouglas1999.tally.media.kit.formatPosition
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.kit.techBoxes
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Formatting for the film page, checked against a captured
 * `GET /Items/{id}/PlaybackInfo` for Inception on the dev server.
 */
class MediaFormatTest {
    private val playback: PlaybackInfoResponse by lazy {
        ApiSerializer.json.decodeFromString<PlaybackInfoResponse>(PLAYBACK_JSON)
    }

    @Test
    fun techBoxesFromCapturedPlaybackInfo() {
        val source = playback.mediaSources!!.first()
        assertEquals(900_230_000L, source.runTimeTicks)
        assertEquals(
            listOf("360p", "H264", "EN · AAC STEREO", "CC · EN ES"),
            techBoxes(source),
        )
        assertEquals("1m 30s", formatRuntime(source.runTimeTicks!!))
    }

    @Test
    fun runtimeAndResume() {
        assertEquals("2h 28m", formatRuntime(88_800_000_000L))
        assertEquals("2h", formatRuntime(72_000_000_000L))
        assertEquals("52m", formatRuntime(31_200_000_000L))
        assertEquals("45s", formatRuntime(450_000_000L))
        assertEquals("0s", formatRuntime(0L))
        assertEquals("0s", formatRuntime(-1L))
        assertEquals(40, resumePercent(360_092_000L, 900_230_000L))
        assertEquals(65, resumePercent(585_149_500L, 900_230_000L))
        assertEquals(0, resumePercent(0L, 900_230_000L))
        assertEquals(0, resumePercent(100L, 0L))
    }

    @Test
    fun endsAt() {
        val zone = ZoneId.of("America/New_York")
        val evening = Instant.parse("2026-09-22T21:00:00Z")
        assertEquals("ENDS 9:41 PM", formatEndsAt(evening, 16_860_000L, zone, is24h = false))
        assertEquals("ENDS 21:41", formatEndsAt(evening, 16_860_000L, zone, is24h = true))
        val morning = Instant.parse("2026-09-22T13:00:00Z")
        assertEquals("ENDS 9:05 AM", formatEndsAt(morning, 5L * 60_000L, zone, is24h = false))
    }

    @Test
    fun chapterClock() {
        assertEquals("30:00", formatPosition(18_000_000_000L))
        assertEquals("01:00", formatPosition(600_000_000L))
        assertEquals("00:00", formatPosition(0L))
        assertEquals("1:02:03", formatPosition((1 * 3600 + 2 * 60 + 3) * 10_000_000L))
    }

    @Test
    fun hdrAndExtraSubtitleLanguages() {
        val source = playback.mediaSources!!.first()
        val streams = source.mediaStreams!!.toMutableList()
        val videoIndex = streams.indexOfFirst { it.type == MediaStreamType.VIDEO }
        streams[videoIndex] =
            streams[videoIndex].copy(
                videoRange = VideoRange.HDR,
                videoRangeType = VideoRangeType.HDR10,
            )
        val english =
            streams.first { it.type == MediaStreamType.SUBTITLE && it.language == "eng" }
        listOf("fra", "deu", "ita", "por").forEachIndexed { offset, language ->
            streams +=
                english.copy(
                    language = language,
                    index = 10 + offset,
                    isExternal = false,
                )
        }
        val boxes = techBoxes(source.copy(mediaStreams = streams))
        assertEquals("HDR10", boxes[2])
        assertEquals("CC · EN FR DE +3", boxes.last())
    }

    @Test
    fun `labels are uppercase but units keep their standard case`() {
        assertEquals(
            "TRANSCODING · 1080p · 14.8 Mbps",
            "Transcoding · 1080p · 14.8 Mbps".tallyUppercase(),
        )
        assertEquals("2021 · PG-13 · 2h 35m · ★ 7.8", "2021 · PG-13 · 2h 35m · ★ 7.8".tallyUppercase())
        assertEquals("7 EPISODES · 5 LEFT · 4m 34s", "7 episodes · 5 left · 4m 34s".tallyUppercase())
        assertEquals("S1 E3 · 42%", "S1 E3 · 42%".tallyUppercase())
        assertEquals("BOT 1ST · 4K · 1080i · 500 kbps", "Bot 1st · 4k · 1080i · 500 kbps".tallyUppercase())
    }
}

private const val PLAYBACK_JSON = """
{"MediaSources":[{"Protocol":"File","Id":"1a27aba7d8639d762d762bf9f64218fa","Path":"/media/movies/Inception (2010)/Inception (2010).mkv","Type":"Default","Container":"mkv","Size":4179979,"Name":"Inception (2010)","IsRemote":false,"ETag":"2846513c66333d8c344cf01937502267","RunTimeTicks":900230000,"ReadAtNativeFramerate":false,"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,"SupportsTranscoding":true,"SupportsDirectStream":true,"SupportsDirectPlay":true,"IsInfiniteStream":false,"UseMostCompatibleTranscodingProfile":false,"RequiresOpening":false,"RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":true,"VideoType":"VideoFile","MediaStreams":[{"Codec":"subrip","Language":"spa","TimeBase":"1/1000","VideoRange":"Unknown","VideoRangeType":"Unknown","AudioSpatialFormat":"None","LocalizedUndefined":"Undefined","LocalizedDefault":"Default","LocalizedForced":"Forced","LocalizedExternal":"External","LocalizedHearingImpaired":"Hearing Impaired","DisplayTitle":"Spanish - SUBRIP - External","IsInterlaced":false,"IsAVC":false,"IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"Height":0,"Width":0,"Type":"Subtitle","Index":0,"IsExternal":true,"IsTextSubtitleStream":true,"SupportsExternalStream":true,"Path":"/media/movies/Inception (2010)/Inception (2010).es.srt","Level":0},{"Codec":"h264","TimeBase":"1/1000","VideoRange":"SDR","VideoRangeType":"SDR","AudioSpatialFormat":"None","DisplayTitle":"360p H264 SDR","NalLengthSize":"4","IsInterlaced":false,"IsAVC":true,"BitRate":371458,"BitDepth":8,"RefFrames":1,"IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"Height":360,"Width":640,"AverageFrameRate":24,"RealFrameRate":24,"ReferenceFrameRate":24,"Profile":"High","Type":"Video","AspectRatio":"16:9","Index":1,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false,"PixelFormat":"yuv420p","Level":30,"IsAnamorphic":false},{"Codec":"aac","Language":"eng","TimeBase":"1/1000","Title":"English Stereo","VideoRange":"Unknown","VideoRangeType":"Unknown","AudioSpatialFormat":"None","LocalizedDefault":"Default","LocalizedExternal":"External","DisplayTitle":"English Stereo - AAC - Default","IsInterlaced":false,"IsAVC":false,"ChannelLayout":"stereo","BitRate":192000,"Channels":2,"SampleRate":44100,"IsDefault":true,"IsForced":false,"IsHearingImpaired":false,"Profile":"LC","Type":"Audio","Index":2,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false,"Level":0},{"Codec":"aac","Language":"spa","TimeBase":"1/1000","Title":"Espa\u00F1ol Stereo","VideoRange":"Unknown","VideoRangeType":"Unknown","AudioSpatialFormat":"None","LocalizedDefault":"Default","LocalizedExternal":"External","DisplayTitle":"Espa\u00F1ol Stereo - Spanish - AAC","IsInterlaced":false,"IsAVC":false,"ChannelLayout":"stereo","BitRate":192000,"Channels":2,"SampleRate":44100,"IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"Profile":"LC","Type":"Audio","Index":3,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false,"Level":0},{"Codec":"subrip","Language":"eng","TimeBase":"1/1000","Title":"English","VideoRange":"Unknown","VideoRangeType":"Unknown","AudioSpatialFormat":"None","LocalizedUndefined":"Undefined","LocalizedDefault":"Default","LocalizedForced":"Forced","LocalizedExternal":"External","LocalizedHearingImpaired":"Hearing Impaired","DisplayTitle":"English - SUBRIP","IsInterlaced":false,"IsAVC":false,"IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"Height":0,"Width":0,"Type":"Subtitle","Index":4,"IsExternal":false,"IsTextSubtitleStream":true,"SupportsExternalStream":true,"Level":0}],"MediaAttachments":[],"Formats":[],"Bitrate":755458,"RequiredHttpHeaders":{},"TranscodingSubProtocol":"http","DefaultAudioStreamIndex":2,"DefaultSubtitleStreamIndex":0,"HasSegments":false}],"PlaySessionId":"ce10b02ec74b4c469afcd48d96174816"
}
"""
