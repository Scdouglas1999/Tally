package com.github.damontecres.wholphin.jellytv

import android.view.KeyEvent
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.jellytv.remote.JellyTvRemoteBus
import com.github.damontecres.wholphin.jellytv.remote.displayContentFor
import com.github.damontecres.wholphin.jellytv.remote.keyCodeFor
import com.github.damontecres.wholphin.jellytv.remote.trackCommandFor
import com.github.damontecres.wholphin.jellytv.remote.volumeIndexFor
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RemoteCommandMappingTest {
    @Test
    fun `only d-pad and menu commands map to key codes`() {
        val expected =
            mapOf(
                GeneralCommandType.MOVE_UP to KeyEvent.KEYCODE_DPAD_UP,
                GeneralCommandType.MOVE_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                GeneralCommandType.MOVE_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
                GeneralCommandType.MOVE_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
                GeneralCommandType.SELECT to KeyEvent.KEYCODE_DPAD_CENTER,
                GeneralCommandType.TOGGLE_OSD_MENU to KeyEvent.KEYCODE_MENU,
            )
        for (command in GeneralCommandType.entries) {
            assertEquals(expected[command], keyCodeFor(command))
        }
    }

    @Test
    fun `volume percent scales, rounds half up, and clamps`() {
        assertEquals(0, volumeIndexFor(0, 15))
        assertEquals(15, volumeIndexFor(100, 15))
        // 50% of 15 is 7.5 → 8; 30% of 15 is 4.5 → 5 (the phone's SetVolume 30).
        assertEquals(8, volumeIndexFor(50, 15))
        assertEquals(5, volumeIndexFor(30, 15))
        assertEquals(0, volumeIndexFor(1, 15))
        assertEquals(15, volumeIndexFor(99, 15))
        assertEquals(4, volumeIndexFor(35, 10))
        assertEquals(3, volumeIndexFor(33, 10))
        assertEquals(1, volumeIndexFor(50, 1))
        assertEquals(100, volumeIndexFor(100, 100))
        assertEquals(30, volumeIndexFor(30, 100))
    }

    @Test
    fun `volume percent outside 0 to 100 and a bad max clamp to the stream range`() {
        assertEquals(0, volumeIndexFor(-1, 15))
        assertEquals(0, volumeIndexFor(Int.MIN_VALUE, 15))
        assertEquals(15, volumeIndexFor(101, 15))
        assertEquals(15, volumeIndexFor(Int.MAX_VALUE, 15))
        assertEquals(0, volumeIndexFor(50, 0))
        assertEquals(0, volumeIndexFor(50, -1))
        assertEquals(0, volumeIndexFor(-20, 0))
    }

    @Test
    fun `audio index passes through including off`() {
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Audio(2),
            trackCommandFor(GeneralCommandType.SET_AUDIO_STREAM_INDEX, mapOf("Index" to "2")),
        )
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Audio(0),
            trackCommandFor(GeneralCommandType.SET_AUDIO_STREAM_INDEX, mapOf("Index" to "0")),
        )
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Audio(-1),
            trackCommandFor(GeneralCommandType.SET_AUDIO_STREAM_INDEX, mapOf("Index" to "-1")),
        )
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Audio(3),
            trackCommandFor(GeneralCommandType.SET_AUDIO_STREAM_INDEX, mapOf("Index" to " 3 ")),
        )
    }

    @Test
    fun `subtitle off from the remote is the player's disabled index`() {
        assertEquals(TrackIndex.DISABLED, -2)
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Subtitle(TrackIndex.DISABLED),
            trackCommandFor(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, mapOf("Index" to "-1")),
        )
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Subtitle(4),
            trackCommandFor(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, mapOf("Index" to "4")),
        )
        // An index that is already the player's off value is left alone.
        assertEquals(
            JellyTvRemoteBus.TrackCommand.Subtitle(TrackIndex.DISABLED),
            trackCommandFor(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, mapOf("Index" to "-2")),
        )
    }

    @Test
    fun `track commands reject a missing or junk index and every other command`() {
        val bad =
            listOf(
                emptyMap(),
                mapOf("Index" to null),
                mapOf("Index" to ""),
                mapOf("Index" to "   "),
                mapOf("Index" to "nope"),
                mapOf("Index" to "1.5"),
                mapOf("Index" to "1e2"),
                mapOf("Volume" to "1"),
            )
        for (arguments in bad) {
            assertNull(trackCommandFor(GeneralCommandType.SET_AUDIO_STREAM_INDEX, arguments))
            assertNull(trackCommandFor(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, arguments))
        }
        val withIndex = mapOf("Index" to "1")
        for (command in GeneralCommandType.entries) {
            if (command == GeneralCommandType.SET_AUDIO_STREAM_INDEX ||
                command == GeneralCommandType.SET_SUBTITLE_STREAM_INDEX
            ) {
                continue
            }
            assertNull(trackCommandFor(command, withIndex))
        }
    }

    @Test
    fun `display content accepts a dashed or bare id and a serial kind`() {
        val dashed = UUID.fromString("11111111-2222-3333-4444-555555555555")
        assertEquals(
            dashed to BaseItemKind.SERIES,
            displayContentFor(
                mapOf(
                    "ItemId" to "11111111-2222-3333-4444-555555555555",
                    "ItemType" to "Series",
                    "ItemName" to "Severance",
                ),
            ),
        )
        val bare = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertEquals(
            UUID.fromString(bare) to BaseItemKind.MOVIE,
            displayContentFor(
                mapOf(
                    "ItemId" to "aaaaaaaabbbbccccddddeeeeeeeeeeee",
                    "ItemType" to "Movie",
                ),
            ),
        )
        assertEquals(
            dashed to BaseItemKind.EPISODE,
            displayContentFor(
                mapOf(
                    "ItemId" to "  11111111-2222-3333-4444-555555555555  ",
                    "ItemType" to " Episode ",
                ),
            ),
        )
    }

    @Test
    fun `display content ignores a bad id or unknown kind`() {
        val goodId = mapOf("ItemId" to "11111111-2222-3333-4444-555555555555")
        assertNull(displayContentFor(emptyMap()))
        assertNull(displayContentFor(goodId))
        assertNull(displayContentFor(goodId + ("ItemType" to null)))
        assertNull(displayContentFor(goodId + ("ItemType" to "")))
        assertNull(displayContentFor(goodId + ("ItemType" to "series")))
        assertNull(displayContentFor(goodId + ("ItemType" to "NotAKind")))
        assertNull(displayContentFor(goodId + ("ItemType" to "Mov ie")))
        assertNull(
            displayContentFor(
                mapOf(
                    "ItemId" to "not-a-uuid",
                    "ItemType" to "Movie",
                    "ItemName" to "Inception",
                ),
            ),
        )
        assertNull(
            displayContentFor(
                mapOf(
                    "ItemId" to null,
                    "ItemType" to "Movie",
                ),
            ),
        )
        assertNull(
            displayContentFor(
                mapOf(
                    "ItemId" to "",
                    "ItemType" to "Movie",
                ),
            ),
        )
        assertNull(
            displayContentFor(
                mapOf(
                    "ItemType" to "Series",
                    "ItemName" to "Severance",
                ),
            ),
        )
    }
}
