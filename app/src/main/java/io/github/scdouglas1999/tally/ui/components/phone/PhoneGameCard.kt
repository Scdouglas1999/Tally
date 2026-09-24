package io.github.scdouglas1999.tally.ui.components.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.spoilerGuarded
import io.github.scdouglas1999.tally.dvr.ui.RecTag
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.ScoreDigits
import io.github.scdouglas1999.tally.ui.components.StartsIn
import io.github.scdouglas1999.tally.ui.components.TeamMark
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.components.hasNoResult
import io.github.scdouglas1999.tally.ui.components.parseGameStart
import io.github.scdouglas1999.tally.ui.components.rememberCardNow
import io.github.scdouglas1999.tally.ui.components.startsInLabel
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

private val StatusHeight = 32.dp
private val TeamLineHeight = 44.dp
private val FooterHeight = 28.dp
private val MarkSize = 28.dp
private val CardPadding = 12.dp

/**
 * A game on a phone: the TV [io.github.scdouglas1999.tally.ui.components.GameCard] as a full-width card (it also
 * fits [PhoneDimens.gameCardWidth]). A status line (league, the FOLLOWING mark, and at the right `■ LIVE` in `live`
 * red with the inning or clock, the start time, or the result), two team lines (mark, name in `PhoneType.headline`,
 * score in `PhoneType.score` rolling as on the TV, the loser dimmed when final unless scores are hidden), and the
 * black label bar with the channel or NOT ON YOUR CHANNELS. A game on none of your channels is drawn at 60%, as on
 * the TV. A tap calls [onClick] and a long-press [onLongClick] (both open the game sheet where it is used).
 */
@Composable
fun PhoneGameCard(
    game: TallyGame,
    hideScores: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    followed: Boolean = false,
) {
    val watchable = game.watch != null
    // No spoilers: a finished game with a recording keeps its score and result out of sight.
    val scoresHidden = hideScores || game.spoilerGuarded
    Column(
        modifier =
            modifier
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .background(TallyColors.ground)
                .phoneClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().alpha(if (watchable) 1f else DIMMED_ALPHA)) {
            PhoneGameStatusLine(
                game = game,
                isFavorite = isFavorite,
                followed = followed,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(StatusHeight)
                        .padding(horizontal = CardPadding),
            )
            if (game.away.isBlankTeam() && game.home.isBlankTeam()) {
                // A channel with no game (the player's switcher lists them when nothing is live): its name in place of
                // two empty team lines.
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(TeamLineHeight * 2)
                            .padding(horizontal = CardPadding),
                ) {
                    Text(
                        text = game.watch?.channelName ?: game.name,
                        style = PhoneType.headline,
                        color = TallyColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                PhoneTeamLine(team = game.away, game = game, hideScores = scoresHidden)
                PhoneTeamLine(team = game.home, game = game, hideScores = scoresHidden)
            }
            PhoneGameFooter(
                text = game.watch?.channelName ?: stringResource(R.string.tally_not_on_your_channels),
                live = game.isLive && watchable,
                muted = !watchable,
            )
        }
    }
}

private const val DIMMED_ALPHA = 0.6f

private fun TallyTeam.isBlankTeam(): Boolean = abbr.isBlank() && shortName.isBlank() && name.isBlank()

/**
 * The card's status line: the league (accent for a favorite) and FOLLOWING, then at the right the state. Live:
 * `■ LIVE` in `live` red and the detail (`BOT 7TH`, `8:25 - 1ST`). A followed team's game about to start: IN 23 MIN /
 * STARTING in accent (the TV card's nudge). Otherwise the start time, or the result (FINAL, POSTPONED) muted.
 */
@Composable
internal fun PhoneGameStatusLine(
    game: TallyGame,
    isFavorite: Boolean,
    followed: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = game.league.tallyUppercase(),
            style = PhoneType.label,
            color = if (isFavorite) TallyColors.accent else TallyColors.muted,
            maxLines = 1,
        )
        if (followed) {
            IndicatorSquare(color = TallyColors.accent, size = 6.dp)
            Text(
                text = stringResource(R.string.tally_actions_following_mark).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
            )
        }
        RecTag(recording = game.recording, style = PhoneType.label, dot = 5.dp)
        Spacer(Modifier.weight(1f))
        if (game.isLive) {
            IndicatorSquare(color = TallyColors.live, size = 6.dp)
            Text(
                text = stringResource(R.string.tally_phone_sports_live).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.liveText,
                maxLines = 1,
            )
            if (game.detail.isNotBlank()) {
                Text(
                    text = game.detail.tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val (text, color) = phoneStatus(game, followed)
            Text(
                text = text,
                style = PhoneType.label,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun phoneStatus(
    game: TallyGame,
    followed: Boolean,
): Pair<String, Color> {
    val now = rememberCardNow(active = followed && game.isUpcoming)
    if (followed && game.isUpcoming) {
        val soon = parseGameStart(game.start)?.let { startsInLabel(it, now) }
        if (soon != null) {
            val text =
                when (soon) {
                    StartsIn.Starting -> stringResource(R.string.tally_actions_starting)
                    is StartsIn.InMinutes -> stringResource(R.string.tally_actions_in_min, soon.minutes)
                }
            return text.tallyUppercase() to TallyColors.accent
        }
    }
    val color = if (game.isUpcoming) TallyColors.textSecondary else TallyColors.muted
    return gameStatusLabel(game).tallyUppercase() to color
}

@Composable
private fun PhoneTeamLine(
    team: TallyTeam,
    game: TallyGame,
    hideScores: Boolean,
) {
    // Dimming the loser names the winner: not while scores are hidden.
    val loser = game.isFinal && !team.winner && !hideScores
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TeamLineHeight)
                .padding(horizontal = CardPadding),
    ) {
        TeamMark(team = team, size = MarkSize)
        Text(
            text = team.shortName.ifBlank { team.abbr },
            style = PhoneType.headline,
            color = if (loser) TallyColors.muted else TallyColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (team.possession && game.isLive) {
            IndicatorSquare(color = TallyColors.accent, size = 6.dp)
        }
        val score = team.score
        if (!game.isUpcoming && !game.hasNoResult && (score != null || hideScores)) {
            ScoreDigits(
                gameId = game.id,
                score = score ?: 0,
                hidden = hideScores,
                style = PhoneType.score,
                color = if (loser) TallyColors.muted else TallyColors.text,
            )
        }
    }
}

/**
 * The card's black label bar: a 1dp `rule` on top, the indicator square (`live` red while live), the channel ([muted]
 * when the game is on none of your channels, [accent] for a label that marks yours), and a [trailing] accent value.
 */
@Composable
internal fun PhoneGameFooter(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    trailing: String? = null,
    accent: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(FooterHeight)
                .background(TallyColors.labelBar)
                .drawBehind {
                    val stroke = PhoneDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, stroke / 2f),
                        end = Offset(size.width, stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.padding(horizontal = CardPadding),
    ) {
        IndicatorSquare(color = if (live) TallyColors.live else TallyColors.ruleStrong, size = 6.dp)
        Text(
            text = text.tallyUppercase(),
            style = PhoneType.label,
            color =
                when {
                    accent -> TallyColors.accent
                    muted -> TallyColors.muted
                    else -> TallyColors.text
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
            )
        }
    }
}
