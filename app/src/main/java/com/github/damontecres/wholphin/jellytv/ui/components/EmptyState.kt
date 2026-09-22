package com.github.damontecres.wholphin.jellytv.ui.components

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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.tryRequestFocus

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
                    if (focused) {
                        drawRect(color = JtvColors.accent, style = Stroke(width = JtvDimens.focusBorder.toPx()))
                    } else {
                        val dash = 6.dp.toPx()
                        drawRect(
                            color = JtvColors.ruleStrong,
                            style =
                                Stroke(
                                    width = JtvDimens.hairline.toPx(),
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
                style = JtvType.teamCard,
                color = JtvColors.text,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = JtvType.hint,
                color = JtvColors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun EmptyStatePreview() {
    JtvSurface {
        EmptyState(
            title = "No games",
            subtitle = "Nothing is on your channels right now",
            modifier = Modifier.fillMaxSize(),
        )
    }
}
