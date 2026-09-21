package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvTeam
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

/**
 * A game card: league + status strip, two team lines, and a black channel label bar.
 *
 * Focused = 3dp accent border on groundRaised; unfocused = 1dp ruleStrong border on ground.
 * No scale, no glow. A game that is not on your channels ([JtvGame.watch] == null) stays
 * focusable so the hero panel can show it, but its content is dimmed and its click is a no-op.
 */
@Composable
fun GameCard(
    game: JtvGame,
    hideScores: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val watchable = game.watch != null
    Surface(
        onClick = {
            if (watchable) onClick()
        },
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = JtvColors.ground,
                contentColor = JtvColors.text,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = JtvColors.text,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = JtvColors.text,
                disabledContainerColor = JtvColors.ground,
                disabledContentColor = JtvColors.textSecondary,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                disabledBorder =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedDisabledBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier = modifier.size(JtvDimens.cardWidth, JtvDimens.cardHeight),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .alpha(if (watchable) 1f else 0.6f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = game.league.uppercase(),
                    style = JtvType.label,
                    color = if (isFavorite) JtvColors.accent else JtvColors.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = gameStatusLabel(game),
                    style = JtvType.label,
                    color =
                        when {
                            game.isLive -> JtvColors.accent
                            game.isUpcoming -> JtvColors.textSecondary
                            else -> JtvColors.muted
                        },
                    maxLines = 1,
                )
            }
            Column(Modifier.fillMaxWidth().weight(1f)) {
                GameCardTeamLine(
                    team = game.away,
                    game = game,
                    hideScores = hideScores,
                    modifier = Modifier.weight(1f),
                )
                GameCardTeamLine(
                    team = game.home,
                    game = game,
                    hideScores = hideScores,
                    modifier = Modifier.weight(1f),
                )
            }
            LabelBar(
                text = game.watch?.channelName ?: stringResource(R.string.jtv_not_on_your_channels),
                live = game.isLive && watchable,
            )
        }
    }
}

@Composable
private fun GameCardTeamLine(
    team: JtvTeam,
    game: JtvGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    val loser = game.isFinal && !team.winner
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
    ) {
        TeamMark(team = team, size = 32.dp)
        Text(
            text = team.shortName.ifBlank { team.abbr },
            style = JtvType.teamCard,
            color = if (loser) JtvColors.muted else JtvColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (team.possession && game.isLive) {
            IndicatorSquare(color = JtvColors.accent)
        }
        val score =
            when {
                game.isUpcoming -> null
                hideScores -> "\u2013"
                else -> team.score?.toString()
            }
        if (score != null) {
            Text(
                text = score,
                style = JtvType.score,
                color = if (loser) JtvColors.muted else JtvColors.text,
                maxLines = 1,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun GameCardPreview() {
    JtvSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(JtvDimens.cardGap),
            modifier = Modifier.padding(JtvDimens.marginHorizontal / 2),
        ) {
            GameCard(
                game = JtvSamples.liveFootball,
                hideScores = false,
                isFavorite = true,
                onClick = {},
                onLongClick = {},
            )
            GameCard(
                game = JtvSamples.upcoming,
                hideScores = false,
                isFavorite = false,
                onClick = {},
                onLongClick = {},
            )
            GameCard(
                game = JtvSamples.final,
                hideScores = true,
                isFavorite = false,
                onClick = {},
                onLongClick = {},
            )
        }
    }
}
