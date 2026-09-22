package com.github.damontecres.wholphin.jellytv.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.data.JellyTvMultiviewState
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * What the JellyTV home row draws. [games] empty means the row is not there at all.
 */
data class JellyTvHomeRowState(
    val games: List<JtvGame> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val hideScores: Boolean = false,
) {
    val anyLive: Boolean get() = games.any { it.isLive }
}

/**
 * Drives the JellyTV row on Wholphin's home screen: owns the board poll for as long as the row is
 * composed, narrows the board to [HomeRowSelection.select], and handles watch / multiview.
 *
 * Nothing is published unless the server actually has the plugin
 * ([JellyTvRepository.Availability.Available]), so a plain Jellyfin server leaves the home screen
 * exactly as upstream draws it.
 */
@HiltViewModel
class JellyTvHomeRowViewModel
    @Inject
    constructor(
        private val repository: JellyTvRepository,
        private val navigationManager: NavigationManager,
        private val multiviewState: JellyTvMultiviewState,
        private val backdropService: BackdropService,
    ) : ViewModel() {
        /**
         * Moves the "starts within 12 hours" window on even when the board itself is unchanged
         * (an evening with no live game can poll the same payload for hours).
         */
        private val ticker =
            flow {
                while (true) {
                    emit(Instant.now())
                    delay(TICK_MILLIS)
                }
            }

        val uiState: StateFlow<JellyTvHomeRowState> =
            combine(
                repository.availability,
                repository.board,
                repository.settings,
                ticker,
            ) { availability, board, settings, now ->
                if (availability !is JellyTvRepository.Availability.Available) {
                    JellyTvHomeRowState()
                } else {
                    val favorites = settings.favorites.toSet()
                    JellyTvHomeRowState(
                        games = HomeRowSelection.select(board, favorites, now),
                        favorites = favorites,
                        hideScores = settings.hideScores,
                    )
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                JellyTvHomeRowState(),
            )

        /** One-shot string resource ids surfaced as toasts. */
        private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val messages: SharedFlow<Int> = _messages.asSharedFlow()

        // The poll lives as long as this view model (the Home destination), not as long as the row is composed:
        // the LazyColumn disposes the row whenever it scrolls out of view, which must not cancel the fetch.
        init {
            viewModelScope.launch {
                repository.availability.collect { JellyTvHomeFocus.rowExpected = it is JellyTvRepository.Availability.Available }
            }
            repository.startPolling()
            viewModelScope.launchIO {
                if (repository.availability.value is JellyTvRepository.Availability.Unknown) {
                    repository.probe()
                }
                // Favorites and "hide scores" live in the shared settings document; without this
                // the row would order and render as if the user had never set either.
                repository.loadSettings()
            }
        }

        override fun onCleared() {
            repository.stopPolling()
            super.onCleared()
        }

        /**
         * A game card took focus. Upstream sets the page backdrop to the focused library item's art and only ever
         * replaces it, so the last movie's poster would stay behind our cards; the JellyTV ground is plain.
         */
        fun onCardFocused(game: JtvGame) {
            JellyTvHomeHeaderState.focusedGame.value = game
            viewModelScope.launchIO { backdropService.clearBackdrop() }
        }

        fun watch(game: JtvGame) {
            val watch = game.watch ?: return
            val itemId = watch.liveTvItemId?.toUUIDOrNull()
            if (itemId == null) {
                if (watch.liveTvItemId != null) {
                    Timber.w("Unparseable JellyTV liveTvItemId: %s", watch.liveTvItemId)
                }
                emitMessage(R.string.jtv_home_channel_not_ready)
                return
            }
            navigationManager.navigateTo(
                Destination.JellyTvPlayback(itemId = itemId, channelId = watch.channelId),
            )
            viewModelScope.launchIO { repository.setLastChannel(watch.channelId) }
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

        private fun emitMessage(
            @StringRes resId: Int,
        ) {
            viewModelScope.launchDefault { _messages.emit(resId) }
        }

        private companion object {
            const val TICK_MILLIS = 60_000L
        }
    }
