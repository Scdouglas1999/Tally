package io.github.scdouglas1999.tally.media.kit

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
import com.github.damontecres.wholphin.ui.FontAwesome
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * Square text button. [primary] is accent fill with [TallyColors.onAccent] text; focused primary
 * draws a 3dp [TallyColors.text] border inside. Secondary is a hairline until focused.
 */
@Composable
fun TallyButton(
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
    val contentColor = if (primary) TallyColors.onAccent else TallyColors.text
    val fill = if (primary) TallyColors.accent else Color.Transparent
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
                disabledContentColor = TallyColors.muted,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border =
                            BorderStroke(
                                if (primary) 0.dp else TallyDimens.hairline,
                                if (primary) Color.Transparent else TallyColors.ruleStrong,
                            ),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border =
                            BorderStroke(
                                TallyDimens.focusBorder,
                                if (primary) TallyColors.text else TallyColors.accent,
                            ),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border =
                            BorderStroke(
                                TallyDimens.focusBorder,
                                if (primary) TallyColors.text else TallyColors.accent,
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
                style = TallyType.label,
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
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )

/**
 * 40dp square glyph button. While focused, [label] is shown under the button as a muted caption.
 */
@Composable
fun TallyIconButton(
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
                    contentColor = TallyColors.text,
                    focusedContainerColor = Color.Transparent,
                    focusedContentColor = TallyColors.text,
                    pressedContainerColor = Color.Transparent,
                    pressedContentColor = TallyColors.text,
                ),
            border =
                ClickableSurfaceDefaults.border(
                    border =
                        Border(
                            border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
                            shape = RectangleShape,
                        ),
                    focusedBorder =
                        Border(
                            border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                            shape = RectangleShape,
                        ),
                    pressedBorder =
                        Border(
                            border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
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
                    color = TallyColors.text,
                )
            }
        }
        Text(
            text = if (focused) label.tallyUppercase() else "",
            style = iconCaption,
            color = TallyColors.muted,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).height(16.dp),
        )
    }
}
