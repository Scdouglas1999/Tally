package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.media.kit.tallyClickable
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * A full-width focusable row: a label, an optional muted description, and an optional
 * trailing slot. Same focus treatment as the cards: 3dp accent border on groundRaised
 * when focused, 1dp ruleStrong otherwise; no scale, no glow.
 *
 * [primary] paints the label accent for the screen's main action.
 */
@Composable
fun TallyRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    onFocused: () -> Unit = {},
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val labelColor =
        when {
            !enabled -> TallyColors.muted
            primary -> TallyColors.accent
            else -> TallyColors.text
        }
    val showFocus = tallyFocusVisible()
    val idleBorder =
        Border(
            border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
            shape = RectangleShape,
        )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.ground,
                contentColor = labelColor,
                focusedContainerColor = if (showFocus) TallyColors.groundRaised else TallyColors.ground,
                focusedContentColor = labelColor,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = labelColor,
                disabledContainerColor = TallyColors.ground,
                disabledContentColor = TallyColors.muted,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = idleBorder,
                focusedBorder =
                    if (showFocus) {
                        Border(
                            border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                            shape = RectangleShape,
                        )
                    } else {
                        idleBorder
                    },
                pressedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
                disabledBorder =
                    Border(
                        border = BorderStroke(TallyDimens.hairline, TallyColors.rule),
                        shape = RectangleShape,
                    ),
                focusedDisabledBorder =
                    Border(
                        border = BorderStroke(TallyDimens.hairline, TallyColors.rule),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().tallyClickable(onClick = onClick, enabled = enabled),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = TallyType.body,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = TallyType.hint,
                        color = TallyColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}

@PreviewTvSpec
@Composable
private fun TallyRowPreview() {
    TallySurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TallyRow(
                label = "My channels only",
                description = "Only show games you can watch on your channels",
                onClick = {},
                trailing = { IndicatorSquare(color = TallyColors.accent, size = 14.dp) },
            )
            TallyRow(label = "Open multiview", onClick = {}, primary = true)
        }
    }
}
