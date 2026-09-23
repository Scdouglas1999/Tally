package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.media.series.NextUpLabel
import io.github.scdouglas1999.tally.media.series.SeasonSummary
import io.github.scdouglas1999.tally.media.series.airDate
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.media.series.episodeNumber
import io.github.scdouglas1999.tally.media.series.nextUpLabel
import io.github.scdouglas1999.tally.media.series.seasonSummary
import io.github.scdouglas1999.tally.media.series.seasonWatchedFraction
import io.github.scdouglas1999.tally.media.series.seriesYears
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Series formatting against payloads captured from the dev server:
 * `series-list.json` (`/Items?includeItemTypes=Series&fields=ChildCount,RecursiveItemCount,Status,EndDate`),
 * `series-episodes-bb-s1.json` and `series-episodes-office-s2.json` (`/Shows/{id}/Episodes?seasonId=…`,
 * Breaking Bad S1 with E1–E2 played and E3 42% in, The Office S2 all played) and
 * `series-nextup-bb.json` (`/Shows/NextUp?seriesId=…`), `series-seasons-*.json` (the season query the
 * series page makes, with `ChildCount`).
 */
class SeriesFormatTest {
    @Test
    fun `an ended series spans its years`() {
        val bb = series("Breaking Bad")
        assertEquals("2008–2013", seriesYears(bb.productionYear, bb.endDate, bb.status))
        val office = series("The Office")
        assertEquals("2005–2013", seriesYears(office.productionYear, office.endDate, office.status))
    }

    @Test
    fun `a continuing series is open ended`() {
        val severance = series("Severance")
        assertEquals("2022–", seriesYears(severance.productionYear, severance.endDate, severance.status))
        val bluey = series("Bluey")
        assertEquals("2018–", seriesYears(bluey.productionYear, bluey.endDate, bluey.status))
    }

    @Test
    fun `a series that ended in its first year is one year`() {
        val bb = series("Breaking Bad")
        assertEquals("2008", seriesYears(bb.productionYear, bb.premiereDate, "Ended"))
        assertEquals("2008", seriesYears(bb.productionYear, null, "Ended"))
        assertNull(seriesYears(null, bb.endDate, bb.status))
    }

    @Test
    fun `episode codes`() {
        val e3 = bbSeason1[2]
        assertEquals("S1 E3", episodeCode(e3.parentIndexNumber, e3.indexNumber, e3.indexNumberEnd))
        assertEquals("S1 E3–4", episodeCode(e3.parentIndexNumber, e3.indexNumber, 4))
        assertEquals("SPECIAL", episodeCode(0, 1, null))
        assertEquals("E5", episodeCode(null, 5, null))
        assertNull(episodeCode(null, null, null))
        assertEquals("E03", episodeNumber(e3.indexNumber))
        assertNull(episodeNumber(null))
    }

    @Test
    fun `air dates read month day year`() {
        assertEquals("FEB 10, 2008", airDate(bbSeason1[2].premiereDate))
        assertEquals("JAN 20, 2008", airDate(bbSeason1[0].premiereDate))
        assertNull(airDate(null))
    }

    @Test
    fun `breaking bad season one has five left`() {
        val summary = seasonSummary(bbSeason1)
        assertEquals(7, summary.episodes)
        assertEquals(5, summary.left)
        val runtime = bbSeason1[0].runTimeTicks!!
        val e3 = bbSeason1[2]
        val e3Left = runtime - e3.userData!!.playbackPositionTicks
        assertEquals(4 * runtime + e3Left, summary.remainingTicks)
    }

    @Test
    fun `a fully watched season has none left`() {
        assertEquals(SeasonSummary(episodes = 4, left = 0, remainingTicks = 0L), seasonSummary(officeSeason2))
    }

    @Test
    fun `next up in progress reads resume with its share`() {
        val nextUp = bbNextUp.single()
        assertEquals(NextUpLabel.Resume("S1 E3", 42), nextUpLabel(nextUp))
    }

    @Test
    fun `next up not started reads next up`() {
        assertEquals(NextUpLabel.NextUp("S1 E4"), nextUpLabel(bbSeason1[3]))
    }

    @Test
    fun `no next up reads play`() {
        assertEquals(NextUpLabel.Play, nextUpLabel(null))
    }

    @Test
    fun `season share watched from episode counts`() {
        val bb = load("series-seasons-bb.json")
        assertEquals(2f / 7f, seasonWatchedFraction(bb[0])!!, 0.0001f)
        assertEquals(0f, seasonWatchedFraction(bb[1])!!, 0.0001f)
        val office = load("series-seasons-office.json")
        assertEquals(1f, seasonWatchedFraction(office[1])!!, 0.0001f)
    }

    private fun series(name: String): BaseItemDto = seriesList.single { it.name == name }

    private companion object {
        fun load(name: String): List<BaseItemDto> {
            val text =
                SeriesFormatTest::class.java
                    .getResource("/tally/$name")!!
                    .readText()
            return ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(text).items
        }

        val seriesList by lazy { load("series-list.json") }
        val bbSeason1 by lazy { load("series-episodes-bb-s1.json") }
        val officeSeason2 by lazy { load("series-episodes-office-s2.json") }
        val bbNextUp by lazy { load("series-nextup-bb.json") }
    }
}
