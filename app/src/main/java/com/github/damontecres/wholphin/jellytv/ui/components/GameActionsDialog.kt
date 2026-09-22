package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.runtime.Composable
import com.github.damontecres.wholphin.jellytv.api.JtvGame

/**
 * What can be done with a game from wherever it is shown. A null action is not offered.
 * [followedAway]/[followedHome] say whether each team is currently followed (the action toggles it).
 */
data class GameActions(
    val watch: (() -> Unit)? = null,
    val addToMultiview: (() -> Unit)? = null,
    val watchInCorner: (() -> Unit)? = null,
    val followAway: () -> Unit,
    val followHome: () -> Unit,
    val followedAway: Boolean,
    val followedHome: Boolean,
    val hideScores: Boolean,
    val toggleHideScores: () -> Unit,
)

/**
 * The long-press menu for a game: a JellyTV-styled dialog listing [actions]. Every action closes the dialog
 * (calls [onDismiss]) after running, except the follow/hide toggles, which update in place.
 * STUB: draws nothing and dismisses immediately.
 */
@Composable
fun GameActionsDialog(
    game: JtvGame,
    actions: GameActions,
    onDismiss: () -> Unit,
) {
    onDismiss()
}
