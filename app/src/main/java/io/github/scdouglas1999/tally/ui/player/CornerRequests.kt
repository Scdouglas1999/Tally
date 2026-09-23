package io.github.scdouglas1999.tally.ui.player

import io.github.scdouglas1999.tally.api.TallyGame
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot hand-off from the game actions menu to the corner overlay.
 * The menu publishes a game; another task plays it. Nothing here consumes [requested].
 */
object CornerRequests {
    val requested = MutableStateFlow<TallyGame?>(null)

    fun request(game: TallyGame) {
        requested.value = game
    }
}
