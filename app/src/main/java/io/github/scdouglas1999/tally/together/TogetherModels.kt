package io.github.scdouglas1999.tally.together

import org.jellyfin.sdk.model.api.GroupStateType
import java.time.Instant
import java.util.UUID

/*
 * Watch Together is Jellyfin SyncPlay: a group on the server whose members (this TV, the web client, phones,
 * other Tally TVs) play the same item in step. The server is the referee: members send requests (unpause,
 * pause, seek, "I'm buffering", "I'm ready") and the server answers everyone with timed commands.
 *
 * Observed on Jellyfin 10.10 (see `tally/dev/syncplay-peer.py`):
 *  - New queue or a join → `PlayQueue` update, group `Waiting`/`Buffer`; the server waits until every member has
 *    reported Ready, then sends `Unpause` with `When` ≈ 1 s in the future.
 *  - A member reports Buffering while playing → every OTHER member gets `Pause` at that member's position and the
 *    group waits; its Ready → `Unpause` to all with almost no lead (start at once, compensating for lateness).
 *  - `Seek` → group `Waiting`/`Seek`; each member seeks, then reports Buffering/Ready.
 *  - Repeated `Unpause`/`Stop` may carry an OLD `When`: position is always derived from `When`, never assumed.
 */

/** The group this TV is in, as the server last described it. Participants are user names. */
data class TogetherGroup(
    val id: UUID,
    val name: String,
    val participants: List<String>,
    val state: GroupStateType,
    /** The server's reason for [state] ("Buffer", "Seek", "Pause", "Ready", "Unpause"…), when given. */
    val stateReason: String?,
)

/** The group's play queue (`PlayQueueUpdate`), reduced to what the TV needs. */
data class TogetherQueue(
    val itemIds: List<UUID>,
    val playlistItemIds: List<UUID>,
    val playingIndex: Int,
    val startPositionTicks: Long,
    val isPlaying: Boolean,
    val lastUpdate: Instant,
) {
    val playingItemId: UUID? get() = itemIds.getOrNull(playingIndex)
    val playingPlaylistItemId: UUID? get() = playlistItemIds.getOrNull(playingIndex)
}

sealed interface TogetherState {
    /** Not in a group. */
    data object Idle : TogetherState

    /** A create/join request is in flight. */
    data object Joining : TogetherState

    data class InGroup(
        val group: TogetherGroup,
        val queue: TogetherQueue?,
    ) : TogetherState

    /** The last request failed; [message] is shown once, then the state returns to [Idle]. */
    data class Failed(
        val message: String,
    ) : TogetherState
}

/** A group listed by the server (`GET /SyncPlay/List`), for the join list and the home row. */
data class TogetherGroupSummary(
    val id: UUID,
    val name: String,
    val participants: List<String>,
    val state: GroupStateType,
)

/** Short-lived things the overlay announces. Names are Jellyfin user names. */
sealed interface TogetherNotice {
    data class Joined(
        val name: String,
    ) : TogetherNotice

    data class Left(
        val name: String,
    ) : TogetherNotice

    /** Someone is buffering or seeking; playback holds until everyone is ready. */
    data object Waiting : TogetherNotice

    data object Paused : TogetherNotice

    data object Resumed : TogetherNotice

    /** This TV left, or the group ended. */
    data object Ended : TogetherNotice

    data class Error(
        val message: String,
    ) : TogetherNotice
}
