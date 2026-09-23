package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.surprise.SURPRISE_BATCH
import io.github.scdouglas1999.tally.surprise.SURPRISE_KID_RATING
import io.github.scdouglas1999.tally.surprise.SURPRISE_MAX_TICKS
import io.github.scdouglas1999.tally.surprise.SurpriseFilters
import io.github.scdouglas1999.tally.surprise.SurpriseKind
import io.github.scdouglas1999.tally.surprise.accepts
import io.github.scdouglas1999.tally.surprise.admitsRating
import io.github.scdouglas1999.tally.surprise.surpriseMatchCount
import io.github.scdouglas1999.tally.surprise.toQuery
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurpriseFiltersTest {
    @Test
    fun `default query is unwatched movies`() {
        val query = SurpriseFilters().toQuery()
        assertEquals(listOf(BaseItemKind.MOVIE), query.includeItemTypes)
        assertEquals(emptyList<String>(), query.genres)
        assertEquals(false, query.isPlayed)
        assertNull(query.maxOfficialRating)
        assertEquals(SURPRISE_BATCH, query.limit)
    }

    @Test
    fun `shows query asks for series`() {
        val query = SurpriseFilters(kind = SurpriseKind.SHOWS).toQuery()
        assertEquals(listOf(BaseItemKind.SERIES), query.includeItemTypes)
        assertEquals(false, query.isPlayed)
        assertNull(query.maxOfficialRating)
    }

    @Test
    fun `genre is sent and any genre is not`() {
        val named = SurpriseFilters(genre = "Comedy").toQuery()
        assertEquals(listOf("Comedy"), named.genres)
        assertEquals(emptyList<String>(), SurpriseFilters(genre = null).toQuery().genres)
    }

    @Test
    fun `kid friendly caps the official rating at PG`() {
        val query = SurpriseFilters(kidFriendly = true).toQuery()
        assertEquals(SURPRISE_KID_RATING, query.maxOfficialRating)
        assertNull(SurpriseFilters(kidFriendly = false).toQuery().maxOfficialRating)
    }

    @Test
    fun `unwatched off drops the played filter`() {
        val query = SurpriseFilters(unwatchedOnly = false).toQuery()
        assertNull(query.isPlayed)
        assertEquals(false, SurpriseFilters(unwatchedOnly = true).toQuery().isPlayed)
    }

    @Test
    fun `runtime cap is not an items query parameter`() {
        val capped = SurpriseFilters(underTwoHours = true).toQuery()
        val open = SurpriseFilters(underTwoHours = false).toQuery()
        assertEquals(open.includeItemTypes, capped.includeItemTypes)
        assertEquals(open.genres, capped.genres)
        assertEquals(open.isPlayed, capped.isPlayed)
        assertEquals(open.maxOfficialRating, capped.maxOfficialRating)
        assertEquals(open.limit, capped.limit)
    }

    @Test
    fun `combined filters`() {
        val query =
            SurpriseFilters(
                kind = SurpriseKind.SHOWS,
                genre = "Crime",
                underTwoHours = true,
                kidFriendly = true,
                unwatchedOnly = false,
            ).toQuery(limit = 8)
        assertEquals(listOf(BaseItemKind.SERIES), query.includeItemTypes)
        assertEquals(listOf("Crime"), query.genres)
        assertNull(query.isPlayed)
        assertEquals(SURPRISE_KID_RATING, query.maxOfficialRating)
        assertEquals(8, query.limit)
    }

    @Test
    fun `movies at exactly two hours pass`() {
        val filters = SurpriseFilters(kind = SurpriseKind.MOVIES, underTwoHours = true)
        assertTrue(filters.accepts(SURPRISE_MAX_TICKS))
    }

    @Test
    fun `movies just over two hours are rejected`() {
        val filters = SurpriseFilters(kind = SurpriseKind.MOVIES, underTwoHours = true)
        assertFalse(filters.accepts(SURPRISE_MAX_TICKS + 1))
    }

    @Test
    fun `movies under the cap and with the cap off pass`() {
        val capped = SurpriseFilters(underTwoHours = true)
        assertTrue(capped.accepts(SURPRISE_MAX_TICKS - 1))
        assertTrue(SurpriseFilters(underTwoHours = false).accepts(SURPRISE_MAX_TICKS + 1))
    }

    @Test
    fun `unknown runtime passes`() {
        assertTrue(SurpriseFilters(underTwoHours = true).accepts(null))
    }

    @Test
    fun `shows ignore the runtime cap`() {
        val filters = SurpriseFilters(kind = SurpriseKind.SHOWS, underTwoHours = true)
        assertTrue(filters.accepts(SURPRISE_MAX_TICKS + 1))
        assertTrue(filters.accepts(null))
    }

    @Test
    fun `kid friendly admits ratings at or under PG`() {
        val filters = SurpriseFilters(kidFriendly = true)
        listOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG", " pg ").forEach { rating ->
            assertTrue(rating, filters.admitsRating(rating))
        }
        listOf("PG-13", "R", "NC-17", "TV-14", "TV-MA", "TV-Y7-FV", null, "").forEach { rating ->
            assertFalse(rating ?: "null", filters.admitsRating(rating))
        }
        assertTrue(SurpriseFilters(kidFriendly = false).admitsRating("TV-MA"))
        assertTrue(SurpriseFilters(kidFriendly = false).admitsRating(null))
    }

    @Test
    fun `match count drops items the rating cap failed to exclude`() {
        assertEquals(1, surpriseMatchCount(totalRecordCount = 5, returned = 5, keptAfterRating = 1))
        assertEquals(0, surpriseMatchCount(totalRecordCount = 1, returned = 1, keptAfterRating = 0))
        assertEquals(11, surpriseMatchCount(totalRecordCount = 11, returned = 11, keptAfterRating = 11))
    }
}
