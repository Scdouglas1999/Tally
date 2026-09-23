package io.github.scdouglas1999.tally.year

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Played movies and episodes for the signed-in user. The list is cached for ten minutes;
 * [years] is the distinct local-zone years of those plays, newest first.
 */
@Singleton
class YearRepository
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutex = Mutex()
        private var cache: Cache? = null

        suspend fun watched(): List<WatchedItem> =
            mutex.withLock {
                withContext(ioDispatcher) {
                    val userId =
                        serverRepository.currentUser?.id
                            ?: throw IllegalStateException("No signed-in user")
                    val hit = cache
                    val now = Instant.now()
                    if (
                        hit != null &&
                        hit.userId == userId &&
                        Duration.between(hit.at, now) < CACHE_FOR
                    ) {
                        return@withContext hit.items
                    }
                    val loaded = fetch(userId)
                    cache = Cache(userId, Instant.now(), loaded)
                    loaded
                }
            }

        suspend fun years(): List<Int> {
            val zone = ZoneId.systemDefault()
            return watched()
                .mapNotNull { it.lastPlayed?.atZone(zone)?.year }
                .distinct()
                .sortedDescending()
        }

        private suspend fun fetch(userId: UUID): List<WatchedItem> {
            Timber.d("Loading played items for the year recap")
            val dtos = ArrayList<BaseItemDto>()
            var start = 0
            while (true) {
                val page =
                    api.itemsApi
                        .getItems(
                            GetItemsRequest(
                                userId = userId,
                                recursive = true,
                                isPlayed = true,
                                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                                fields = listOf(ItemFields.GENRES),
                                enableUserData = true,
                                enableImages = false,
                                enableTotalRecordCount = true,
                                startIndex = start,
                                limit = PAGE_SIZE,
                            ),
                        ).content
                val batch = page.items
                dtos.addAll(batch)
                val total = page.totalRecordCount
                if (batch.isEmpty() || dtos.size >= total || batch.size < PAGE_SIZE) break
                start += batch.size
            }
            val seriesIds =
                dtos
                    .asSequence()
                    .filter { it.type == BaseItemKind.EPISODE && it.genreNames().isEmpty() }
                    .mapNotNull { it.seriesId }
                    .distinct()
                    .toList()
            val seriesGenres =
                if (seriesIds.isEmpty()) {
                    emptyMap()
                } else {
                    api.itemsApi
                        .getItems(
                            GetItemsRequest(
                                userId = userId,
                                ids = seriesIds,
                                fields = listOf(ItemFields.GENRES),
                                enableImages = false,
                                enableUserData = false,
                            ),
                        ).content.items
                        .associate { it.id to it.genreNames() }
                }
            val items =
                dtos
                    .filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.EPISODE }
                    .map { toWatchedItem(it, seriesGenres) }
            Timber.d("Year recap loaded %d played items", items.size)
            return items
        }

        private data class Cache(
            val userId: UUID,
            val at: Instant,
            val items: List<WatchedItem>,
        )

        private companion object {
            const val PAGE_SIZE = 500
            val CACHE_FOR: Duration = Duration.ofMinutes(10)
        }
    }
