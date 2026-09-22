package com.github.damontecres.wholphin.jellytv.quality

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodeReason
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Plain-language "what is playing right now" for the quality dialog. Resolution names match the ladder
 * (`1080P`, `4K`). Bitrates are decimal megabits, one decimal only when it is not a whole number
 * (`14.8 MBPS`, `4 MBPS`).
 */
object QualityStatus {
    enum class Method { DIRECT_PLAY, DIRECT_STREAM, TRANSCODING }

    data class Playing(
        val method: Method,
        val resolution: String?,
        val bitrateLabel: String?,
        val reasons: List<TranscodeReason>,
    )

    /** Null when the player has not published a source yet (the dialog says LOADING…). */
    fun now(playback: CurrentPlayback?): Playing? {
        if (playback == null) return null
        if (playback.playMethod == PlayMethod.TRANSCODE) {
            val info = playback.transcodeInfo
            return Playing(
                method = Method.TRANSCODING,
                resolution = resolutionLabel(info?.height, info?.width),
                bitrateLabel = bitrateLabel(info?.bitrate),
                reasons = info?.transcodeReasons.orEmpty().distinct(),
            )
        }
        val video = primaryVideo(playback)
        return Playing(
            method =
                if (playback.playMethod == PlayMethod.DIRECT_STREAM) {
                    Method.DIRECT_STREAM
                } else {
                    Method.DIRECT_PLAY
                },
            resolution = resolutionLabel(video?.height ?: playback.item.data.height, video?.width),
            bitrateLabel = bitrateLabel(sourceBitrate(playback)),
            reasons = emptyList(),
        )
    }

    /**
     * Height of the file, not the transcode. The item's own height wins when the open stream has already
     * been scaled down, so the ladder does not shrink after a quality change.
     */
    fun sourceHeight(playback: CurrentPlayback?): Int? {
        if (playback == null) return null
        val heights = mutableListOf<Int>()
        playback.item.data.height
            ?.takeIf { it > 0 }
            ?.let { heights.add(it) }
        videoStreams(
            playback.item.data.mediaSources
                .orEmpty()
                .flatMap { it.mediaStreams.orEmpty() },
        ).mapNotNull { it.height?.takeIf { height -> height > 0 } }
            .forEach { heights.add(it) }
        videoStreams(playback.mediaSourceInfo.mediaStreams.orEmpty())
            .mapNotNull { it.height?.takeIf { height -> height > 0 } }
            .forEach { heights.add(it) }
        return heights.maxOrNull()
    }

    /**
     * Container bitrate of the file. The item's media source is preferred over a transcoded source. Unknown
     * (null) for a live stream: the server's figure for one is not the stream's bitrate (a JellyTV channel
     * whose playlist tops out at 1080p 6.2 Mbps reports 192018 bits/s, its audio plus 18 for the video).
     */
    fun sourceBitrate(playback: CurrentPlayback?): Int? {
        if (playback == null) return null
        if (playback.mediaSourceInfo.isInfiniteStream) return null
        val fromItem =
            playback.item.data.mediaSources
                .orEmpty()
                .mapNotNull { it.bitrate }
                .filter { it > 0 }
                .maxOrNull()
        if (fromItem != null) return fromItem
        return playback.mediaSourceInfo.bitrate?.takeIf { it > 0 }
    }

    fun resolutionLabel(
        height: Int?,
        width: Int? = null,
    ): String? {
        val h = height?.takeIf { it > 0 }
        val w = width?.takeIf { it > 0 }
        if (h == null && w == null) return null
        if ((h ?: 0) > 1440 || (w ?: 0) > 2560) return "4K"
        val pixels = h ?: return null
        return when {
            pixels >= 900 -> "1080P"
            pixels >= 600 -> "720P"
            pixels >= 400 -> "480P"
            pixels >= 240 -> "360P"
            else -> "${pixels}P"
        }
    }

    fun bitrateLabel(bitsPerSecond: Int?): String? {
        if (bitsPerSecond == null || bitsPerSecond <= 0) return null
        val tenths = (bitsPerSecond / 1_000_000.0 * 10).roundToInt()
        val whole = tenths / 10
        val fraction = kotlin.math.abs(tenths % 10)
        val number = if (fraction == 0) whole.toString() else "$whole.$fraction"
        return "$number MBPS"
    }

    fun formatNowLine(
        methodLabel: String,
        resolution: String?,
        bitrateLabel: String?,
    ): String = listOfNotNull(methodLabel, resolution, bitrateLabel).joinToString(" · ")

