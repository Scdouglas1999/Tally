package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.compose.runtime.mutableStateOf
import java.util.UUID

/**
 * Requests from the players' settings menu to the JellyTV overlay host (which lives above every screen):
 * upstream's dialog only knows how to name an entry, the JellyTV overlays do the rest.
 */
object JellyTvPlayerMenu {
    enum class Request { SLEEP_TIMER, SEND_TO }

    val request = mutableStateOf<Request?>(null)

    /** The item the upstream player is on, published by a seam in PlaybackViewModel. */
    @Volatile
    var nowPlayingItemId: UUID? = null
}
