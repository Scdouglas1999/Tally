package com.github.damontecres.wholphin.jellytv.ui.components

import com.github.damontecres.wholphin.jellytv.api.JtvChannel
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvTeam
import com.github.damontecres.wholphin.jellytv.api.JtvWatch

/**
 * Hard-coded sample data for previews. Mirrors app/src/test/resources/jellytv/board-sample.json.
 */
object JtvSamples {
    /** Live NFL game, watchable on a resolved channel. */
    val liveFootball =
        JtvGame(
            id = "401872945",
            sport = "football",
            league = "NFL",
            name = "IND @ KC",
            start = "2026-09-21T00:20:00+00:00",
            state = "in",
            detail = "8:25 - 1st",
            period = 1,
            clock = "8:25",
            home =
                JtvTeam(
                    id = "12",
                    abbr = "KC",
                    name = "Kansas City Chiefs",
                    shortName = "Chiefs",
                    location = "Kansas City",
                    logo = "https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/kc.png",
                    score = 0,
                    record = "1-1",
                ),
            away =
                JtvTeam(
                    id = "11",
                    abbr = "IND",
                    name = "Indianapolis Colts",
                    shortName = "Colts",
                    location = "Indianapolis",
                    logo = "https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/ind.png",
                    score = 7,
                    record = "2-0",
                    possession = true,
                ),
            lastPlay = "(Shotgun) D.Jones pass short right to M.Pittman to KC 41 for 9 yards.",
            lastPlayType = "Pass Reception",
            downDistance = "2nd & 1 at KC 41",
            broadcasts = listOf("NBC", "Peacock"),
            watch =
                JtvWatch(
                    channelId = "dea2bdfac2d6739c",
                    channelName = "Indianapolis Colts Kansas City Chiefs",
                    liveTvItemId = "1fcfacdbc1fa4309f9ac372a218380ed",
                    hlsPath = "/JellyTV/Live/dea2bdfac2d6739c.m3u8?s=95c3",
                    cardPath = "/JellyTV/Card/4cdecdcf9b58eec4.png?v=x",
                    confidence = "teams",
                ),
        )

    /** Live MLB game with count/outs/bases. Not watchable: exercises the no-channel path. */
    val liveBaseball =
        JtvGame(
            id = "401817017",
            sport = "baseball",
            league = "MLB",
            name = "PHI @ NYM",
            start = "2026-09-20T23:10:00+00:00",
            state = "in",
            detail = "Top 8th",
            period = 8,
            clock = "0:00",
            home =
                JtvTeam(
                    id = "21",
                    abbr = "NYM",
                    name = "New York Mets",
                    shortName = "Mets",
                    location = "New York",
                    logo = "https://a.espncdn.com/i/teamlogos/mlb/500/scoreboard/nym.png",
                    score = 2,
                    record = "71-84",
                ),
            away =
                JtvTeam(
                    id = "22",
                    abbr = "PHI",
                    name = "Philadelphia Phillies",
                    shortName = "Phillies",
                    location = "Philadelphia",
                    logo = "https://a.espncdn.com/i/teamlogos/mlb/500/scoreboard/phi.png",
                    score = 4,
                    record = "85-70",
                ),
            lastPlay = "Pitch 4 : Ball 3",
            lastPlayType = "Ball",
            balls = 3,
            strikes = 1,
            outs = 1,
            onFirst = true,
            onSecond = true,
            onThird = false,
            broadcasts = listOf("MLB.TV", "WPIX"),
            watch = null,
        )

    /** Upcoming NFL game resolved to a network channel. */
    val upcoming =
        JtvGame(
            id = "401872950",
            sport = "football",
            league = "NFL",
            name = "DET @ GB",
            start = "2026-09-22T00:15:00+00:00",
            state = "pre",
            detail = "9/21 - 8:15 PM EDT",
            home =
                JtvTeam(
                    id = "9",
                    abbr = "GB",
                    name = "Green Bay Packers",
                    shortName = "Packers",
                    location = "Green Bay",
                    logo = "https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/gb.png",
                    record = "1-1",
                ),
            away =
                JtvTeam(
                    id = "8",
                    abbr = "DET",
                    name = "Detroit Lions",
                    shortName = "Lions",
                    location = "Detroit",
                    logo = "https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/det.png",
                    record = "2-0",
                ),
            broadcasts = listOf("ESPN", "ABC"),
            watch =
                JtvWatch(
                    channelId = "aa11bb22cc33dd44",
                    channelName = "ESPN",
                    hlsPath = "/JellyTV/Live/aa11bb22cc33dd44.m3u8?s=00ff",
                    cardPath = "/JellyTV/Card/9f8e7d6c5b4a3921.png?v=x",
                    confidence = "network",
                ),
        )

    /** Final game; the loser renders muted. */
    val final =
        JtvGame(
            id = "401872941",
            sport = "football",
            league = "NFL",
            name = "DAL @ PHI",
            start = "2026-09-20T20:20:00+00:00",
            state = "post",
            detail = "Final",
            home =
                JtvTeam(
                    id = "7",
                    abbr = "PHI",
                    name = "Philadelphia Eagles",
                    shortName = "Eagles",
                    location = "Philadelphia",
                    logo = "https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/phi.png",
                    score = 24,
                    record = "2-0",
                    winner = true,
                ),
            away =
                JtvTeam(
                    id = "6",
                    abbr = "DAL",
                    name = "Dallas Cowboys",
                    shortName = "Cowboys",
                    location = "Dallas",
                    logo = "https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/dal.png",
                    score = 17,
                    record = "1-1",
                ),
            broadcasts = listOf("FOX"),
            watch =
                JtvWatch(
                    channelId = "bb22cc33dd44ee55",
                    channelName = "FOX",
                    hlsPath = "/JellyTV/Live/bb22cc33dd44ee55.m3u8?s=11aa",
                    cardPath = "/JellyTV/Card/1a2b3c4d5e6f7081.png?v=x",
                    confidence = "network",
                ),
        )

    /** A board-like list in board order: favorites/live first, then upcoming, then final. */
    val games = listOf(liveFootball, liveBaseball, upcoming, final)

    val channels =
        listOf(
            JtvChannel(
                id = "dea2bdfac2d6739c",
                name = "Indianapolis Colts Kansas City Chiefs",
                group = "NFL",
                liveTvItemId = "1fcfacdbc1fa4309f9ac372a218380ed",
                hlsPath = "/JellyTV/Live/dea2bdfac2d6739c.m3u8?s=95c3",
                cardPath = "/JellyTV/Card/4cdecdcf9b58eec4.png?v=x",
                gameId = "401872945",
            ),
            JtvChannel(
                id = "aa11bb22cc33dd44",
                name = "ESPN",
                group = "US Sports",
                logo = "https://example.invalid/espn.png",
                hlsPath = "/JellyTV/Live/aa11bb22cc33dd44.m3u8?s=00ff",
                cardPath = "/JellyTV/Card/9f8e7d6c5b4a3921.png?v=x",
            ),
        )
}
