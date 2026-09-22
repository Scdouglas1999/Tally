package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.together.TogetherFrame
import com.github.damontecres.wholphin.jellytv.together.TogetherFrames
import com.github.damontecres.wholphin.jellytv.together.TogetherGroup
import com.github.damontecres.wholphin.jellytv.together.TogetherNotice
import com.github.damontecres.wholphin.jellytv.together.TogetherQueue
import com.github.damontecres.wholphin.jellytv.together.TogetherReducer
import com.github.damontecres.wholphin.jellytv.together.TogetherState
import com.github.damontecres.wholphin.jellytv.together.TogetherUpdate
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupUpdateType
import org.jellyfin.sdk.model.api.SendCommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Replays `syncplay-session.jsonl`: 25 frames from a real two-member session, decoded with the SDK serializer.
 */
class TogetherReducerTest {
    private val lines: List<String> =
        javaClass
            .getResource("/jellytv/syncplay-session.jsonl")!!
            .readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

    @Test
    fun replayFixture() {
        assertEquals(25, lines.size)
        var state: TogetherState = TogetherState.Idle
        lines.forEachIndexed { index, line ->
            val frame =
                TogetherFrames.parse(line)
                    ?: error("frame $index did not parse")
            when (frame) {
                is TogetherFrame.Command -> {
                    assertEquals("frame $index", commandAt(index), frame.command.command)
                }

                is TogetherFrame.Group -> {
                    val (next, notices) = TogetherReducer.reduce(state, frame.update)
                    assertGroupFrame(index, next, notices)
                    state = next
                }
            }
        }
        assertTrue(state is TogetherState.Idle)
    }

    @Test
    fun userLeftRemovesThemAndErrorsFail() {
        val joined = inGroup(listOf("admin", "friend"))
        val (left, leftNotices) = TogetherReducer.reduce(joined, TogetherUpdate.UserLeft("friend"))
        val afterLeft = left as TogetherState.InGroup
        assertEquals(listOf("admin"), afterLeft.group.participants)
        assertEquals(listOf(TogetherNotice.Left("friend")), leftNotices)

        val (failed, errorNotices) =
            TogetherReducer.reduce(
                TogetherState.Joining,
                TogetherUpdate.Denied(TogetherFrames.deniedMessage(GroupUpdateType.JOIN_GROUP_DENIED)),
            )
        val failure = failed as TogetherState.Failed
        assertEquals("Could not join that watch party", failure.message)
        assertEquals(listOf(TogetherNotice.Error("Could not join that watch party")), errorNotices)
        assertEquals(
            "Library access denied",
            TogetherFrames.deniedMessage(GroupUpdateType.LIBRARY_ACCESS_DENIED),
        )
        assertEquals(
            "That watch party no longer exists",
            TogetherFrames.deniedMessage(GroupUpdateType.GROUP_DOES_NOT_EXIST),
        )
        assertEquals(
            "Could not start a watch party",
            TogetherFrames.deniedMessage(GroupUpdateType.CREATE_GROUP_DENIED),
        )
    }

    @Test
    fun idleIgnoresParticipantAndStateUpdatesAndGroupJoinedClearsTheQueue() {
        val idle = TogetherState.Idle
        assertEquals(idle to emptyList<TogetherNotice>(), TogetherReducer.reduce(idle, TogetherUpdate.UserJoined("friend")))
        assertEquals(
            idle to emptyList<TogetherNotice>(),
            TogetherReducer.reduce(idle, TogetherUpdate.State(GroupStateType.WAITING, "Buffer")),
        )
        val (again, notices) = TogetherReducer.reduce(idle, TogetherUpdate.NotInGroup)
        assertEquals(idle, again)
        assertTrue(notices.isEmpty())

        val queued =
            inGroup(listOf("admin")).copy(
                queue =
                    TogetherQueue(
                        itemIds = listOf(UUID(0, 1)),
                        playlistItemIds = listOf(UUID(0, 2)),
                        playingIndex = 0,
                        startPositionTicks = 0,
                        isPlaying = false,
                        lastUpdate = Instant.EPOCH,
                    ),
            )
        val (reset, _) = TogetherReducer.reduce(queued, TogetherUpdate.GroupJoined(queued.group))
        assertNull((reset as TogetherState.InGroup).queue)
    }

    @Test
    fun joiningTheSamePersonTwiceDoesNotRepeatTheNotice() {
        val once = TogetherReducer.reduce(inGroup(listOf("admin")), TogetherUpdate.UserJoined("friend"))
        val twice = TogetherReducer.reduce(once.first, TogetherUpdate.UserJoined("friend"))
        assertEquals(listOf(TogetherNotice.Joined("friend")), once.second)
        assertTrue(twice.second.isEmpty())
        assertEquals(listOf("admin", "friend"), (twice.first as TogetherState.InGroup).group.participants)
    }

