package com.github.damontecres.wholphin.jellytv.household

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.api.client.ApiClient
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

/**
 * Jellyfin's session list, minus this device. Polled every 15 s while someone is subscribed
 * (start/stop, reference counted, same pattern as JellyTvRepository). STUB: never loads anything.
 */
@Singleton
class HouseholdRepository
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        private val _sessions = MutableStateFlow<List<HouseholdSession>>(emptyList())
        val sessions: StateFlow<List<HouseholdSession>> = _sessions.asStateFlow()

        fun startPolling() {}

        fun stopPolling() {}

        /** Send [itemId] to another session, starting at [positionMs]. Returns false when the server refused. */
        suspend fun sendTo(
            session: HouseholdSession,
            itemId: UUID,
            positionMs: Long,
        ): Boolean = false
    }
