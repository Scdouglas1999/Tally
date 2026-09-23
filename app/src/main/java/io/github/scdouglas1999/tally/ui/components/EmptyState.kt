package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * Centered title + muted subtitle inside a 1dp dashed ruleStrong border.
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = true,
) {
    // An empty state is a focus target: it usually replaces the only focusable content on the screen, and focus
    // with nowhere to go falls out to the navigation drawer. Focused = solid accent frame; idle = dashed hairline.
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (takeFocus) LaunchedEffect(Unit) { requester.tryRequestFocus("jellytv-empty") }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .drawBehind {
                    // Strokes are inset by half their width so the whole frame lies inside the bounds (a stroke
                    // on the edge puts half of it outside, where a screen edge or a parent clips it).
                    if (focused) {
                        val w = TallyDimens.focusBorder.toPx()
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            style = Stroke(width = w),
                        )
                    } else {
                        val dash = 6.dp.toPx()
                        val w = TallyDimens.hairline.toPx()
                        drawRect(
                            color = TallyColors.ruleStrong,
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            style =
                                Stroke(
                                    width = TallyDimens.hairline.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                                ),
                        )
                    }
                }.padding(32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = TallyType.teamCard,
                color = TallyColors.text,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = TallyType.hint,
                color = TallyColors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun EmptyStatePreview() {
    TallySurface {
        EmptyState(
            title = "No games",
            subtitle = "Nothing is on your channels right now",
            modifier = Modifier.fillMaxSize(),
        )
    }
}