    private fun assertGroupFrame(
        index: Int,
        state: TogetherState,
        notices: List<TogetherNotice>,
    ) {
        if (index == 24) {
            assertTrue("frame 24", state is TogetherState.Idle)
            assertEquals(listOf(TogetherNotice.Ended), notices)
            return
        }
        val snap =
            snaps[index] ?: error("frame $index was a group update with no expected snapshot")
        val group = state as TogetherState.InGroup
        assertEquals("frame $index name", "Fixture party", group.group.name)
        assertEquals("frame $index id", GROUP_ID, group.group.id.toString())
        assertEquals("frame $index participants", snap.participants, group.group.participants)
        assertEquals("frame $index state", snap.state, group.group.state)
        assertEquals("frame $index reason", snap.reason, group.group.stateReason)
        assertEquals("frame $index notices", snap.notices, notices)
        if (!snap.queue) {
            assertNull("frame $index queue", group.queue)
            return
        }
        val queue = group.queue ?: error("frame $index missing queue")
        assertEquals(0, queue.playingIndex)
        assertEquals(0L, queue.startPositionTicks)
        assertEquals(false, queue.isPlaying)
        assertEquals(ITEM_ID, queue.playingItemId.toString())
        assertEquals(PLAYLIST_ID, queue.playingPlaylistItemId.toString())
        assertEquals(UPDATED, queue.lastUpdate)
    }

    private fun commandAt(index: Int): SendCommandType = commands[index] ?: error("frame $index was a command with no expected type")

    private fun inGroup(participants: List<String>): TogetherState.InGroup =
        TogetherState.InGroup(
            group =
                TogetherGroup(
                    id = UUID.fromString(GROUP_ID),
                    name = "Fixture party",
                    participants = participants,
                    state = GroupStateType.WAITING,
                    stateReason = "Buffer",
                ),
            queue = null,
        )

    private data class Snap(
        val participants: List<String>,
        val state: GroupStateType,
        val reason: String?,
        val queue: Boolean,
        val notices: List<TogetherNotice> = emptyList(),
    )

    private companion object {
        const val GROUP_ID = "f2bcd403-697b-465a-a611-2080860162db"
        const val ITEM_ID = "1a27aba7-d863-9d76-2d76-2bf9f64218fa"
        const val PLAYLIST_ID = "91bcff78-fa6b-43cb-895d-c750cfb92c30"
        val UPDATED: Instant = Instant.parse("2026-09-22T17:17:55.149097300Z")

        val commands =
            mapOf(
                1 to SendCommandType.STOP,
                6 to SendCommandType.PAUSE,
                7 to SendCommandType.UNPAUSE,
                9 to SendCommandType.PAUSE,
                11 to SendCommandType.UNPAUSE,
                13 to SendCommandType.SEEK,
                17 to SendCommandType.UNPAUSE,
                19 to SendCommandType.PAUSE,
                21 to SendCommandType.UNPAUSE,
                23 to SendCommandType.STOP,
            )

        val snaps =
            mapOf(
                0 to Snap(listOf("admin"), GroupStateType.IDLE, null, queue = false),
                2 to Snap(listOf("admin"), GroupStateType.IDLE, null, queue = true),
                3 to Snap(listOf("admin"), GroupStateType.WAITING, "Buffer", queue = true),
                4 to
                    Snap(
                        listOf("admin", "friend"),
                        GroupStateType.WAITING,
                        "Buffer",
                        queue = true,
                        notices = listOf(TogetherNotice.Joined("friend")),
                    ),
                5 to Snap(listOf("admin", "friend"), GroupStateType.WAITING, "Buffer", queue = true),
                8 to Snap(listOf("admin", "friend"), GroupStateType.PLAYING, "Ready", queue = true),
                10 to Snap(listOf("admin", "friend"), GroupStateType.WAITING, "Buffer", queue = true),
                12 to Snap(listOf("admin", "friend"), GroupStateType.PLAYING, "Ready", queue = true),
                14 to Snap(listOf("admin", "friend"), GroupStateType.WAITING, "Seek", queue = true),
                15 to Snap(listOf("admin", "friend"), GroupStateType.WAITING, "Buffer", queue = true),
                16 to Snap(listOf("admin", "friend"), GroupStateType.WAITING, "Buffer", queue = true),
                18 to Snap(listOf("admin", "friend"), GroupStateType.PLAYING, "Ready", queue = true),
                20 to Snap(listOf("admin", "friend"), GroupStateType.PAUSED, "Pause", queue = true),
                22 to Snap(listOf("admin", "friend"), GroupStateType.PLAYING, "Unpause", queue = true),
            )
    }
}
