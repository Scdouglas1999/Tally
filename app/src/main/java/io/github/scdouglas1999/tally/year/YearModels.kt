package io.github.scdouglas1999.tally.year

import java.time.Instant
import java.util.UUID

/**
 * One played movie or episode, reduced to what the recap needs. Built from the Items API
 * (`isPlayed=true`, user data, RunTimeTicks, Genres, ProductionYear, SeriesId/SeriesName).
 * Episodes usually carry no genres of their own, so [genres] holds the series' genres for an episode.
 */
data class WatchedItem(
    val id: UUID,
    val name: String,
    val isEpisode: Boolean,
    val seriesId: UUID?,
    val seriesName: String?,
    val runtimeMinutes: Int,
    val playCount: Int,
    val lastPlayed: Instant?,
    val genres: List<String>,
    val productionYear: Int?,
)

/** A movie or a series in a top list. [imageItemId] is what to draw: the movie, or the series of an episode. */
data class RankedItem(
    val imageItemId: UUID,
    val name: String,
    val count: Int,
    val minutes: Long,
)

data class GenreShare(
    val genre: String,
    val minutes: Long,
    /** Of the year's total minutes, 0..1. */
    val fraction: Float,
)

/**
 * A year of watching, for "Your Year". Jellyfin keeps only the LAST time something was played plus a play count,
 * so an item belongs to the year of its last play and counts `runtime × playCount` minutes. The page says so.
 * Lists are sorted best-first and hold at most five entries. [monthMinutes] always has 12 entries (Jan..Dec).
 */
data class YearStats(
    val year: Int,
    val totalMinutes: Long,
    val movies: Int,
    val episodes: Int,
    val series: Int,
    val topMovies: List<RankedItem>,
    val topSeries: List<RankedItem>,
    val topGenres: List<GenreShare>,
    val monthMinutes: List<Long>,
    /** 1..12, null when nothing was watched. */
    val busiestMonth: Int?,
    val medianReleaseYear: Int?,
    val firstWatch: RankedItem?,
    val latestWatch: RankedItem?,
) {
    val isEmpty: Boolean get() = movies == 0 && episodes == 0
}
