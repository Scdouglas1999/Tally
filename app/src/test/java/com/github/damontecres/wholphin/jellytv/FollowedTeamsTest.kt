package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvTeam
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import com.github.damontecres.wholphin.jellytv.data.isFollowed
import com.github.damontecres.wholphin.jellytv.ui.components.StartsIn
import com.github.damontecres.wholphin.jellytv.ui.components.startsInLabel
import com.github.damontecres.wholphin.jellytv.ui.components.text
import com.github.damontecres.wholphin.jellytv.ui.home.followedStartupNudge
import com.github.damontecres.wholphin.jellytv.ui.home.withFollowedTeamsFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FollowedTeamsTest {
    private val now = Instant.parse("2026-09-22T17:00:00Z")

    private fun game(
        id: String,
        league: String = "NFL",
        state: String = "pre",
        start: String = "2026-09-22T17:00:00Z",
        away: String = "KC",
        home: String = "BUF",
    ) = JtvGame(
        id = id,
        league = league,
        state = state,
        start = start,
        away = JtvTeam(abbr = away, shortName = away),
        home = JtvTeam(abbr = home, shortName = home),
    )

    @Test
    fun `a followed upcoming game does not sort ahead of a live row`() {
        val rows =
            BoardOrganizer.rows(
                games =
                    listOf(
                        game("live", league = "MLB", state = "in", away = "NYY", home = "BOS"),
                        game("soon", state = "pre", start = "2026-09-22T18:00:00Z", away = "KC", home = "BUF"),
                    ),
                favoriteChannelIds = emptySet(),
                onlyWatchable = false,
                favoriteTeams = setOf("NFL:KC"),
            )
        assertEquals(listOf("in", "pre"), rows.map { it.state })
        assertEquals(
            "live",
            rows
                .first()
                .games
                .single()
                .id,
        )
        assertEquals(
            "soon",
            rows
                .last()
                .games
                .single()
                .id,
        )
    }

    @Test
    fun `a followed game sorts first inside its row`() {
        val row =
            BoardOrganizer
                .rows(
                    games =
                        listOf(
                            game("early", start = "2026-09-22T17:00:00Z", away = "TB", home = "NYY"),
                            game("late", start = "2026-09-22T23:00:00Z", away = "KC", home = "BUF"),
                        ),
                    favoriteChannelIds = emptySet(),
                    onlyWatchable = false,
                    favoriteTeams = setOf("NFL:KC"),
                ).single()
        assertEquals(listOf("late", "early"), row.games.map { it.id })
    }

    @Test
    fun `home row keeps live games ahead of a followed upcoming game`() {
        val ordered =
            listOf(
                game("live", state = "in", away = "NYY", home = "BOS"),
                game("soon", state = "pre", start = "2026-09-22T17:20:00Z", away = "KC", home = "BUF"),
            ).withFollowedTeamsFirst(setOf("nfl:kc"))
        assertEquals(listOf("live", "soon"), ordered.map { it.id })
    }

    @Test
    fun `home row pins a followed game first inside the live group`() {
        val ordered =
            listOf(
                game("other", state = "in", away = "NYY", home = "BOS"),
                game("followed", state = "in", away = "KC", home = "BUF"),
            ).withFollowedTeamsFirst(setOf("NFL:KC"))
        assertEquals(listOf("followed", "other"), ordered.map { it.id })
    }

    @Test
    fun `teamKey ignores case`() {
        val game = JtvGame(league = "nfl", away = JtvTeam(abbr = "kc"), home = JtvTeam(abbr = "Buf"))
        assertEquals("NFL:KC", game.teamKey(game.away))
        assertEquals("NFL:BUF", game.teamKey(game.home))
        assertEquals(game.teamKey(JtvTeam(abbr = "KC")), game.teamKey(game.away))
        val stored = setOf("nfl:kc").map { it.uppercase() }.toSet()
        assertTrue(game.isFollowed(stored))
        assertFalse(game.isFollowed(setOf("nfl:kc")))
    }

    @Test
    fun `starts in label thresholds`() {
        assertNull(startsInLabel(now.plusSeconds(90 * 60 + 1), now))
        assertEquals(StartsIn.InMinutes(90), startsInLabel(now.plusSeconds(90 * 60), now))
        assertEquals(StartsIn.InMinutes(23), startsInLabel(now.plusSeconds(23 * 60), now))
        assertEquals(StartsIn.InMinutes(23), startsInLabel(now.plusSeconds(23 * 60 + 59), now))
        assertEquals(StartsIn.InMinutes(1), startsInLabel(now.plusSeconds(60), now))
        assertEquals(StartsIn.Starting, startsInLabel(now.plusSeconds(59), now))
        assertEquals(StartsIn.Starting, startsInLabel(now, now))
        assertEquals(StartsIn.Starting, startsInLabel(now.minusSeconds(59), now))
        assertNull(startsInLabel(now.minusSeconds(60), now))
        assertEquals("IN 23 MIN", StartsIn.InMinutes(23).text())
        assertEquals("STARTING", StartsIn.Starting.text())
    }

    @Test
    fun `startup nudge uses the closest followed game inside the windows`() {
        val soon =
            game("soon", state = "pre", start = "2026-09-22T17:12:00Z", away = "KC", home = "BUF")
        val later =
            game("later", state = "pre", start = "2026-09-22T17:20:00Z", away = "DAL", home = "PHI")
        val tooFar =
            game("far", state = "pre", start = "2026-09-22T17:31:00Z", away = "KC", home = "BUF")
        val justLive =
            game("live", state = "in", start = "2026-09-22T16:57:00Z", away = "NYJ", home = "NE")
        val liveTooOld =
            game("old", state = "in", start = "2026-09-22T16:54:00Z", away = "KC", home = "BUF")

        val upcoming =
            followedStartupNudge(listOf(later, soon, tooFar), setOf("nfl:kc"), now)
        assertEquals("KC", upcoming?.away)
        assertEquals("BUF", upcoming?.home)
        assertEquals(12, upcoming?.startsInMinutes)

        assertNull(followedStartupNudge(listOf(tooFar), setOf("NFL:KC"), now))
        assertNull(followedStartupNudge(listOf(soon), emptySet(), now))

        val live = followedStartupNudge(listOf(soon, justLive), setOf("NFL:KC", "NFL:NYJ"), now)
        assertEquals("NYJ", live?.away)
        assertNull(live?.startsInMinutes)

        assertNull(followedStartupNudge(listOf(liveTooOld), setOf("NFL:KC"), now))

        val boundary =
            followedStartupNudge(
                listOf(game("edge", state = "pre", start = "2026-09-22T17:30:00Z")),
                setOf("NFL:KC"),
                now,
            )
        assertEquals(30, boundary?.startsInMinutes)
    }
}
