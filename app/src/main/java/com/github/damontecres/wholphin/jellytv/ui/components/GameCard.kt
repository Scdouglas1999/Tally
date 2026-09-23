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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A game card: league + status strip, two team lines, and a black channel label bar.
 *
 * Focused = 3dp accent border on groundRaised; unfocused = 1dp ruleStrong border on ground.
 * No scale, no glow. A game that is not on your channels ([JtvGame.watch] == null) stays
 * focusable so the hero panel can show it, but its content is dimmed and its click is a no-op.
 *
 * [followed] draws the FOLLOWING mark. A followed team's game that starts within 90 minutes
 * replaces the start time with [startsInLabel].
 */
@Composable
fun GameCard(
    game: JtvGame,
    hideScores: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    followed: Boolean = false,
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
            val now = rememberCardNow(active = followed && game.isUpcoming)
            val (statusText, statusColor) = cardStatus(game, followed, now)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (followed) {
                    IndicatorSquare(color = JtvColors.accent, size = 8.dp)
                    Text(
                        text = stringResource(R.string.jtv_actions_following_mark).uppercase(),
                        style = JtvType.label,
                        color = JtvColors.accent,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = statusText,
                    style = JtvType.label,
                    color = statusColor,
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
        val score = team.score
        if (!game.isUpcoming && (score != null || hideScores)) {
            ScoreDigits(
                gameId = game.id,
                score = score ?: 0,
                hidden = hideScores,
                style = JtvType.score,
                color = if (loser) JtvColors.muted else JtvColors.text,
            )
        }
    }
}

/**
 * How soon a followed team's game starts, for the card clock.
 *
 * - more than 90 minutes away, or 60 seconds or more past [now]: null (show the start time)
 * - otherwise under 60 seconds until start, including up to 59 seconds past: [StartsIn.Starting]
 * - otherwise, within 90 minutes: [StartsIn.InMinutes] ("IN N MIN"), whole minutes, truncated
 */
fun startsInLabel(
    start: Instant,
    now: Instant,
): StartsIn? {
    val seconds = Duration.between(now, start).seconds
    return when {
        seconds > STARTS_IN_WINDOW_SECONDS -> null
        seconds <= -STARTING_WINDOW_SECONDS -> null
        seconds < STARTING_WINDOW_SECONDS -> StartsIn.Starting
        else -> StartsIn.InMinutes((seconds / SECONDS_PER_MINUTE).toInt())
    }
}

/** "STARTING", "IN 23 MIN", matching [startsInLabel]. */
fun StartsIn.text(): String =
    when (this) {
        StartsIn.Starting -> "STARTING"
        is StartsIn.InMinutes -> "IN $minutes MIN"
    }

sealed interface StartsIn {
    data object Starting : StartsIn

    data class InMinutes(
        val minutes: Int,
    ) : StartsIn
}

@Composable
private fun cardStatus(
    game: JtvGame,
    followed: Boolean,
    now: Instant,
): Pair<String, Color> {
    if (followed && game.isUpcoming) {
        val soon = parseGameStart(game.start)?.let { startsInLabel(it, now) }
        if (soon != null) {
            val text =
                when (soon) {
                    StartsIn.Starting -> stringResource(R.string.jtv_actions_starting).uppercase()
                    is StartsIn.InMinutes -> stringResource(R.string.jtv_actions_in_min, soon.minutes).uppercase()
                }
            return text to JtvColors.accent
        }
    }
    val color =
        when {
            game.isLive -> JtvColors.accent
            game.isUpcoming -> JtvColors.textSecondary
            else -> JtvColors.muted
        }
    return gameStatusLabel(game) to color
}

@Composable
private fun rememberCardNow(active: Boolean): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(CARD_CLOCK_TICK_MS)
            now = Instant.now()
        }
    }
    return now
}

private fun parseGameStart(start: String): Instant? =
    try {
        OffsetDateTime.parse(start).toInstant()
    } catch (_: DateTimeException) {
        null
    }

private const val STARTS_IN_WINDOW_SECONDS = 90L * 60L
private const val STARTING_WINDOW_SECONDS = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val CARD_CLOCK_TICK_MS = 10_000L

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
                followed = true,
                onClick = {},
                onLongClick = {},
            )
            GameCard(
                game = JtvSamples.upcoming,
                hideScores = false,
                isFavorite = false,
                followed = true,
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
