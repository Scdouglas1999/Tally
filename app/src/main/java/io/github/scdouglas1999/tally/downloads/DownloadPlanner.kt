package io.github.scdouglas1999.tally.downloads

import java.util.UUID
import kotlin.math.ceil

/** Jellyfin ticks are 100 ns: 10,000 per millisecond. */
internal const val TICKS_PER_MS = 10_000L

/** Why an option is not allowed (the facade turns it into a sentence). */
internal enum class Disallowed {
    /** The user policy lacks EnableContentDownloading. */
    NO_DOWNLOAD_PERMISSION,

    /** The user policy lacks EnableVideoPlaybackTranscoding (converted downloads only). */
    NO_TRANSCODE_PERMISSION,

    /** The server says the item cannot be downloaded (CanDownload false). */
    NOT_DOWNLOADABLE,
}

/** The facts about one item that decide its options; everything from the item's first media source. */
internal data class PlanItem(
    val isVideo: Boolean,
    val sourceHeight: Int?,
    val sourceWidth: Int?,
    /** Total bitrate of the source, bits per second. */
    val sourceBitrate: Int?,
    val sizeBytes: Long?,
    val runtimeTicks: Long?,
    val canDownload: Boolean?,
)

/** What the user may do. */
internal data class DownloadPermissions(
    val download: Boolean,
    val transcode: Boolean,
)

internal data class PlannedOption(
    val quality: DownloadQuality,
    val estimatedBytes: Long?,
    val disallowed: Disallowed?,
)

/**
 * The pure rules of downloading: which qualities are offered, how big they are, which episodes are "the next N
 * unwatched", and how offline progress merges with the server's. No Android, no network.
 */
internal object DownloadPlanner {
    /**
     * The picture height a source counts as: its height, or the height a 16:9 frame of its width would have (a
     * 1920x800 scope film is "1080p").
     */
    fun effectiveHeight(
        height: Int?,
        width: Int?,
    ): Int? {
        val h = height?.takeIf { it > 0 }
        val fromWidth = width?.takeIf { it > 0 }?.let { ceil(it * 9.0 / 16.0).toInt() }
        return listOfNotNull(h, fromWidth).maxOrNull()
    }

    /**
     * A rung is offered for a source when it is not taller than the source and its video bitrate is strictly below
     * the source's total bitrate (same resolution at a lower bitrate is a real saving; a higher one is not). Unknown
     * height or bitrate never excludes a rung on that axis.
     */
    fun rungIsBelow(
        rung: DownloadRung,
        item: PlanItem,
    ): Boolean {
        if (!item.isVideo) return false
        val height = effectiveHeight(item.sourceHeight, item.sourceWidth)
        val bitrate = item.sourceBitrate?.takeIf { it > 0 }
        return (height == null || rung.height <= height) &&
            (bitrate == null || rung.videoBitsPerSecond < bitrate)
    }

    /** The quality an item is actually downloaded at when [requested] is chosen: a rung that is not below it falls back to Original. */
    fun qualityFor(
        item: PlanItem,
        requested: DownloadQuality,
    ): DownloadQuality =
        when (requested) {
            DownloadQuality.Original -> requested
            is DownloadQuality.Converted -> if (rungIsBelow(requested.rung, item)) requested else DownloadQuality.Original
        }

    /** The file size, else bitrate x runtime; null when neither is known. */
    fun originalBytes(item: PlanItem): Long? {
        item.sizeBytes?.takeIf { it > 0 }?.let { return it }
        val bitrate = item.sourceBitrate?.takeIf { it > 0 } ?: return null
        val runtime = item.runtimeTicks?.takeIf { it > 0 } ?: return null
        return bitsToBytes(bitrate.toLong(), runtime)
    }

    /** (rung video + stereo AAC) x runtime; null without a runtime. */
    fun convertedBytes(
        rung: DownloadRung,
        runtimeTicks: Long?,
    ): Long? {
        val runtime = runtimeTicks?.takeIf { it > 0 } ?: return null
        return bitsToBytes(rung.videoBitsPerSecond.toLong() + DownloadRung.AUDIO_BITS_PER_SECOND, runtime)
    }

    private fun bitsToBytes(
        bitsPerSecond: Long,
        runtimeTicks: Long,
    ): Long = (bitsPerSecond.toDouble() * (runtimeTicks.toDouble() / (TICKS_PER_MS * 1000.0)) / 8.0).toLong()

