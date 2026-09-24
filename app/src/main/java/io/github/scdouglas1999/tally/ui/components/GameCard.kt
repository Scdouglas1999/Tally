package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.spoilerGuarded
import io.github.scdouglas1999.tally.dvr.ui.RecTag
import io.github.scdouglas1999.tally.media.kit.tallyClickable
import io.github.scdouglas1999.tally.ui.components.phone.PhoneGameCard
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A game card: league + status strip, two team lines, and a black channel label bar.
 *
 * Focused = 3dp accent border on groundRaised; unfocused = 1dp ruleStrong border on ground.
 * No scale, no glow. A game that is not on your channels ([TallyGame.watch] == null) stays
 * focusable so the hero panel can show it, but its content is dimmed and its click is a no-op.
 *
 * [followed] draws the FOLLOWING mark. A followed team's game that starts within 90 minutes
 * replaces the start time with [startsInLabel].
 */
@Composable
fun GameCard(
    game: TallyGame,
    hideScores: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    followed: Boolean = false,
    onFocused: () -> Unit = {},
) {
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        // On a phone a tap opens the game sheet (what a long OK opens on the TV), as long-press does: the sheet
        // carries WATCH. The home row places these cards at the phone card width.
        PhoneGameCard(
            game = game,
            hideScores = hideScores,
            isFavorite = isFavorite,
            followed = followed,
            onClick = onLongClick,
            onLongClick = onLongClick,
            modifier = modifier.width(PhoneDimens.gameCardWidth),
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val watchable = game.watch != null
    // No spoilers: a finished game with a recording keeps its score and result out of sight.
    val scoresHidden = hideScores || game.spoilerGuarded
    val showFocus = tallyFocusVisible()
    val idleBorder =
        Border(
            border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
            shape = RectangleShape,
        )
    val focusBorder =
        if (showFocus) {
            Border(
                border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                shape = RectangleShape,
            )
        } else {
            idleBorder
        }
    Surface(
        onClick = {
            if (watchable) onClick()
        },
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.ground,
                contentColor = TallyColors.text,
                focusedContainerColor = if (showFocus) TallyColors.groundRaised else TallyColors.ground,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
                disabledContainerColor = TallyColors.ground,
                disabledContentColor = TallyColors.textSecondary,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = idleBorder,
                focusedBorder = focusBorder,
                pressedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
                disabledBorder =
                    Border(
                        border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedDisabledBorder = focusBorder,
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .size(TallyDimens.cardWidth, TallyDimens.cardHeight)
                .tallyClickable(onClick = { if (watchable) onClick() }, onLongClick = onLongClick),
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
                    style = TallyType.label,
                    color = if (isFavorite) TallyColors.accent else TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (followed) {
                    IndicatorSquare(color = TallyColors.accent, size = 8.dp)
                    Text(
                        text = stringResource(R.string.tally_actions_following_mark).uppercase(),
                        style = TallyType.label,
                        color = TallyColors.accent,
                        maxLines = 1,
                    )
                }
                RecTag(recording = game.recording, style = TallyType.label.copy(fontSize = 12.sp), dot = 6.dp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = statusText,
                    style = TallyType.label,
                    color = statusColor,
                    maxLines = 1,
                )
            }
            if (game.away.isBlankTeam() && game.home.isBlankTeam()) {
                // A channel with no game (the player's switcher lists them when nothing is live): its name in place
                // of two empty team lines.
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = game.watch?.channelName ?: game.name,
                        style = TallyType.teamCard,
                        color = TallyColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    GameCardTeamLine(
                        team = game.away,
                        game = game,
                        hideScores = scoresHidden,
                        modifier = Modifier.weight(1f),
                    )
                    GameCardTeamLine(
                        team = game.home,
                        game = game,
                        hideScores = scoresHidden,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            LabelBar(
                text = game.watch?.channelName ?: stringResource(R.string.tally_not_on_your_channels),
                live = game.isLive && watchable,
            )
        }
    }
}

private fun TallyTeam.isBlankTeam(): Boolean = abbr.isBlank() && shortName.isBlank() && name.isBlank()

@Composable
private fun GameCardTeamLine(
    team: TallyTeam,
    game: TallyGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    // Dimming the loser names the winner: not while scores are hidden.
    val loser = game.isFinal && !team.winner && !hideScores
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
            style = TallyType.teamCard,
            color = if (loser) TallyColors.muted else TallyColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (team.possession && game.isLive) {
            IndicatorSquare(color = TallyColors.accent)
        }
        val score = team.score
        if (!game.isUpcoming && !game.hasNoResult && (score != null || hideScores)) {
            ScoreDigits(
                gameId = game.id,
                score = score ?: 0,
                hidden = hideScores,
                style = TallyType.score,
                color = if (loser) TallyColors.muted else TallyColors.text,
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
    game: TallyGame,
    followed: Boolean,
    now: Instant,
): Pair<String, Color> {
    if (followed && game.isUpcoming) {
        val soon = parseGameStart(game.start)?.let { startsInLabel(it, now) }
        if (soon != null) {
            val text =
                when (soon) {
                    StartsIn.Starting -> stringResource(R.string.tally_actions_starting).uppercase()
                    is StartsIn.InMinutes -> stringResource(R.string.tally_actions_in_min, soon.minutes).uppercase()
                }
            return text to TallyColors.accent
        }
    }
    val color =
        when {
            game.isLive -> TallyColors.accent
            game.isUpcoming -> TallyColors.textSecondary
            else -> TallyColors.muted
        }
    return gameStatusLabel(game) to color
}

@Composable
internal fun rememberCardNow(active: Boolean): Instant {
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

internal fun parseGameStart(start: String): Instant? =
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
    TallySurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TallyDimens.cardGap),
            modifier = Modifier.padding(TallyDimens.marginHorizontal / 2),
        ) {
            GameCard(
                game = TallySamples.liveFootball,
                hideScores = false,
                isFavorite = true,
                followed = true,
                onClick = {},
                onLongClick = {},
            )
            GameCard(
                game = TallySamples.upcoming,
                hideScores = false,
                isFavorite = false,
                followed = true,
                onClick = {},
                onLongClick = {},
            )
            GameCard(
                game = TallySamples.final,
                hideScores = true,
                isFavorite = false,
                onClick = {},
                onLongClick = {},
            )
        }
    }
}
