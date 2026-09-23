package io.github.scdouglas1999.tally.year

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private const val TICKS_PER_MINUTE = 600_000_000L
private const val TOP_LIST = 5

/**
 * Maps one Items API row. Episodes with no genres of their own take [seriesGenres] (keyed by series id).
 * The SDK stores [BaseItemDto.userData]'s last-played time as a [LocalDateTime] in the system zone;
 * [playedInstant] puts that back on the same zone to recover the original instant.
 */
fun toWatchedItem(
    dto: BaseItemDto,
    seriesGenres: Map<UUID, List<String>>,
): WatchedItem {
    val episode = dto.type == BaseItemKind.EPISODE
    val ownGenres = dto.genreNames()
    val genres =
        if (episode && ownGenres.isEmpty()) {
            dto.seriesId?.let { seriesGenres[it] }.orEmpty()
        } else {
            ownGenres
        }
    return WatchedItem(
        id = dto.id,
        name = dto.name.orEmpty(),
        isEpisode = episode,
        seriesId = dto.seriesId,
        seriesName = dto.seriesName,
        runtimeMinutes = runtimeMinutes(dto.runTimeTicks),
        playCount = maxOf(1, dto.userData?.playCount ?: 1),
        lastPlayed = dto.userData?.lastPlayedDate?.let(::playedInstant),
        genres = genres,
        productionYear = dto.productionYear,
    )
}

internal fun BaseItemDto.genreNames(): List<String> = genres.orEmpty().filter { it.isNotBlank() }

internal fun playedInstant(played: LocalDateTime): Instant = played.atZone(ZoneId.systemDefault()).toInstant()

private fun runtimeMinutes(ticks: Long?): Int {
    if (ticks == null || ticks <= 0L) return 0
    return (ticks / TICKS_PER_MINUTE).toInt()
}

private fun itemMinutes(item: WatchedItem): Long = item.runtimeMinutes.toLong() * item.playCount.toLong()

/**
 * Items whose last play falls in [year] in [zone]. Jellyfin only keeps the latest play, so a rewatch
 * counts entirely in that year (runtime × play count). See [YearStats].
 */
object YearStatsCalculator {
    fun compute(
        items: List<WatchedItem>,
        year: Int,
        zone: ZoneId,
    ): YearStats {
        val kept = items.filter { it.lastPlayed?.atZone(zone)?.year == year }
        val totalMinutes = kept.sumOf(::itemMinutes)
        val movies = kept.count { !it.isEpisode }
        val episodes = kept.count { it.isEpisode }
        val series = kept.mapNotNull { it.seriesId }.distinct().size
        val monthMinutes = MutableList(12) { 0L }
        for (item in kept) {
            val played = item.lastPlayed ?: continue
            val month = played.atZone(zone).monthValue
            monthMinutes[month - 1] += itemMinutes(item)
        }
        val busiest = monthMinutes.withIndex().maxByOrNull { it.value }
        val busiestMonth = if (busiest == null || busiest.value == 0L) null else busiest.index + 1
        val releaseYears = kept.mapNotNull { it.productionYear }.sorted()
        val median =
            if (releaseYears.isEmpty()) {
                null
            } else {
                releaseYears[(releaseYears.size - 1) / 2]
            }
        val first = kept.filter { it.lastPlayed != null }.minByOrNull { it.lastPlayed!! }
        val latest = kept.filter { it.lastPlayed != null }.maxByOrNull { it.lastPlayed!! }
        return YearStats(
            year = year,
            totalMinutes = totalMinutes,
            movies = movies,
            episodes = episodes,
            series = series,
            topMovies = topMovies(kept),
            topSeries = topSeries(kept),
            topGenres = topGenres(kept, totalMinutes),
            monthMinutes = monthMinutes,
            busiestMonth = busiestMonth,
            medianReleaseYear = median,
            firstWatch = first?.let(::watchRank),
            latestWatch = latest?.let(::watchRank),
        )
    }
}

private fun topMovies(kept: List<WatchedItem>): List<RankedItem> =
    kept
        .filter { !it.isEpisode }
        .sortedWith(
            compareByDescending<WatchedItem> { it.playCount }
                .thenByDescending(::itemMinutes)
                .thenBy { it.name },
        ).take(TOP_LIST)
        .map { item ->
            RankedItem(
                imageItemId = item.id,
                name = item.name,
                count = item.playCount,
                minutes = itemMinutes(item),
            )
        }

private fun topSeries(kept: List<WatchedItem>): List<RankedItem> =
    kept
        .filter { it.isEpisode && it.seriesId != null }
        .groupBy { it.seriesId!! }
        .map { (seriesId, episodes) ->
            RankedItem(
                imageItemId = seriesId,
                name = episodes.firstNotNullOfOrNull { it.seriesName?.takeIf(String::isNotBlank) }.orEmpty(),
                count = episodes.size,
                minutes = episodes.sumOf(::itemMinutes),
            )
        }.sortedWith(
            compareByDescending<RankedItem> { it.count }
                .thenByDescending { it.minutes }
                .thenBy { it.name },
        ).take(TOP_LIST)

private fun topGenres(
    kept: List<WatchedItem>,
    totalMinutes: Long,
): List<GenreShare> {
    val minutesByGenre = linkedMapOf<String, Long>()
    for (item in kept) {
        val minutes = itemMinutes(item)
        for (genre in item.genres.distinct()) {
            if (genre.isBlank() || minutes == 0L) continue
            minutesByGenre[genre] = (minutesByGenre[genre] ?: 0L) + minutes
        }
    }
    return minutesByGenre.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(TOP_LIST)
        .map { (genre, minutes) ->
            GenreShare(
                genre = genre,
                minutes = minutes,
                fraction = if (totalMinutes == 0L) 0f else minutes.toFloat() / totalMinutes.toFloat(),
            )
        }
}

private fun watchRank(item: WatchedItem): RankedItem {
    val series = item.seriesName?.takeIf { it.isNotBlank() }
    val name =
        if (item.isEpisode && series != null) {
            "$series \u2014 ${item.name}"
        } else {
            item.name
        }
    return RankedItem(
        imageItemId = if (item.isEpisode) item.seriesId ?: item.id else item.id,
        name = name,
        count = item.playCount,
        minutes = itemMinutes(item),
    )
}
