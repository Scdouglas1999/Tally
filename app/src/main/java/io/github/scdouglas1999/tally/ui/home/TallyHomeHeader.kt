package io.github.scdouglas1999.tally.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** The game whose card is focused on the home row, for the page header; null when none is. */
object TallyHomeHeaderState {
    val focusedGame = mutableStateOf<TallyGame?>(null)
    val hideScores = mutableStateOf(false)
}

/**
 * Takes the place of Wholphin's home header while a game card has focus: the matchup as the title, then league,
 * clock and last play. Same footprint as [HeaderUtils.modifier] so the rows below do not move.
 */
@Composable
fun TallyHomeHeader(
    game: TallyGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.then(HeaderUtils.modifier)) {
        Text(
            text = "${game.away.shortName.ifBlank { game.away.abbr }} at ${game.home.shortName.ifBlank { game.home.abbr }}",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        val score =
            if (hideScores || !game.isLive) {
                null
            } else {
                "${game.away.abbr} ${game.away.score ?: 0} · ${game.home.abbr} ${game.home.score ?: 0}"
            }
        Text(
            text = listOfNotNull(game.league.uppercase(), gameStatusLabel(game).uppercase(), score).joinToString("   "),
            style = TallyType.label.copy(fontSize = 16.sp, letterSpacing = 2.sp),
            color = TallyColors.accent,
            maxLines = 1,
        )
        val detail = if (hideScores) null else game.lastPlay?.takeIf { it.isNotBlank() }
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (game.broadcasts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(R.string.tally_hero_on, game.broadcasts.joinToString(", ")),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
