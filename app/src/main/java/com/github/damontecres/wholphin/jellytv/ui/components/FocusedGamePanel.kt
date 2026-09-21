package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * The large panel mirroring the focused game card: kicker, big mono scores, situation,
 * last play, and a black bar with the channel and key hints.
 *
 * [game] == null renders an empty panel of the same height so the layout does not jump.
 */
@Composable
fun FocusedGamePanel(
    game: JtvGame?,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(JtvDimens.heroHeight)
                .border(JtvDimens.hairline, JtvColors.ruleStrong)
                .background(JtvColors.groundRaised),
    ) {
        if (game != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        modifier =
                            Modifier
                                .weight(0.55f)
                                .fillMaxHeight(),
                    ) {
                        Kicker(game)
                        HeroTeamLine(
                            team = game.away,
                            game = game,
                            home = false,
                            hideScores = hideScores,
                            modifier = Modifier.weight(1f),
                        )
                        HeroTeamLine(
                            team = game.home,
                            game = game,
                            home = true,
                            hideScores = hideScores,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Column(
                        modifier =
                            Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                                .padding(start = 28.dp)
                                .drawBehind {
                                    drawRect(JtvColors.rule, size = Size(1.dp.toPx(), size.height))
                                }.padding(start = 28.dp),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    when {
                                        game.isLive -> R.string.jtv_situation
                                        game.isFinal -> R.string.jtv_hero_final
                                        else -> R.string.jtv_hero_starts
                                    },
                                ).uppercase(),
                            style = JtvType.label,
                            color = JtvColors.muted,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (hideScores) {
                            Text(
                                text = stringResource(R.string.jtv_scores_hidden),
                                style = JtvType.body,
                                color = JtvColors.muted,
                            )
                        } else if (!game.isLive) {
                            Text(
                                text = gameStatusLabel(game).uppercase(),
                                style = JtvType.situation,
                                color = if (game.isFinal) JtvColors.textSecondary else JtvColors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (game.broadcasts.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.jtv_hero_on, game.broadcasts.joinToString(", ")),
                                    style = JtvType.body,
                                    color = JtvColors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Situation(game)
                            Spacer(Modifier.height(10.dp))
                            game.lastPlay?.let {
                                Text(
                                    text = it,
                                    style = JtvType.body,
                                    color = JtvColors.textSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                WatchBar(game)
            }
        }
    }
}

@Composable
private fun Kicker(game: JtvGame) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IndicatorSquare(
            color = if (game.isLive) JtvColors.live else JtvColors.ruleStrong,
            size = 10.dp,
        )
        Text(
            text = "${game.league} · ${gameStatusLabel(game)}".uppercase(),
            style = JtvType.labelLarge,
            color = if (game.isLive) JtvColors.liveText else JtvColors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroTeamLine(
    team: JtvTeam,
    game: JtvGame,
    home: Boolean,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    val loser = game.isFinal && !team.winner
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        TeamMark(team = team, size = 78.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = team.shortName.ifBlank { team.abbr },
                style = JtvType.teamHero,
                color = if (loser) JtvColors.muted else JtvColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(
                        team.record,
                        stringResource(if (home) R.string.jtv_home else R.string.jtv_away).uppercase(),
                    ).joinToString(" · "),
                style = JtvType.label,
                color = JtvColors.muted,
                maxLines = 1,
            )
        }
        if (team.possession && game.isLive) {
            IndicatorSquare(color = JtvColors.accent, size = 10.dp)
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
                style = JtvType.scoreHero,
                color = if (loser) JtvColors.muted else JtvColors.text,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Situation(game: JtvGame) {
    when (game.sport) {
        "football" ->
            game.downDistance?.let {
                Text(
                    text = it.uppercase(),
                    style = JtvType.situation,
                    color = JtvColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        "baseball" -> {
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
                val count =
                    if (game.balls != null && game.strikes != null) {
                        "${game.balls}-${game.strikes}"
                    } else {
                        null
                    }
                val outs = game.outs?.let { pluralStringResource(R.plurals.jtv_outs, it, it) }
                val situation = listOfNotNull(count, outs).joinToString(" · ")
                if (situation.isNotBlank()) {
                    Text(
                        text = situation,
                        style = JtvType.situation,
                        color = JtvColors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
        else ->
            if (game.detail.isNotBlank()) {
                Text(
                    text = game.detail.uppercase(),
                    style = JtvType.situation,
                    color = JtvColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}

@Composable
private fun WatchBar(game: JtvGame) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .border(JtvDimens.hairline, JtvColors.ruleStrong)
                .background(JtvColors.labelBar)
                .padding(horizontal = 12.dp),
    ) {
        val watch = game.watch
        if (watch != null) {
            IndicatorSquare(color = if (game.isLive) JtvColors.live else JtvColors.ruleStrong)
            Text(
                text = watch.channelName.uppercase(),
                style = JtvType.label,
                color = JtvColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                KeyHint(
                    key = stringResource(R.string.jtv_key_ok),
                    label = stringResource(R.string.jtv_watch),
                )
                KeyHint(
                    key = stringResource(R.string.jtv_key_hold),
                    label = stringResource(R.string.jtv_add_to_multiview),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.jtv_not_on_your_channels).uppercase(),
                style = JtvType.label,
                color = JtvColors.muted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (game.broadcasts.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.jtv_on_broadcasters, game.broadcasts.joinToString(", ")),
                    style = JtvType.hint,
                    color = JtvColors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@PreviewTvSpec
@Composable
private fun FocusedGamePanelPreview() {
    JtvSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            FocusedGamePanel(game = JtvSamples.liveFootball, hideScores = false)
            FocusedGamePanel(game = JtvSamples.liveBaseball, hideScores = false)
        }
    }
}

@PreviewTvSpec
@Composable
private fun FocusedGamePanelEmptyPreview() {
    JtvSurface {
        FocusedGamePanel(game = null, hideScores = false)
    }
}
