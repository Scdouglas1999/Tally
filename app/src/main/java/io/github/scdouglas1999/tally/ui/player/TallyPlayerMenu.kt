package io.github.scdouglas1999.tally.ui.player

import androidx.compose.runtime.mutableStateOf
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import java.util.UUID

/**
 * Requests from the players' settings menu to the Tally overlay host (which lives above every screen):
 * upstream's dialog only knows how to name an entry, the Tally overlays do the rest.
 */
object TallyPlayerMenu {
    enum class Request { SLEEP_TIMER, SEND_TO, TOGETHER, QUALITY }

    val request = mutableStateOf<Request?>(null)

    /** The request behind a settings-menu entry, or null for upstream's own entries. */
    fun requestFor(type: PlaybackDialogType): Request? =
        when (type) {
            PlaybackDialogType.TALLY_SLEEP_TIMER -> Request.SLEEP_TIMER
            PlaybackDialogType.TALLY_SEND_TO -> Request.SEND_TO
            PlaybackDialogType.TALLY_TOGETHER -> Request.TOGETHER
            PlaybackDialogType.TALLY_QUALITY -> Request.QUALITY
            else -> null
        }

    /** The item the upstream player is on, published by a seam in PlaybackViewModel. */
    @Volatile
    var nowPlayingItemId: UUID? = null
}
