package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToLong

/** How long one character cell takes to roll to its new glyph. */
const val ROLL_MS = 220

/** How long a stat number takes to count up from 0 (Your Year). */
const val COUNT_UP_MS = 900

/** Cubic ease-out: fast start, settles gently. */
fun easeOutCubic(fraction: Float): Float {
    val inverse = 1f - fraction.coerceIn(0f, 1f)
    return 1f - inverse * inverse * inverse
}

/** [easeOutCubic] as a Compose [Easing]. */
val EaseOutCubic = Easing { easeOutCubic(it) }

/** The number a count-up toward [target] shows at [progress] (0..1, already eased). */
fun countUpValue(
    target: Long,
    progress: Float,
): Long = if (progress >= 1f) target else (target * progress.coerceIn(0f, 1f).toDouble()).roundToLong()

/** How long a score that just went up stays drawn in the accent color. */
const val SCORE_HIGHLIGHT_HOLD_MS = 2_500L

/** How long the accent takes to ease back to the score's normal color. */
const val SCORE_HIGHLIGHT_FADE_MS = 400

/**
 * Scoreboard digits: draws [text] one character cell at a time (monospaced fonts only, so a cell
 * never changes width). When [text] changes, every character that changed rolls inside its own
 * clipped cell: the old glyph moves up and out while the new one comes up from below, [ROLL_MS]
 * ease-out. Unchanged characters stay still. A change that arrives mid-roll restarts that cell
 * from where it is. Cells are aligned right, so a longer text grows to the left and its new
 * cells roll in from below. Nothing animates on first composition.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // False only while the first composition of this RollingText is being built: cells that
    // appear later (the text got longer) roll in instead of just appearing.
    val composed = remember { ComposedFlag() }
    val animateNewCells = composed.value
    Row(modifier) {
        val length = text.length
        for (index in 0 until length) {
            // Keyed by position from the right, so "9" -> "10" keeps the ones cell.
            key(length - 1 - index) {
                RollingCell(
                    char = text[index],
                    style = style,
                    color = color,
                    rollIn = animateNewCells,
                )
            }
        }
    }
    LaunchedEffect(Unit) { composed.value = true }
}

private class ComposedFlag {
    var value = false
}

@Composable
private fun RollingCell(
    char: Char,
    style: TextStyle,
    color: Color,
    rollIn: Boolean,
) {
    // The glyph that is (or is rolling) in, the one rolling out, and where the outgoing one
    // started (in cell heights: 0 = in place, 1 = one cell below).
    var current by remember { mutableStateOf(char) }
    var previous by remember { mutableStateOf(if (rollIn) ' ' else char) }
    var previousFrom by remember { mutableStateOf(0f) }
    val progress = remember { Animatable(if (rollIn) 0f else 1f) }
    // Bumped whenever the roll restarts from 0; the running roll is not restarted by changes that
    // only swap the incoming glyph (else a counter changing every frame would never move).
    var rollId by remember { mutableIntStateOf(0) }
    LaunchedEffect(char) {
        if (char == current) return@LaunchedEffect
        // Restart from where the cell is. If the incoming glyph is more in view than the one
        // leaving, it becomes the one leaving (from where it is) and the new glyph comes up from
        // below. If it has barely started, the new glyph just takes its place and the roll goes on.
        val restart = rollRestart(previousFrom, progress.value)
        if (!restart.keepPrevious) {
            previous = current
            previousFrom = restart.from
            progress.snapTo(0f)
            rollId++
        }
        current = char
    }
    LaunchedEffect(rollId) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(durationMillis = ROLL_MS, easing = EaseOut))
        }
    }
    Box(Modifier.clipToBounds()) {
        val p = progress.value
        if (p < 1f && previous != ' ') {
            Text(
                text = previous.toString(),
                style = style,
                color = color,
                maxLines = 1,
                modifier =
                    Modifier.graphicsLayer {
                        translationY = rollOutgoingOffset(previousFrom, p) * size.height
                    },
            )
        }
        Text(
            text = current.toString(),
            style = style,
            color = color,
            maxLines = 1,
            modifier =
                Modifier.graphicsLayer {
                    translationY = rollIncomingOffset(p) * size.height
                },
        )
    }
}

/** Offset of the incoming glyph, in cell heights (1 = one cell below, 0 = in place). */
fun rollIncomingOffset(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

/**
 * Offset of the outgoing glyph, in cell heights: from where it was when the roll started
 * ([from], 0 when it was at rest) up to one cell above (-1).
 */
fun rollOutgoingOffset(
    from: Float,
    progress: Float,
): Float {
    val p = progress.coerceIn(0f, 1f)
    return from + (-1f - from) * p
}

/**
 * An interrupted roll. [keepPrevious] = the glyph already leaving is still more in view than the one
 * coming in: it keeps leaving, and the new glyph simply replaces the incoming one ([from] is where the
 * leaving glyph started, unchanged). Otherwise the incoming glyph becomes the leaving one, from where
 * it is ([from]), and the roll restarts. A cell at rest restarts with its glyph leaving from 0.
 */
data class RollRestart(
    val keepPrevious: Boolean,
    val from: Float,
)

fun rollRestart(
    previousFrom: Float,
    progress: Float,
): RollRestart {
    val incoming = rollIncomingOffset(progress)
    val outgoing = rollOutgoingOffset(previousFrom, progress)
    return if (abs(incoming) <= abs(outgoing)) RollRestart(false, incoming) else RollRestart(true, previousFrom)
}

/** True when a score went up between two board updates (not on the first value seen). */
fun scoreWentUp(
    old: Int?,
    new: Int?,
): Boolean = old != null && new != null && new > old

/**
 * The color to draw a team's score in: [base], except that when [score] goes up while this is
 * on screen it switches to [JtvColors.accent] for [SCORE_HIGHLIGHT_HOLD_MS], then eases back to
 * [base] over [SCORE_HIGHLIGHT_FADE_MS]. Callers key this (and the [RollingText] it colors) by
 * game and team, so switching to another game is not a score change.
 */
@Composable
fun rememberScoreColor(
    score: Int?,
    base: Color,
): Color {
    val color = remember { Animatable(base) }
    var lastScore by remember { mutableStateOf(score) }
    var highlighting by remember { mutableStateOf(false) }
    val currentBase by rememberUpdatedState(base)
    LaunchedEffect(score) {
        val wentUp = scoreWentUp(lastScore, score)
        lastScore = score
        if (wentUp) {
            highlighting = true
            color.snapTo(JtvColors.accent)
            delay(SCORE_HIGHLIGHT_HOLD_MS)
        }
        // Also finishes a highlight that a later board update interrupted.
        if (highlighting) {
            color.animateTo(currentBase, tween(durationMillis = SCORE_HIGHLIGHT_FADE_MS, easing = EaseOut))
            highlighting = false
        }
    }
    LaunchedEffect(base) {
        if (!highlighting) color.snapTo(base)
    }
    return color.value
}

/**
 * A team's score as scoreboard digits, colored by [rememberScoreColor]. [hidden] (spoiler mode)
 * draws a plain dash that never rolls; when scores are shown again the digits simply appear.
 * Keyed by [gameId], so a panel that switches to another game does not roll.
 */
@Composable
fun ScoreDigits(
    gameId: String,
    score: Int,
    hidden: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (hidden) {
        Text(
            text = "\u2013",
            style = style,
            color = color,
            maxLines = 1,
            modifier = modifier,
        )
        return
    }
    key(gameId) {
        RollingText(
            text = score.toString(),
            style = style,
            color = rememberScoreColor(score, color),
            modifier = modifier,
        )
    }
}
