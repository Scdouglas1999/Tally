package com.github.damontecres.wholphin.jellytv.household

import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.SessionInfoDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Another device in the house, and what it is playing. */
data class HouseholdSession(
    val sessionId: String,
    val deviceName: String,
    val client: String,
    val userName: String,
    val itemId: UUID?,
    val itemName: String?,
    val seriesName: String?,
    val positionMs: Long?,
    val runtimeMs: Long?,
    val isPaused: Boolean,
    val supportsRemoteControl: Boolean,
)

/** Jellyfin ticks are 100ns; 10,000 ticks is one millisecond. */
internal const val HOUSEHOLD_TICKS_PER_MS = 10_000L

/**
 * Sessions worth showing: not this device, and only those actually playing something.
 * [HouseholdSession.positionMs] and [HouseholdSession.runtimeMs] are ticks divided by
 * [HOUSEHOLD_TICKS_PER_MS].
 */
internal fun mapHouseholdSessions(
    sessions: List<SessionInfoDto>,
    thisDeviceId: String,
): List<HouseholdSession> =
    sessions.mapNotNull { session ->
        if (session.deviceId == thisDeviceId) return@mapNotNull null
        val item = session.nowPlayingItem ?: return@mapNotNull null
        val sessionId = session.id ?: return@mapNotNull null
        HouseholdSession(
            sessionId = sessionId,
            deviceName = session.deviceName.orEmpty(),
            client = session.client.orEmpty(),
            userName = session.userName.orEmpty(),
            itemId = item.id,
            itemName = item.name,
            seriesName = item.seriesName,
            positionMs = session.playState?.positionTicks?.div(HOUSEHOLD_TICKS_PER_MS),
            runtimeMs = item.runTimeTicks?.div(HOUSEHOLD_TICKS_PER_MS),
            isPaused = session.playState?.isPaused == true,
            supportsRemoteControl = session.supportsRemoteControl,
        )
    }

/**
 * Jellyfin's session list, minus this device. Polled every 15 s while someone is subscribed
 * (start/stop, reference counted, same pattern as JellyTvRepository).
 */
@Singleton
class HouseholdRepository
    @Inject
    constructor(
        private val api: ApiClient,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val _sessions = MutableStateFlow<List<HouseholdSession>>(emptyList())
        val sessions: StateFlow<List<HouseholdSession>> = _sessions.asStateFlow()

        /** False until the first poll attempt finishes, so the send dialog does not flash its empty copy. */
        private val _hasFetched = MutableStateFlow(false)
        val hasFetched: StateFlow<Boolean> = _hasFetched.asStateFlow()

        private val pollLock = Any()
        private var pollRefCount = 0
        private var pollJob: Job? = null

        fun startPolling() {
            synchronized(pollLock) {
                pollRefCount++
                if (pollJob == null) {
                    pollJob =
                        scope.launch {
                            while (true) {
                                refresh()
                                delay(POLL_INTERVAL_MS)
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

        /** Send [itemId] to another session, starting at [positionMs]. Returns false when the server refused. */
        suspend fun sendTo(
            session: HouseholdSession,
            itemId: UUID,
            positionMs: Long,
        ): Boolean =
            try {
                withContext(ioDispatcher) {
                    api.sessionApi.play(
                        sessionId = session.sessionId,
                        playCommand = PlayCommand.PLAY_NOW,
                        itemIds = listOf(itemId),
                        startPositionTicks = positionMs * HOUSEHOLD_TICKS_PER_MS,
                    )
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Send to %s failed", session.sessionId)
                false
            }

        private suspend fun refresh() {
            try {
                val deviceId = api.deviceInfo.id
                val listed =
                    withContext(ioDispatcher) {
                        api.sessionApi.getSessions().content
                    }
                _sessions.value = mapHouseholdSessions(listed, deviceId)
                _hasFetched.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Household session refresh failed")
                _hasFetched.value = true
            }
        }

        private companion object {
            const val POLL_INTERVAL_MS = 15_000L
        }
    }
