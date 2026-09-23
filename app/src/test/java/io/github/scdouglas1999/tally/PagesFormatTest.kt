package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.media.pages.CountNoun
import io.github.scdouglas1999.tally.media.pages.countNoun
import io.github.scdouglas1999.tally.media.pages.joinMeta
import io.github.scdouglas1999.tally.media.pages.monthFirstDate
import io.github.scdouglas1999.tally.media.pages.personLifeLine
import io.github.scdouglas1999.tally.media.pages.primaryRole
import io.github.scdouglas1999.tally.media.pages.rundownMeta
import io.github.scdouglas1999.tally.media.pages.rundownNumber
import io.github.scdouglas1999.tally.media.pages.totalRuntimeTicks
import io.github.scdouglas1999.tally.media.pages.yearRange
import io.github.scdouglas1999.tally.media.series.episodeCode
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PersonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Formatting for the search, person, collection, favorites and playlist pages, against payloads captured
 * from the dev server: `movies-provider-ids.json` (every film, with the Toy Story and Back to the Future
 * collections' films in it) and `series-episodes-bb-s1.json` (Breaking Bad season 1).
 */
class PagesFormatTest {
    @Test
    fun `the toy story collection spans 1995 to 2010`() {
        val films = movies.filter { it.name?.startsWith("Toy Story") == true }
        assertEquals(3, films.size)
        assertEquals("1995–2010", yearRange(films.map { it.productionYear }))
        // The dev server's films are 90 s test patterns.
        assertEquals(3 * 900_230_000L, totalRuntimeTicks(films.map { it.runTimeTicks }))
        assertEquals(CountNoun.FILMS, countNoun(films.map { it.type }))
    }

    @Test
    fun `one year alone, and none`() {
        val inception = movies.single { it.name == "Inception" }
        assertEquals("2010", yearRange(listOf(inception.productionYear, inception.productionYear)))
        assertNull(yearRange(listOf(null, null)))
        assertNull(yearRange(emptyList()))
        assertEquals(0L, totalRuntimeTicks(listOf(null, 0L, -5L)))
    }

    @Test
    fun `mixed kinds count as items`() {
        val kinds = movies.take(2).map { it.type } + bbSeason1.take(1).map { it.type }
        assertEquals(CountNoun.ITEMS, countNoun(kinds))
        assertEquals(CountNoun.EPISODES, countNoun(bbSeason1.map { it.type }))
        assertEquals(CountNoun.SHOWS, countNoun(listOf(BaseItemKind.SERIES)))
        assertEquals(CountNoun.ITEMS, countNoun(emptyList()))
    }

    @Test
    fun `rundown meta for a film and an episode`() {
        val inception = movies.single { it.name == "Inception" }
        assertEquals(
            "Film · 2010 · 1m 30s",
            rundownMeta(inception.type, "Film", inception.productionYear, inception.seriesName, null, inception.runTimeTicks),
        )
        val e3 = bbSeason1[2]
        assertEquals(
            "Breaking Bad · S1 E3 · 1m",
            rundownMeta(
                e3.type,
                "Episode",
                e3.productionYear,
                e3.seriesName,
                episodeCode(e3.parentIndexNumber, e3.indexNumber, e3.indexNumberEnd),
                e3.runTimeTicks,
            ),
        )
        assertEquals("Film", rundownMeta(BaseItemKind.MOVIE, "Film", null, null, null, 0L))
    }

    @Test
    fun `rundown numbers are two digits at least`() {
        assertEquals("01", rundownNumber(0))
        assertEquals("12", rundownNumber(11))
        assertEquals("124", rundownNumber(123))
    }

    @Test
    fun `life line of a living and a late person`() {
        // Christopher Nolan on the dev server: PremiereDate 1970-07-30, ProductionLocations "Westminster, London, England, UK".
        val place = "Westminster, London, England, UK"
        assertEquals(
            "Born Jul 30, 1970 · Westminster, London, England, UK",
            personLifeLine(LocalDate.of(1970, 7, 30), null, place, "Born"),
        )
        assertEquals("1928–2016", personLifeLine(LocalDate.of(1928, 3, 2), LocalDate.of(2016, 1, 5), null, "Born"))
        assertEquals("–2016 · Chicago", personLifeLine(null, LocalDate.of(2016, 1, 5), " Chicago ", "Born"))
        assertNull(personLifeLine(null, null, null, "Born"))
        assertEquals("Mar 3, 1974", monthFirstDate(LocalDate.of(1974, 3, 3)))
    }

    @Test
    fun `the most frequent credit wins and ties follow the usual order`() {
        // Nolan directs, writes and produces each film: director before writer before producer.
        val nolan = List(3) { listOf(PersonKind.DIRECTOR, PersonKind.WRITER, PersonKind.PRODUCER) }.flatten()
        assertEquals(PersonKind.DIRECTOR, primaryRole(nolan))
        assertEquals(PersonKind.ACTOR, primaryRole(listOf(PersonKind.ACTOR, PersonKind.ACTOR, PersonKind.DIRECTOR)))
        assertEquals(PersonKind.WRITER, primaryRole(listOf(PersonKind.WRITER, PersonKind.WRITER, PersonKind.ACTOR)))
        assertNull(primaryRole(listOf(PersonKind.UNKNOWN)))
        assertNull(primaryRole(emptyList()))
    }

    @Test
    fun `meta parts drop blanks`() {
        assertEquals("3 items · 4m 30s", joinMeta("3 items", null, "", "4m 30s"))
        assertEquals("", joinMeta(null, " "))
    }

    private companion object {
        fun load(name: String): List<BaseItemDto> {
            val text =
                PagesFormatTest::class.java
                    .getResource("/tally/$name")!!
                    .readText()
            return ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(text).items
        }

        val movies = load("movies-provider-ids.json")
        val bbSeason1 = load("series-episodes-bb-s1.json")
    }
}
