package io.github.scdouglas1999.tally.watch

import android.content.Context
import android.os.SystemClock
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyException
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.api.TallyWatch
import io.github.scdouglas1999.tally.data.TallyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WATCH, everywhere a game or channel is played from (the board, the home row and hero, the game menu and sheet, the
 * player's switcher, multiview). Never blocks a game that is not final:
 *
 * - a game with a stream plays at once: in the Tally player when its Live TV item is ready, otherwise from the
 *   stream's signed HLS playlist ([TallyLiveStream]), so a channel Jellyfin has not registered yet still plays;
 * - a game without one asks the server to look for it (`find`) and shows "Looking for a stream…" ([state], drawn by
 *   [FindStreamHost]) until the stream appears and plays, BACK or Cancel, or the round ends with "No stream for
 *   this game yet" and "Keep looking" / "OK".
 *
 * Multiview and the switcher still need a stream to tile or switch to: they pass their own action to
 * [whenStreamFound].
 */
@Singleton
class TallyWatchLauncher
    @Inject
    constructor(
        private val repository: TallyRepository,
        private val navigationManager: NavigationManager,
        @param:ApplicationContext private val context: Context,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        /** What the find dialog shows; null when it is closed. */
        sealed interface State {
            val game: TallyGame

            data class Searching(
                override val game: TallyGame,
            ) : State

            data class NoStream(
                override val game: TallyGame,
            ) : State
        }

        private val _state = MutableStateFlow<State?>(null)
        val state: StateFlow<State?> = _state.asStateFlow()

        private var job: Job? = null
        private var onFound: ((TallyWatch) -> Boolean)? = null

        /**
         * WATCH on [game]. [replaceCurrent] swaps the page on top (the player, when switching games) out for the new
         * player instead of stacking one on it. A final game without a stream has nothing to watch.
         */
        fun watch(
            game: TallyGame,
            replaceCurrent: Boolean = false,
        ) {
            val watch = game.watch
            if (watch != null && play(watch, title(game, watch), replaceCurrent)) return
            if (game.isFinal) return
            whenStreamFound(game) { found -> play(found, title(game, found), replaceCurrent) }
        }

        /** A channel from the channel grid: the Tally player, or its HLS playlist while its Live TV item is not ready. */
        fun watchChannel(channel: TallyChannel): Boolean =
            play(
                TallyWatch(
                    channelId = channel.id,
                    channelName = channel.name,
                    liveTvItemId = channel.liveTvItemId,
                    hlsPath = channel.hlsPath,
                ),
                title = channel.name,
            )

        /**
         * Looks for [game]'s stream with the find dialog up and runs [action] once there is one. [action] answers
         * whether it could use the stream; when it could not, the dialog says there is none yet.
         */
        fun whenStreamFound(
            game: TallyGame,
            action: (TallyWatch) -> Boolean,
        ) {
            job?.cancel()
            onFound = action
            _state.value = State.Searching(game)
            startRound(game, keepLooking = false)
        }

        /** "Keep looking": another round for the game the dialog shows. */
        fun keepLooking() {
            val current = _state.value as? State.NoStream ?: return
            job?.cancel()
            _state.value = State.Searching(current.game)
            startRound(current.game, keepLooking = true)
        }

        /** Cancel, OK or BACK: the dialog closes and nothing plays. */
        fun cancel() {
            job?.cancel()
            job = null
            onFound = null
            _state.value = null
        }

        /**
         * Plays [watch]: the Tally player when its Live TV item is ready, else its HLS playlist. False when it has
         * neither (nothing was opened).
         */
        fun play(
            watch: TallyWatch,
            title: String,
            replaceCurrent: Boolean = false,
        ): Boolean {
            val itemId = watch.liveTvItemId?.toUUIDOrNull()
            if (itemId == null && !watch.liveTvItemId.isNullOrBlank()) {
                Timber.w("Unparseable Tally liveTvItemId: %s", watch.liveTvItemId)
            }
            val destination =
                when {
                    itemId != null -> Destination.TallyPlayback(itemId, watch.channelId)
                    watch.hlsPath.isNotBlank() -> TallyLiveStream.destination(watch.hlsPath, title)
                    else -> return false
                }
            if (replaceCurrent) navigationManager.backStack.removeLastOrNull()
            navigationManager.navigateTo(destination)
            if (watch.channelId.isNotBlank()) {
                scope.launch {
                    try {
                        repository.setLastChannel(watch.channelId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Could not save the last channel")
                    }
                }
            }
            return true
        }

        private fun startRound(
            game: TallyGame,
            keepLooking: Boolean,
        ) {
            val search =
                StreamSearch(
                    find = { find(game.id) },
                    boardWatch = {
                        repository.refreshNow()
                        repository.board.value
                            ?.games
                            ?.firstOrNull { it.id == game.id }
                            ?.watch
                    },
                    now = SystemClock::elapsedRealtime,
                )
            // On the main thread, as cancel() is: a round canceled there never publishes afterwards (the calls
            // themselves run on the IO dispatcher).
            val startedAt = SystemClock.elapsedRealtime()
            job =
                scope.launch(WholphinDispatchers.Main) {
                    val watch = search.round(keepLooking)
                    val used = watch != null && onFound?.invoke(watch) == true
                    if (used) {
                        onFound = null
                        _state.value = null
                    } else {
                        // "Looking" stays up long enough to be read: the answer is often at once.
                        delay(MIN_SEARCHING_MS - (SystemClock.elapsedRealtime() - startedAt))
                        _state.value = State.NoStream(game)
                    }
                }
        }

        private suspend fun find(gameId: String): FindReply =
            try {
                FindReply.of(repository.find(gameId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TallyException.NotInstalled) {
                FindReply.Unsupported
            } catch (e: Exception) {
                Timber.w(e, "Find a stream failed for game %s", gameId)
                FindReply.Failed
            }

        /** "Rays at Phillies"; the channel's name for a channel with no game on it. */
        private fun title(
            game: TallyGame,
            watch: TallyWatch,
        ): String {
            val away = game.away.titleName()
            val home = game.home.titleName()
            return if (away.isBlank() || home.isBlank()) {
                watch.channelName.ifBlank { game.name }
            } else {
                context.getString(R.string.tally_actions_at, away, home)
            }
        }

        private fun TallyTeam.titleName(): String = shortName.ifBlank { abbr.ifBlank { name } }

        private companion object {
            /** The least time "Looking for a stream…" is shown before "No stream yet", so the panel does not flash. */
            const val MIN_SEARCHING_MS = 1_500L
        }
    }

/**
 * A live channel played from its signed HLS playlist on the start-over page's own player (at the live edge, with a
 * LIVE kicker instead of the recording's): what WATCH opens while a stream's Live TV item is not ready yet. Jellyfin's
 * player needs that item, so it cannot play the playlist itself.
 */
object TallyLiveStream {
    /** The start-over destination's job id for a live channel (no DVR job). */
    const val JOB_ID = "tally-live"

    fun destination(
        path: String,
        title: String,
    ): Destination.TallyStartOver = Destination.TallyStartOver(jobId = JOB_ID, path = path, title = title)

    fun isLive(destination: Destination.TallyStartOver): Boolean = destination.jobId == JOB_ID
}
