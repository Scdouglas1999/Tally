package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvTeam
import com.github.damontecres.wholphin.jellytv.ui.components.BaseballDiamond
import com.github.damontecres.wholphin.jellytv.ui.components.JtvSamples
import com.github.damontecres.wholphin.jellytv.ui.components.LineScore
import com.github.damontecres.wholphin.jellytv.ui.components.gameStatusLabel
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

private val boxTitle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

/**
 * Opened with UP while watching: the game's line score, situation and last play over a scrim at the top of
 * the picture. Not focusable; the page closes it on BACK/UP.
 *
 * The scrim is 85% black at the top edge and transparent at 40% of the picture height. It stays dark
 * across the type, then falls away, so the line stays readable over a bright picture.
 */
@Composable
fun BoxScoreOverlay(
    game: JtvGame?,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null) return
    Box(modifier = modifier.fillMaxWidth()) {
        // The scrim is sized by the content, not the screen: dark behind every line, then a fade below the last.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(boxScrim)
                    .padding(
                        start = JtvDimens.marginHorizontal,
                        end = JtvDimens.marginHorizontal,
                        top = JtvDimens.marginVertical,
                        bottom = SCRIM_FADE,
                    ),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.jtv_box_matchup,
                        teamLabel(game.away),
                        teamLabel(game.home),
                    ),
                style = boxTitle,
                color = JtvColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = boxKicker(game),
                style = JtvType.labelLarge,
                color = if (game.isLive) JtvColors.liveText else JtvColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            if (hideScores) {
                Text(
                    text = stringResource(R.string.jtv_scores_hidden),
                    style = JtvType.body,
                    color = JtvColors.muted,
                    maxLines = 1,
                )
            } else {
                LineScore(game = game, hideScores = false, compact = false)
                Spacer(Modifier.height(12.dp))
                BoxScoreSituation(game)
                val lastPlay = game.lastPlay?.takeIf { it.isNotBlank() }
                if (lastPlay != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = lastPlay,
                        style = JtvType.body,
                        color = JtvColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** How far below the last line of text the scrim takes to fade out. */
private val SCRIM_FADE = 96.dp

private val boxScrim =
    Brush.verticalGradient(
        // Hold the dark across all of the text, then fall away over the bottom padding.
        0f to Color.Black.copy(alpha = 0.86f),
        0.7f to Color.Black.copy(alpha = 0.8f),
        1f to Color.Transparent,
    )

@Composable
private fun boxKicker(game: JtvGame): String {
    val status = gameStatusLabel(game)
    val broadcasts = game.broadcasts.joinToString(", ")
    return listOf(game.league, status, broadcasts)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .uppercase()
}

@Composable
private fun BoxScoreSituation(game: JtvGame) {
    when (game.sport) {
        "football" -> {
            val down = game.downDistance?.takeIf { it.isNotBlank() } ?: return
            Text(
                text = down.uppercase(),
                style = JtvType.situation,
                color = JtvColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        "baseball" -> {
            val count =
                if (game.balls != null && game.strikes != null) {
                    "${game.balls}-${game.strikes}"
                } else {
                    null
                }
            val outs = game.outs?.let { pluralStringResource(R.plurals.jtv_outs, it, it) }
            val line = listOfNotNull(count, outs).joinToString(" · ")
            if (line.isBlank() && !game.onFirst && !game.onSecond && !game.onThird) return
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BaseballDiamond(
                    onFirst = game.onFirst,
                    onSecond = game.onSecond,
                    onThird = game.onThird,
                    size = 30.dp,
                )
                if (line.isNotBlank()) {
                    Text(
                        text = line,
                        style = JtvType.situation,
                        color = JtvColors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun teamLabel(team: JtvTeam): String = team.shortName.ifBlank { team.name }.ifBlank { team.abbr }

@PreviewTvSpec
@Composable
private fun BoxScoreOverlayPreview() {
    val game =
        JtvSamples.final.copy(
            sport = "football",
            league = "NFL",
            detail = "Final",
            broadcasts = listOf("Prime Video"),
            away =
                JtvSamples.final.away.copy(
                    abbr = "DET",
                    shortName = "Lions",
                    score = 31,
                    periods = listOf(0, 10, 7, 14),
                ),
            home =
                JtvSamples.final.home.copy(
                    abbr = "BUF",
                    shortName = "Bills",
                    score = 41,
                    winner = true,
                    periods = listOf(14, 13, 7, 7),
                ),
        )
    JtvSurface {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1E4D32)),
        ) {
            BoxScoreOverlay(game = game, hideScores = false, modifier = Modifier.fillMaxSize())
        }
    }
}
