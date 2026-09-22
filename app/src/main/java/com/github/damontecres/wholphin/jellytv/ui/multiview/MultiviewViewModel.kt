package com.github.damontecres.wholphin.jellytv.ui.multiview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import com.github.damontecres.wholphin.jellytv.data.JellyTvMultiviewState
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * One video slot in multiview: a queued channel joined with the board.
 *
 * [hlsUrl] is null until the channel is on the board with a signed `hlsPath`;
 * [game] is the live game currently resolved to the channel, when known;
 * [liveTvItemId] is the Live TV item full-screen playback needs, when the server has registered one.
 */
data class MultiviewTile(
    val channelId: String,
    val name: String,
    val hlsUrl: String?,
    val game: JtvGame?,
    val liveTvItemId: String? = null,
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
        private val keyValueService: KeyValueService,
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
                        liveTvItemId = channel?.liveTvItemId ?: game?.watch?.liveTvItemId,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /**
         * What can be put on screen next: live games first (board order: favorites, league, start time), then
         * every other channel that is not already tiled, so the rail is useful outside game time too.
         */
        val bench: StateFlow<List<MultiviewBenchEntry>> =
            combine(
                repository.board,
                repository.settings,
                multiviewState.channelIds,
            ) { board, settings, ids ->
                val live =
                    BoardOrganizer
                        .rows(board?.games ?: emptyList(), settings.favorites.toSet(), onlyWatchable = true)
                        .flatMap { it.games }
                        .filter { it.isLive }
                        .mapNotNull { game ->
                            val watch = game.watch ?: return@mapNotNull null
                            if (watch.channelId.isBlank()) null else MultiviewBenchEntry(watch.channelId, watch.channelName, game)
                        }.distinctBy { it.channelId }
                val liveIds = live.map { it.channelId }.toSet()
                val others =
                    (board?.channels ?: emptyList())
                        .filter { it.id !in liveIds }
                        .map { MultiviewBenchEntry(it.id, it.name, null) }
                (live + others).filter { it.channelId !in ids }.take(MAX_BENCH)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val hideScores: StateFlow<Boolean> =
            repository.settings
                .map { it.hideScores }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** Followed team keys, upper-cased like [JtvGame.teamKey], for the tile actions menu. */
        val favoriteTeams: StateFlow<Set<String>> =
            repository.settings
                .map { settings -> settings.favoriteTeams.map { it.uppercase() }.toSet() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

        /** Index of the tile whose audio is unmuted; follows focus. */
        private val _audioIndex = MutableStateFlow(0)
        val audioIndex: StateFlow<Int> = _audioIndex.asStateFlow()

        /**
         * Null until the user presses OK (or a saved choice is restored). While null, the page
         * uses [defaultMultiviewLayout] so three and four tiles open in focus and one or two stay equal.
         */
        private val _layoutChoice = MutableStateFlow<MultiviewLayout?>(null)
        val layoutChoice: StateFlow<MultiviewLayout?> = _layoutChoice.asStateFlow()

        /** Which queued tile occupies the large slot in [MultiviewLayout.FOCUS]. */
        private val _bigIndex = MutableStateFlow(0)
        val bigIndex: StateFlow<Int> = _bigIndex.asStateFlow()

        init {
            repository.startPolling()
            viewModelScope.launchIO { repository.loadSettings() }
            viewModelScope.launchIO {
                try {
                    val saved = keyValueService.get<String>(LAYOUT_KEY).first()
                    val choice = saved?.let { runCatching { MultiviewLayout.valueOf(it) }.getOrNull() }
                    if (choice != null) _layoutChoice.value = choice
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not restore multiview layout")
                }
            }
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
            val ids = multiviewState.channelIds.value
            val channelId = ids.getOrNull(index) ?: return
            val newLast = (ids.lastIndex - 1).coerceAtLeast(0)
            _bigIndex.update { current ->
                when {
                    index < current -> (current - 1).coerceAtMost(newLast)
                    else -> current.coerceAtMost(newLast)
                }
            }
            _audioIndex.update { current ->
                when {
                    index < current -> (current - 1).coerceAtMost(newLast)
                    else -> current.coerceAtMost(newLast)
                }
            }
            multiviewState.remove(channelId)
        }

        /**
         * OK on a tile. In focus, the large tile returns to equal tiles; any other tile
         * (including every tile while equal) becomes the large one.
         */
        fun onTileOk(index: Int) {
            val count = tiles.value.size
            if (index !in 0 until count) return
            val big = _bigIndex.value.coerceIn(0, (count - 1).coerceAtLeast(0))
            val current = _layoutChoice.value ?: defaultMultiviewLayout(count)
            if (current == MultiviewLayout.FOCUS && index == big) {
                chooseLayout(MultiviewLayout.EQUAL)
            } else {
                _bigIndex.value = index
                chooseLayout(MultiviewLayout.FOCUS)
            }
        }

        /**
         * Put [entry]'s channel on screen: replaces the audio tile when the queue is
         * full, otherwise appends.
         */
        fun swapIn(entry: MultiviewBenchEntry) {
            if (multiviewState.channelIds.value.size >= JellyTvMultiviewState.MAX) {
                multiviewState.replace(_audioIndex.value, entry.channelId)
            } else {
                multiviewState.add(entry.channelId)
            }
        }

        /**
         * Play [index]'s channel full screen. Multiview stays on the back stack, so BACK returns to it
         * (its players are released while it is hidden and rebuilt on return).
         */
        fun watchFullScreen(index: Int) {
            val tile = tiles.value.getOrNull(index) ?: return
            val itemId = tile.liveTvItemId?.toUUIDOrNull() ?: return
            navigationManager.navigateTo(Destination.JellyTvPlayback(itemId, tile.channelId))
        }

        fun toggleFollow(teamKey: String) {
            viewModelScope.launchIO { repository.toggleFavoriteTeam(teamKey) }
        }

        fun toggleHideScores() {
            viewModelScope.launchIO { repository.setHideScores(!hideScores.value) }
        }

        fun close() = navigationManager.goBack()

        /** Keep the screen on while a tile is playing, the way upstream's playback does. */
        fun setKeepScreenOn(keep: Boolean) = screensaverService.keepScreenOn(keep)

        private fun chooseLayout(layout: MultiviewLayout) {
            _layoutChoice.value = layout
            viewModelScope.launchIO {
                try {
                    keyValueService.save(LAYOUT_KEY, layout.name)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not save multiview layout")
                }
            }
        }

        private companion object {
            const val MAX_BENCH = 12
            const val LAYOUT_KEY = "jellytv.multiview.layout"
        }
    }

/** One row of the swap-in rail: a channel, with the live game it is showing when there is one. */
data class MultiviewBenchEntry(
    val channelId: String,
    val name: String,
    val game: JtvGame?,
)

/** How the tiles share the stage. [FOCUS] gives one tile the large slot. */
enum class MultiviewLayout {
    EQUAL,
    FOCUS,
}

/**
 * Layout before the user has pressed OK: focus when there are enough tiles to stack,
 * equal for one or two.
 */
fun defaultMultiviewLayout(tileCount: Int): MultiviewLayout = if (tileCount >= 3) MultiviewLayout.FOCUS else MultiviewLayout.EQUAL
