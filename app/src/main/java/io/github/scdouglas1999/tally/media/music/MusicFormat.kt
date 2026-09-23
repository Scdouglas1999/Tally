package io.github.scdouglas1999.tally.media.music

import java.util.Locale

/** Pure formatting and layout rules of the Tally music screens (unit-tested in `MusicFormatTest`). */
object MusicFormat {
    private const val TICKS_PER_SECOND = 10_000_000L

    /** Track number in the rundown: `01`, `12`, `124`; empty when the track has none. */
    fun trackNumber(indexNumber: Int?): String = if (indexNumber == null || indexNumber < 0) "" else "%02d".format(Locale.US, indexNumber)

    /** A track's length as a clock: `0:45`, `4:05`, `1:02:03`; empty when unknown. */
    fun duration(ticks: Long?): String {
        if (ticks == null || ticks <= 0L) return ""
        val total = ticks / TICKS_PER_SECOND
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** One line of an album's rundown: a disc header, or the track at [index] of the album's list. */
    sealed interface TrackListEntry {
        data class Disc(
            val number: Int,
        ) : TrackListEntry

        data class Track(
            val index: Int,
        ) : TrackListEntry
    }

    /**
     * The rundown of an album whose tracks carry [discNumbers] (the tracks' `ParentIndexNumber`, in list order):
     * a `DISC n` header before each disc's first track, only when the album has more than one disc.
     */
    fun tracklist(discNumbers: List<Int?>): List<TrackListEntry> {
        val discs = discNumbers.filterNotNull().distinct()
        if (discs.size <= 1) return discNumbers.indices.map { TrackListEntry.Track(it) }
        val entries = mutableListOf<TrackListEntry>()
        var current: Int? = null
        discNumbers.forEachIndexed { index, disc ->
            if (disc != null && disc != current) {
                entries += TrackListEntry.Disc(disc)
                current = disc
            }
            entries += TrackListEntry.Track(index)
        }
        return entries
    }

    /**
     * The artist to show on a track row: the track's artists when they are not just the album's artist
     * ([albumArtist]); null when they are the same or unknown.
     */
    fun artistIfDifferent(
        trackArtists: List<String?>?,
        albumArtist: String?,
    ): String? {
        val names = trackArtists.orEmpty().filterNotNull().filter { it.isNotBlank() }
        if (names.isEmpty()) return null
        val joined = names.joinToString(", ")
        return if (albumArtist != null && joined.equals(albumArtist, ignoreCase = true)) null else joined
    }

    /** The repeat button's state tag. */
    enum class RepeatTag { NONE, ONE, ALL }

    /** Media3's repeat mode (`Player.REPEAT_MODE_*`: 0 off, 1 one, 2 all) as the button's tag. */
    fun repeatTag(repeatMode: Int): RepeatTag =
        when (repeatMode) {
            1 -> RepeatTag.ONE
            2 -> RepeatTag.ALL
            else -> RepeatTag.NONE
        }

    /** Lyrics are synced when at least one line has a start time. */
    fun isSynced(starts: List<Long?>): Boolean = starts.any { it != null }

    /** Where the current lyric line rests: this fraction of the lyrics column's height from its top. */
    const val LYRIC_ANCHOR = 0.4f

    /**
     * The vertical offset for the lyrics column so the line at [lineTop] (its height [lineHeight], both in the
     * column's own coordinates) is centered on [LYRIC_ANCHOR] of the [viewportHeight]. Positive moves the column
     * down (the first lines sit below the top), negative scrolls it up.
     */
    fun lyricOffset(
        viewportHeight: Float,
        lineTop: Float,
        lineHeight: Float,
    ): Float = viewportHeight * LYRIC_ANCHOR - (lineTop + lineHeight / 2f)
}
