package io.github.scdouglas1999.tally.ui.components.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.ui.GameDvr
import io.github.scdouglas1999.tally.dvr.ui.phone.PhoneGameDvrBlock
import io.github.scdouglas1999.tally.dvr.ui.phone.PhoneKeepLastSheet
import io.github.scdouglas1999.tally.dvr.ui.phone.PhoneTeamRuleRow
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.components.BaseballDiamond
import io.github.scdouglas1999.tally.ui.components.GameActions
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LineScore
import io.github.scdouglas1999.tally.ui.components.ScoreDigits
import io.github.scdouglas1999.tally.ui.components.TeamMark
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.components.hasNoResult
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * The game sheet: what a tap on a game opens on a phone (and a long-press, the TV's long OK). The focused-game
 * panel's content ([PhoneGamePanel]: matchup with records, the situation and last play, the line score, broadcasts,
 * the start time before the game), the channel's label bar and a full-width WATCH when a channel carries the game,
 * then the TV game menu's other actions: add to multiview, follow each team, hide / show scores, remove from
 * multiview. Follow and hide scores toggle in place (the sheet stays, its rows and the scores update); the others
 * close the sheet after they run, as on the TV. The corner view is not offered on a phone.
 *
 * [game] null is a multiview tile's channel with no game on it right now: [channelName] heads the sheet and the
 * follow rows are left out.
 */
@Composable
fun PhoneGameSheet(
    game: TallyGame?,
    actions: GameActions,
    onDismiss: () -> Unit,
    channelName: String = "",
    dvr: GameDvr? = null,
) {
    // No spoilers: a finished game with a recording shows its score only behind SHOW THE SCORE.
    var scoreShown by remember(game?.id) { mutableStateOf(false) }
    var keepTeam by remember { mutableStateOf<TallyTeam?>(null) }
    val guarded = dvr?.guarded == true
    val recordingItem = dvr?.watchableItemId?.takeIf { guarded }
    PhoneSheet(onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
        ) {
            if (game != null) {
                PhoneGamePanel(
                    game = game,
                    hideScores = actions.hideScores || (guarded && !scoreShown),
                    modifier = Modifier.padding(horizontal = PhoneDimens.margin),
                )
                Spacer(Modifier.height(16.dp))
                PhoneGameFooter(
                    text = game.watch?.channelName ?: stringResource(R.string.tally_not_on_your_channels),
                    live = game.isLive && game.watch != null,
                    muted = game.watch == null,
                    modifier = Modifier.padding(horizontal = PhoneDimens.margin),
                )
            } else {
                Text(
                    text = channelName,
                    style = PhoneType.title,
                    color = TallyColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = PhoneDimens.margin),
                )
            }
            if (dvr != null && recordingItem != null) {
                Spacer(Modifier.height(12.dp))
                PhoneButton(
                    label = stringResource(R.string.tally_dvr_watch_recording),
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    onClick = {
                        dvr.watchRecording()
                        onDismiss()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PhoneDimens.margin),
                )
            }
            actions.watch?.let { watch ->
                Spacer(Modifier.height(12.dp))
                PhoneButton(
                    label = stringResource(actions.watchLabel),
                    glyph = stringResource(R.string.fa_play),
                    // The recording leads for a finished game that has one.
                    primary = recordingItem == null,
                    onClick = {
                        watch()
                        onDismiss()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PhoneDimens.margin),
                )
            }
            dvr?.let { PhoneGameDvrBlock(dvr = it, onDismiss = onDismiss) }
            Spacer(Modifier.height(8.dp))
            actions.addToMultiview?.let { add ->
                SheetActionRow(
                    label = stringResource(R.string.tally_actions_multiview),
                    onClick = {
                        add()
                        onDismiss()
                    },
                )
            }
            if (game != null) {
                actions.followAway?.let { follow ->
                    SheetActionRow(
                        label = followLabel(actions.followedAway, game.away),
                        on = actions.followedAway,
                        onClick = follow,
                    )
                }
                dvr?.let { PhoneTeamRuleRow(dvr = it, team = game.away, onOpen = { team -> keepTeam = team }) }
                actions.followHome?.let { follow ->
                    SheetActionRow(
                        label = followLabel(actions.followedHome, game.home),
                        on = actions.followedHome,
                        onClick = follow,
                    )
                }
                dvr?.let { PhoneTeamRuleRow(dvr = it, team = game.home, onOpen = { team -> keepTeam = team }) }
            }
            SheetActionRow(
                label =
                    stringResource(
                        if (actions.hideScores) R.string.tally_actions_show_scores else R.string.tally_actions_hide_scores,
                    ),
                onClick = actions.toggleHideScores,
            )
            if (guarded) {
                SheetActionRow(
                    label =
                        stringResource(
                            if (scoreShown) R.string.tally_dvr_hide_the_score else R.string.tally_dvr_show_the_score,
                        ),
                    onClick = { scoreShown = !scoreShown },
                )
            }
            actions.removeFromMultiview?.let { remove ->
                SheetActionRow(
                    label = stringResource(R.string.tally_actions_remove_multiview),
                    onClick = {
                        remove()
                        onDismiss()
                    },
                )
            }
        }
    }
    val keepFor = keepTeam
    if (dvr != null && keepFor != null) {
        PhoneKeepLastSheet(
            team = keepFor,
            rule = dvr.ruleFor(keepFor),
            onChoose = { keepLast -> dvr.recordTeam(keepFor, keepLast) },
            onDelete = { dvr.ruleFor(keepFor)?.let(dvr::deleteRule) },
            onDismiss = { keepTeam = null },
        )
    }
}

