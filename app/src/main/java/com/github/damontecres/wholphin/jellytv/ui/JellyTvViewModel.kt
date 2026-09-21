package com.github.damontecres.wholphin.jellytv.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import com.github.damontecres.wholphin.jellytv.api.JtvChannel
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvSettings
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import com.github.damontecres.wholphin.jellytv.data.BoardRow
import com.github.damontecres.wholphin.jellytv.data.JellyTvMultiviewState
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.jellytv.ui.components.JtvTab
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * Everything the JellyTV screens need, in one state object.
 */
data class JellyTvUiState(
    val availability: JellyTvRepository.Availability = JellyTvRepository.Availability.Unknown,
    val rows: List<BoardRow> = emptyList(),
    val channels: List<JtvChannel> = emptyList(),
    val games: List<JtvGame> = emptyList(),
    val hideScores: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val onlyWatchable: Boolean = false,
    val boardError: String? = null,
    val hasBoard: Boolean = false,
    val feedErrors: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val selectedTab: JtvTab = JtvTab.GAMES,
    val multiview: List<String> = emptyList(),
)

/**
 * Drives the JellyTV section: starts/stops the board poll with the page, merges the
 * repository flows into [JellyTvUiState], and handles watch / favorite / multiview actions.
 */
@HiltViewModel
class JellyTvViewModel
    @Inject
    constructor(
        private val repository: JellyTvRepository,
        val navigationManager: NavigationManager,
        private val multiviewState: JellyTvMultiviewState,
    ) : ViewModel() {
        private data class RepositoryState(
            val availability: JellyTvRepository.Availability,
            val board: JtvBoard?,
            val boardError: String?,
            val settings: JtvSettings,
            val multiview: List<String>,
        )

        private val repositoryState =
            combine(
                repository.availability,
                repository.board,
                repository.boardError,
                repository.settings,
                multiviewState.channelIds,
                ::RepositoryState,
            )

        private val _selectedTab = MutableStateFlow(JtvTab.GAMES)

        /** The user's explicit "My channels" choice; null = not chosen, derive from the board. */
        private val _onlyWatchableChoice = MutableStateFlow<Boolean?>(null)

        val uiState: StateFlow<JellyTvUiState> =
            combine(
                repositoryState,
                _selectedTab,
                _onlyWatchableChoice,
            ) { repo, selectedTab, onlyWatchableChoice ->
                val games = repo.board?.games.orEmpty()
                val favorites = repo.settings.favorites.toSet()
                // Default on when at least one game is watchable so the board is
                // never mysteriously empty.
                val onlyWatchable = onlyWatchableChoice ?: games.any { it.watch != null }
                JellyTvUiState(
                    availability = repo.availability,
                    rows = BoardOrganizer.rows(games, favorites, onlyWatchable),
                    channels = repo.board?.channels.orEmpty(),
                    games = games,
                    hideScores = repo.settings.hideScores,
                    favorites = favorites,
                    onlyWatchable = onlyWatchable,
                    boardError = repo.boardError,
                    hasBoard = repo.board != null,
                    feedErrors = repo.board?.errors.orEmpty(),
                    loading = repo.board == null && repo.boardError == null,
                    selectedTab = selectedTab,
                    multiview = repo.multiview,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                JellyTvUiState(),
            )

        /** One-shot string resource ids surfaced as toasts. */
        private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val messages: SharedFlow<Int> = _messages.asSharedFlow()

        /** Wall clock for the top bar, ticking once a minute in the device locale. */
        val clock: StateFlow<String> =
            flow {
                while (true) {
                    emit(LocalTime.now().format(clockFormatter))
                    delay(MILLIS_PER_MINUTE - System.currentTimeMillis() % MILLIS_PER_MINUTE)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                LocalTime.now().format(clockFormatter),
            )

        init {
            repository.startPolling()
            viewModelScope.launchIO {
                if (repository.availability.value is JellyTvRepository.Availability.Unknown) {
                    repository.probe()
                }
                repository.loadSettings()
            }
        }

        override fun onCleared() {
            repository.stopPolling()
            super.onCleared()
        }

        fun selectTab(tab: JtvTab) {
            _selectedTab.value = tab
        }

        fun toggleOnlyWatchable() {
            _onlyWatchableChoice.value = !uiState.value.onlyWatchable
        }

        fun setHideScores(hide: Boolean) {
            viewModelScope.launchIO { repository.setHideScores(hide) }
        }

        fun toggleFavorite(channelId: String) {
            viewModelScope.launchIO { repository.toggleFavorite(channelId) }
        }

        fun watch(game: JtvGame) {
            val watch = game.watch ?: return
            play(watch.liveTvItemId, watch.channelId)
        }

        fun watchChannel(channel: JtvChannel) {
            play(channel.liveTvItemId, channel.id)
        }

        private fun play(
            liveTvItemId: String?,
            channelId: String,
        ) {
            val itemId = liveTvItemId?.toUUIDOrNull()
            if (itemId == null) {
                if (liveTvItemId != null) {
                    Timber.w("Unparseable JellyTV liveTvItemId: %s", liveTvItemId)
                }
                emitMessage(R.string.jtv_channel_registering)
                return
            }
            navigationManager.navigateTo(Destination.JellyTvPlayback(itemId, channelId))
            viewModelScope.launchIO { repository.setLastChannel(channelId) }
        }

        fun addToMultiview(channelId: String) {
            @StringRes val message =
                when (multiviewState.add(channelId)) {
                    JellyTvMultiviewState.AddResult.ADDED -> R.string.jtv_multiview_added
                    JellyTvMultiviewState.AddResult.ALREADY_PRESENT -> R.string.jtv_multiview_already
                    JellyTvMultiviewState.AddResult.FULL -> R.string.jtv_multiview_full
                }
            emitMessage(message)
        }

        fun removeFromMultiview(channelId: String) {
            multiviewState.remove(channelId)
        }

        fun openMultiview() {
            navigationManager.navigateTo(Destination.JellyTvMultiview)
        }

        fun absoluteUrl(path: String): String? = repository.absoluteUrl(path)

        private fun emitMessage(
            @StringRes resId: Int,
        ) {
            viewModelScope.launchDefault { _messages.emit(resId) }
        }

        private companion object {
            const val MILLIS_PER_MINUTE = 60_000L
            val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        }
    }
