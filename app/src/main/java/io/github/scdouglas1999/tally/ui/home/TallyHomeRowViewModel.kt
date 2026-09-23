package io.github.scdouglas1999.tally.ui.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.data.TallyMultiviewState
import io.github.scdouglas1999.tally.data.TallyRepository
import io.github.scdouglas1999.tally.data.isFollowed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.abs

/**
 * What the Tally home row draws. [games] empty means the row is not there at all.
 */
data class TallyHomeRowState(
    val games: List<TallyGame> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val hideScores: Boolean = false,
    val favoriteTeams: Set<String> = emptySet(),
) {
    val anyLive: Boolean get() = games.any { it.isLive }
}

/**
 * A one-shot startup line for a followed team. [startsInMinutes] is null when the game is already
 * live and kicked off within the last few minutes ("Just started"), otherwise "Starts in N min".
 */
data class FollowedNudge(
    val away: String,
    val home: String,
    val startsInMinutes: Int?,
)

/**
 * Drives the Tally row on Wholphin's home screen: owns the board poll for as long as the row is
 * composed, narrows the board to [HomeRowSelection.select], and handles watch / multiview.
 *
 * Nothing is published unless the server actually has the plugin
 * ([TallyRepository.Availability.Available]), so a plain Jellyfin server leaves the home screen
 * exactly as upstream draws it.
 */
