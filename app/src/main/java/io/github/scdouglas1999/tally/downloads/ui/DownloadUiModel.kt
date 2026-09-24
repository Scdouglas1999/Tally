package io.github.scdouglas1999.tally.downloads.ui

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import io.github.scdouglas1999.tally.downloads.DownloadEntry
import io.github.scdouglas1999.tally.downloads.DownloadState
import io.github.scdouglas1999.tally.downloads.DownloadTarget
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/*
 * What the downloads UI talks about: the thing a DOWNLOAD control downloads ([DownloadSubject]), how far along its
 * downloads are ([DownloadStatus]) and, for a show, how much of it ([ShowScope]). Plain data, no Android.
 *
 * Downloads are a phone feature: a TV draws none of this (see [downloadsEnabled]).
 */

/** Whether this device gets downloads at all: phones only (a TV has no DOWNLOAD, marks, page or settings). */
fun downloadsEnabled(context: Context): Boolean = TallyFormFactor.of(context) == TallyFormFactor.PHONE

/** The thing a DOWNLOAD control or menu entry downloads, with the name the sheet shows. */
sealed interface DownloadSubject {
    val title: String

    /** The downloads that belong to this subject. */
    fun matches(entry: DownloadEntry): Boolean

    /** A film, episode, video or track. */
    data class Item(
        val itemId: UUID,
        override val title: String,
    ) : DownloadSubject {
        override fun matches(entry: DownloadEntry) = entry.itemId == itemId

        val target: DownloadTarget get() = DownloadTarget.Items(listOf(itemId))
    }

    /** Every episode of one season. */
    data class Season(
        val seriesId: UUID,
        val seasonId: UUID,
        val seasonNumber: Int?,
        override val title: String,
    ) : DownloadSubject {
        override fun matches(entry: DownloadEntry) = entry.seasonId == seasonId

        val target: DownloadTarget get() = DownloadTarget.Season(seriesId, seasonId)
    }

    /** A show: the sheet asks how much of it ([ShowScope]); [seasonId] is the season the page shows. */
    data class Show(
        val seriesId: UUID,
        val seasonId: UUID?,
        val seasonNumber: Int?,
        override val title: String,
    ) : DownloadSubject {
        override fun matches(entry: DownloadEntry) = entry.seriesId == seriesId
    }

    data class Album(
        val albumId: UUID,
        override val title: String,
    ) : DownloadSubject {
        override fun matches(entry: DownloadEntry) = entry.albumId == albumId
    }

    /** Every album of an artist; downloads are matched by the artist's name (records keep names, not ids). */
    data class Artist(
        val artistId: UUID,
        override val title: String,
    ) : DownloadSubject {
        override fun matches(entry: DownloadEntry) =
            entry.type == BaseItemKind.AUDIO && (entry.albumArtist == title || title in entry.artists)
    }

    data class Playlist(
        val playlistId: UUID,
        override val title: String,
    ) : DownloadSubject {
        override fun matches(entry: DownloadEntry) = entry.playlistId == playlistId
    }

    /** True for a subject of several items (the sheet shows the total, removing says "downloads"). */
    val isGroup: Boolean get() = this !is Item

    companion object {
        private val ITEM_KINDS =
            setOf(
                BaseItemKind.MOVIE,
                BaseItemKind.EPISODE,
                BaseItemKind.VIDEO,
                BaseItemKind.MUSIC_VIDEO,
                BaseItemKind.AUDIO,
            )

        /** What downloading [item] means, or null for things that cannot be downloaded (people, live TV, folders). */
        fun of(item: BaseItem): DownloadSubject? {
            val title = item.name ?: item.title ?: ""
            return when (item.type) {
                in ITEM_KINDS -> {
                    Item(item.id, title)
                }

                BaseItemKind.SERIES -> {
                    Show(item.id, null, null, title)
                }

                BaseItemKind.SEASON -> {
                    item.data.seriesId?.let { Season(it, item.id, item.indexNumber, title) }
                }

                BaseItemKind.MUSIC_ALBUM -> {
                    Album(item.id, title)
                }

                BaseItemKind.MUSIC_ARTIST -> {
                    Artist(item.id, title)
                }

                BaseItemKind.PLAYLIST -> {
                    Playlist(item.id, title)
                }

                else -> {
                    null
                }
            }
        }
    }
}

