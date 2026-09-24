package io.github.scdouglas1999.tally.dvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.dvr.DvrViewModel

/**
 * WATCH FROM THE START in the live player: provided by the live player around upstream's player while the game on
 * screen is being recorded, drawn by the TV controls' row ([io.github.scdouglas1999.tally.ui.player.controls
 * .TallyControlsRow]). Null everywhere else.
 */
val LocalStartOverAction = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The live player's WATCH FROM THE START for [game]: null unless the server is recording it and has something to
 * play. It takes the live player's place.
 */
@Composable
fun rememberStartOverAction(game: TallyGame?): (() -> Unit)? {
    val viewModel = hiltViewModel<DvrViewModel>()
    val recording = game?.recording ?: return null
    val path =
        recording.startOverPath?.takeIf { it.isNotBlank() && recording.state == io.github.scdouglas1999.tally.dvr.DvrState.RECORDING }
            ?: return null
    val title = matchupTitle(game)
    return { viewModel.watchFromStart(recording.jobId, path, title, replaceCurrent = true) }
}
