package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.jellytv.api.JtvGame

/**
 * The line score: one column per period (Q1..Q4/OT, innings 1..9+), a row per team, totals at the end.
 * Renders nothing when [game] has no period data or [hideScores]. STUB.
 */
@Composable
fun LineScore(
    game: JtvGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
}
