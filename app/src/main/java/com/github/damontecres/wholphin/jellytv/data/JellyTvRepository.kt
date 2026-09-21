package com.github.damontecres.wholphin.jellytv.data

import com.github.damontecres.wholphin.jellytv.api.JellyTvApi
import com.github.damontecres.wholphin.jellytv.api.JellyTvException
import com.github.damontecres.wholphin.jellytv.api.JellyTvJson
import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import com.github.damontecres.wholphin.jellytv.api.JtvEvent
import com.github.damontecres.wholphin.jellytv.api.JtvInfo
import com.github.damontecres.wholphin.jellytv.api.JtvSettings
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * Read-modify-write helpers for the shared settings document.
 *
 * The settings store is a free-form JSON object shared with the JellyTV web UI,
 * so every mutation preserves keys this app does not know about.
 */
internal object SettingsJson {
    fun parse(raw: JsonObject): JtvSettings =
        try {
            JellyTvJson.decodeFromJsonElement(JtvSettings.serializer(), raw)
        } catch (e: Exception) {
            Timber.w(e, "Malformed JellyTV settings, using defaults")
            JtvSettings()
        }

    fun toggleFavorite(
        raw: JsonObject,
        channelId: String,
    ): JsonObject {
        val favorites = parse(raw).favorites
        val updated =
            if (channelId in favorites) {
                favorites - channelId
            } else {
                favorites + channelId
            }
        return JsonObject(raw + ("favorites" to JsonArray(updated.map { JsonPrimitive(it) })))
    }

    fun setHideScores(
        raw: JsonObject,
        hide: Boolean,
    ): JsonObject = JsonObject(raw + ("hideScores" to JsonPrimitive(hide)))

    fun setLastChannel(
        raw: JsonObject,
        channelId: String,
    ): JsonObject = JsonObject(raw + ("lastChannel" to JsonPrimitive(channelId)))
}

/**
 * JellyTV data: plugin availability, the polled games board, events, and the
 * shared per-user settings document.
 */
@Singleton
class JellyTvRepository
    @Inject
    constructor(
        private val jellyTvApi: JellyTvApi,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        sealed interface Availability {
            data object Unknown : Availability

            data class Available(
                val info: JtvInfo,
            ) : Availability

            data object NotInstalled : Availability

            data object SignedOut : Availability

            data class Error(
                val message: String,
            ) : Availability
        }

        private val _availability = MutableStateFlow<Availability>(Availability.Unknown)
        val availability: StateFlow<Availability> = _availability.asStateFlow()

        private val _board = MutableStateFlow<JtvBoard?>(null)
        val board: StateFlow<JtvBoard?> = _board.asStateFlow()

        private val _boardError = MutableStateFlow<String?>(null)
        val boardError: StateFlow<String?> = _boardError.asStateFlow()

        private val _settings = MutableStateFlow(JtvSettings())
        val settings: StateFlow<JtvSettings> = _settings.asStateFlow()

        private val _events = MutableSharedFlow<JtvEvent>(replay = 0, extraBufferCapacity = 32)
        val events: SharedFlow<JtvEvent> = _events.asSharedFlow()

        private var rawSettings: JsonObject = JsonObject(emptyMap())
        private val settingsMutex = Mutex()

        /** Highest event id seen; null until [probe] succeeds. Passed as `since`. */
        private var cursor: Long? = null

        private var pollIntervalSeconds = 15

        private val pollLock = Any()
        private var pollRefCount = 0
        private var pollJob: Job? = null

        /**
         * Checks whether the server has the JellyTV plugin and publishes the result.
         * On success, seeds the event cursor so old events are never replayed.
         */
        suspend fun probe(): Availability {
            val result =
                try {
                    Availability.Available(jellyTvApi.info())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: JellyTvException.NotInstalled) {
                    Availability.NotInstalled
                } catch (e: JellyTvException.SignedOut) {
                    Availability.SignedOut
                } catch (e: JellyTvException) {
                    Availability.Error(e.message ?: "Unknown error")
                }
            if (result is Availability.Available) {
                cursor = result.info.latestEventId
                pollIntervalSeconds = result.info.pollSeconds
            }
            _availability.value = result
            return result
        }

        /**
         * Starts the board poll loop. Reference counted: every call must be balanced
         * by [stopPolling]; the loop runs while at least one caller is interested.
         */
        fun startPolling() {
            synchronized(pollLock) {
                pollRefCount++
                if (pollJob == null) {
                    pollJob =
                        scope.launch {
                            while (true) {
                                tick()
                                delay(pollIntervalSeconds.coerceAtLeast(MIN_POLL_SECONDS) * 1000L)
                            }
                        }
                }
            }
        }

        fun stopPolling() {
            synchronized(pollLock) {
                if (pollRefCount > 0) pollRefCount--
                if (pollRefCount == 0) {
                    pollJob?.cancel()
                    pollJob = null
                }
            }
        }

        /** Fetches the board once, outside of the poll loop. */
        suspend fun refreshNow() = tick()

        private suspend fun tick() {
            try {
                // Seed the event cursor first: without it `since` is never sent
                // and no events ever arrive. Safe to call every time; probe()
                // swallows non-cancellation failures.
                if (cursor == null) {
                    probe()
                }
                val newBoard = jellyTvApi.board(cursor)
                _board.value = newBoard
                _boardError.value = null
                newBoard.events
                    .sortedBy { it.id }
                    .forEach { event ->
                        val seen = cursor ?: Long.MIN_VALUE
                        if (event.id > seen) {
                            _events.emit(event)
                            cursor = event.id
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "JellyTV board refresh failed")
                _boardError.value = e.message ?: "Refresh failed"
            }
        }

        suspend fun loadSettings() {
            try {
                val raw = jellyTvApi.settingsRaw()
                settingsMutex.withLock {
                    rawSettings = raw
                    _settings.value = SettingsJson.parse(raw)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to load JellyTV settings")
            }
        }

        suspend fun toggleFavorite(channelId: String) = mutateSettings { SettingsJson.toggleFavorite(it, channelId) }

        suspend fun setHideScores(hide: Boolean) = mutateSettings { SettingsJson.setHideScores(it, hide) }

        suspend fun setLastChannel(channelId: String) = mutateSettings { SettingsJson.setLastChannel(it, channelId) }

        /**
         * Optimistic read-modify-write of the shared settings document. Reverts the
         * published settings if the server write fails.
         */
        private suspend fun mutateSettings(mutate: (JsonObject) -> JsonObject) {
            settingsMutex.withLock {
                val previous = rawSettings
                val updated = mutate(previous)
                rawSettings = updated
                _settings.value = SettingsJson.parse(updated)
                try {
                    jellyTvApi.putSettingsRaw(updated)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to save JellyTV settings")
                    rawSettings = previous
                    _settings.value = SettingsJson.parse(previous)
                }
            }
        }

        fun absoluteUrl(path: String) = jellyTvApi.absoluteUrl(path)

        private companion object {
            const val MIN_POLL_SECONDS = 5
        }
    }
