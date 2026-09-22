package com.github.damontecres.wholphin.jellytv.ui.player

import com.github.damontecres.wholphin.jellytv.api.JtvGame
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot hand-off from the game actions menu to the corner overlay.
 * The menu publishes a game; another task plays it. Nothing here consumes [requested].
 */
object CornerRequests {
    val requested = MutableStateFlow<JtvGame?>(null)

    fun request(game: JtvGame) {
        requested.value = game
    }
}
