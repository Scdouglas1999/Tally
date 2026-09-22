package com.github.damontecres.wholphin.jellytv.media.series

import com.github.damontecres.wholphin.jellytv.media.kit.resumePercent
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Years a series ran: `2008–2013` once it has ended, `2022–` while it continues, `2019` for a
 * single year (or when only the first year is known).
 */
fun seriesYears(
    productionYear: Int?,
    endDate: LocalDateTime?,
    status: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val start = productionYear ?: return null
    if (status.equals(STATUS_CONTINUING, ignoreCase = true)) return "$start–"
    val end = endDate?.let { serverDate(it, zone).year }
    return if (end != null && end > start) "$start–$end" else start.toString()
}

private const val STATUS_CONTINUING = "Continuing"

/**
 * `S1 E3`, `S1 E3–4` for a double episode, [special] for season 0.
 * Missing numbers are left out: `S2`, `E5`; null when neither is known.
 */
fun episodeCode(
    season: Int?,
    episode: Int?,
    indexNumberEnd: Int?,
    special: String = "SPECIAL",
): String? {
    if (season == 0) return special
    val episodePart =
        episode?.let {
            if (indexNumberEnd != null && indexNumberEnd > it) "E$it–$indexNumberEnd" else "E$it"
        }
    return listOfNotNull(season?.let { "S$it" }, episodePart)
        .joinToString(" ")
        .ifEmpty { null }
}

/** `E03` for the rundown's number column (a double episode shows its first number). */
fun episodeNumber(episode: Int?): String? = episode?.let { "E%02d".format(Locale.US, it) }

/**
 * Air date as `JAN 20, 2008`. The SDK hands dates over converted to [zone]; the server stores air
 * dates as midnight UTC, so convert back before taking the day.
 */
fun airDate(
    date: LocalDateTime?,
    zone: ZoneId = ZoneId.systemDefault(),
): String? = date?.let { AIR_DATE.format(serverDate(it, zone)).uppercase(Locale.US) }

/** The calendar date the server meant by [date] (a UTC timestamp the SDK moved into [zone]). */
fun serverDate(
    date: LocalDateTime,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate = date.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate()

private val AIR_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/**
 * Counts for a season's episodes: how many, how many are not yet played, and the runtime still
 * to watch (unplayed episodes, less what has been watched of any in progress).
 */
data class SeasonSummary(
    val episodes: Int,
    val left: Int,
    val remainingTicks: Long,
)

fun seasonSummary(episodes: List<BaseItemDto>): SeasonSummary {
    var left = 0
    var remaining = 0L
    episodes.forEach { episode ->
        if (episode.userData?.played != true) {
            left++
            val runtime = episode.runTimeTicks ?: 0L
            val position = episode.userData?.playbackPositionTicks ?: 0L
            remaining += (runtime - position).coerceAtLeast(0L)
        }
    }
    return SeasonSummary(episodes = episodes.size, left = left, remainingTicks = remaining)
}

/** What the series page's primary button says. */
sealed interface NextUpLabel {
    /** Nothing to continue: plays the first episode. */
    data object Play : NextUpLabel

    /** `NEXT UP · S2 E3` */
    data class NextUp(
        val code: String,
    ) : NextUpLabel

    /** `RESUME S2 E3 · 42%` */
    data class Resume(
        val code: String,
        val percent: Int,
    ) : NextUpLabel
}

/**
 * The primary button for a series whose next-up episode is [nextUp] (the server's Next Up for
 * the series, or null when it has none).
 */
fun nextUpLabel(
    nextUp: BaseItemDto?,
    special: String = "SPECIAL",
): NextUpLabel {
    if (nextUp == null) return NextUpLabel.Play
    val code =
        episodeCode(nextUp.parentIndexNumber, nextUp.indexNumber, nextUp.indexNumberEnd, special)
            ?: return NextUpLabel.Play
    val position = nextUp.userData?.playbackPositionTicks ?: 0L
    if (nextUp.userData?.played != true && position > 0L) {
        val percent =
            resumePercent(position, nextUp.runTimeTicks ?: 0L).coerceAtLeast(1)
        return NextUpLabel.Resume(code, percent)
    }
    return NextUpLabel.NextUp(code)
}

/**
 * Share of a season watched, 0–1: the server's `PlayedPercentage` when it sent one, else worked
 * out from `ChildCount` (episodes) and `UnplayedItemCount`; null when neither is known.
 */
fun seasonWatchedFraction(season: BaseItemDto): Float? {
    season.userData?.playedPercentage?.let { percent ->
        return (percent / 100.0).toFloat().coerceIn(0f, 1f)
    }
    val episodes = season.childCount?.takeIf { it > 0 } ?: return null
    val unplayed = season.userData?.unplayedItemCount ?: return null
    return ((episodes - unplayed).toFloat() / episodes).coerceIn(0f, 1f)
}
