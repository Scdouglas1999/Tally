package com.github.damontecres.wholphin.jellytv.surprise

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One random batch from the Items API, plus the genre names the filter chip cycles through.
 * Genre names are cached per [SurpriseKind] for the process; the page only asks once per kind.
 */
@Singleton
class SurpriseRepository
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val genresMutex = Mutex()
        private val genresByKind = mutableMapOf<SurpriseKind, List<String>>()

        /**
         * Survivors in server order, and the match count. Runtime rejects stay in the server total.
         * Items dropped for the kid rating are subtracted: series queries ignore MaxOfficialRating when IsPlayed is set.
         */
        suspend fun shuffle(filters: SurpriseFilters): Pair<List<BaseItem>, Int> =
            withContext(ioDispatcher) {
                val userId = serverRepository.currentUser?.id ?: error("Not signed in")
                val query = filters.toQuery()
                val result =
                    api.itemsApi
                        .getItems(
                            GetItemsRequest(
                                userId = userId,
                                recursive = true,
                                sortBy = listOf(ItemSortBy.RANDOM),
                                includeItemTypes = query.includeItemTypes,
                                genres = query.genres.takeIf { it.isNotEmpty() },
                                isPlayed = query.isPlayed,
                                maxOfficialRating = query.maxOfficialRating,
                                limit = query.limit,
                                fields =
                                    listOf(
                                        ItemFields.OVERVIEW,
                                        ItemFields.GENRES,
                                        // Seasons are not on the default payload; the meta line reads childCount.
                                        ItemFields.CHILD_COUNT,
                                    ),
                                enableUserData = true,
                                imageTypeLimit = 1,
                                enableImageTypes =
                                    listOf(
                                        ImageType.PRIMARY,
                                        ImageType.BACKDROP,
                                        ImageType.LOGO,
                                    ),
                                enableTotalRecordCount = true,
                            ),
                        ).content
                val items = result.items.map { BaseItem(it) }
                val ratingOk = items.filter { filters.admitsRating(it.data.officialRating) }
                val survivors = ratingOk.filter { filters.accepts(it.data.runTimeTicks) }
                val matches =
                    surpriseMatchCount(
                        totalRecordCount = result.totalRecordCount,
                        returned = items.size,
                        keptAfterRating = ratingOk.size,
                    )
                survivors to matches
            }

        suspend fun genres(kind: SurpriseKind): List<String> =
            withContext(ioDispatcher) {
                genresMutex.withLock {
                    genresByKind[kind]?.let { return@withLock it }
                    val userId = serverRepository.currentUser?.id ?: error("Not signed in")
                    val types =
                        listOf(
                            if (kind == SurpriseKind.MOVIES) BaseItemKind.MOVIE else BaseItemKind.SERIES,
                        )
                    val names =
                        api.genresApi
                            .getGenres(
                                userId = userId,
                                includeItemTypes = types,
                            ).content.items
                            .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                            .distinct()
                            .sorted()
                    genresByKind[kind] = names
                    names
                }
            }

        /** Next-up episode for a series, or the first episode when nothing is in progress. */
        suspend fun nextEpisode(seriesId: UUID): BaseItem? =
            withContext(ioDispatcher) {
                val userId = serverRepository.currentUser?.id
                val next =
                    api.tvShowsApi
                        .getNextUp(
                            userId = userId,
                            seriesId = seriesId,
                            limit = 1,
                        ).content.items
                        .firstOrNull()
                val episode =
                    next
                        ?: api.tvShowsApi
                            .getEpisodes(
                                seriesId = seriesId,
                                userId = userId,
                                limit = 1,
                            ).content.items
                            .firstOrNull()
                episode?.let { BaseItem(it) }
            }
    }

/**
 * Ratings the PG cap admits. TV-PG counts; PG-13 does not.
 * Jellyfin ignores MaxOfficialRating on series when IsPlayed is also set, so those rows are dropped here.
 */
private val KID_RATINGS = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")

/** Kid-friendly picks must be in [KID_RATINGS]. With the cap off, every rating passes, including a missing one. */
fun SurpriseFilters.admitsRating(officialRating: String?): Boolean {
    if (!kidFriendly) return true
    val rating = officialRating?.trim()?.uppercase() ?: return false
    return rating in KID_RATINGS
}

/**
 * Server total, minus items this page had to drop because the rating cap was ignored.
 * Runtime rejects stay in the total: the server cannot express that filter.
 */
fun surpriseMatchCount(
    totalRecordCount: Int,
    returned: Int,
    keptAfterRating: Int,
): Int = (totalRecordCount - (returned - keptAfterRating)).coerceAtLeast(0)
