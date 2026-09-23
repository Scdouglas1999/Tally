package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.PlayedFilter
import com.github.damontecres.wholphin.data.filter.YearFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import io.github.scdouglas1999.tally.media.library.JUMP_LETTERS
import io.github.scdouglas1999.tally.media.library.LibraryNoun
import io.github.scdouglas1999.tally.media.library.isFilterValueOn
import io.github.scdouglas1999.tally.media.library.jumpBarShown
import io.github.scdouglas1999.tally.media.library.jumpLetterFor
import io.github.scdouglas1999.tally.media.library.libraryNoun
import io.github.scdouglas1999.tally.media.library.nextSort
import io.github.scdouglas1999.tally.media.library.toggleFilterValue
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library pages' pure helpers, against payloads captured from the dev server: `movies-provider-ids.json`
 * (the 22 films of the Movies library, `/Items?includeItemTypes=Movie`) and `series-list.json` (its 5 shows).
 */
class LibraryFormatTest {
    @Test
    fun `films file under the first letter of their sort name`() {
        val letters = movies.associate { it.name!! to jumpLetterFor(it.sortName ?: it.name) }
        assertEquals('B', letters["Back to the Future Part III"])
        assertEquals('I', letters["Inception"])
        assertEquals('P', letters["Paddington 2"])
        assertEquals('U', letters["Up"])
        // The captured payload has no SortName, so the name itself is used (the server's would be "Matrix").
        assertEquals('T', letters["The Matrix"])
        assertTrue(letters.values.all { it in JUMP_LETTERS })
    }

    @Test
    fun `digits, other scripts and no name file under the hash`() {
        assertEquals('#', jumpLetterFor("2001: A Space Odyssey"))
        assertEquals('#', jumpLetterFor("Élite"))
        assertEquals('#', jumpLetterFor(""))
        assertEquals('#', jumpLetterFor(null))
        assertEquals('M', jumpLetterFor("matrix"))
    }

    @Test
    fun `the letters are the hash then A to Z`() {
        assertEquals(27, JUMP_LETTERS.length)
        assertEquals('#', JUMP_LETTERS.first())
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", JUMP_LETTERS.drop(1))
    }

    @Test
    fun `the jump bar shows only for a name sort with items`() {
        assertTrue(jumpBarShown(ItemSortBy.SORT_NAME, movies.size))
        assertFalse(jumpBarShown(ItemSortBy.PREMIERE_DATE, movies.size))
        assertFalse(jumpBarShown(ItemSortBy.RANDOM, movies.size))
        assertFalse(jumpBarShown(ItemSortBy.SORT_NAME, 0))
    }

    @Test
    fun `choosing the current sort reverses it, another sort keeps the direction`() {
        val byName = SortAndDirection(ItemSortBy.SORT_NAME, SortOrder.ASCENDING)
        assertEquals(SortAndDirection(ItemSortBy.SORT_NAME, SortOrder.DESCENDING), nextSort(byName, ItemSortBy.SORT_NAME))
        assertEquals(
            SortAndDirection(ItemSortBy.PREMIERE_DATE, SortOrder.ASCENDING),
            nextSort(byName, ItemSortBy.PREMIERE_DATE),
        )
        val newestFirst = SortAndDirection(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
        assertEquals(
            SortAndDirection(ItemSortBy.SORT_NAME, SortOrder.DESCENDING),
            nextSort(newestFirst, ItemSortBy.SORT_NAME),
        )
    }

    @Test
    fun `the count names what the grid holds`() {
        assertEquals(LibraryNoun.FILMS, libraryNoun(movies.map { it.type }.distinct(), CollectionType.MOVIES))
        assertEquals(LibraryNoun.SHOWS, libraryNoun(shows.map { it.type }.distinct(), CollectionType.TVSHOWS))
        assertEquals(LibraryNoun.COLLECTIONS, libraryNoun(listOf(BaseItemKind.BOX_SET), CollectionType.MOVIES))
        assertEquals(LibraryNoun.COLLECTIONS, libraryNoun(null, CollectionType.BOXSETS))
        assertEquals(LibraryNoun.VIDEOS, libraryNoun(null, CollectionType.HOMEVIDEOS))
        assertEquals(LibraryNoun.ITEMS, libraryNoun(null, null))
        assertEquals(LibraryNoun.ITEMS, libraryNoun(listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), CollectionType.MOVIES))
    }

    @Test
    fun `year values toggle in and out of the filter`() {
        // Values as upstream's FilterOptionCache builds them: the year as name and value.
        val years =
            movies
                .mapNotNull { it.productionYear }
                .distinct()
                .sorted()
                .map { FilterValueOption(it.toString(), it) }
        val y1999 = years.single { it.value == 1999 }
        val y2019 = years.single { it.value == 2019 }
        var filter = GetItemsFilter()
        assertFalse(isFilterValueOn(YearFilter, filter, y1999))

        filter = toggleFilterValue(YearFilter, filter, y1999)
        assertEquals(listOf(1999), filter.years)
        filter = toggleFilterValue(YearFilter, filter, y2019)
        assertEquals(listOf(1999, 2019), filter.years)
        assertTrue(isFilterValueOn(YearFilter, filter, y2019))
        assertEquals(1, filter.countFilters(listOf(YearFilter, PlayedFilter)))

        filter = toggleFilterValue(YearFilter, filter, y1999)
        filter = toggleFilterValue(YearFilter, filter, y2019)
        // An empty list clears the filter.
        assertNull(filter.years)
        assertEquals(0, filter.countFilters(listOf(YearFilter, PlayedFilter)))
    }

    @Test
    fun `played is a single choice`() {
        // Upstream offers played/unplayed as the names "True" and "False".
        val yes = FilterValueOption("True", null)
        val no = FilterValueOption("False", null)
        var filter = toggleFilterValue(PlayedFilter, GetItemsFilter(), no)
        assertEquals(false, filter.played)
        assertTrue(isFilterValueOn(PlayedFilter, filter, no))
        assertFalse(isFilterValueOn(PlayedFilter, filter, yes))
        filter = toggleFilterValue(PlayedFilter, filter, yes)
        assertEquals(true, filter.played)
    }

    private companion object {
        fun load(name: String): List<BaseItemDto> {
            val text =
                LibraryFormatTest::class.java
                    .getResource("/tally/$name")!!
                    .readText()
            return ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(text).items
        }

        val movies by lazy { load("movies-provider-ids.json") }
        val shows by lazy { load("series-list.json") }
    }
}
