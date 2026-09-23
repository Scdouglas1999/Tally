package io.github.scdouglas1999.tally.ui.home

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import io.github.scdouglas1999.tally.api.TallyBoard

/*
 * Upstream's "Watch live" home row lists every Live TV channel with a programme airing now. For a
 * Tally channel carrying a game, the server's guide fills the hours before kickoff with an
 * "Up next" block, so the row would fill with games hours before any of them start. In the Tally
 * look the row shows those channels only once their game is live; channels that are not tied to a
 * game (a 24/7 stream, an IPTV channel with its own guide) are left alone.
 */

/** Jellyfin item ids of Tally channels whose game has not started, without dashes and lowercase. */
fun pregameChannelIds(board: TallyBoard?): Set<String> {
    if (board == null) return emptySet()
    val upcoming =
        board.games
            .filter { it.isUpcoming }
            .map { it.id }
            .toSet()
    return board.channels
        .filter { it.gameId != null && it.gameId in upcoming }
        .mapNotNull { it.liveTvItemId?.let(::normalizeItemId) }
        .toSet()
}

/** The home rows with pre-game Tally channels taken out of "Watch live" rows; other rows are untouched. */
fun List<HomeRowLoadingState>.withoutPregameChannels(pregame: Set<String>): List<HomeRowLoadingState> {
    if (pregame.isEmpty()) return this
    return map { row ->
        if (row is HomeRowLoadingState.Success && row.rowType is HomeRowConfig.TvPrograms) {
            row.copy(
                items =
                    row.items.filterNot { item ->
                        item
                            ?.data
                            ?.channelId
                            ?.toString()
                            ?.let(::normalizeItemId) in pregame
                    },
            )
        } else {
            row
        }
    }
}

internal fun normalizeItemId(id: String): String = id.replace("-", "").lowercase()
