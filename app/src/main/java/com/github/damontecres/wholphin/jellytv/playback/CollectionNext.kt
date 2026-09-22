package com.github.damontecres.wholphin.jellytv.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import kotlin.comparisons.nullsLast

/**
 * One film in the in-memory collection index. Only films that carry a TMDb collection id are stored.
 */
data class CollectionEntry(
    val id: UUID,
    val collectionId: String,
    val premiereDate: LocalDateTime?,
    val productionYear: Int?,
    val sortName: String,
)

/**
 * The film that follows [BaseItemDto] in its TMDb collection (Toy Story → Toy Story 2), so upstream's
 * own Up Next card, autoplay countdown and pass-out protection work for movies the way they already
 * do for episodes. Called from the movie branch of `PlaylistCreator.createFrom` (seam), after playback
 * has started. Returns null when there is no next film or on any error — playback must not fail here.
 */
object CollectionNext {
    private const val TMDB_COLLECTION = "TmdbCollection"
    private const val PAGE_SIZE = 1000
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val mutex = Mutex()
    private val cache = mutableMapOf<CacheKey, CachedIndex>()

    private val byRelease =
        compareBy<CollectionEntry, LocalDateTime?>(nullsLast()) { it.premiereDate }
            .thenBy(nullsLast()) { it.productionYear }
            .thenBy { it.sortName }

    /**
     * The id of the film that follows [currentId] among [entries] that share its collection,
     * ordered by premiere date (nulls last), then production year (nulls last), then sort name.
     * Null when [currentId] is unknown, alone in its collection, or last.
     */
    fun nextInCollection(
        entries: List<CollectionEntry>,
        currentId: UUID,
    ): UUID? {
        val current = entries.find { it.id == currentId } ?: return null
        val siblings =
            entries
                .filter { it.collectionId == current.collectionId }
                .sortedWith(byRelease)
        val index = siblings.indexOfFirst { it.id == currentId }
        return siblings.getOrNull(index + 1)?.id
    }

    suspend fun nextFor(
        api: ApiClient,
        item: BaseItemDto,
    ): BaseItemDto? {
        if (item.type != BaseItemKind.MOVIE) {
            Timber.i("CollectionNext skip %s type=%s", item.name, item.type)
            return null
        }
        return try {
            val userId =
                api.userApi
                    .getCurrentUser()
                    .content.id
            val nextId = nextInCollection(entriesFor(api, userId, item), item.id)
            if (nextId == null) {
                Timber.i("CollectionNext none after %s", item.name)
                return null
            }
            Timber.i("CollectionNext %s -> %s", item.name, nextId)
            api.userLibraryApi.getItem(itemId = nextId, userId = userId).content
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.w(ex, "Collection next failed for %s", item.id)
            null
        }
    }

    private suspend fun entriesFor(
        api: ApiClient,
        userId: UUID,
        item: BaseItemDto,
    ): List<CollectionEntry> {
        val indexed = index(api, userId)
        val fromDto = item.providerIds?.get(TMDB_COLLECTION)?.takeIf { it.isNotBlank() } ?: return indexed
        val existing = indexed.indexOfFirst { it.id == item.id }
        return if (existing >= 0) {
            if (indexed[existing].collectionId == fromDto) {
                indexed
            } else {
                indexed.toMutableList().also { it[existing] = it[existing].copy(collectionId = fromDto) }
            }
        } else {
            indexed +
                CollectionEntry(
                    id = item.id,
                    collectionId = fromDto,
                    premiereDate = item.premiereDate,
                    productionYear = item.productionYear,
                    sortName = item.sortName?.takeIf { it.isNotBlank() } ?: item.name.orEmpty(),
                )
        }
    }

    private suspend fun index(
        api: ApiClient,
        userId: UUID,
    ): List<CollectionEntry> {
        val key = CacheKey(serverUrl = api.baseUrl.orEmpty(), userId = userId)
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val hit = cache[key]
            if (hit != null && now - hit.fetchedAtMs < CACHE_TTL_MS) {
                hit.entries
            } else {
                val entries = fetchIndex(api, userId)
                cache[key] = CachedIndex(fetchedAtMs = now, entries = entries)
                entries
            }
        }
    }

    private suspend fun fetchIndex(
        api: ApiClient,
        userId: UUID,
    ): List<CollectionEntry> {
        val entries = ArrayList<CollectionEntry>()
        var startIndex = 0
        while (true) {
            val page =
                api.itemsApi
                    .getItems(
                        userId = userId,
                        startIndex = startIndex,
                        limit = PAGE_SIZE,
                        recursive = true,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        fields = listOf(ItemFields.PROVIDER_IDS),
                        enableImages = false,
                        enableUserData = false,
                    ).content
            for (movie in page.items) {
                val collectionId = movie.providerIds?.get(TMDB_COLLECTION)?.takeIf { it.isNotBlank() } ?: continue
                entries +=
                    CollectionEntry(
                        id = movie.id,
                        collectionId = collectionId,
                        premiereDate = movie.premiereDate,
                        productionYear = movie.productionYear,
                        sortName = movie.sortName?.takeIf { it.isNotBlank() } ?: movie.name.orEmpty(),
                    )
            }
            startIndex += page.items.size
            if (page.items.size < PAGE_SIZE) break
            if (page.totalRecordCount in 1..startIndex) break
        }
        return entries
    }

    private data class CacheKey(
        val serverUrl: String,
        val userId: UUID,
    )

    private data class CachedIndex(
        val fetchedAtMs: Long,
        val entries: List<CollectionEntry>,
    )
}
