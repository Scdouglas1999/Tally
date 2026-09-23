package io.github.scdouglas1999.tally.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/** How long the accent bar stands alone before the panel starts to open. */
const val LOWER_THIRD_BAR_LEAD_MS = 60

/** How long the panel takes to open, left to right. */
const val LOWER_THIRD_REVEAL_MS = 200

/** How long the panel takes to close, right to left. */
const val LOWER_THIRD_HIDE_MS = 150

/** Width of the accent bar at the left edge. */
val LowerThirdBarWidth = 3.dp

/**
 * A broadcast caption's entrance for a transient notice. Enter: a 3dp accent bar appears at the
 * left edge at full height, [LOWER_THIRD_BAR_LEAD_MS] later a clip reveals [content] left to right
 * over [LOWER_THIRD_REVEAL_MS] (ease-out). Exit: the clip narrows right to left over
 * [LOWER_THIRD_HIDE_MS], then the bar goes and [onExited] is called. No fades, no slides; the
 * lower third takes its final size from the first frame.
 *
 * Composed with [visible] already true, it enters (a notice is new when it is composed).
 */
@Composable
fun LowerThird(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onExited: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var present by remember { mutableStateOf(visible) }
    var barShown by remember { mutableStateOf(visible) }
    val reveal = remember { Animatable(0f) }
    val exited by rememberUpdatedState(onExited)
    LaunchedEffect(visible) {
        if (visible) {
            present = true
            barShown = true
            reveal.animateTo(
                1f,
                tween(
                    durationMillis = LOWER_THIRD_REVEAL_MS,
                    delayMillis = if (reveal.value == 0f) LOWER_THIRD_BAR_LEAD_MS else 0,
                    easing = EaseOut,
                ),
            )
        } else if (present) {
            reveal.animateTo(0f, tween(durationMillis = LOWER_THIRD_HIDE_MS, easing = EaseOut))
            barShown = false
            present = false
            exited()
        }
    }
    if (!present) return
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(LowerThirdBarWidth)
                .fillMaxHeight()
                .then(if (barShown) Modifier.background(TallyColors.accent) else Modifier),
        )
        Box(
            Modifier.drawWithContent {
                clipRect(right = size.width * reveal.value) {
                    this@drawWithContent.drawContent()
                }
            },
        ) {
            content()
        }
    }
}
