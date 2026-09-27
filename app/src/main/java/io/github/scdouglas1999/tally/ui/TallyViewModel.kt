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
import io.github.scdouglas1999.tally.api.TallyLanguage
import io.github.scdouglas1999.tally.api.TallyRedZone
import io.github.scdouglas1999.tally.api.TallySettings
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.BoardRow
import io.github.scdouglas1999.tally.data.RedZone
import io.github.scdouglas1999.tally.data.RedZoneTile
import io.github.scdouglas1999.tally.data.TallyMultiviewState
import io.github.scdouglas1999.tally.data.TallyRepository
import io.github.scdouglas1999.tally.ui.components.TallyTab
import io.github.scdouglas1999.tally.watch.TallyWatchLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** A RedZone status answer; [known] is false until the server has answered once (the status may still be null). */
internal data class RedZoneAnswer(
    val known: Boolean,
    val status: TallyRedZone?,
)

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
    /** The commentary WATCH prefers: "en" or "es". */
    val streamLanguage: String = TallyLanguage.ENGLISH,
    /** The RedZone channel's tile, first in the first live row; null when the server has none or it is not on. */
    val redZone: RedZoneTile? = null,
    /**
     * Whether [redZone] is settled: the board has no RedZone channel, or the server has answered what it shows. The
     * board waits for it (briefly) before placing focus, so the tile is in its row when focus lands.
     */
    val redZoneKnown: Boolean = false,
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
        private val watchLauncher: TallyWatchLauncher,
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

        /** The user's explicit "Only games with a stream" choice this session; null = the saved one (off when unset). */
        private val onlyWatchableChoice = MutableStateFlow<Boolean?>(null)

        /** The last RedZone answer ([RedZoneAnswer.known] once one came back): a board shown again starts from it. */
        @Volatile
        private var lastRedZone = RedZoneAnswer(known = false, status = null)

        /**
         * What the RedZone channel shows, asked at the board's pace while a board screen collects [uiState] and the
         * board lists a RedZone channel; never asked on a server without one (then it is known at once: no tile).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val redZoneStatus: Flow<RedZoneAnswer> =
            repository.board
                .map { board -> if (board == null) null else RedZone.channel(board) != null }
                .distinctUntilChanged()
                .flatMapLatest { hasChannel ->
                    when (hasChannel) {
                        null -> {
                            flowOf(lastRedZone)
                        }

                        false -> {
                            flowOf(RedZoneAnswer(known = true, status = null))
                        }

                        true -> {
                            flow {
                                emit(lastRedZone)
                                while (true) {
                                    val answer = RedZoneAnswer(known = true, status = repository.redZone())
                                    lastRedZone = answer
                                    emit(answer)
                                    delay(RedZone.BOARD_POLL_MS)
                                }
                            }
                        }
                    }
                }.onStart { emit(lastRedZone) }

        val uiState: StateFlow<TallyUiState> =
            combine(
                repositoryState,
                selectedTabState,
                onlyWatchableChoice,
                redZoneStatus,
            ) { repo, selectedTab, onlyWatchableChoice, redZone ->
                val games = repo.board?.games.orEmpty()
                val favorites = repo.settings.favorites.toSet()
                val teams =
                    repo.settings.favoriteTeams
                        .map { it.uppercase() }
                        .toSet()
                // Off until the viewer turns it on: every game shows, the ones without a stream labeled so.
                val onlyWatchable = onlyWatchableChoice ?: repo.settings.onlyWatchable ?: false
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
                    streamLanguage = repo.settings.streamLanguage ?: TallyLanguage.ENGLISH,
                    redZone = RedZone.tile(repo.board, redZone.status),
                    redZoneKnown = redZone.known,
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

        /** The commentary WATCH prefers; the board is fetched again so every game's WATCH follows it. */
        fun setStreamLanguage(language: String) {
            viewModelScope.launchIO { repository.setStreamLanguage(language) }
        }

        fun toggleFavorite(channelId: String) {
            viewModelScope.launchIO { repository.toggleFavorite(channelId) }
        }

        fun toggleFollow(teamKey: String) {
            viewModelScope.launchIO { repository.toggleFavoriteTeam(teamKey) }
        }

        /** WATCH: plays the game's stream, or looks for one first (see [TallyWatchLauncher]). */
        fun watch(game: TallyGame) = watchLauncher.watch(game)

        fun watchChannel(channel: TallyChannel) {
            // A channel always has a playlist; one with neither it nor a Live TV item is still being set up.
            if (!watchLauncher.watchChannel(channel)) emitMessage(R.string.tally_channel_registering)
        }

        /** Multiview needs a stream: a game without one looks for it first, then its channel is added. */
        fun addGameToMultiview(game: TallyGame) {
            val channelId = game.watch?.channelId?.takeIf { it.isNotBlank() }
            if (channelId != null) {
                addToMultiview(channelId)
                return
            }
            watchLauncher.whenStreamFound(game) { watch ->
                watch.channelId.isNotBlank().also { if (it) addToMultiview(watch.channelId) }
            }
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
