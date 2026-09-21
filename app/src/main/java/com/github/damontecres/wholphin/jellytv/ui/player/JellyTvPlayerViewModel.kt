package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvEvent
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import com.github.damontecres.wholphin.jellytv.data.JellyTvMultiviewState
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import javax.inject.Inject

/**
 * Backs the JellyTV overlays on top of the unmodified upstream player: the score bug
 * for the bound channel's game, the "also on now" game switcher, and transient event
 * banners. The page calls [bind] once with the channel id from its destination.
 *
 * Board polling is ref-counted by [JellyTvRepository]: starts with this view model and
 * stops in [onCleared].
 */
@HiltViewModel
class JellyTvPlayerViewModel
    @Inject
    constructor(
        private val repository: JellyTvRepository,
        private val multiviewState: JellyTvMultiviewState,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val channelId = MutableStateFlow<String?>(null)

        /** The live game carried on the bound channel, if the board shows one. */
        val game: StateFlow<JtvGame?> =
            combine(channelId, repository.board) { id, board ->
                id?.let { BoardOrganizer.gameFor(it, board?.games.orEmpty()) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val hideScores: StateFlow<Boolean> =
            repository.settings
                .map { it.hideScores }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        val favorites: StateFlow<Set<String>> =
            repository.settings
                .map { it.favorites.toSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        /**
         * Other live games on real channels (excludes the bound channel), flattened in
         * [BoardOrganizer.rows] order — favorites/league/start — capped at [MAX_OTHERS].
         */
        val others: StateFlow<List<JtvGame>> =
            combine(channelId, repository.board, favorites) { id, board, favs ->
                BoardOrganizer
                    .rows(
                        games =
                            board?.games.orEmpty().filter {
                                it.isLive && it.watch != null && it.watch?.channelId != id
                            },
                        favoriteChannelIds = favs,
                        onlyWatchable = true,
                    ).flatMap { it.games }
                    .take(MAX_OTHERS)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val _banner = MutableStateFlow<JtvEvent?>(null)

        /** Latest event for a game other than the current one; auto-clears after [BANNER_MS]. */
        val banner: StateFlow<JtvEvent?> = _banner.asStateFlow()

        /** One-shot messages (string resource ids) for the page to show as toasts. */
        private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val messages: SharedFlow<Int> = _messages.asSharedFlow()

        private var bannerJob: Job? = null

        init {
            repository.startPolling()
            viewModelScope.launch { repository.loadSettings() }
            viewModelScope.launch {
                repository.events.collect(::onEvent)
            }
            viewModelScope.launch {
                hideScores.collect { hidden -> if (hidden) clearBanner() }
            }
        }

        /** Sets the channel this page is playing; idempotent. */
        fun bind(channelId: String) {
            if (this.channelId.value != channelId) {
                this.channelId.value = channelId
            }
        }

        /**
         * Replaces the current playback destination with the channel carrying [game],
         * so players never stack.
         */
        fun switchTo(game: JtvGame) {
            val watch = game.watch ?: return
            val itemId = watch.liveTvItemId?.toUUIDOrNull()
            if (itemId == null) {
                // The channel exists but its Live TV item has not been resolved yet.
                _messages.tryEmit(R.string.jtv_player_channel_pending)
                return
            }
            navigationManager.backStack.removeLastOrNull()
            navigationManager.navigateTo(Destination.JellyTvPlayback(itemId, watch.channelId))
        }

        fun addToMultiview(game: JtvGame) {
            val channelId = game.watch?.channelId ?: return
            val message =
                when (multiviewState.add(channelId)) {
                    JellyTvMultiviewState.AddResult.ADDED -> R.string.jtv_player_added_to_multiview
                    JellyTvMultiviewState.AddResult.ALREADY_PRESENT -> R.string.jtv_player_already_in_multiview
                    JellyTvMultiviewState.AddResult.FULL -> R.string.jtv_player_multiview_full
                }
            _messages.tryEmit(message)
        }

        private fun onEvent(event: JtvEvent) {
            if (event.watch == null || event.gameId == game.value?.id || hideScores.value) return
            _banner.value = event
            bannerJob?.cancel()
            bannerJob =
                viewModelScope.launch {
                    delay(BANNER_MS)
                    _banner.value = null
                }
        }

        private fun clearBanner() {
            bannerJob?.cancel()
            _banner.value = null
        }

        override fun onCleared() {
            repository.stopPolling()
            super.onCleared()
        }

        private companion object {
            const val MAX_OTHERS = 12
            const val BANNER_MS = 8_000L
        }
    }
