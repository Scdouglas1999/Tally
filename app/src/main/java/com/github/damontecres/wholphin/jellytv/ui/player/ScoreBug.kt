package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.ui.components.IndicatorSquare
import com.github.damontecres.wholphin.jellytv.ui.components.JtvSamples
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

private val scoreBugBorder = Color(0xFF5A5D57)

/**
 * The in-player score bug: "IND 7 · KC 0" over the clock/situation line, top-end of the
 * picture. Renders nothing unless [game] is live and scores are visible.
 */
@Composable
fun ScoreBug(
    game: JtvGame?,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null || !game.isLive || hideScores) return
    Column(
        modifier =
            modifier
                .border(JtvDimens.hairline, scoreBugBorder, RectangleShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (game.away.possession) {
                IndicatorSquare(color = JtvColors.accent)
            }
            Text(
                text = scoreLine(game),
                style = JtvType.situation,
                color = JtvColors.text,
                maxLines = 1,
            )
        }
        val situation = situationLine(game)
        if (situation.isNotBlank()) {
            Text(
                text = situation.uppercase(),
                style = JtvType.clock,
                color = JtvColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** "IND 7 · KC 0" — away first, scores semibold. */
private fun scoreLine(game: JtvGame) =
    buildAnnotatedString {
        append(game.away.abbr.ifBlank { game.away.shortName })
        append(" ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(game.away.score?.toString() ?: "–")
        }
        append(" · ")
        append(game.home.abbr.ifBlank { game.home.shortName })
        append(" ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(game.home.score?.toString() ?: "–")
        }
    }

/**
 * The small line under the score: the status detail plus the sport-specific situation —
 * down & distance for football, count & outs for baseball.
 */
@Composable
private fun situationLine(game: JtvGame): String {
    val situation =
        when (game.sport) {
            "football" -> game.downDistance
            "baseball" -> {
                val count =
                    if (game.balls != null && game.strikes != null) {
                        "${game.balls}-${game.strikes}"
                    } else {
                        null
                    }
                val outs = game.outs?.let { pluralStringResource(R.plurals.jtv_outs, it, it) }
                listOfNotNull(count, outs).joinToString(" · ").ifBlank { null }
            }
            else -> null
        }
    return listOfNotNull(game.detail.ifBlank { null }, situation).joinToString(" · ")
}

@PreviewTvSpec
@Composable
private fun ScoreBugPreview() {
    JtvSurface {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreBug(game = JtvSamples.liveFootball, hideScores = false)
            ScoreBug(game = JtvSamples.liveBaseball, hideScores = false)
            ScoreBug(game = JtvSamples.liveFootball, hideScores = true)
        }
    }
}
