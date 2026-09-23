package io.github.scdouglas1999.tally.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.RollingText
import io.github.scdouglas1999.tally.ui.components.TallySamples
import io.github.scdouglas1999.tally.ui.components.rememberScoreColor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

private val scoreBugBorder = Color(0xFF5A5D57)

/**
 * The in-player score bug: "IND 7 · KC 0" over the clock/situation line, top-end of the
 * picture. Renders nothing unless [game] is live and scores are visible.
 */
@Composable
fun ScoreBug(
    game: TallyGame?,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null || !game.isLive || hideScores) return
    Column(
        modifier =
            modifier
                .border(TallyDimens.hairline, scoreBugBorder, RectangleShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (game.away.possession) {
                IndicatorSquare(color = TallyColors.accent)
            }
            ScoreLine(game)
        }
        val situation = situationLine(game)
        if (situation.isNotBlank()) {
            Text(
                text = situation.uppercase(),
                style = TallyType.clock,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/**
 * "IND 7 · KC 0" — away first, scores semibold. The scores are scoreboard digits: a score that
 * goes up rolls and shows in the accent color for a moment. Keyed by game, so switching games
 * is not a score change.
 */
@Composable
private fun ScoreLine(game: TallyGame) {
    key(game.id) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = game.away.abbr.ifBlank { game.away.shortName } + " ",
                style = TallyType.situation,
                color = TallyColors.text,
                maxLines = 1,
            )
            BugScore(game.away.score)
            Text(
                text = " · " + game.home.abbr.ifBlank { game.home.shortName } + " ",
                style = TallyType.situation,
                color = TallyColors.text,
                maxLines = 1,
            )
            BugScore(game.home.score)
        }
    }
}

@Composable
private fun BugScore(score: Int?) {
    RollingText(
        text = score?.toString() ?: "–",
        style = TallyType.situation.copy(fontWeight = FontWeight.SemiBold),
        color = rememberScoreColor(score, TallyColors.text),
    )
}

/**
 * The small line under the score: the status detail plus the sport-specific situation —
 * down & distance for football, count & outs for baseball.
 */
@Composable
private fun situationLine(game: TallyGame): String {
    val situation =
        when (game.sport) {
            "football" -> {
                game.downDistance
            }

            "baseball" -> {
                val count =
                    if (game.balls != null && game.strikes != null) {
                        "${game.balls}-${game.strikes}"
                    } else {
                        null
                    }
                val outs = game.outs?.let { pluralStringResource(R.plurals.tally_outs, it, it) }
                listOfNotNull(count, outs).joinToString(" · ").ifBlank { null }
            }

            else -> {
                null
            }
        }
    return listOfNotNull(game.detail.ifBlank { null }, situation).joinToString(" · ")
}

@PreviewTvSpec
@Composable
private fun ScoreBugPreview() {
    TallySurface {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreBug(game = TallySamples.liveFootball, hideScores = false)
            ScoreBug(game = TallySamples.liveBaseball, hideScores = false)
            ScoreBug(game = TallySamples.liveFootball, hideScores = true)
        }
    }
}