    /** String resource for a known reason, or null when the enum grew and [enumWords] should be used. */
    fun reasonRes(reason: TranscodeReason): Int? = REASON_RES[reason]

    fun enumWords(name: String): String =
        name
            .lowercase(Locale.US)
            .replace('_', ' ')
            .replaceFirstChar { it.titlecase(Locale.US) }

    private fun primaryVideo(playback: CurrentPlayback): MediaStream? =
        videoStreams(playback.mediaSourceInfo.mediaStreams.orEmpty()).maxByOrNull { it.height ?: 0 }
            ?: videoStreams(
                playback.item.data.mediaSources
                    .orEmpty()
                    .flatMap { it.mediaStreams.orEmpty() },
            ).maxByOrNull { it.height ?: 0 }

    private fun videoStreams(streams: List<MediaStream>): List<MediaStream> = streams.filter { it.type == MediaStreamType.VIDEO }

    private val REASON_RES: Map<TranscodeReason, Int> =
        mapOf(
            TranscodeReason.CONTAINER_NOT_SUPPORTED to R.string.jtv_quality_reason_container,
            TranscodeReason.VIDEO_CODEC_NOT_SUPPORTED to R.string.jtv_quality_reason_video_codec,
            TranscodeReason.AUDIO_CODEC_NOT_SUPPORTED to R.string.jtv_quality_reason_audio_codec,
            TranscodeReason.SUBTITLE_CODEC_NOT_SUPPORTED to R.string.jtv_quality_reason_subtitles,
            TranscodeReason.AUDIO_IS_EXTERNAL to R.string.jtv_quality_reason_external_audio,
            TranscodeReason.SECONDARY_AUDIO_NOT_SUPPORTED to R.string.jtv_quality_reason_secondary_audio,
            TranscodeReason.VIDEO_PROFILE_NOT_SUPPORTED to R.string.jtv_quality_reason_video_profile,
            TranscodeReason.VIDEO_LEVEL_NOT_SUPPORTED to R.string.jtv_quality_reason_video_level,
            TranscodeReason.VIDEO_RESOLUTION_NOT_SUPPORTED to R.string.jtv_quality_reason_resolution,
            TranscodeReason.VIDEO_BIT_DEPTH_NOT_SUPPORTED to R.string.jtv_quality_reason_bit_depth,
            TranscodeReason.VIDEO_FRAMERATE_NOT_SUPPORTED to R.string.jtv_quality_reason_framerate,
            TranscodeReason.REF_FRAMES_NOT_SUPPORTED to R.string.jtv_quality_reason_ref_frames,
            TranscodeReason.ANAMORPHIC_VIDEO_NOT_SUPPORTED to R.string.jtv_quality_reason_anamorphic,
            TranscodeReason.INTERLACED_VIDEO_NOT_SUPPORTED to R.string.jtv_quality_reason_interlaced,
            TranscodeReason.AUDIO_CHANNELS_NOT_SUPPORTED to R.string.jtv_quality_reason_audio_channels,
            TranscodeReason.AUDIO_PROFILE_NOT_SUPPORTED to R.string.jtv_quality_reason_audio_profile,
            TranscodeReason.AUDIO_SAMPLE_RATE_NOT_SUPPORTED to R.string.jtv_quality_reason_sample_rate,
            TranscodeReason.AUDIO_BIT_DEPTH_NOT_SUPPORTED to R.string.jtv_quality_reason_audio_bit_depth,
            TranscodeReason.CONTAINER_BITRATE_EXCEEDS_LIMIT to R.string.jtv_quality_reason_quality_limit,
            TranscodeReason.VIDEO_BITRATE_NOT_SUPPORTED to R.string.jtv_quality_reason_video_bitrate,
            TranscodeReason.AUDIO_BITRATE_NOT_SUPPORTED to R.string.jtv_quality_reason_audio_bitrate,
            TranscodeReason.UNKNOWN_VIDEO_STREAM_INFO to R.string.jtv_quality_reason_unknown_video,
            TranscodeReason.UNKNOWN_AUDIO_STREAM_INFO to R.string.jtv_quality_reason_unknown_audio,
            TranscodeReason.DIRECT_PLAY_ERROR to R.string.jtv_quality_reason_direct_play,
            TranscodeReason.VIDEO_RANGE_TYPE_NOT_SUPPORTED to R.string.jtv_quality_reason_hdr,
            TranscodeReason.VIDEO_CODEC_TAG_NOT_SUPPORTED to R.string.jtv_quality_reason_codec_tag,
        )
}
