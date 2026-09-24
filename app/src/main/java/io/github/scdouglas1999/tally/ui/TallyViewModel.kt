package io.github.scdouglas1999.tally.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallySettings
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.BoardRow
import io.github.scdouglas1999.tally.data.TallyMultiviewState
import io.github.scdouglas1999.tally.data.TallyRepository
import io.github.scdouglas1999.tally.ui.components.TallyTab
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
 * Everything the Tally screens need, in one state object.
 */
data class TallyUiState(
    val availability: TallyRepository.Availability = TallyRepository.Availability.Unknown,
    val rows: List<BoardRow> = emptyList(),
    val channels: List<TallyChannel> = emptyList(),
    val games: List<TallyGame> = emptyList(),
    val hideScores: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val favoriteTeams: Set<String> = emptySet(),
    val onlyWatchable: Boolean = false,
    val boardError: String? = null,
    val hasBoard: Boolean = false,
    val feedErrors: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val selectedTab: TallyTab = TallyTab.GAMES,
    val multiview: List<String> = emptyList(),
) {
    /** The server records games (the plugin's `dvr` feature): Sports shows RECORDINGS. */
    val hasDvr: Boolean
        get() = (availability as? TallyRepository.Availability.Available)?.info?.features?.contains("dvr") == true
}

/**
 * Drives the Tally section: starts/stops the board poll with the page, merges the
 * repository flows into [TallyUiState], and handles watch / favorite / multiview actions.
 */
@HiltViewModel
class TallyViewModel
    @Inject
    constructor(
        private val repository: TallyRepository,
        val navigationManager: NavigationManager,
        private val multiviewState: TallyMultiviewState,
    ) : ViewModel() {
        private data class RepositoryState(
            val availability: TallyRepository.Availability,
            val board: TallyBoard?,
            val boardError: String?,
            val settings: TallySettings,
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

        private val selectedTabState = MutableStateFlow(TallyTab.GAMES)

        /** The user's explicit "My channels" choice; null = not chosen, derive from the board. */
        private val onlyWatchableChoice = MutableStateFlow<Boolean?>(null)

        val uiState: StateFlow<TallyUiState> =
            combine(
                repositoryState,
                selectedTabState,
                onlyWatchableChoice,
            ) { repo, selectedTab, onlyWatchableChoice ->
                val games = repo.board?.games.orEmpty()
                val favorites = repo.settings.favorites.toSet()
                val teams =
                    repo.settings.favoriteTeams
                        .map { it.uppercase() }
                        .toSet()
                // Default on when at least one game is watchable so the board is
                // never mysteriously empty.
                val onlyWatchable = onlyWatchableChoice ?: repo.settings.onlyWatchable ?: games.any { it.watch != null }
                TallyUiState(
                    availability = repo.availability,
                    rows = BoardOrganizer.rows(games, favorites, onlyWatchable, teams),
                    channels = repo.board?.channels.orEmpty(),
                    games = games,
                    hideScores = repo.settings.hideScores,
                    favorites = favorites,
                    favoriteTeams = teams,
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
                TallyUiState(),
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
                if (repository.availability.value is TallyRepository.Availability.Unknown) {
                    repository.probe()
                }
                repository.loadSettings()
            }
        }

        override fun onCleared() {
            repository.stopPolling()
            super.onCleared()
        }

        fun selectTab(tab: TallyTab) {
            selectedTabState.value = tab
        }

        fun toggleOnlyWatchable() {
            val only = !uiState.value.onlyWatchable
            onlyWatchableChoice.value = only
            viewModelScope.launchIO { repository.setOnlyWatchable(only) }
        }

        fun setHideScores(hide: Boolean) {
            viewModelScope.launchIO { repository.setHideScores(hide) }
        }

        fun toggleFavorite(channelId: String) {
            viewModelScope.launchIO { repository.toggleFavorite(channelId) }
        }

        fun toggleFollow(teamKey: String) {
            viewModelScope.launchIO { repository.toggleFavoriteTeam(teamKey) }
        }

        fun watch(game: TallyGame) {
            val watch = game.watch ?: return
            play(watch.liveTvItemId, watch.channelId)
        }

        fun watchChannel(channel: TallyChannel) {
            play(channel.liveTvItemId, channel.id)
        }

        private fun play(
            liveTvItemId: String?,
            channelId: String,
        ) {
            val itemId = liveTvItemId?.toUUIDOrNull()
            if (itemId == null) {
                if (liveTvItemId != null) {
                    Timber.w("Unparseable Tally liveTvItemId: %s", liveTvItemId)
                }
                emitMessage(R.string.tally_channel_registering)
                return
            }
            navigationManager.navigateTo(Destination.TallyPlayback(itemId, channelId))
            viewModelScope.launchIO { repository.setLastChannel(channelId) }
        }

        fun addToMultiview(channelId: String) {
            @StringRes val message =
                when (multiviewState.add(channelId)) {
                    TallyMultiviewState.AddResult.ADDED -> R.string.tally_multiview_added
                    TallyMultiviewState.AddResult.ALREADY_PRESENT -> R.string.tally_multiview_already
                    TallyMultiviewState.AddResult.FULL -> R.string.tally_multiview_full
                }
            emitMessage(message)
        }

        fun removeFromMultiview(channelId: String) {
            multiviewState.remove(channelId)
        }

        fun openMultiview() {
            navigationManager.navigateTo(Destination.TallyMultiview)
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