/** How much of a show the sheet downloads. */
enum class ShowScope {
    NEXT_1,
    NEXT_3,
    NEXT_5,
    ALL_UNWATCHED,
    SEASON,
    ;

    fun target(show: DownloadSubject.Show): DownloadTarget? =
        when (this) {
            NEXT_1 -> DownloadTarget.NextUnwatched(show.seriesId, 1)
            NEXT_3 -> DownloadTarget.NextUnwatched(show.seriesId, 3)
            NEXT_5 -> DownloadTarget.NextUnwatched(show.seriesId, 5)
            ALL_UNWATCHED -> DownloadTarget.NextUnwatched(show.seriesId, Int.MAX_VALUE)
            SEASON -> show.seasonId?.let { DownloadTarget.Season(show.seriesId, it) }
        }
}

/** What to download for [subject] (a show at [scope]). */
fun targetOf(
    subject: DownloadSubject,
    scope: ShowScope,
): DownloadTarget? =
    when (subject) {
        is DownloadSubject.Item -> subject.target
        is DownloadSubject.Season -> subject.target
        is DownloadSubject.Show -> scope.target(subject)
        is DownloadSubject.Album -> DownloadTarget.Album(subject.albumId)
        is DownloadSubject.Artist -> DownloadTarget.ArtistAlbums(subject.artistId)
        is DownloadSubject.Playlist -> DownloadTarget.Playlist(subject.playlistId)
    }

/** How far along a subject's downloads are. */
sealed interface DownloadStatus {
    /** Nothing downloaded or downloading. */
    data object None : DownloadStatus

    /**
     * Something still to finish. [progress] 0..1 over all of the subject's downloads (finished ones count as whole);
     * [paused] when everything unfinished is paused; [failed] when one stopped on an error.
     */
    data class Active(
        val progress: Float,
        val paused: Boolean,
        val failed: Boolean,
        val entries: List<DownloadEntry>,
        val queued: Boolean = false,
    ) : DownloadStatus {
        val percent: Int get() = (progress * 100).toInt().coerceIn(0, 99)
    }

    /** Everything downloaded. */
    data class Done(
        val entries: List<DownloadEntry>,
    ) : DownloadStatus {
        val bytes: Long get() = entries.sumOf { it.sizeBytes ?: 0L }
    }
}

/** 0..1 for one download (finished = 1). */
fun DownloadEntry.fraction(): Float =
    when (val state = state) {
        DownloadState.Done -> 1f
        is DownloadState.Downloading -> state.progress
        is DownloadState.Paused -> state.progress
        is DownloadState.Queued, is DownloadState.Failed -> 0f
    }

val DownloadEntry.isDone: Boolean get() = state == DownloadState.Done

/** The status of [entries] (a subject's downloads). */
fun statusOf(entries: List<DownloadEntry>): DownloadStatus {
    if (entries.isEmpty()) return DownloadStatus.None
    val unfinished = entries.filter { !it.isDone }
    if (unfinished.isEmpty()) return DownloadStatus.Done(entries)
    // weighted by size where known, so a big film does not jump when a small extra finishes
    val weights = entries.map { (it.sizeBytes ?: 0L).coerceAtLeast(1L).toDouble() }
    val total = weights.sum()
    val done = entries.indices.sumOf { weights[it] * entries[it].fraction() }
    return DownloadStatus.Active(
        progress = if (total > 0) (done / total).toFloat().coerceIn(0f, 1f) else 0f,
        paused = unfinished.all { it.state is DownloadState.Paused },
        failed = unfinished.any { it.state is DownloadState.Failed },
        entries = entries,
        queued = unfinished.all { it.state is DownloadState.Queued },
    )
}

/** [subject]'s status among [all] downloads. */
fun statusOf(
    subject: DownloadSubject,
    all: List<DownloadEntry>,
): DownloadStatus = statusOf(all.filter(subject::matches))