    /** Estimated bytes for [items] at [requested] (each item at the quality it really gets); null if any is unknown. */
    fun estimate(
        items: List<PlanItem>,
        requested: DownloadQuality,
    ): Long? {
        var total = 0L
        for (item in items) {
            val bytes =
                when (val quality = qualityFor(item, requested)) {
                    DownloadQuality.Original -> originalBytes(item)
                    is DownloadQuality.Converted -> convertedBytes(quality.rung, item.runtimeTicks)
                } ?: return null
            total += bytes
        }
        return total
    }

    /**
     * The options for a set of items (one item, a season, an album...): Original first, then every rung that is
     * below the source of at least one video item. Music gets Original only. Empty for no items.
     */
    fun options(
        items: List<PlanItem>,
        permissions: DownloadPermissions,
    ): List<PlannedOption> {
        if (items.isEmpty()) return emptyList()
        val originalBlocked =
            when {
                !permissions.download -> Disallowed.NO_DOWNLOAD_PERMISSION
                items.any { it.canDownload == false } -> Disallowed.NOT_DOWNLOADABLE
                else -> null
            }
        val original = PlannedOption(DownloadQuality.Original, estimate(items, DownloadQuality.Original), originalBlocked)
        val convertedBlocked = originalBlocked ?: if (!permissions.transcode) Disallowed.NO_TRANSCODE_PERMISSION else null
        val rungs =
            DownloadRung.entries
                .filter { rung -> items.any { rungIsBelow(rung, it) } }
                .map { rung ->
                    val quality = DownloadQuality.Converted(rung)
                    PlannedOption(quality, estimate(items, quality), convertedBlocked)
                }
        return listOf(original) + rungs
    }

    /** An episode as far as "next unwatched" cares. */
    data class EpisodeRef(
        val id: UUID,
        val season: Int?,
        val episode: Int?,
        val played: Boolean,
        /** False for virtual (missing) episodes, which have no file. */
        val playable: Boolean,
    )

    /**
     * The next [count] unwatched episodes: in season/episode order, specials (season 0) left out, starting after the
     * last watched episode (from the first when none is watched), skipping watched and missing ones.
     */
    fun nextUnwatched(
        episodes: List<EpisodeRef>,
        count: Int,
    ): List<UUID> {
        if (count <= 0) return emptyList()
        val ordered =
            episodes
                .filter { it.season != 0 }
                .sortedWith(compareBy<EpisodeRef>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
        val lastWatched = ordered.indexOfLast { it.played }
        return ordered
            .drop(lastWatched + 1)
            .filter { !it.played && it.playable }
            .take(count)
            .map { it.id }
    }

    /** Progress as recorded on this device. */
    data class LocalProgress(
        val positionMs: Long,
        val played: Boolean,
        val updatedAtMs: Long,
    )

    /** Progress as the server has it; [lastPlayedAtMs] is its LastPlayedDate (null when never played). */
    data class ServerProgress(
        val positionMs: Long,
        val played: Boolean,
        val lastPlayedAtMs: Long?,
    )

    /**
     * Jellyfin's resume rules applied to where playback stopped: under 5 % is "not started" (position 0), over 90 %
     * or the end is "played" (position 0). An item already played stays played.
     */
    fun progressAt(
        positionMs: Long,
        durationMs: Long?,
        ended: Boolean,
        wasPlayed: Boolean,
        nowMs: Long,
    ): LocalProgress {
        val duration = durationMs?.takeIf { it > 0 }
        val fraction = if (duration != null) positionMs.toDouble() / duration else 0.0
        return when {
            ended || fraction >= MAX_RESUME_FRACTION -> LocalProgress(0, true, nowMs)
            duration != null && fraction < MIN_RESUME_FRACTION -> LocalProgress(0, wasPlayed, nowMs)
            else -> LocalProgress(positionMs.coerceAtLeast(0), wasPlayed, nowMs)
        }
    }

    /**
     * Whether offline progress may be written to the server: only when it is newer than the server's last play (made
     * on another device in the meantime otherwise wins). A server that never saw the item played always takes it.
     */
    fun shouldPush(
        local: LocalProgress,
        server: ServerProgress,
    ): Boolean {
        val serverAt = server.lastPlayedAtMs ?: return true
        return local.updatedAtMs > serverAt
    }

    /** The newest of both, for resuming offline: local when it is newer than the server's last play, else the server's. */
    fun newest(
        local: LocalProgress?,
        server: ServerProgress?,
    ): Pair<Long, Boolean> {
        if (local == null) return (server?.positionMs ?: 0L) to (server?.played ?: false)
        if (server == null || shouldPush(local, server)) return local.positionMs to local.played
        return server.positionMs to server.played
    }

    const val MIN_RESUME_FRACTION = 0.05
    const val MAX_RESUME_FRACTION = 0.90
}
