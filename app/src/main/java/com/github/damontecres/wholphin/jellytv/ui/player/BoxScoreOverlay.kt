package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.jellytv.api.JtvGame

/**
 * Opened with UP while watching: the game's line score, situation and last play over a scrim at the top of
 * the picture. Not focusable; the page closes it on BACK/UP. STUB.
 */
@Composable
fun BoxScoreOverlay(
    game: JtvGame?,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
}
