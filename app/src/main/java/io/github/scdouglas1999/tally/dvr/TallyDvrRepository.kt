package io.github.scdouglas1999.tally.dvr

import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import io.github.scdouglas1999.tally.data.TallyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DVR as the app sees it: whether the server records at all (`dvr` in `/info`'s features), the rules and jobs
 * list, the storage line, and per-game estimates. The list is fetched when a screen asks ([refresh]), every few
 * seconds while the Recordings tab is open ([startPolling]), and after every change the app makes. A change also
 * refreshes the games board, whose cards carry each game's recording.
 */
@Singleton
class TallyDvrRepository
    @Inject
    constructor(
        private val api: TallyDvrApi,
        private val tally: TallyRepository,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        /** The server has the DVR: the plugin lists the `dvr` feature. */
        val enabled: StateFlow<Boolean> =
            tally.availability
                .map { (it as? TallyRepository.Availability.Available)?.info?.features?.contains(FEATURE) == true }
                .stateIn(scope, SharingStarted.Eagerly, false)

        private val _list = MutableStateFlow<DvrList?>(null)
        val list: StateFlow<DvrList?> = _list.asStateFlow()

        private val _storage = MutableStateFlow<DvrStorage?>(null)
        val storage: StateFlow<DvrStorage?> = _storage.asStateFlow()

        /** Why the last list fetch failed, null after a good one. */
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        /** gameId -> the storage answer with that game's estimate. */
        private val _estimates = MutableStateFlow<Map<String, DvrStorage>>(emptyMap())
        val estimates: StateFlow<Map<String, DvrStorage>> = _estimates.asStateFlow()

        private val pollLock = Any()
        private var pollCount = 0
        private var pollJob: Job? = null

        /** Fetches the list and the storage line (the list first: it is what the screens wait for). */
        suspend fun refresh() {
            if (!enabled.value) return
            try {
                _list.value = api.list()
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: DvrException) {
                Timber.w(e, "Tally DVR: list failed")
                _error.value = e.serverMessage ?: e.message
            }
            try {
                _storage.value = api.storage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DvrException) {
                Timber.w(e, "Tally DVR: storage failed")
            }
        }

        fun refreshSoon() {
            scope.launch { refresh() }
        }

        /** Fetches how much recording [gameId] would take and whether it fits. */
        suspend fun loadEstimate(gameId: String) {
            if (!enabled.value) return
            try {
                val answer = api.storage(gameId)
                _estimates.update { it + (gameId to answer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DvrException) {
                Timber.w(e, "Tally DVR: estimate for %s failed", gameId)
            }
        }

        /** Keeps the list fresh while a screen that shows it is open. Balanced by [stopPolling]. */
        fun startPolling() {
            synchronized(pollLock) {
                pollCount++
                if (pollJob == null) {
                    pollJob =
                        scope.launch {
                            while (true) {
                                refresh()
                                delay(POLL_MS)
                            }
                        }
                }
            }
        }

        fun stopPolling() {
            synchronized(pollLock) {
                if (pollCount > 0) pollCount--
                if (pollCount == 0) {
                    pollJob?.cancel()
                    pollJob = null
                }
            }
        }

        suspend fun recordGame(gameId: String) = change { api.recordGame(gameId) }

        suspend fun recordTeam(
            teamId: String,
            league: String,
            keepLast: Int,
        ) = change { api.recordTeam(teamId, league, keepLast) }

        suspend fun cancelJob(jobId: String) = change { api.cancelJob(jobId) }

        suspend fun deleteRecording(jobId: String) = change { api.deleteRecording(jobId) }

        suspend fun deleteRule(ruleId: String) = change { api.deleteRule(ruleId) }

        fun absoluteUrl(path: String): String? = api.absoluteUrl(path)

        /** Runs a change, then refreshes the list and the board whatever the outcome; rethrows the failure. */
        private suspend fun change(block: suspend () -> Unit) {
            try {
                block()
            } finally {
                scope.launch {
                    refresh()
                    tally.refreshNow()
                }
            }
        }

        private companion object {
            const val FEATURE = "dvr"
            const val POLL_MS = 10_000L
        }
    }
