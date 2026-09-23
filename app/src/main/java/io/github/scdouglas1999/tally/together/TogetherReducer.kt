package io.github.scdouglas1999.tally.together

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupStateUpdate
import org.jellyfin.sdk.model.api.GroupUpdateType
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateCommandMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * One websocket frame the service and the fixture replay both understand.
 * [messageId] is the server's id, used to ignore a frame delivered twice.
 */
sealed interface TogetherFrame {
    val messageId: UUID

    data class Group(
        override val messageId: UUID,
        val update: TogetherUpdate,
    ) : TogetherFrame

    data class Command(
        override val messageId: UUID,
        val command: SendCommand,
    ) : TogetherFrame
}

/** A group-update payload, already decoded. Commands are not updates. */
sealed interface TogetherUpdate {
    data class GroupJoined(
        val group: TogetherGroup,
    ) : TogetherUpdate

    data class UserJoined(
        val name: String,
    ) : TogetherUpdate

    data class UserLeft(
        val name: String,
    ) : TogetherUpdate

    data class State(
        val state: GroupStateType,
        val reason: String?,
    ) : TogetherUpdate

    data class Queue(
        val queue: TogetherQueue,
        val reason: PlayQueueUpdateReason,
    ) : TogetherUpdate

    data object Left : TogetherUpdate

    data object NotInGroup : TogetherUpdate

    data class Denied(
        val message: String,
    ) : TogetherUpdate
}

/**
 * Decodes one socket frame the way the SDK does, then reads the group-update payload the SDK drops.
 *
 * `SyncPlayGroupUpdateCommandMessage.data` is a [org.jellyfin.sdk.model.api.GroupUpdate] with only
 * group id and type (Jellyfin's OpenAPI omits the generic `Data` field; SDK 1.8 fixes it). The
 * typed models (`GroupInfoDto`, `PlayQueueUpdate`, …) still decode that payload, so after
 * [ApiSerializer.decodeSocketMessage] we decode `Data` again with the serializer for `Type`.
 */
object TogetherFrames {
    fun parse(raw: String): TogetherFrame? {
        val message =
            try {
                ApiSerializer.decodeSocketMessage(raw)
            } catch (e: IllegalArgumentException) {
                return null
            }
        return when (message) {
            is SyncPlayCommandMessage -> {
                message.data?.let { TogetherFrame.Command(message.messageId, it) }
            }

            is SyncPlayGroupUpdateCommandMessage -> {
                val update = groupUpdate(raw, message.data?.type) ?: return null
                TogetherFrame.Group(message.messageId, update)
            }

            else -> {
                null
            }
        }
    }

    fun deniedMessage(type: GroupUpdateType): String =
        when (type) {
            GroupUpdateType.LIBRARY_ACCESS_DENIED -> "Library access denied"
            GroupUpdateType.GROUP_DOES_NOT_EXIST -> "That watch party no longer exists"
            GroupUpdateType.CREATE_GROUP_DENIED -> "Could not start a watch party"
            GroupUpdateType.JOIN_GROUP_DENIED -> "Could not join that watch party"
            else -> type.serialName
        }
}

/** The device-local reading the SDK's date serializer produced, back to an instant. */
internal fun LocalDateTime.toTogetherInstant(): Instant = atZone(ZoneId.systemDefault()).toInstant()

