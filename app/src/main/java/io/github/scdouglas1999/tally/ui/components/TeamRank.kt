package io.github.scdouglas1999.tally.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** How big the rank is next to the name it leads: small, as the web app's `.g-rank`. */
private const val RANK_SCALE = 0.62f

/** "#7" for a ranked team (college football: the AP Top 25); null when unranked or the server does not say. */
fun rankLabel(team: TallyTeam): String? = team.pollRank?.let { "#$it" }

/** "#7 BAMA" for plain-text lines (the corner tile's score line); just [name] when the team is unranked. */
fun rankedText(
    team: TallyTeam,
    name: String,
): String = rankLabel(team)?.let { "$it $name" } ?: name

/**
 * [name] led by the team's rank as a small mono "#7" in `muted` ([nameSize] × 0.62), the web app's look; just [name]
 * when the team is unranked or the server predates ranks.
 */
fun rankedName(
    team: TallyTeam,
    name: String,
    nameSize: TextUnit,
): AnnotatedString {
    val rank = rankLabel(team) ?: return AnnotatedString(name)
    return buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontFamily = TallyType.Mono,
                fontWeight = FontWeight.Medium,
                fontSize = nameSize * RANK_SCALE,
                color = TallyColors.muted,
            ),
        ) {
            append(rank)
        }
        append(' ')
        append(name)
    }
}

/**
 * True while the server tags [game] as a likely upset (UPSET ALERT) and scores are visible: the tag says who is losing,
 * so hidden scores hide it too.
 */
fun showsUpsetAlert(
    game: TallyGame?,
    hideScores: Boolean,
): Boolean = game != null && game.isUpsetAlert && !hideScores

/** "UPSET ALERT" when [showsUpsetAlert], else null. */
@Composable
fun upsetAlertLabel(
    game: TallyGame?,
    hideScores: Boolean,
): String? =
    if (showsUpsetAlert(game, hideScores)) {
        stringResource(R.string.tally_233_upset_alert).tallyUppercase()
    } else {
        null
    }
