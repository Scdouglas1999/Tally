package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytv.ui.components.tallyUppercase
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.FontAwesome

/**
 * Square text button. [primary] is accent fill with [JtvColors.onAccent] text; focused primary
 * draws a 3dp [JtvColors.text] border inside. Secondary is a hairline until focused.
 */
@Composable
fun JtvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    glyph: String? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onFocused: () -> Unit = {},
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val contentColor = if (primary) JtvColors.onAccent else JtvColors.text
    val fill = if (primary) JtvColors.accent else Color.Transparent
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = fill,
                contentColor = contentColor,
                focusedContainerColor = fill,
                focusedContentColor = contentColor,
                pressedContainerColor = fill,
                pressedContentColor = contentColor,
                disabledContainerColor = fill,
                disabledContentColor = JtvColors.muted,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border =
                            BorderStroke(
                                if (primary) 0.dp else JtvDimens.hairline,
                                if (primary) Color.Transparent else JtvColors.ruleStrong,
                            ),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border =
                            BorderStroke(
                                JtvDimens.focusBorder,
                                if (primary) JtvColors.text else JtvColors.accent,
                            ),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border =
                            BorderStroke(
                                JtvDimens.focusBorder,
                                if (primary) JtvColors.text else JtvColors.accent,
                            ),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier = modifier.height(40.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // tv-material3 Surface lays its content out top-start: fill the 40dp so the label is centered.
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 18.dp),
        ) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 16.sp,
                    color = contentColor,
                    maxLines = 1,
                )
            }
            Text(
                text = label.tallyUppercase(),
                style = JtvType.label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Plex Mono capitals sit below the center of their line box (the descent is empty
                // for caps): lift them so the ink has equal room above and below.
                modifier = Modifier.offset(y = CapsLift),
            )
            trailing?.invoke(this)
        }
    }
}

/** Upward nudge for an uppercase mono label centered in a fixed-height box. */
internal val CapsLift = (-1).dp

private val iconCaption =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )

/**
 * 40dp square glyph button. While focused, [label] is shown under the button as a muted caption.
 */
@Composable
fun JtvIconButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = JtvColors.text,
                    focusedContainerColor = Color.Transparent,
                    focusedContentColor = JtvColors.text,
                    pressedContainerColor = Color.Transparent,
                    pressedContentColor = JtvColors.text,
                ),
            border =
                ClickableSurfaceDefaults.border(
                    border =
                        Border(
                            border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                            shape = RectangleShape,
                        ),
                    focusedBorder =
                        Border(
                            border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                            shape = RectangleShape,
                        ),
                    pressedBorder =
                        Border(
                            border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                            shape = RectangleShape,
                        ),
                ),
            glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
            interactionSource = interactionSource,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 18.sp,
                    color = JtvColors.text,
                )
            }
        }
        Text(
            text = if (focused) label.tallyUppercase() else "",
            style = iconCaption,
            color = JtvColors.muted,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).height(16.dp),
        )
    }
}