private fun groupUpdate(
    raw: String,
    fallback: GroupUpdateType?,
): TogetherUpdate? {
    val body =
        runCatching {
            ApiSerializer.json
                .parseToJsonElement(raw)
                .jsonObject["Data"]
                ?.jsonObject
        }.getOrNull()
    val type =
        body
            ?.get("Type")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { GroupUpdateType.entries.firstOrNull { entry -> entry.serialName == it } }
            ?: fallback
            ?: return null
    val payload = body?.get("Data")
    return when (type) {
        GroupUpdateType.GROUP_JOINED -> {
            decode(payload, GroupInfoDto.serializer())?.let { TogetherUpdate.GroupJoined(it.toTogetherGroup()) }
        }

        GroupUpdateType.USER_JOINED -> {
            payload.asText()?.let { TogetherUpdate.UserJoined(it) }
        }

        GroupUpdateType.USER_LEFT -> {
            payload.asText()?.let { TogetherUpdate.UserLeft(it) }
        }

        GroupUpdateType.GROUP_LEFT -> {
            TogetherUpdate.Left
        }

        GroupUpdateType.NOT_IN_GROUP -> {
            TogetherUpdate.NotInGroup
        }

        GroupUpdateType.STATE_UPDATE -> {
            decode(payload, GroupStateUpdate.serializer())?.let {
                TogetherUpdate.State(it.state, it.reason.serialName)
            }
        }

        GroupUpdateType.PLAY_QUEUE -> {
            decode(payload, PlayQueueUpdate.serializer())?.let {
                TogetherUpdate.Queue(it.toTogetherQueue(), it.reason)
            }
        }

        GroupUpdateType.LIBRARY_ACCESS_DENIED,
        GroupUpdateType.GROUP_DOES_NOT_EXIST,
        GroupUpdateType.CREATE_GROUP_DENIED,
        GroupUpdateType.JOIN_GROUP_DENIED,
        -> {
            TogetherUpdate.Denied(TogetherFrames.deniedMessage(type))
        }

        else -> {
            null
        }
    }
}

private fun <T> decode(
    payload: JsonElement?,
    serializer: KSerializer<T>,
): T? {
    if (payload == null || payload !is JsonObject) return null
    return runCatching { ApiSerializer.json.decodeFromJsonElement(serializer, payload) }.getOrNull()
}

private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun GroupInfoDto.toTogetherGroup(): TogetherGroup =
    TogetherGroup(
        id = groupId,
        name = groupName,
        participants = participants,
        state = state,
        stateReason = null,
    )

private fun PlayQueueUpdate.toTogetherQueue(): TogetherQueue =
    TogetherQueue(
        itemIds = playlist.map { it.itemId },
        playlistItemIds = playlist.map { it.playlistItemId },
        playingIndex = playingItemIndex,
        startPositionTicks = startPositionTicks,
        isPlaying = isPlaying,
        lastUpdate = lastUpdate.toTogetherInstant(),
    )

/** Pure group-update → state. Waiting / Paused / Resumed are announced by the service, not here. */
object TogetherReducer {
    fun reduce(
        state: TogetherState,
        update: TogetherUpdate,
    ): Pair<TogetherState, List<TogetherNotice>> =
        when (update) {
            is TogetherUpdate.GroupJoined -> {
                TogetherState.InGroup(update.group, queue = null) to emptyList()
            }

            is TogetherUpdate.UserJoined -> {
                val group = (state as? TogetherState.InGroup) ?: return state to emptyList()
                if (update.name in group.group.participants) {
                    state to emptyList()
                } else {
                    group.copy(group = group.group.copy(participants = group.group.participants + update.name)) to
                        listOf(TogetherNotice.Joined(update.name))
                }
            }

            is TogetherUpdate.UserLeft -> {
                val group = (state as? TogetherState.InGroup) ?: return state to emptyList()
                group.copy(group = group.group.copy(participants = group.group.participants - update.name)) to
                    listOf(TogetherNotice.Left(update.name))
            }

            is TogetherUpdate.State -> {
                val group = (state as? TogetherState.InGroup) ?: return state to emptyList()
                group.copy(group = group.group.copy(state = update.state, stateReason = update.reason)) to emptyList()
            }

            is TogetherUpdate.Queue -> {
                val group = (state as? TogetherState.InGroup) ?: return state to emptyList()
                group.copy(queue = update.queue) to emptyList()
            }

            TogetherUpdate.Left,
            TogetherUpdate.NotInGroup,
            -> {
                if (state is TogetherState.Idle) state to emptyList() else TogetherState.Idle to listOf(TogetherNotice.Ended)
            }

            is TogetherUpdate.Denied -> {
                if (state is TogetherState.Idle) {
                    state to emptyList()
                } else {
                    TogetherState.Failed(update.message) to listOf(TogetherNotice.Error(update.message))
                }
            }
        }
}
