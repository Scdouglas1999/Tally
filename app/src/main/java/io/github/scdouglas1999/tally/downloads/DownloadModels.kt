package io.github.scdouglas1999.tally.downloads

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * A rung of Tally's download ladder: the server transcodes to H.264/AAC no larger than [width] x [height] at no more
 * than [videoBitsPerSecond] (plus [AUDIO_BITS_PER_SECOND] of stereo AAC). [label] is what the UI shows.
 */
@Serializable
enum class DownloadRung(
    val height: Int,
    val width: Int,
    val videoBitsPerSecond: Int,
    val label: String,
) {
    P1080(1080, 1920, 8_000_000, "1080p · 8 Mbps"),
    P720(720, 1280, 4_000_000, "720p · 4 Mbps"),
    P480(480, 854, 1_500_000, "480p · 1.5 Mbps"),
    P360(360, 640, 800_000, "360p · 0.8 Mbps"),
    ;

    companion object {
        /** Stereo AAC that goes with every converted rung. */
        const val AUDIO_BITS_PER_SECOND = 128_000
    }
}

/** What gets downloaded: the file as it is on the server, or a conversion at a [DownloadRung]. */
@Serializable
sealed interface DownloadQuality {
    /** The file as it is on the server (`/Items/{id}/Download`): no server work, all audio and subtitle tracks. */
    @Serializable
    data object Original : DownloadQuality

    /** Converted by the server to H.264/AAC at [rung], downloaded as HLS, text subtitles as WebVTT files. */
    @Serializable
    data class Converted(
        val rung: DownloadRung,
    ) : DownloadQuality

    /** "Original" or the rung's label ("720p · 4 Mbps"). */
    val label: String
        get() =
            when (this) {
                Original -> "Original"
                is Converted -> rung.label
            }
}

/**
 * One choice for the download sheet. [estimatedBytes] is the file size (Original) or bitrate x runtime (converted),
 * summed over every item of a group; null when the server does not know it. When [allowed] is false, [reason] says
 * why in a sentence for the user.
 */
data class DownloadOption(
    val quality: DownloadQuality,
    val label: String,
    val estimatedBytes: Long?,
    val allowed: Boolean,
    val reason: String?,
)

/**
 * What to download. A group is expanded on the server when it is enqueued; every item gets the same quality (a rung
 * that is not below an item's own source falls back to Original for that item).
 */
sealed interface DownloadTarget {
    /** Films, episodes, music videos or tracks by id. */
    data class Items(
        val itemIds: List<UUID>,
    ) : DownloadTarget

    /** Every episode of one season. */
    data class Season(
        val seriesId: UUID,
        val seasonId: UUID,
    ) : DownloadTarget

    /** Every episode of a show (specials included). */
    data class Series(
        val seriesId: UUID,
    ) : DownloadTarget

    /** The next [count] unwatched episodes after the last watched one (specials left out). */
    data class NextUnwatched(
        val seriesId: UUID,
        val count: Int,
    ) : DownloadTarget

    /** Every track of an album. */
    data class Album(
        val albumId: UUID,
    ) : DownloadTarget

    /** Every track of every album of an artist. */
    data class ArtistAlbums(
        val artistId: UUID,
    ) : DownloadTarget

    /** Every playable item of a playlist (music or video), in playlist order. */
    data class Playlist(
        val playlistId: UUID,
    ) : DownloadTarget
}

/** The state of one download. */
sealed interface DownloadState {
    /** Waiting its turn; [waitingFor] says what it waits for ("Waiting for Wi-Fi"), null when just queued. */
    data class Queued(
        val waitingFor: String? = null,
    ) : DownloadState

    /** [progress] 0..1 (estimated for converted downloads until the server has sent everything), [bytes] so far. */
    data class Downloading(
        val progress: Float,
        val bytes: Long,
    ) : DownloadState

    /** Paused by the user, with how far it got. */
    data class Paused(
        val progress: Float,
        val bytes: Long,
    ) : DownloadState

    /** Stopped on an error; [reason] is for the user. [retrying] is true when Tally will try again by itself. */
    data class Failed(
        val reason: String,
        val retrying: Boolean,
    ) : DownloadState

    /** Complete: plays without the server. */
    data object Done : DownloadState
}

/**
 * A download with everything the downloads page and offline playback need; nothing here needs the server. Artwork
 * paths are local files (null when the item has no such image). [positionMs] and [played] are the newest known
 * progress (local playback or the server's).
 */
data class DownloadEntry(
    val itemId: UUID,
    val serverId: UUID,
    val userId: UUID,
    val type: BaseItemKind,
    val title: String,
    val seriesId: UUID?,
    val seriesName: String?,
    val seasonId: UUID?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val albumId: UUID?,
    val album: String?,
    val albumArtist: String?,
    val artists: List<String>,
    val playlistId: UUID?,
    val playlistName: String?,
    val playlistIndex: Int?,
    val runtimeMs: Long?,
    val overview: String?,
    val productionYear: Int?,
    val quality: DownloadQuality,
    val qualityLabel: String,
    /** Bytes on the device when done, else the estimate. */
    val sizeBytes: Long?,
    val state: DownloadState,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val thumbPath: String?,
    val positionMs: Long,
    val played: Boolean,
    val createdAtMs: Long,
    val completedAtMs: Long?,
)

/** Where new downloads go. */
@Serializable
enum class StorageLocation {
    /** App-specific storage on the device. */
    INTERNAL,

    /** App-specific storage on a removable card (only offered when one is present). */
    REMOVABLE,
}

/** [usedBytes] by Tally's downloads (all locations), [freeBytes] on the current [location]. */
data class StorageInfo(
    val usedBytes: Long,
    val freeBytes: Long,
    val location: StorageLocation,
    val removableAvailable: Boolean,
)

/**
 * Download settings (Tally's own preferences). [defaultQuality] null means ask each time. [wifiOnly] defaults to on on
 * phones and off on TVs. [concurrentDownloads] 1..3. [autoDeleteWatchedAfterHours] null is off: a downloaded episode
 * is deleted that many hours after it was watched to the end on this device.
 */
data class DownloadSettings(
    val defaultQuality: DownloadQuality?,
    val wifiOnly: Boolean,
    val concurrentDownloads: Int,
    val autoDeleteWatchedAfterHours: Int?,
    val location: StorageLocation,
)

/** The outcome of [TallyDownloads.enqueue]. */
data class EnqueueResult(
    /** Items newly queued (or re-queued after a failure). */
    val queued: List<UUID>,
    /** Items already downloaded or downloading; left alone. */
    val alreadyPresent: List<UUID>,
    /** Nothing was queued because of this (no permission, server unreachable, not enough space...), or null. */
    val error: String?,
)