@HiltViewModel
class TallyHomeRowViewModel
    @Inject
    constructor(
        private val repository: TallyRepository,
        private val navigationManager: NavigationManager,
        private val multiviewState: TallyMultiviewState,
        private val backdropService: BackdropService,
        @param:ApplicationContext private val appContext: Context,
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

        val uiState: StateFlow<TallyHomeRowState> =
            combine(
                repository.availability,
                repository.board,
                repository.settings,
                ticker,
            ) { availability, board, settings, now ->
                if (availability !is TallyRepository.Availability.Available) {
                    TallyHomeRowState()
                } else {
                    val favorites = settings.favorites.toSet()
                    val teams = settings.favoriteTeams.map { it.uppercase() }.toSet()
                    TallyHomeRowState(
                        games =
                            HomeRowSelection
                                .select(board, favorites, now)
                                .withFollowedTeamsFirst(teams),
                        favorites = favorites,
                        hideScores = settings.hideScores,
                        favoriteTeams = teams,
                    )
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                TallyHomeRowState(),
            )

        /** One-shot string resource ids surfaced as toasts. */
        private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val messages: SharedFlow<Int> = _messages.asSharedFlow()

        /** Plain-text toasts, used for the followed-team startup nudge (team names are not resource ids). */
        private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val notices: SharedFlow<String> = _notices.asSharedFlow()

        // The poll lives as long as this view model (the Home destination), not as long as the row is composed:
        // the LazyColumn disposes the row whenever it scrolls out of view, which must not cancel the fetch.
        init {
            viewModelScope.launch {
                repository.availability.collect { TallyHomeFocus.rowExpected = it is TallyRepository.Availability.Available }
            }
            repository.startPolling()
            viewModelScope.launchIO {
                if (repository.availability.value is TallyRepository.Availability.Unknown) {
                    repository.probe()
                }
                // Favorites and "hide scores" live in the shared settings document; without this
                // the row would order and render as if the user had never set either.
                repository.loadSettings()
                val board = repository.board.value ?: repository.board.filterNotNull().first()
                if (!startupNudgeSent.compareAndSet(false, true)) return@launchIO
                val nudge =
                    followedStartupNudge(
                        games = board.games,
                        favoriteTeams = repository.settings.value.favoriteTeams,
                        now = Instant.now(),
                    ) ?: return@launchIO
                val text =
                    if (nudge.startsInMinutes == null) {
                        appContext.getString(R.string.tally_actions_just_started, nudge.away, nudge.home)
                    } else {
                        appContext.getString(
                            R.string.tally_actions_starts_in,
                            nudge.startsInMinutes,
                            nudge.away,
                            nudge.home,
                        )
                    }
                _notices.emit(text)
            }
        }

        override fun onCleared() {
            repository.stopPolling()
            super.onCleared()
        }

        /**
         * A game card took focus. The game's matchup art (server-rendered, text-free) becomes the page backdrop,
         * so upstream fades it in and tints the page from the teams' colors exactly as it does for a film.
         * Servers without the art get a cleared backdrop: the last film's poster must not linger behind a game.
         */
        fun onCardFocused(game: TallyGame) {
            TallyHomeHeaderState.focusedGame.value = game
            val art = game.backdropPath?.takeIf { it.isNotBlank() }?.let(repository::absoluteUrl)
            viewModelScope.launchIO {
                if (art != null) backdropService.submit("jellytv_game_${game.id}", art) else backdropService.clearBackdrop()
            }
        }

        fun watch(game: TallyGame) {
            val watch = game.watch ?: return
            val itemId = watch.liveTvItemId?.toUUIDOrNull()
            if (itemId == null) {
                if (watch.liveTvItemId != null) {
                    Timber.w("Unparseable Tally liveTvItemId: %s", watch.liveTvItemId)
                }
                emitMessage(R.string.tally_home_channel_not_ready)
                return
            }
            navigationManager.navigateTo(
                Destination.TallyPlayback(itemId = itemId, channelId = watch.channelId),
            )
            viewModelScope.launchIO { repository.setLastChannel(watch.channelId) }
        }

        fun toggleFollow(teamKey: String) {
            viewModelScope.launchIO { repository.toggleFavoriteTeam(teamKey) }
        }

        fun toggleHideScores() {
            viewModelScope.launchIO { repository.setHideScores(!uiState.value.hideScores) }
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

        private fun emitMessage(
            @StringRes resId: Int,
        ) {
            viewModelScope.launchDefault { _messages.emit(resId) }
        }

        private companion object {
            const val TICK_MILLIS = 60_000L

            /** One toast per process, not per visit to Home. */
            val startupNudgeSent = AtomicBoolean(false)
        }
    }

/**
 * Live games stay ahead of upcoming ones. Inside each of those groups, followed teams come first
 * and the previous order (channel favorite, league, start) is kept.
 */
internal fun List<TallyGame>.withFollowedTeamsFirst(favoriteTeams: Set<String>): List<TallyGame> {
    if (favoriteTeams.isEmpty() || isEmpty()) return this
    val teams = favoriteTeams.map { it.uppercase() }.toSet()

    fun List<TallyGame>.pin() = sortedWith(compareByDescending<TallyGame> { it.isFollowed(teams) })
    val (live, later) = partition { it.isLive }
    return live.pin() + later.pin()
}

/**
 * The closest followed game that starts within 30 minutes, or is live and started within the last
 * 5 minutes. Live rows are not a concern here: this is one toast, so the nearest kickoff wins.
 */
fun followedStartupNudge(
    games: List<TallyGame>,
    favoriteTeams: Collection<String>,
    now: Instant,
): FollowedNudge? {
    val teams = favoriteTeams.map { it.uppercase() }.toSet()
    if (teams.isEmpty()) return null

    data class Candidate(
        val game: TallyGame,
        val seconds: Long,
        val startsInMinutes: Int?,
    )
    val best =
        games
            .mapNotNull { game ->
                if (!game.isFollowed(teams)) return@mapNotNull null
                val start = parseStart(game.start) ?: return@mapNotNull null
                val seconds =
                    java.time.Duration
                        .between(now, start)
                        .seconds
                when {
                    game.isLive && seconds in -LIVE_NUDGE_SECONDS..0 -> {
                        Candidate(game, seconds, null)
                    }

                    game.isUpcoming && seconds in 0..START_NUDGE_SECONDS -> {
                        Candidate(
                            game,
                            seconds,
                            ((seconds + 30) / 60).toInt().coerceAtLeast(1),
                        )
                    }

                    else -> {
                        null
                    }
                }
            }.minWithOrNull(compareBy<Candidate> { abs(it.seconds) }.thenBy { it.seconds })
            ?: return null
    return FollowedNudge(
        away = best.game.away.shortLabel(),
        home = best.game.home.shortLabel(),
        startsInMinutes = best.startsInMinutes,
    )
}

private fun TallyTeam.shortLabel(): String = shortName.ifBlank { abbr.ifBlank { name } }

private fun parseStart(value: String): Instant? =
    try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeException) {
        null
    }

private const val START_NUDGE_SECONDS = 30L * 60L
private const val LIVE_NUDGE_SECONDS = 5L * 60L