@Composable
private fun followLabel(
    followed: Boolean,
    team: TallyTeam,
): String =
    stringResource(
        if (followed) R.string.tally_actions_following else R.string.tally_actions_follow,
        team.shortName.ifBlank { team.abbr.ifBlank { team.name } },
    )

/** A sheet row ([PhoneDialogRow]): the label; a toggle that is on ([on]) carries the accent square. */
@Composable
internal fun SheetActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean? = null,
) {
    PhoneDialogRow(
        onClick = onClick,
        marked = on == true,
        modifier = modifier,
        headline = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * The TV's focused-game panel at phone size, stacked: the kicker (`■ MLB · BOT 7TH`, red while live), the two teams
 * (mark, name in `PhoneType.title`, record · AWAY / HOME, the score in `PhoneType.scoreHero`, rolling), then by state:
 * live: the situation in accent (count, outs and the diamond; down and distance) and the last play; upcoming: STARTS
 * and the start time in accent; final: the result. Then the line score (live and final) and the broadcasts. With
 * scores hidden: "Scores hidden" in place of the situation, the last play and the line score, as on the TV.
 */
@Composable
fun PhoneGamePanel(
    game: TallyGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IndicatorSquare(color = if (game.isLive) TallyColors.live else TallyColors.ruleStrong, size = 8.dp)
            Text(
                text = listOf(game.league, gameStatusLabel(game)).filter { it.isNotBlank() }.joinToString(" · ").tallyUppercase(),
                style = PhoneType.labelLarge,
                color = if (game.isLive) TallyColors.liveText else TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        PanelTeamLine(team = game.away, game = game, home = false, hideScores = hideScores)
        Spacer(Modifier.height(8.dp))
        PanelTeamLine(team = game.home, game = game, home = true, hideScores = hideScores)
        Spacer(Modifier.height(16.dp))
        when {
            hideScores -> {
                Text(
                    text = stringResource(R.string.tally_scores_hidden),
                    style = PhoneType.body,
                    color = TallyColors.muted,
                )
            }

            game.isLive -> {
                PhoneSituation(game)
                game.lastPlay?.takeIf { it.isNotBlank() }?.let { play ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = play,
                        style = PhoneType.body,
                        color = TallyColors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            game.isUpcoming -> {
                Text(
                    text = stringResource(R.string.tally_phone_sports_starts).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = gameStatusLabel(game).tallyUppercase(),
                    style = PhoneType.title.copy(fontFamily = PhoneType.score.fontFamily),
                    color = TallyColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            else -> {
                Text(
                    text = gameStatusLabel(game).tallyUppercase(),
                    style = PhoneType.title.copy(fontFamily = PhoneType.score.fontFamily),
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!hideScores && (game.isLive || game.isFinal) && !game.hasNoResult) {
            val played = maxOf(game.away.periods.size, game.home.periods.size)
            if (played > 0) {
                Spacer(Modifier.height(16.dp))
                LineScore(game = game, hideScores = false, compact = true)
            }
        }
        if (game.broadcasts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tally_phone_sports_on, game.broadcasts.joinToString(", ")),
                style = PhoneType.bodySmall,
                color = TallyColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PanelTeamLine(
    team: TallyTeam,
    game: TallyGame,
    home: Boolean,
    hideScores: Boolean,
) {
    // Dimming the loser names the winner: not while scores are hidden.
    val loser = game.isFinal && !team.winner && !hideScores
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        TeamMark(team = team, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = team.shortName.ifBlank { team.abbr },
                style = PhoneType.title,
                color = if (loser) TallyColors.muted else TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(
                        team.record,
                        stringResource(if (home) R.string.tally_home else R.string.tally_away),
                    ).joinToString(" · ").tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
        if (team.possession && game.isLive) {
            IndicatorSquare(color = TallyColors.accent, size = 8.dp)
        }
        val score = team.score
        if (!game.isUpcoming && !game.hasNoResult && (score != null || hideScores)) {
            ScoreDigits(
                gameId = game.id,
                score = score ?: 0,
                hidden = hideScores,
                style = PhoneType.scoreHero,
                color = if (loser) TallyColors.muted else TallyColors.text,
            )
        }
    }
}

/** The live situation in accent mono: down and distance; the diamond, count and outs; else the status detail. */
@Composable
internal fun PhoneSituation(game: TallyGame) {
    val style = PhoneType.title.copy(fontFamily = PhoneType.score.fontFamily)
    when (game.sport) {
        "football" -> {
            game.downDistance?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.tallyUppercase(),
                    style = style,
                    color = TallyColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        "baseball" -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BaseballDiamond(
                    onFirst = game.onFirst,
                    onSecond = game.onSecond,
                    onThird = game.onThird,
                    size = 24.dp,
                )
                val count =
                    if (game.balls != null && game.strikes != null) "${game.balls}-${game.strikes}" else null
                val outs = game.outs?.let { pluralStringResource(R.plurals.tally_outs, it, it) }
                val line = listOfNotNull(count, outs).joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(
                        text = line.tallyUppercase(),
                        style = style,
                        color = TallyColors.accent,
                        maxLines = 1,
                    )
                }
            }
        }

        else -> {
            if (game.detail.isNotBlank()) {
                Text(
                    text = game.detail.tallyUppercase(),
                    style = style,
                    color = TallyColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
