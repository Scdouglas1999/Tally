package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyJson
import io.github.scdouglas1999.tally.dvr.DvrFormat
import io.github.scdouglas1999.tally.dvr.DvrList
import io.github.scdouglas1999.tally.dvr.DvrState
import io.github.scdouglas1999.tally.dvr.forGame
import io.github.scdouglas1999.tally.dvr.recordingView
import io.github.scdouglas1999.tally.dvr.spoilerGuarded
import io.github.scdouglas1999.tally.dvr.teamRuleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DVR's client payloads, in the shapes the plugin's RecordingsController and board enricher send. */
class DvrModelsTest {
    private val list: DvrList =
        TallyJson.decodeFromString(
            """
            {"canManage":true,
             "rules":[
              {"id":"eae14ff5","kind":"game","title":"Delta Cranes at Summit Elks","leaguePath":"baseball/mlb","gameId":"900003","keepLast":0},
              {"id":"77aa","kind":"team","title":"Every Harbor Hawks game","leaguePath":"baseball/mlb","teamId":"900450","teamName":"Harbor Hawks","keepLast":5}],
             "jobs":[
              {"id":"c353","ruleId":"x","state":"recording","title":"Riverton Otters at Lakeside Herons",
               "game":{"id":"900001","league":"MLB","leaguePath":"baseball/mlb","start":"2026-09-24T14:49:00+00:00",
                       "away":{"id":"900446","abbr":"ROT","name":"Riverton Otters","shortName":"Otters"},
                       "home":{"id":"900447","abbr":"LKH","name":"Lakeside Herons","shortName":"Herons"}},
               "createdAt":"2026-09-24T17:40:30Z","startedAt":"2026-09-24T17:40:31Z","bytes":20657816,"seconds":42,
               "startOverPath":"/JellyTV/Recordings/c353/playlist.m3u8?s=abc"},
              {"id":"old1","ruleId":"x","state":"failed","reason":"No stream found for this game","title":"Riverton Otters at Lakeside Herons",
               "game":{"id":"900001","league":"MLB","leaguePath":"baseball/mlb"},"createdAt":"2026-09-24T17:30:00Z"},
              {"id":"75a4","ruleId":"eae14ff5","state":"failed","reason":"No stream found for this game","title":"Delta Cranes at Summit Elks",
               "game":{"id":"900003","league":"MLB","leaguePath":"baseball/mlb"},"createdAt":"2026-09-24T17:40:10Z","unknownKey":1}]}
            """.trimIndent(),
        )

    private fun game(json: String): TallyGame = TallyJson.decodeFromString(json)

    @Test
    fun `decodes the list and keeps unknown keys out`() {
        assertTrue(list.canManage)
        assertEquals(2, list.rules.size)
        assertEquals(3, list.jobs.size)
        assertEquals(
            "Otters",
            list.jobs[0]
                .game.away.displayName,
        )
        assertTrue(list.jobs[0].isRecording)
        assertTrue(list.jobs[2].isFinal)
    }

    @Test
    fun `a running job wins over an older failed one of the same game`() {
        assertEquals("c353", list.jobs.forGame("900001")?.id)
        assertEquals("75a4", list.jobs.forGame("900003")?.id)
        assertNull(list.jobs.forGame("nope"))
    }

    @Test
    fun `the board's recording decodes and a game without one has none`() {
        val with =
            game(
                """{"id":"900001","league":"MLB","state":"in","recording":{"state":"recording","jobId":"c353",
                   "startOverPath":"/JellyTV/Recordings/c353/playlist.m3u8?s=abc"}}""",
            )
        assertEquals(DvrState.RECORDING, with.recording?.state)
        assertNull(game("""{"id":"1","state":"pre"}""").recording)
    }

    @Test
    fun `the view prefers the list's job, falls back to the board`() {
        val g = game("""{"id":"900001","state":"in","recording":{"state":"scheduled","jobId":"c353"}}""")
        val fromList = recordingView(g, list)
        assertNotNull(fromList)
        assertEquals(DvrState.RECORDING, fromList!!.state)
        assertEquals("2026-09-24T17:40:31Z", fromList.startedAt)
        assertEquals(DvrState.SCHEDULED, recordingView(g, null)?.state)
    }

    @Test
    fun `a finished game with a recording hides its score, one without does not`() {
        assertTrue(game("""{"id":"1","state":"post","recording":{"state":"done","jobId":"a","itemId":"b"}}""").spoilerGuarded)
        assertTrue(game("""{"id":"1","state":"post","recording":{"state":"finishing","jobId":"a"}}""").spoilerGuarded)
        assertFalse(game("""{"id":"1","state":"post","recording":{"state":"failed","jobId":"a"}}""").spoilerGuarded)
        assertFalse(game("""{"id":"1","state":"in","recording":{"state":"recording","jobId":"a"}}""").spoilerGuarded)
        assertFalse(game("""{"id":"1","state":"post"}""").spoilerGuarded)
    }

    @Test
    fun `team rules match by team id within the game's league`() {
        val hawks =
            game(
                """{"id":"900002","sport":"baseball","league":"MLB","state":"pre",
                   "away":{"id":"900450","abbr":"HBH"},"home":{"id":"900451","abbr":"MSO"}}""",
            )
        assertEquals("77aa", list.teamRuleFor(hawks, hawks.away)?.id)
        assertNull(list.teamRuleFor(hawks, hawks.home))
        val otherSport = hawks.copy(sport = "football", league = "NFL")
        assertNull(list.teamRuleFor(otherSport, otherSport.away))
    }

    @Test
    fun `sizes read like the server's`() {
        val gb = 1024L * 1024 * 1024
        assertEquals("350 MB", DvrFormat.size(350L * 1024 * 1024))
        assertEquals("4.2 GB", DvrFormat.size((4.2 * gb).toLong()))
        assertEquals("5 GB", DvrFormat.size(5 * gb))
        assertEquals("12 GB", DvrFormat.size(12 * gb))
        assertEquals("1.2 TB", DvrFormat.size((1.2 * 1024 * gb).toLong()))
        assertEquals("2h 58m", DvrFormat.length(2 * 3600.0 + 58 * 60))
    }
}
