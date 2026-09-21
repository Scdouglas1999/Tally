package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

enum class JtvTab(
    @StringRes val title: Int,
) {
    GAMES(R.string.jtv_tab_games),
    CHANNELS(R.string.jtv_tab_channels),
    MULTIVIEW(R.string.jtv_tab_multiview),
    SETTINGS(R.string.jtv_tab_settings),
}

/**
 * JellyTV top bar: the wordmark, focusable tabs, and the clock.
 * Moving focus onto a tab does not select it; pressing OK calls [onSelect].
 */
@Composable
fun JtvTopBar(
    tabs: List<JtvTab>,
    selected: JtvTab,
    onSelect: (JtvTab) -> Unit,
    clock: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(JtvDimens.topBarHeight)
                .background(JtvColors.ground)
                .drawBehind {
                    val strokeWidth = JtvDimens.hairline.toPx()
                    val y = size.height - strokeWidth / 2f
                    drawLine(
                        color = JtvColors.rule,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }.padding(horizontal = JtvDimens.marginHorizontal),
    ) {
        Text(
            text = stringResource(R.string.jtv_wordmark),
            style =
                JtvType.teamCard.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 5.sp,
                ),
            color = JtvColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(36.dp))
        tabs.forEach { tab ->
            JtvTabItem(
                tab = tab,
                selected = tab == selected,
                onSelect = { onSelect(tab) },
                modifier = Modifier.fillMaxHeight(),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = clock,
            style = JtvType.clock.copy(fontSize = 22.sp),
            color = JtvColors.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun JtvTabItem(
    tab: JtvTab,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val contentColor =
        when {
            selected -> JtvColors.accent
            focused -> JtvColors.text
            else -> JtvColors.textSecondary
        }
    Surface(
        onClick = onSelect,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = contentColor,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = contentColor,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = contentColor,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border.None,
                focusedDisabledBorder = Border.None,
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier = modifier.semantics { role = Role.Tab },
    ) {
        Box(Modifier.fillMaxHeight()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 18.dp),
            ) {
                IndicatorSquare(color = contentColor)
                Text(
                    text = stringResource(tab.title).uppercase(),
                    style = JtvType.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                )
            }
            if (selected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(JtvDimens.focusBorder)
                            .background(JtvColors.accent),
                )
            }
        }
    }
}

@PreviewTvSpec
@Composable
private fun JtvTopBarPreview() {
    JtvSurface {
        JtvTopBar(
            tabs = JtvTab.entries,
            selected = JtvTab.GAMES,
            onSelect = {},
            clock = "9:41 PM",
        )
    }
}
