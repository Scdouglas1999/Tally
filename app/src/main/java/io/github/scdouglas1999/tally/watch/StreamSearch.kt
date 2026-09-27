package io.github.scdouglas1999.tally.watch

import io.github.scdouglas1999.tally.api.TallyFindResult
import io.github.scdouglas1999.tally.api.TallyWatch
import kotlinx.coroutines.delay

/** What `find` answered, as the search loop uses it. */
sealed interface FindReply {
    /** The game has a stream: play [watch]. */
    data class Found(
        val watch: TallyWatch,
    ) : FindReply

    /** A search that covers the game is running on the server. */
    data object Searching : FindReply

    /** A search for the game finished within the last minute and found nothing. */
    data object None : FindReply

    /** The server has no `find` (404, a plugin from before 2.2.1): the board alone says whether a stream appeared. */
    data object Unsupported : FindReply

    /** The call failed (network, 5xx): try again on the next tick. */
    data object Failed : FindReply

    companion object {
        fun of(result: TallyFindResult): FindReply =
            when {
                result.watch != null -> Found(result.watch)
                result.state == "searching" -> Searching
                else -> None
            }
    }
}

/**
 * One round of looking for a game's stream: `find`, then again every [POLL_MS] for up to [ROUND_MS], until a stream
 * appears. Pure logic: the calls come in as functions, time as [now] (virtual in tests).
 *
 * - `found` ends the round with the stream.
 * - `searching` (or a failed call) keeps polling.
 * - `none`, or no `find` on the server (404): the board is fetched once, and the game's stream there wins. Otherwise
 *   the first round ends at once (the server just looked and found nothing); a "Keep looking" round
 *   ([keepLooking]) keeps polling `find` and the board until its time is up, since the server's own schedule may
 *   still turn one up.
 *
 * Returns the stream, or null when there is none yet.
 */
class StreamSearch(
    private val find: suspend () -> FindReply,
    private val boardWatch: suspend () -> TallyWatch?,
    private val now: () -> Long,
) {
    suspend fun round(keepLooking: Boolean): TallyWatch? {
        val deadline = now() + ROUND_MS
        while (true) {
            when (val reply = find()) {
                is FindReply.Found -> {
                    return reply.watch
                }

                FindReply.Searching -> {}

                FindReply.None, FindReply.Unsupported -> {
                    boardWatch()?.let { return it }
                    if (!keepLooking) return null
                }

                FindReply.Failed -> {
                    boardWatch()?.let { return it }
                }
            }
            if (now() + POLL_MS > deadline) break
            delay(POLL_MS)
        }
        // Time is up: the board has the last word (a stream the regular schedule found while this round polled).
        return boardWatch()
    }

    companion object {
        /** How often a round asks again, as the server contract says. */
        const val POLL_MS = 3_000L

        /** How long a round looks before it says there is no stream yet. */
        const val ROUND_MS = 45_000L
    }
}
