package io.github.scdouglas1999.tally.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlayerFactory
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.api.TallyEvent
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.TallyMultiviewState
import io.github.scdouglas1999.tally.data.TallyRepository
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Backs the Tally overlays on top of the unmodified upstream player: the score bug
 * for the bound channel's game, the "also on now" game switcher, and transient event
 * banners. The page calls [bind] once with the channel id from its destination.
 *
 * Board polling is ref-counted by [TallyRepository]: starts with this view model and
 * stops in [onCleared].
 */
@HiltViewModel
class TallyPlayerViewModel
    @Inject
    constructor(
        private val repository: TallyRepository,
        private val multiviewState: TallyMultiviewState,
        private val navigationManager: NavigationManager,
        private val playerFactory: PlayerFactory,
    ) : ViewModel() {
        private val channelId = MutableStateFlow<String?>(null)

        init {
            // Buffer health of the live stream, for the log: how far behind the edge we sit and how much is
            // ready to play. When someone reports a stall, this is the first thing to look at.
            viewModelScope.launch {
                while (true) {
                    delay(HEALTH_LOG_MS)
                    withContext(Dispatchers.Main) {
                        val player = playerFactory.currentPlayer ?: return@withContext
                        if (player.isCurrentMediaItemLive) {
                            // Jellyfin's live playlists carry no PROGRAM-DATE-TIME, so currentLiveOffset is unset;
                            // the distance to the end of the live window is the same thing.
                            Timber.d(
                                "Live health: %.1fs behind edge, %.1fs buffered, state %d",
                                (player.duration - player.currentPosition) / 1000.0,
                                (player.bufferedPosition - player.currentPosition) / 1000.0,
                                player.playbackState,
                            )
                        }
                    }
                }
            }
        }

        /** The live game carried on the bound channel, if the board shows one. */
        val game: StateFlow<TallyGame?> =
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

        val favoriteTeams: StateFlow<Set<String>> =
            repository.settings
                .map { settings -> settings.favoriteTeams.map { it.uppercase() }.toSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        /**
         * Other live games on real channels (excludes the bound channel), flattened in
         * [BoardOrganizer.rows] order — followed teams and favorite channels first, then
         * league and start — capped at [MAX_OTHERS].
         */
        val others: StateFlow<List<TallyGame>> =
            combine(channelId, repository.board, repository.settings) { id, board, settings ->
                BoardOrganizer
                    .rows(
                        games =
                            board?.games.orEmpty().filter {
                                it.isLive && it.watch != null && it.watch?.channelId != id
                            },
                        favoriteChannelIds = settings.favorites.toSet(),
                        onlyWatchable = true,
                        favoriteTeams = settings.favoriteTeams.map { it.uppercase() }.toSet(),
                    ).flatMap { it.games }
                    .take(MAX_OTHERS)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val _banner = MutableStateFlow<TallyEvent?>(null)

        /** Latest event for a game other than the current one; auto-clears after [BANNER_MS]. */
        val banner: StateFlow<TallyEvent?> = _banner.asStateFlow()

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
        fun switchTo(game: TallyGame) {
            val watch = game.watch ?: return
            val itemId = watch.liveTvItemId?.toUUIDOrNull()
            if (itemId == null) {
                // The channel exists but its Live TV item has not been resolved yet.
                _messages.tryEmit(R.string.tally_player_channel_pending)
                return
            }
            navigationManager.backStack.removeLastOrNull()
            navigationManager.navigateTo(Destination.TallyPlayback(itemId, watch.channelId))
        }

        /** Asks the corner overlay (owned elsewhere) to show [game]. */
        fun watchInCorner(game: TallyGame) {
            CornerRequests.request(game)
        }

        fun toggleFollow(teamKey: String) {
            viewModelScope.launchIO { repository.toggleFavoriteTeam(teamKey) }
        }

        fun toggleHideScores() {
            viewModelScope.launchIO { repository.setHideScores(!hideScores.value) }
        }

        fun addToMultiview(game: TallyGame) {
            val channelId = game.watch?.channelId ?: return
            val message =
                when (multiviewState.add(channelId)) {
                    TallyMultiviewState.AddResult.ADDED -> R.string.tally_player_added_to_multiview
                    TallyMultiviewState.AddResult.ALREADY_PRESENT -> R.string.tally_player_already_in_multiview
                    TallyMultiviewState.AddResult.FULL -> R.string.tally_player_multiview_full
                }
            _messages.tryEmit(message)
        }

        private fun onEvent(event: TallyEvent) {
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
            const val HEALTH_LOG_MS = 10_000L
            const val MAX_OTHERS = 12
            const val BANNER_MS = 8_000L
        }
    }
