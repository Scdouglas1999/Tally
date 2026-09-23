package io.github.scdouglas1999.tally.surprise

import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.model.api.BaseItemKind

/** What "Surprise me" picks from. */
enum class SurpriseKind { MOVIES, SHOWS }

/**
 * The filter bar. [genre] is a server genre name (from the Genres API), null for any genre.
 * [underTwoHours] only applies to movies. [kidFriendly] caps the official rating at PG (the server compares
 * rating scores, so this also admits G, TV-Y, TV-Y7, TV-G and TV-PG). [unwatchedOnly] is on by default.
 */
data class SurpriseFilters(
    val kind: SurpriseKind = SurpriseKind.MOVIES,
    val genre: String? = null,
    val underTwoHours: Boolean = false,
    val kidFriendly: Boolean = false,
    val unwatchedOnly: Boolean = true,
)

/** The server half of a filter set: exactly the Items API parameters to send. */
data class SurpriseQuery(
    val includeItemTypes: List<BaseItemKind>,
    val genres: List<String>,
    val isPlayed: Boolean?,
    val maxOfficialRating: String?,
    val limit: Int,
)

/** How many random candidates one shuffle asks for: the pick plus the reel shown while it spins. */
const val SURPRISE_BATCH = 24

/** Longest runtime [SurpriseFilters.underTwoHours] admits. */
const val SURPRISE_MAX_TICKS = 2L * 60 * 60 * 10_000_000

/** The rating cap for [SurpriseFilters.kidFriendly]. */
const val SURPRISE_KID_RATING = "PG"

fun SurpriseFilters.toQuery(limit: Int = SURPRISE_BATCH): SurpriseQuery =
    SurpriseQuery(
        includeItemTypes = listOf(if (kind == SurpriseKind.MOVIES) BaseItemKind.MOVIE else BaseItemKind.SERIES),
        genres = listOfNotNull(genre),
        // A show counts as watched only when every episode is; "unwatched" shows still have something to play.
        isPlayed = if (unwatchedOnly) false else null,
        maxOfficialRating = if (kidFriendly) SURPRISE_KID_RATING else null,
        limit = limit,
    )

/** The client half: what the Items API cannot express. Unknown runtimes pass. */
fun SurpriseFilters.accepts(runTimeTicks: Long?): Boolean =
    !(kind == SurpriseKind.MOVIES && underTwoHours && runTimeTicks != null && runTimeTicks > SURPRISE_MAX_TICKS)

/**
 * Everything the page draws. [reel] is the posters the shuffle spins through; its last entry is [pick].
 * [shuffleId] increases on every shuffle so the page can restart the reel animation even when the pick repeats.
 * [matches] is the server's total for the current filters (null until known).
 */
data class SurpriseState(
    val filters: SurpriseFilters = SurpriseFilters(),
    val genres: List<String> = emptyList(),
    val pick: BaseItem? = null,
    val reel: List<BaseItem> = emptyList(),
    val matches: Int? = null,
    val shuffleId: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)
