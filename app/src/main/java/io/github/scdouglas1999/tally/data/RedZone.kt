package io.github.scdouglas1999.tally.data

import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyRedZone

/**
 * The RedZone tile on the Games board: the server's RedZone [channel], what its [status] says is on, and that game
 * from the board when the board has it.
 */
data class RedZoneTile(
    val channel: TallyChannel,
    val status: TallyRedZone,
    val game: TallyGame?,
)

/**
 * The server's RedZone channel (a board channel of kind `redzone`, fixed id [ID]): one stream that cuts to the hottest
 * game. Pure logic; a server that predates it has no such channel and every answer here is "none".
 */
object RedZone {
    /** The RedZone channel's fixed id. */
    const val ID = "redzone"

    /** How often a Games board asks what RedZone shows, for its tile (the board's own pace). */
    const val BOARD_POLL_MS = 15_000L

    /** How often the player asks what RedZone shows, while it plays RedZone. */
    const val PLAYER_POLL_MS = 10_000L

    /** The board's RedZone channel, or null. */
    fun channel(board: TallyBoard?): TallyChannel? = board?.channels?.firstOrNull { it.isRedZone }

    /** True when [channelId] is the RedZone channel. */
    fun isRedZone(
        channelId: String?,
        board: TallyBoard?,
    ): Boolean = channelId != null && (channelId == ID || channelId == channel(board)?.id)

    /**
     * The tile to show, or null: only when the board lists a RedZone channel and its [status] says it is [active]
     * ([TallyRedZone.active]); a status that could not be read (null) hides it.
     */
    fun tile(
        board: TallyBoard?,
        status: TallyRedZone?,
    ): RedZoneTile? {
        val channel = channel(board) ?: return null
        if (status?.active != true) return null
        return RedZoneTile(
            channel = channel,
            status = status,
            game = status.gameId?.let { id -> board?.games?.firstOrNull { it.id == id } },
        )
    }

    /** The board row the tile leads: the first live row; -1 when no row is live. */
    fun rowIndex(rows: List<BoardRow>): Int = rows.indexOfFirst { it.state == "in" }

    /** The game RedZone is on, from the board. */
    fun gameOn(
        status: TallyRedZone?,
        games: List<TallyGame>,
    ): TallyGame? = status?.takeIf { it.active }?.gameId?.let { id -> games.firstOrNull { it.id == id } }
}
