package com.github.damontecres.wholphin.jellytv.year

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.NavigationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import javax.inject.Inject

sealed interface YearUiState {
    data object Loading : YearUiState

    data class Failed(
        val message: String?,
    ) : YearUiState

    data class Ready(
        val stats: YearStats,
        val years: List<Int>,
        val serverName: String,
        val firstPlayed: Instant?,
        val latestPlayed: Instant?,
    ) : YearUiState
}

/**
 * Played-item totals for one year. [load] picks [requestedYear], or the current year when it has
 * plays, otherwise the newest year that does.
 */
@HiltViewModel
class YearViewModel
    @Inject
    constructor(
        private val repository: YearRepository,
        private val serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow<YearUiState>(YearUiState.Loading)
        val state: StateFlow<YearUiState> = _state.asStateFlow()

        private var loadJob: Job? = null
        private var items: List<WatchedItem>? = null
        private var years: List<Int> = emptyList()
        private var serverName: String = ""

        fun load(requestedYear: Int?) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    if (_state.value !is YearUiState.Ready) {
                        _state.value = YearUiState.Loading
                    }
                    try {
                        val loaded = repository.watched()
                        val available = repository.years()
                        items = loaded
                        years = available
                        serverName = serverRepository.currentServer?.name.orEmpty()
                        _state.value = ready(loaded, chooseYear(requestedYear, available))
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Year recap failed")
                        _state.value = YearUiState.Failed(ex.message)
                    }
                }
        }

        fun selectYear(year: Int) {
            val loaded = items ?: return
            _state.value = ready(loaded, year)
        }

        fun close() {
            navigationManager.goBack()
        }

        private fun chooseYear(
            requested: Int?,
            available: List<Int>,
        ): Int {
            if (requested != null) return requested
            val zone = ZoneId.systemDefault()
            val current = Year.now(zone).value
            return available.firstOrNull { it == current } ?: available.firstOrNull() ?: current
        }

        private fun ready(
            loaded: List<WatchedItem>,
            year: Int,
        ): YearUiState.Ready {
            val zone = ZoneId.systemDefault()
            val stats = YearStatsCalculator.compute(loaded, year, zone)
            val instants =
                loaded.mapNotNull { item ->
                    item.lastPlayed?.takeIf { it.atZone(zone).year == year }
                }
            return YearUiState.Ready(
                stats = stats,
                years = years,
                serverName = serverName,
                firstPlayed = instants.minOrNull(),
                latestPlayed = instants.maxOrNull(),
            )
        }
    }
