package io.github.scdouglas1999.tally.media.pages

import io.github.scdouglas1999.tally.media.kit.formatRuntime
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PersonKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure formatting for the search, person, collection, favorites and playlist pages.

/** Parts joined with ` · `, blanks dropped. */
fun joinMeta(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/** `1995–2010`; one year alone when first and last are the same; null when there is none. */
fun yearRange(years: Collection<Int?>): String? {
    val known = years.filterNotNull().filter { it > 0 }
    if (known.isEmpty()) return null
    val first = known.min()
    val last = known.max()
    return if (first == last) "$first" else "$first–$last"
}

/** Sum of the positive run times, in ticks. */
fun totalRuntimeTicks(ticks: Collection<Long?>): Long = ticks.filterNotNull().filter { it > 0L }.sum()

/** Rundown number for a zero-based [index]: `01`, `12`, `124`. */
fun rundownNumber(index: Int): String = "%02d".format(Locale.US, index + 1)

/** Month-first date: `Jul 30, 1970`. */
fun monthFirstDate(date: LocalDate): String = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(date)

/**
 * A person's life line: `Born Jul 30, 1970 · Westminster, London` while living, `1928–2016 · Chicago` after.
 * [bornLabel] is the localized word for "Born". Null when nothing is known.
 */
fun personLifeLine(
    born: LocalDate?,
    died: LocalDate?,
    place: String?,
    bornLabel: String,
): String? {
    val dates =
        when {
            died != null && born != null -> yearRange(listOf(born.year, died.year))
            died != null -> "–${died.year}"
            born != null -> "$bornLabel ${monthFirstDate(born)}"
            else -> null
        }
    return joinMeta(dates, place?.trim()).ifBlank { null }
}

/** The order credits are ranked in when a person holds several equally often. */
private val ROLE_ORDER =
    listOf(
        PersonKind.ACTOR,
        PersonKind.DIRECTOR,
        PersonKind.WRITER,
        PersonKind.PRODUCER,
        PersonKind.COMPOSER,
        PersonKind.GUEST_STAR,
    )

/**
 * The credit a person holds most often across [credits] (one entry per credit on each item); ties go
 * to [ROLE_ORDER]. Null when there are no credits or only unknown ones.
 */
fun primaryRole(credits: List<PersonKind>): PersonKind? {
    val counted = credits.filter { it != PersonKind.UNKNOWN }.groupingBy { it }.eachCount()
    if (counted.isEmpty()) return null
    val rank = { kind: PersonKind -> ROLE_ORDER.indexOf(kind).let { if (it < 0) ROLE_ORDER.size else it } }
    return counted.entries
        .sortedWith(compareByDescending<Map.Entry<PersonKind, Int>> { it.value }.thenBy { rank(it.key) })
        .first()
        .key
}

/** What a collection's count is of: films when all are films, shows when all are series, else items. */
enum class CountNoun { FILMS, SHOWS, EPISODES, ITEMS }

fun countNoun(types: Collection<BaseItemKind>): CountNoun {
    val distinct = types.toSet()
    return when {
        distinct.isEmpty() -> CountNoun.ITEMS
        distinct == setOf(BaseItemKind.MOVIE) -> CountNoun.FILMS
        distinct == setOf(BaseItemKind.SERIES) -> CountNoun.SHOWS
        distinct == setOf(BaseItemKind.EPISODE) -> CountNoun.EPISODES
        else -> CountNoun.ITEMS
    }
}

/**
 * The meta line of a rundown row: `SERIES · S1 E3 · 47m` for an episode, else `FILM · 2010 · 2h 28m`
 * ([typeLabel] is the localized kind). Blank parts are dropped.
 */
fun rundownMeta(
    type: BaseItemKind,
    typeLabel: String?,
    year: Int?,
    seriesName: String?,
    episodeCode: String?,
    runtimeTicks: Long?,
): String {
    val runtime = runtimeTicks?.takeIf { it > 0L }?.let(::formatRuntime)
    return if (type == BaseItemKind.EPISODE) {
        joinMeta(seriesName, episodeCode, runtime)
    } else {
        joinMeta(typeLabel, year?.toString(), runtime)
    }
}
