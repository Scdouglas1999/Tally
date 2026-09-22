package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.year.WatchedItem
import com.github.damontecres.wholphin.jellytv.year.YearStats
import com.github.damontecres.wholphin.jellytv.year.YearStatsCalculator
import com.github.damontecres.wholphin.jellytv.year.toWatchedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class YearStatsTest {
    private val parsed: BaseItemDtoQueryResult by lazy {
        ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(fixtureText())
    }

    private val watched: List<WatchedItem> by lazy {
        parsed.items.map { toWatchedItem(it, emptyMap()) }
    }

    @Test
    fun `fixture maps to the same instants the payload recorded`() {
        assertEquals(36, parsed.totalRecordCount)
        assertEquals(36, parsed.items.size)
        val sexual = watched.first { it.name == "Sexual Harassment" }
        assertEquals(Instant.parse("2025-02-20T21:02:00Z"), sexual.lastPlayed)
        assertEquals(1, sexual.runtimeMinutes)
        assertEquals(1, sexual.playCount)
        assertEquals(listOf("Comedy"), sexual.genres)
        assertTrue(sexual.isEpisode)
        assertEquals("The Office", sexual.seriesName)
        watched.zip(rawItems()).forEach { (item, raw) ->
            assertEquals(raw.name, item.name)
            assertEquals(raw.played, item.lastPlayed)
            assertEquals(raw.minutes, item.runtimeMinutes)
            assertEquals(raw.plays, item.playCount)
        }
    }

    @Test
    fun `episode with no genres takes the series genres`() {
        val office = parsed.items.first { it.name == "Sexual Harassment" }
        val seriesId = office.seriesId!!
        val fromSeries = listOf("Drama")
        val stripped = toWatchedItem(office.copy(genres = emptyList()), mapOf(seriesId to fromSeries))
        assertEquals(fromSeries, stripped.genres)
        val missing = toWatchedItem(office.copy(genres = null), mapOf(seriesId to fromSeries))
        assertEquals(fromSeries, missing.genres)
        val own = toWatchedItem(office, mapOf(seriesId to fromSeries))
        assertEquals(listOf("Comedy"), own.genres)
    }

    @Test
    fun `missing runtime is zero and a zero play count still counts once`() {
        val office = parsed.items.first { it.type == BaseItemKind.EPISODE }
        assertEquals(0, toWatchedItem(office.copy(runTimeTicks = null), emptyMap()).runtimeMinutes)
        val user = office.userData!!.copy(playCount = 0)
        assertEquals(1, toWatchedItem(office.copy(userData = user), emptyMap()).playCount)
        val undated = toWatchedItem(office.copy(userData = office.userData!!.copy(lastPlayedDate = null)), emptyMap())
        assertTrue(YearStatsCalculator.compute(listOf(undated), 2025, ZoneOffset.UTC).isEmpty)
    }

    @Test
    fun `2026 totals months median and top lists match a hand sum`() {
        val expected = hand(2026)
        val stats = YearStatsCalculator.compute(watched, 2026, ZoneOffset.UTC)
        assertEquals(28L, expected.totalMinutes)
        assertHand(expected, stats)
        assertEquals("Parasite", stats.topMovies.first().name)
        assertEquals(2, stats.topMovies.first().count)
        assertEquals(
            listOf("Parasite", "Back to the Future", "Back to the Future Part III", "Finding Nemo", "Interstellar"),
            stats.topMovies.map { it.name },
        )
        assertEquals(listOf("Breaking Bad", "The Office", "Bluey"), stats.topSeries.map { it.name })
        val bluey = stats.topSeries.first { it.name == "Bluey" }
        assertEquals(3, bluey.count)
        assertTrue(bluey.minutes > bluey.count)
        assertEquals("Comedy", stats.topGenres.first().genre)
        assertEquals(9, stats.busiestMonth)
        assertEquals(2005, stats.medianReleaseYear)
        assertEquals("Bluey \u2014 The Magic Xylophone", stats.firstWatch?.name)
        assertEquals("The Office \u2014 Pilot", stats.latestWatch?.name)
    }

    @Test
    fun `2025 totals and the earliest busiest month win a tie`() {
        val expected = hand(2025)
        val stats = YearStatsCalculator.compute(watched, 2025, ZoneOffset.UTC)
        assertEquals(13L, expected.totalMinutes)
        assertHand(expected, stats)
        assertEquals(expected.months[6], expected.months[11])
        assertTrue(expected.months[6] > 0L)
        assertEquals(7, stats.busiestMonth)
        assertEquals(2008, stats.medianReleaseYear)
        assertEquals(listOf("Back to the Future Part II", "The Grand Budapest Hotel", "The Matrix", "Up"), stats.topMovies.map { it.name })
        assertEquals("The Grand Budapest Hotel", stats.firstWatch?.name)
        assertEquals("Bluey \u2014 Hospital", stats.latestWatch?.name)
        val office = parsed.items.first { it.name == "The Grand Budapest Hotel" }
        assertEquals(office.id, stats.firstWatch?.imageItemId)
        val hospital = parsed.items.first { it.name == "Hospital" && it.seriesName == "Bluey" }
        assertEquals(hospital.seriesId, stats.latestWatch?.imageItemId)
    }

    @Test
    fun `empty input is an empty year`() {
        val stats = YearStatsCalculator.compute(emptyList(), 2026, ZoneOffset.UTC)
        assertTrue(stats.isEmpty)
        assertEquals(0L, stats.totalMinutes)
        assertEquals(List(12) { 0L }, stats.monthMinutes)
        assertNull(stats.busiestMonth)
        assertNull(stats.medianReleaseYear)
        assertNull(stats.firstWatch)
        assertNull(stats.latestWatch)
        assertTrue(stats.topMovies.isEmpty())
        assertTrue(stats.topSeries.isEmpty())
        assertTrue(stats.topGenres.isEmpty())
    }

    @Test
    fun `year follows the supplied zone`() {
        val item =
            film(
                played = "2026-01-01T03:30:00Z",
                runtime = 40,
            )
        val utc = YearStatsCalculator.compute(listOf(item), 2026, ZoneOffset.UTC)
        val eastern = YearStatsCalculator.compute(listOf(item), 2025, ZoneId.of("America/New_York"))
        assertEquals(40L, utc.totalMinutes)
        assertEquals(1, utc.movies)
        assertEquals(40L, eastern.totalMinutes)
        assertTrue(YearStatsCalculator.compute(listOf(item), 2026, ZoneId.of("America/New_York")).isEmpty)
    }

    @Test
    fun `lists cap at five and ties break by the spec order`() {
        val movies =
            (1..6).map { n ->
                film(name = "M$n", played = "2026-03-0${n}T00:00:00Z", runtime = 10, plays = 1, year = 1990 + n)
            } + film(name = "Rewatch", played = "2026-04-01T00:00:00Z", runtime = 5, plays = 3, year = 1980)
        val stats = YearStatsCalculator.compute(movies, 2026, ZoneOffset.UTC)
        assertEquals(5, stats.topMovies.size)
        assertEquals("Rewatch", stats.topMovies.first().name)
        val tied =
            listOf(
                film(name = "Zebra", played = "2026-05-01T00:00:00Z", runtime = 8, plays = 2),
                film(name = "Alpha", played = "2026-05-02T00:00:00Z", runtime = 8, plays = 2),
            )
        assertEquals(
            listOf("Alpha", "Zebra"),
            YearStatsCalculator.compute(tied, 2026, ZoneOffset.UTC).topMovies.map { it.name },
        )
    }

    @Test
    fun `a series ranks by episodes watched and a genre keeps the full minutes`() {
        val seriesA = UUID.randomUUID()
        val seriesB = UUID.randomUUID()
        val items =
            listOf(
                episode("One", seriesA, "A", played = "2026-01-02T00:00:00Z", plays = 4),
                episode("Two", seriesB, "B", played = "2026-02-02T00:00:00Z"),
                episode("Three", seriesB, "B", played = "2026-03-02T00:00:00Z"),
                film(
                    name = "Both",
                    played = "2026-06-01T00:00:00Z",
                    runtime = 30,
                    genres = listOf("Action", "Comedy", "Action"),
                ),
            )
        val stats = YearStatsCalculator.compute(items, 2026, ZoneOffset.UTC)
        assertEquals(listOf("B", "A"), stats.topSeries.map { it.name })
        assertEquals(2, stats.topSeries.first().count)
        assertEquals(1, stats.topSeries.first { it.name == "A" }.count)
        assertEquals(40L, stats.topSeries.first { it.name == "A" }.minutes)
        assertEquals(2, stats.series)
        val action = stats.topGenres.first { it.genre == "Action" }
        val comedy = stats.topGenres.first { it.genre == "Comedy" }
        assertEquals(30L, action.minutes)
        assertEquals(30L, comedy.minutes)
        assertEquals(action.minutes.toFloat() / stats.totalMinutes.toFloat(), action.fraction, 0.0001f)
    }

    @Test
    fun `even median is the lower middle`() {
        val items =
            listOf(
                film(year = 2010, played = "2026-01-01T00:00:00Z"),
                film(year = 2000, played = "2026-02-01T00:00:00Z"),
                film(year = 1990, played = "2026-03-01T00:00:00Z"),
                film(year = 2020, played = "2026-04-01T00:00:00Z"),
                film(year = null, played = "2026-05-01T00:00:00Z"),
            )
        assertEquals(2000, YearStatsCalculator.compute(items, 2026, ZoneOffset.UTC).medianReleaseYear)
    }

    private fun assertHand(
        expected: Hand,
        stats: YearStats,
    ) {
        assertEquals(expected.totalMinutes, stats.totalMinutes)
        assertEquals(expected.totalMinutes, stats.monthMinutes.sum())
        assertEquals(expected.months, stats.monthMinutes)
        assertEquals(expected.movies, stats.movies)
        assertEquals(expected.episodes, stats.episodes)
        assertEquals(expected.series, stats.series)
        assertEquals(expected.busiest, stats.busiestMonth)
        assertEquals(expected.median, stats.medianReleaseYear)
        assertEquals(expected.topMovies, stats.topMovies.map { it.name to it.count })
        assertEquals(expected.topSeries, stats.topSeries.map { Triple(it.name, it.count, it.minutes) })
        assertEquals(expected.topGenres, stats.topGenres.map { it.genre to it.minutes })
        assertTrue(stats.topMovies.size <= 5)
        assertTrue(stats.topSeries.size <= 5)
        assertTrue(stats.topGenres.size <= 5)
        stats.topGenres.forEach { share ->
            if (stats.totalMinutes > 0L) {
                assertEquals(share.minutes.toFloat() / stats.totalMinutes.toFloat(), share.fraction, 0.0001f)
            }
        }
    }

    /** Independent sum from the fixture fields: runtime minutes × play count, year in UTC. */
    private fun hand(year: Int): Hand {
        val kept = rawItems().filter { it.played.atZone(ZoneOffset.UTC).year == year }
        val months = MutableList(12) { 0L }
        val genreMinutes = linkedMapOf<String, Long>()
        for (item in kept) {
            val contributed = item.minutes.toLong() * item.plays
            months[item.played.atZone(ZoneOffset.UTC).monthValue - 1] += contributed
            for (genre in item.genres.distinct()) {
                if (contributed == 0L) continue
                genreMinutes[genre] = (genreMinutes[genre] ?: 0L) + contributed
            }
        }
        val total = months.sum()
        val busiestIndex = months.indices.maxByOrNull { months[it] }
        val busiest = if (busiestIndex == null || months[busiestIndex] == 0L) null else busiestIndex + 1
        val years = kept.mapNotNull { it.productionYear }.sorted()
        val movies =
            kept
                .filter { !it.episode }
                .sortedWith(compareByDescending<Raw> { it.plays }.thenByDescending { it.minutes.toLong() * it.plays }.thenBy { it.name })
                .take(5)
                .map { it.name to it.plays }
        val series =
            kept
                .filter { it.episode && it.seriesId != null }
                .groupBy { it.seriesId }
                .map { (_, episodes) ->
                    Triple(
                        episodes.firstNotNullOf { it.seriesName },
                        episodes.size,
                        episodes.sumOf { it.minutes.toLong() * it.plays },
                    )
                }.sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third }.thenBy { it.first })
                .take(5)
        val genres =
            genreMinutes.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(5)
                .map { it.key to it.value }
        return Hand(
            totalMinutes = total,
            movies = kept.count { !it.episode },
            episodes = kept.count { it.episode },
            series = kept.mapNotNull { it.seriesId }.distinct().size,
            months = months,
            busiest = busiest,
            median = if (years.isEmpty()) null else years[(years.size - 1) / 2],
            topMovies = movies,
            topSeries = series,
            topGenres = genres,
        )
    }

    private fun rawItems(): List<Raw> {
        val root = Json.parseToJsonElement(fixtureText()).jsonObject
        return root.getValue("Items").jsonArray.map { element ->
            val item = element.jsonObject
            val user = item.getValue("UserData").jsonObject
            val ticks = item["RunTimeTicks"]?.jsonPrimitive?.long ?: 0L
            Raw(
                name = item.getValue("Name").jsonPrimitive.content,
                episode = item.getValue("Type").jsonPrimitive.content == "Episode",
                seriesId = item["SeriesId"]?.jsonPrimitive?.content,
                seriesName = item["SeriesName"]?.jsonPrimitive?.content,
                minutes = if (ticks <= 0L) 0 else (ticks / 600_000_000L).toInt(),
                plays = maxOf(1, user.getValue("PlayCount").jsonPrimitive.int),
                played = Instant.parse(user.getValue("LastPlayedDate").jsonPrimitive.content),
                genres =
                    item["Genres"]
                        ?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        ?.filter { it.isNotBlank() }
                        .orEmpty(),
                productionYear = item["ProductionYear"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    private fun fixtureText(): String = javaClass.getResource("/jellytv/played-items.json")!!.readText()

    private data class Raw(
        val name: String,
        val episode: Boolean,
        val seriesId: String?,
        val seriesName: String?,
        val minutes: Int,
        val plays: Int,
        val played: Instant,
        val genres: List<String>,
        val productionYear: Int?,
    )

    private data class Hand(
        val totalMinutes: Long,
        val movies: Int,
        val episodes: Int,
        val series: Int,
        val months: List<Long>,
        val busiest: Int?,
        val median: Int?,
        val topMovies: List<Pair<String, Int>>,
        val topSeries: List<Triple<String, Int, Long>>,
        val topGenres: List<Pair<String, Long>>,
    )
}

private fun film(
    name: String = "Film",
    played: String = "2026-06-01T00:00:00Z",
    runtime: Int = 10,
    plays: Int = 1,
    year: Int? = 2000,
    genres: List<String> = listOf("Drama"),
) = WatchedItem(
    id = UUID.randomUUID(),
    name = name,
    isEpisode = false,
    seriesId = null,
    seriesName = null,
    runtimeMinutes = runtime,
    playCount = plays,
    lastPlayed = Instant.parse(played),
    genres = genres,
    productionYear = year,
)

private fun episode(
    name: String,
    seriesId: UUID,
    seriesName: String,
    played: String,
    plays: Int = 1,
    runtime: Int = 10,
) = WatchedItem(
    id = UUID.randomUUID(),
    name = name,
    isEpisode = true,
    seriesId = seriesId,
    seriesName = seriesName,
    runtimeMinutes = runtime,
    playCount = plays,
    lastPlayed = Instant.parse(played),
    genres = listOf("Drama"),
    productionYear = 2010,
)
