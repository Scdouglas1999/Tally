package io.github.scdouglas1999.tally.ui.player.controls.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBarAction
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/** Width of the player's side panel on a phone (landscape). */
val PhonePanelWidth = 360.dp

/** The dimming at the panel's edge; none at the left edge of the picture. */
private const val EDGE_DIM = 0.45f

/**
 * One row of the side panel: [label], a mono [value] at the right, an accent square when [current]; [onClick] null
 * shows the row grayed and not choosable (as upstream grays it).
 */
data class PhonePanelRow(
    val key: Any,
    val label: String,
    val value: String? = null,
    val current: Boolean = false,
    val onClick: (() -> Unit)?,
)

/**
 * The player's settings on a phone: the TV's side panel as a [PhonePanelWidth] panel sliding in from the right over
 * the picture, which stays visible (dimmed by a horizontal gradient: nothing at the left edge, rising to the panel)
 * so subtitle and scale changes can be seen. A header with the page's accent kicker ([kicker]), a mono [readout] at
 * the right, then [lines] (plain text, e.g. what is playing now) and the [rows], scrolling, all tappable. [onBack]
 * (a sub-page) shows a back arrow; otherwise a close button. Back and a tap on the picture call [onDismiss]. The
 * system bars stay hidden.
 */
@Composable
fun PhoneSidePanel(
    kicker: String,
    rows: List<PhonePanelRow>,
    onDismiss: () -> Unit,
    readout: String? = null,
    lines: List<String> = emptyList(),
    onBack: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onBack ?: onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val edge = (size.width - PhonePanelWidth.toPx()).coerceAtLeast(1f)
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = EDGE_DIM),
                                    startX = 0f,
                                    endX = edge,
                                ),
                        )
                    }.pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = slideInHorizontally(tween(180)) { it },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(PhonePanelWidth)
                            .background(TallyColors.ground)
                            .drawBehind {
                                val stroke = PhoneDimens.hairline.toPx()
                                drawLine(
                                    color = TallyColors.ruleStrong,
                                    start = Offset(stroke / 2f, 0f),
                                    end = Offset(stroke / 2f, size.height),
                                    strokeWidth = stroke,
                                )
                            }
                            // Taps inside the panel are the panel's, never the picture's.
                            .pointerInput(Unit) { detectTapGestures { } }
                            // Only the panel's own (right) edge can meet a camera cutout.
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
                    ) {
                        if (onBack != null) {
                            PhoneTopBarAction(
                                glyph = R.string.tally_phone_fa_arrow_left,
                                label = stringResource(R.string.tally_phone_back),
                                onClick = onBack,
                            )
                        } else {
                            PhoneTopBarAction(
                                glyph = R.string.fa_xmark,
                                label = stringResource(R.string.tally_phone_player_close),
                                onClick = onDismiss,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = kicker.tallyUppercase(),
                            style = PhoneType.labelLarge,
                            color = TallyColors.accent,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (readout != null) {
                            Text(
                                text = readout.tallyUppercase(),
                                style = PhoneType.labelLarge,
                                color = TallyColors.text,
                                maxLines = 1,
                            )
                        }
                    }
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 12.dp),
                    ) {
                        lines.forEachIndexed { index, line ->
                            Text(
                                text = line,
                                style = if (index == 0) PhoneType.meta else PhoneType.bodySmall,
                                color = if (index == 0) TallyColors.textSecondary else TallyColors.muted,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                            )
                        }
                        if (lines.isNotEmpty()) Spacer(Modifier.height(4.dp))
                        rows.forEach { row -> PanelRowView(row) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelRowView(row: PhonePanelRow) {
    val enabled = row.onClick != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(if (enabled) Modifier.phoneClickable(onClick = row.onClick!!) else Modifier)
                .drawBehind {
                    val stroke = PhoneDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(16.dp.toPx(), stroke / 2f),
                        end = Offset(size.width - 16.dp.toPx(), stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = row.label,
            style = PhoneType.body,
            color = if (enabled) TallyColors.text else TallyColors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.value != null) {
            Text(
                text = row.value.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.current) IndicatorSquare(color = TallyColors.accent, size = 8.dp)
    }
}
