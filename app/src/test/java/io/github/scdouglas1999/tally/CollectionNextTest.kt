package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.playback.CollectionEntry
import io.github.scdouglas1999.tally.playback.CollectionNext
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Collection order against the dev server's captured movie list
 * (`movies-provider-ids.json`): films that share `TmdbCollection` play in premiere order.
 */
class CollectionNextTest {
    @Test
    fun `toy story plays in release order then stops`() {
        assertEquals(id("Toy Story 2"), next("Toy Story"))
        assertEquals(id("Toy Story 3"), next("Toy Story 2"))
        assertNull(next("Toy Story 3"))
    }

    @Test
    fun `back to the future plays in release order then stops`() {
        assertEquals(id("Back to the Future Part II"), next("Back to the Future"))
        assertEquals(id("Back to the Future Part III"), next("Back to the Future Part II"))
        assertNull(next("Back to the Future Part III"))
    }

    @Test
    fun `a film with no collection has no next`() {
        assertNull(next("Inception"))
    }

    @Test
    fun `a film alone in its collection has no next`() {
        assertNull(next("The Matrix"))
    }

    @Test
    fun `an unknown id has no next`() {
        assertNull(CollectionNext.nextInCollection(entries, UUID.randomUUID()))
    }

    @Test
    fun `order holds when the fixture list is shuffled`() {
        val shuffled = entries.shuffled(Random(1))
        for (entry in entries) {
            assertEquals(
                CollectionNext.nextInCollection(entries, entry.id),
                CollectionNext.nextInCollection(shuffled, entry.id),
            )
        }
    }

    private fun next(name: String): UUID? = CollectionNext.nextInCollection(entries, id(name))

    private fun id(name: String): UUID = ids.getValue(name)

    private companion object {
        val items: List<BaseItemDto> by lazy {
            val text =
                CollectionNextTest::class.java
                    .getResource("/tally/movies-provider-ids.json")!!
                    .readText()
            ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(text).items
        }

        val ids: Map<String, UUID> by lazy { items.associate { it.name!! to it.id } }

        val entries: List<CollectionEntry> by lazy {
            items.mapNotNull { item ->
                val collectionId = item.providerIds?.get("TmdbCollection") ?: return@mapNotNull null
                CollectionEntry(
                    id = item.id,
                    collectionId = collectionId,
                    premiereDate = item.premiereDate,
                    productionYear = item.productionYear,
                    sortName = item.sortName?.takeIf { it.isNotBlank() } ?: item.name.orEmpty(),
                )
            }
        }
    }
}
