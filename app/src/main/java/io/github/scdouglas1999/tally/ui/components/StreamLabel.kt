package io.github.scdouglas1999.tally.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame

/**
 * The label bar's text for a game no channel carries yet ([TallyGame.watch] == null): LOOKING FOR A STREAM while the
 * server searches for it, NO STREAM YET for a game still to come or on, NO STREAM for a final one.
 */
@StringRes
fun noStreamLabelRes(game: TallyGame): Int =
    when {
        game.search?.isSearching == true -> R.string.tally_221_looking_for_stream
        game.isFinal -> R.string.tally_221_no_stream
        else -> R.string.tally_not_on_your_channels
    }

@Composable
fun noStreamLabel(game: TallyGame): String = stringResource(noStreamLabelRes(game))

/** WATCH is offered: the game has a stream, or it is not over (WATCH then looks for one). */
val TallyGame.canWatch: Boolean get() = watch != null || !isFinal
