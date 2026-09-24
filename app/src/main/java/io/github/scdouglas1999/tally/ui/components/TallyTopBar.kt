package io.github.scdouglas1999.tally.ui.components

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

enum class TallyTab(
    @StringRes val title: Int,
) {
    GAMES(R.string.tally_tab_games),
    CHANNELS(R.string.tally_tab_channels),
    MULTIVIEW(R.string.tally_tab_multiview),

    /** Only when the server records (the plugin's `dvr` feature): [tallyTabs]. */
    RECORDINGS(R.string.tally_tab_recordings),
    SETTINGS(R.string.tally_tab_settings),
}

/** The Sports tabs this server offers: RECORDINGS only when it records. */
fun tallyTabs(dvr: Boolean): List<TallyTab> = TallyTab.entries.filter { dvr || it != TallyTab.RECORDINGS }

/**
 * Tally top bar: the wordmark, focusable tabs, and the clock.
 * Moving focus onto a tab does not select it; pressing OK calls [onSelect].
 */
@Composable
fun TallyTopBar(
    tabs: List<TallyTab>,
    selected: TallyTab,
    onSelect: (TallyTab) -> Unit,
    clock: String,
    modifier: Modifier = Modifier,
    selectedTabFocus: FocusRequester? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(TallyDimens.topBarHeight)
                .background(TallyColors.ground)
                .drawBehind {
                    val strokeWidth = TallyDimens.hairline.toPx()
                    val y = size.height - strokeWidth / 2f
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }.padding(horizontal = TallyDimens.marginHorizontal),
    ) {
        Text(
            text = stringResource(R.string.tally_wordmark),
            style =
                TallyType.teamCard.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 5.sp,
                ),
            color = TallyColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(36.dp))
        tabs.forEach { tab ->
            TallyTabItem(
                tab = tab,
                selected = tab == selected,
                onSelect = { onSelect(tab) },
                modifier =
                    if (tab == selected && selectedTabFocus != null) {
                        Modifier.fillMaxHeight().focusRequester(selectedTabFocus)
                    } else {
                        Modifier.fillMaxHeight()
                    },
            )
        }
        Spacer(Modifier.weight(1f))
        if (clock.isNotBlank()) {
            Text(
                text = clock,
                style = TallyType.clock.copy(fontSize = 22.sp),
                color = TallyColors.accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TallyTabItem(
    tab: TallyTab,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val contentColor =
        when {
            selected -> TallyColors.accent
            focused -> TallyColors.text
            else -> TallyColors.textSecondary
        }
    Surface(
        onClick = onSelect,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = contentColor,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = contentColor,
                pressedContainerColor = TallyColors.groundRaised,
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
        // The underline is drawn, not laid out: a fillMaxWidth child would make the selected tab take the whole bar.
        val underline = TallyDimens.focusBorder
        Box(
            Modifier
                .fillMaxHeight()
                .drawBehind {
                    if (selected) {
                        val h = underline.toPx()
                        drawRect(TallyColors.accent, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                    }
                },
        ) {
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
                    style = TallyType.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@PreviewTvSpec
@Composable
private fun TallyTopBarPreview() {
    TallySurface {
        TallyTopBar(
            tabs = TallyTab.entries,
            selected = TallyTab.GAMES,
            onSelect = {},
            clock = "9:41 PM",
        )
    }
}
