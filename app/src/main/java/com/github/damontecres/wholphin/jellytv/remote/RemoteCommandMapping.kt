package com.github.damontecres.wholphin.jellytv.remote

import android.view.KeyEvent
import com.github.damontecres.wholphin.data.model.TrackIndex
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import kotlin.math.roundToInt

private const val ARG_INDEX = "Index"
private const val ARG_ITEM_ID = "ItemId"
private const val ARG_ITEM_TYPE = "ItemType"

/**
 * Jellyfin's remote sends -1 to turn subtitles off. Upstream's player uses [TrackIndex.DISABLED]
 * (-2) for that: -1 is [TrackIndex.UNSPECIFIED], which means "no explicit choice" and falls back
 * to the user's subtitle preferences instead of staying off.
 */
private const val REMOTE_SUBTITLE_OFF = -1

/** D-pad and menu commands. Everything else is null (volume, back, and navigation are not keys). */
fun keyCodeFor(command: GeneralCommandType): Int? =
    when (command) {
        GeneralCommandType.MOVE_UP -> KeyEvent.KEYCODE_DPAD_UP
        GeneralCommandType.MOVE_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        GeneralCommandType.MOVE_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        GeneralCommandType.MOVE_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        GeneralCommandType.SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
        GeneralCommandType.TOGGLE_OSD_MENU -> KeyEvent.KEYCODE_MENU
        else -> null
    }

/**
 * Maps a 0–100 volume percent onto a stream index in `0..max`.
 * Half steps round away from zero (4.5 → 5). A non-positive [max] is 0.
 */
fun volumeIndexFor(
    percent: Int,
    max: Int,
): Int {
    if (max <= 0) return 0
    val scaled = (percent.toDouble() / 100.0 * max.toDouble()).roundToInt()
    return scaled.coerceIn(0, max)
}

/**
 * Audio and subtitle stream commands. [arguments] uses the server's `Index` key.
 * Subtitle index [REMOTE_SUBTITLE_OFF] is translated to [TrackIndex.DISABLED].
 * Missing or non-numeric indexes, and any other command, are null.
 */
fun trackCommandFor(
    command: GeneralCommandType,
    arguments: Map<String, String?>,
): JellyTvRemoteBus.TrackCommand? {
    val index = arguments[ARG_INDEX]?.trim()?.toIntOrNull() ?: return null
    return when (command) {
        GeneralCommandType.SET_AUDIO_STREAM_INDEX -> {
            JellyTvRemoteBus.TrackCommand.Audio(index)
        }

        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX -> {
            JellyTvRemoteBus.TrackCommand.Subtitle(
                if (index == REMOTE_SUBTITLE_OFF) TrackIndex.DISABLED else index,
            )
        }

        else -> {
            null
        }
    }
}

/**
 * `DisplayContent` → item id and kind. [BaseItemKind] must be the serial name (`Movie`, `Series`).
 * A bad id or unknown kind is null. `ItemName` is not needed to open the page.
 */
fun displayContentFor(arguments: Map<String, String?>): Pair<UUID, BaseItemKind>? {
    val itemId = arguments[ARG_ITEM_ID]?.trim()?.toUUIDOrNull() ?: return null
    val kind = arguments[ARG_ITEM_TYPE]?.trim()?.let { BaseItemKind.fromNameOrNull(it) } ?: return null
    return itemId to kind
}
