package com.github.damontecres.wholphin.jellytv.surprise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/** How many posters spin before the pick. The pick itself is always the last frame. */
internal const val SURPRISE_REEL_OTHERS = 8

@HiltViewModel
class SurpriseViewModel
    @Inject
    constructor(
        private val repository: SurpriseRepository,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SurpriseState())
        val state: StateFlow<SurpriseState> = _state.asStateFlow()

        private var requestGeneration = 0
        private var work: Job? = null
        private var playInFlight = false

        init {
            launchWork(reloadGenres = true)
        }

        fun shuffle() = launchWork(reloadGenres = false)

        fun setKind(kind: SurpriseKind) {
            if (_state.value.filters.kind == kind) return
            _state.update {
                it.copy(
                    filters = it.filters.copy(kind = kind, genre = null),
                    genres = emptyList(),
                )
            }
            launchWork(reloadGenres = true)
        }

        fun cycleGenre() {
            _state.update { current ->
                val genres = current.genres
                val next =
                    when (val selected = current.filters.genre) {
                        null -> {
                            genres.firstOrNull()
                        }

                        else -> {
                            val index = genres.indexOf(selected)
                            if (index < 0 || index >= genres.lastIndex) null else genres[index + 1]
                        }
                    }
                current.copy(filters = current.filters.copy(genre = next))
            }
            launchWork(reloadGenres = false)
        }

        fun toggleUnderTwoHours() = updateFilters { it.copy(underTwoHours = !it.underTwoHours) }

        fun toggleKidFriendly() = updateFilters { it.copy(kidFriendly = !it.kidFriendly) }

        fun toggleUnwatchedOnly() = updateFilters { it.copy(unwatchedOnly = !it.unwatchedOnly) }

        /** Ignored while a shuffle request is in flight. The page also ignores OK during the reel spin. */
        fun play() {
            if (playInFlight || _state.value.loading) return
            val snapshot = _state.value
            val pick = snapshot.pick ?: return
            when (snapshot.filters.kind) {
                SurpriseKind.MOVIES -> {
                    navigationManager.navigateTo(Destination.Playback(pick))
                }

                SurpriseKind.SHOWS -> {
                    playInFlight = true
                    viewModelScope.launch {
                        try {
                            val episode = repository.nextEpisode(pick.id)
                            val destination =
                                if (episode != null) {
                                    Destination.Playback(episode)
                                } else {
                                    Destination.MediaItem(pick)
                                }
                            navigationManager.navigateTo(destination)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.e(error, "Surprise play failed")
                            _state.update { it.copy(loading = false, error = shortError(error)) }
                        } finally {
                            playInFlight = false
                        }
                    }
                }
            }
        }

        fun openDetails() {
            val pick = _state.value.pick ?: return
            navigationManager.navigateTo(Destination.MediaItem(pick))
        }

        private fun updateFilters(transform: (SurpriseFilters) -> SurpriseFilters) {
            _state.update { it.copy(filters = transform(it.filters)) }
            launchWork(reloadGenres = false)
        }

        private fun launchWork(reloadGenres: Boolean) {
            val generation = ++requestGeneration
            val filters = _state.value.filters
            val previousId = _state.value.pick?.id
            work?.cancel()
            _state.update { it.copy(loading = true, error = null) }
            work =
                viewModelScope.launch {
                    try {
                        if (reloadGenres) {
                            val names =
                                try {
                                    repository.genres(filters.kind)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Timber.w(error, "Surprise genres failed")
                                    emptyList()
                                }
                            if (generation != requestGeneration) return@launch
                            _state.update { current ->
                                if (current.filters.kind != filters.kind) {
                                    current
                                } else {
                                    current.copy(genres = names)
                                }
                            }
                        }
                        val (survivors, total) = repository.shuffle(filters)
                        if (generation != requestGeneration) return@launch
                        val pick = surprisePick(survivors, previousId, total)
                        val reel = surpriseReel(survivors, pick)
                        Timber.d("Surprise pick %s of %d", pick?.name, total)
                        _state.update { current ->
                            if (generation != requestGeneration || current.filters != filters) {
                                current
                            } else {
                                current.copy(
                                    pick = pick,
                                    reel = reel,
                                    matches = total,
                                    shuffleId = current.shuffleId + 1,
                                    loading = false,
                                    error = null,
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.e(error, "Surprise shuffle failed")
                        if (generation != requestGeneration) return@launch
                        _state.update { current ->
                            if (current.filters != filters) {
                                current
                            } else {
                                current.copy(
                                    loading = false,
                                    error = shortError(error),
                                    pick = null,
                                    reel = emptyList(),
                                    shuffleId = current.shuffleId + 1,
                                )
                            }
                        }
                    }
                }
        }
    }

internal fun surprisePick(
    survivors: List<BaseItem>,
    currentId: UUID?,
    matches: Int,
): BaseItem? {
    if (survivors.isEmpty()) return null
    if (matches >= 2 && survivors.size >= 2 && survivors.first().id == currentId) {
        return survivors[1]
    }
    return survivors.first()
}

internal fun surpriseReel(
    survivors: List<BaseItem>,
    pick: BaseItem?,
): List<BaseItem> {
    if (pick == null) return emptyList()
    return survivors.filter { it.id != pick.id }.take(SURPRISE_REEL_OTHERS) + pick
}

private fun shortError(error: Throwable): String =
    error.message
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.take(MAX_ERROR_CHARS)
        .orEmpty()

private const val MAX_ERROR_CHARS = 160
