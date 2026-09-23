package io.github.scdouglas1999.tally.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.media.home.HomeHeaderHeight
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.components.hasNoResult
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
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

/** The item header's title style (Sans SemiBold 40/46), one line. */
private val GameTitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    )

/** Same slots as the item header, so the band does not change shape when focus moves from a film to a game. */
private val GameTitleSlot = 56.dp
private val GameMetaLine = 24.dp
private val GameTextWidth = 640.dp

/**
 * The Tally home page's header while a game card has focus, in the item header's type scale and layout
 * ([io.github.scdouglas1999.tally.media.home.HomeHeader]): kicker `MLB · TOP 3RD` in accent, the matchup as the
 * title, the score line in mono (the broadcasts for a game that has not started), the last play as the overview
 * line. With scores hidden there is no score and no last play. Fixed height, fixed slots: nothing below moves.
 */
@Composable
fun TallyGameHeader(
    game: TallyGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    val kicker = listOf(game.league, gameStatusLabel(game)).filter { it.isNotBlank() }.joinToString(" · ")
    val title = stringResource(R.string.tally_actions_at, game.away.headerName(), game.home.headerName())
    val showScore = !hideScores && !game.isUpcoming && !game.hasNoResult
    val meta =
        if (showScore) {
            "${game.away.abbr} ${game.away.score ?: 0} · ${game.home.abbr} ${game.home.score ?: 0}"
        } else {
            game.broadcasts.joinToString(" · ")
        }
    val overview =
        when {
            hideScores -> ""
            !game.lastPlay.isNullOrBlank() -> game.lastPlay
            showScore && game.broadcasts.isNotEmpty() -> stringResource(R.string.tally_hero_on, game.broadcasts.joinToString(", "))
            else -> ""
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(HomeHeaderHeight)
                .clipToBounds(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .padding(start = TallyDimens.marginHorizontal, top = TallyDimens.marginVertical)
                    .widthIn(max = GameTextWidth),
        ) {
            Text(
                text = kicker.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.height(GameTitleSlot)) {
                Text(
                    text = title,
                    style = GameTitleStyle,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.height(GameMetaLine)) {
                Text(
                    text = meta.tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = overview,
                style = TallyType.body,
                color = TallyColors.textSecondary,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun TallyTeam.headerName(): String = shortName.ifBlank { abbr.ifBlank { name } }
