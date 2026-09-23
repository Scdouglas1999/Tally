package io.github.scdouglas1999.tally.ui.screensaver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallySettings
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.TallyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * What the idle screen has to show. [liveGames] is empty whenever the screensaver must
 * stay out of the way: no Tally plugin, no board yet, or nothing in progress.
 */
data class TallyScreensaverUiState(
    val available: Boolean = false,
    val liveGames: List<TallyGame> = emptyList(),
    val hideScores: Boolean = false,
)

/**
 * Feeds the live-scores idle screen: the live games in cycle order, plus the board poll
 * for as long as the screensaver is on screen.
 *
 * The screensaver lives in the activity's composition, so this view model outlives a single
 * appearance; the poll is therefore tied to [onShown]/[onHidden] and not to [onCleared].
 */
@HiltViewModel
class TallyScreensaverViewModel
    @Inject
    constructor(
        private val repository: TallyRepository,
    ) : ViewModel() {
        val uiState: StateFlow<TallyScreensaverUiState> =
            combine(
                repository.availability,
                repository.board,
                repository.settings,
            ) { availability, board, settings ->
                val available = availability is TallyRepository.Availability.Available
                TallyScreensaverUiState(
                    available = available,
                    liveGames = if (available) cycleOrder(board?.games.orEmpty(), settings) else emptyList(),
                    hideScores = settings.hideScores,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
                TallyScreensaverUiState(),
            )

        /** True between [onShown] and [onHidden]: keeps the repository's poll ref count balanced. */
        private var polling = false

        /** The idle screen is up: poll so the scores move, and make sure we know the server and the settings. */
        fun onShown() {
            if (!polling) {
                polling = true
                repository.startPolling()
            }
            viewModelScope.launchIO {
                if (repository.availability.value is TallyRepository.Availability.Unknown) {
                    repository.probe()
                }
                repository.loadSettings()
            }
        }

        fun onHidden() {
            if (polling) {
                polling = false
                repository.stopPolling()
            }
        }

        override fun onCleared() {
            onHidden()
            super.onCleared()
        }

        private companion object {
            const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

            /**
             * The live games only, favorites first and otherwise in board order
             * (the same order the games board uses, so the two agree).
             */
            fun cycleOrder(
                games: List<TallyGame>,
                settings: TallySettings,
            ): List<TallyGame> {
                val favorites = settings.favorites.toSet()

                fun TallyGame.isFavorite() = watch?.channelId?.let { it in favorites } == true
                return BoardOrganizer
                    .rows(games, favorites, onlyWatchable = false)
                    .filter { it.state == "in" }
                    .flatMap { it.games }
                    .sortedByDescending { it.isFavorite() }
            }
        }
    }
