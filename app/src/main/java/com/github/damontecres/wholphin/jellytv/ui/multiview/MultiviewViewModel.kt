package com.github.damontecres.wholphin.jellytv.ui.multiview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import com.github.damontecres.wholphin.jellytv.data.JellyTvMultiviewState
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * One video slot in multiview: a queued channel joined with the board.
 *
 * [hlsUrl] is null until the channel is on the board with a signed `hlsPath`;
 * [game] is the live game currently resolved to the channel, when known.
 */
data class MultiviewTile(
    val channelId: String,
    val name: String,
    val hlsUrl: String?,
    val game: JtvGame?,
)

/**
 * Backing model for the multiview page: the queued channels as tiles, the "swap in"
 * bench of live watchable games, and which tile currently holds the audio.
 */
@HiltViewModel
class MultiviewViewModel
    @Inject
    constructor(
        private val repository: JellyTvRepository,
        private val multiviewState: JellyTvMultiviewState,
        val navigationManager: NavigationManager,
        private val screensaverService: ScreensaverService,
    ) : ViewModel() {
        val tiles: StateFlow<List<MultiviewTile>> =
            combine(multiviewState.channelIds, repository.board) { ids, board ->
                ids.map { id ->
                    val channel = board?.channels?.firstOrNull { it.id == id }
                    val game = board?.games?.let { BoardOrganizer.gameFor(id, it) }
                    MultiviewTile(
                        channelId = id,
                        name = channel?.name ?: game?.watch?.channelName ?: "",
                        hlsUrl =
                            channel
                                ?.hlsPath
                                ?.takeIf { it.isNotBlank() }
                                ?.let(repository::absoluteUrl),
                        game = game,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /**
         * Live, watchable games not already tiled, in board order (favorites first,
         * then league, then start time), capped for the swap-in rail.
         */
        val bench: StateFlow<List<JtvGame>> =
            combine(
                repository.board,
                repository.settings,
                multiviewState.channelIds,
            ) { board, settings, ids ->
                val games = board?.games ?: emptyList()
                BoardOrganizer
                    .rows(games, settings.favorites.toSet(), onlyWatchable = true)
                    .flatMap { it.games }
                    .filter { game ->
                        val channelId = game.watch?.channelId
                        game.isLive && !channelId.isNullOrBlank() && channelId !in ids
                    }.take(MAX_BENCH)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val hideScores: StateFlow<Boolean> =
            repository.settings
                .map { it.hideScores }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** Index of the tile whose audio is unmuted; follows focus. */
        private val _audioIndex = MutableStateFlow(0)
        val audioIndex: StateFlow<Int> = _audioIndex.asStateFlow()

        init {
            repository.startPolling()
            viewModelScope.launchIO { repository.loadSettings() }
        }

        override fun onCleared() {
            screensaverService.keepScreenOn(false)
            repository.stopPolling()
        }

        /** Focus moved to [index]: audio follows focus. */
        fun focusTile(index: Int) {
            if (index in tiles.value.indices) {
                _audioIndex.value = index
            }
        }

        fun removeTile(index: Int) {
            val channelId = multiviewState.channelIds.value.getOrNull(index) ?: return
            multiviewState.remove(channelId)
            val lastIndex = multiviewState.channelIds.value.lastIndex
            _audioIndex.update { current ->
                when {
                    index < current -> current - 1
                    else -> current.coerceAtMost(lastIndex.coerceAtLeast(0))
                }
            }
        }

        /**
         * Put [game]'s channel on screen: replaces the audio tile when the queue is
         * full, otherwise appends.
         */
        fun swapIn(game: JtvGame) {
            val channelId = game.watch?.channelId ?: return
            if (multiviewState.channelIds.value.size >= JellyTvMultiviewState.MAX) {
                multiviewState.replace(_audioIndex.value, channelId)
            } else {
                multiviewState.add(channelId)
            }
        }

        fun close() = navigationManager.goBack()

        /** Keep the screen on while a tile is playing, the way upstream's playback does. */
        fun setKeepScreenOn(keep: Boolean) = screensaverService.keepScreenOn(keep)

        private companion object {
            const val MAX_BENCH = 12
        }
    }
