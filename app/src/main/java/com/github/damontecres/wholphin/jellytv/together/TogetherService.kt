package com.github.damontecres.wholphin.jellytv.together

import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlayerFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.api.client.ApiClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch Together: this TV as a Jellyfin SyncPlay member. Process-wide (a party survives navigating between
 * screens); it attaches to whatever upstream player `PlayerFactory.currentPlayer` holds and keeps it in step
 * with the group by observing it — no upstream player code is wrapped or replaced. See `TogetherModels.kt` for
 * the protocol as observed and `SyncPolicy` for the correction rules.
 *
 * STUB: task `together-core` implements it. The public surface below is the contract for the UI task.
 */
@Singleton
class TogetherService
    @Inject
    constructor(
        private val api: ApiClient,
        private val playerFactory: PlayerFactory,
        private val navigationManager: NavigationManager,
    ) {
        private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle)
        val state: StateFlow<TogetherState> = _state.asStateFlow()

        private val _notices = MutableSharedFlow<TogetherNotice>(extraBufferCapacity = 16)
        val notices: SharedFlow<TogetherNotice> = _notices.asSharedFlow()

        /** Groups on the server this user can join. Empty on any error. */
        suspend fun groups(): List<TogetherGroupSummary> = emptyList()

        /** Create a group named [groupName] and make [itemId] at [positionMs] its queue. */
        suspend fun startParty(
            itemId: UUID,
            positionMs: Long,
            groupName: String,
        ) {}

        /** Join an existing group; the TV then opens and holds the group's item until the group starts it. */
        suspend fun join(groupId: UUID) {}

        suspend fun leave() {}
    }
