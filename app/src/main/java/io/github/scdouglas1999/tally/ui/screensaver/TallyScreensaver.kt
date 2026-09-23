package io.github.scdouglas1999.tally.ui.screensaver

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LineScore
import io.github.scdouglas1999.tally.ui.components.TallySamples
import io.github.scdouglas1999.tally.ui.components.TeamMark
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.random.Random

/** One game at a time; the next live game after this long. */
private const val CYCLE_MILLIS = 12_000L

/** The only cut in the whole screen: one game fades into the next. */
private const val CROSSFADE_MILLIS = 400

/** Burn-in insurance: the whole composition moves this often… */
private const val NUDGE_MILLIS = 20_000L

/** …by up to this many dp on each axis, drifting over [NUDGE_DRIFT_MILLIS] rather than jumping. */
private const val NUDGE_DP = 24
private const val NUDGE_DRIFT_MILLIS = 1_600

private const val MILLIS_PER_MINUTE = 60_000L

/** Width of the score block. Half the canvas: the lit part of the screen stays well under 40%. */
private val PANEL_WIDTH = 600.dp
private val MARK_SIZE = 96.dp

/** Scores are hidden: an en dash stands in for every number. */
private const val HIDDEN_SCORE = "–"

/**
 * A scores screensaver: when the Tally plugin is present and games are on, the idle screen shows them.
 * Returns false (and draws nothing) when it has nothing to show, so the caller falls back to upstream's screensaver.
 *
 * Built for a panel that will sit on this image for hours: pure black, one game at a time, no motion
 * beyond a 400ms crossfade and a slow nudge of the whole composition every 20 seconds.
 */
@Composable
fun TallyScreensaver(modifier: Modifier = Modifier): Boolean {
    val viewModel: TallyScreensaverViewModel = hiltViewModel()
    // On screen == polling, so the scores move while the idle screen is up and stop when it goes away.
    DisposableEffect(viewModel) {
        viewModel.onShown()
        onDispose { viewModel.onHidden() }
    }
    val state by viewModel.uiState.collectAsState()
    val games = state.liveGames
    if (!state.available || games.isEmpty()) return false

    var cycle by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CYCLE_MILLIS)
            cycle++
        }
    }

    var nudge by remember { mutableStateOf(DpOffset.Zero) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NUDGE_MILLIS)
            nudge =
                DpOffset(
                    x = Random.nextInt(-NUDGE_DP, NUDGE_DP + 1).dp,
                    y = Random.nextInt(-NUDGE_DP, NUDGE_DP + 1).dp,
                )
        }
    }
    val nudgeX by animateDpAsState(
        targetValue = nudge.x,
        animationSpec = tween(NUDGE_DRIFT_MILLIS, easing = LinearEasing),
        label = "jtvScreensaverNudgeX",
    )
    val nudgeY by animateDpAsState(
        targetValue = nudge.y,
        animationSpec = tween(NUDGE_DRIFT_MILLIS, easing = LinearEasing),
        label = "jtvScreensaverNudgeY",
    )

    TallyScale {
        IdleScreen(
            games = games,
            shown = games[cycle % games.size],
            hideScores = state.hideScores,
            clock = rememberClock(),
            offsetX = nudgeX,
            offsetY = nudgeY,
            modifier = modifier,
        )
    }
    return true
}

/**
 * The idle screen itself: [shown] centered, a "N live" count and the wall clock in the top corners.
 * [games] is the whole live set, so a score that changes mid-crossfade still lands in the right half.
 */
@Composable
private fun IdleScreen(
    games: List<TallyGame>,
    shown: TallyGame,
    hideScores: Boolean,
    clock: String,
    offsetX: Dp,
    offsetY: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset(x = offsetX, y = offsetY)
                    .padding(horizontal = 64.dp, vertical = 48.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.tally_ss_live_count, games.size, games.size).uppercase(),
                style = TallyType.label,
                color = TallyColors.muted,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                text = clock,
                style = TallyType.label,
                color = TallyColors.muted,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            Crossfade(
                targetState = shown.id,
                animationSpec = tween(CROSSFADE_MILLIS),
                label = "jtvScreensaverGame",
                modifier = Modifier.align(Alignment.Center),
            ) { id ->
                // Look the game up again: the outgoing half of the crossfade keeps drawing its own
                // game, and a score that arrives from the poll updates in place without a fade.
                val game = games.firstOrNull { it.id == id }
                if (game != null) {
                    GameScore(game = game, hideScores = hideScores)
                }
            }
        }
    }
}

@Composable
private fun GameScore(
    game: TallyGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(PANEL_WIDTH)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IndicatorSquare(color = TallyColors.live, size = 10.dp)
            Text(
                text = "${game.league} · ${gameStatusLabel(game)}".uppercase(),
                style = TallyType.labelLarge,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        TeamLine(team = game.away, hideScores = hideScores)
        TeamLine(team = game.home, hideScores = hideScores)
        LineScore(
            game = game,
            hideScores = hideScores,
            compact = true,
            modifier = Modifier.padding(top = 12.dp),
        )
        val lastPlay = game.lastPlay?.takeIf { it.isNotBlank() && !hideScores }
        if (lastPlay != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = lastPlay,
                style = TallyType.body,
                color = TallyColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TeamLine(
    team: TallyTeam,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        TeamMark(team = team, size = MARK_SIZE)
        Text(
            text = team.shortName.ifBlank { team.abbr },
            style = TallyType.teamHero,
            color = TallyColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text =
                when {
                    hideScores -> HIDDEN_SCORE
                    else -> team.score?.toString() ?: HIDDEN_SCORE
                },
            style = TallyType.scoreHero,
            color = TallyColors.text,
            maxLines = 1,
        )
    }
}

/** The wall clock, 12h or 24h as the device is set, reticking on the minute. */
@Composable
private fun rememberClock(): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val clock by produceState(format.format(Date()), format) {
        while (true) {
            value = format.format(Date())
            delay(MILLIS_PER_MINUTE - System.currentTimeMillis() % MILLIS_PER_MINUTE)
        }
    }
    return clock
}

/** DET @ BUF quarters from board-sample-periods.json, so the preview shows a line score. */
private val previewScreensaverGame =
    TallySamples.liveFootball.copy(
        state = "in",
        league = "NFL",
        detail = "2:11 - 4th",
        away =
            TallySamples.liveFootball.away.copy(
                abbr = "DET",
                shortName = "Lions",
                score = 31,
                periods = listOf(0, 10, 7, 14),
            ),
        home =
            TallySamples.liveFootball.home.copy(
                abbr = "BUF",
                shortName = "Bills",
                score = 41,
                periods = listOf(14, 13, 7, 7),
            ),
    )

@PreviewTvSpec
@Composable
private fun TallyScreensaverPreview() {
    TallyScale {
        IdleScreen(
            games = listOf(previewScreensaverGame, TallySamples.liveBaseball),
            shown = previewScreensaverGame,
            hideScores = false,
            clock = "9:41 PM",
            offsetX = 0.dp,
            offsetY = 0.dp,
        )
    }
}

@PreviewTvSpec
@Composable
private fun TallyScreensaverHiddenScoresPreview() {
    TallyScale {
        IdleScreen(
            games = listOf(TallySamples.liveBaseball),
            shown = TallySamples.liveBaseball,
            hideScores = true,
            clock = "21:41",
            offsetX = 0.dp,
            offsetY = 0.dp,
        )
    }
}
