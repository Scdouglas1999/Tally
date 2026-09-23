package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.FontAwesome
import io.github.scdouglas1999.tally.media.drawer.TallyGlyph
import io.github.scdouglas1999.tally.media.kit.TallyPressIndication
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * Tap handling for the phone's own controls (bar cells, sheet rows, top-bar buttons): tap and long-press with the
 * flat pressed state ([TallyPressIndication]), focusable for a keyboard or D-pad, with a [PhoneDimens.focusBorder]
 * accent frame drawn inside only while one is in use (never after a touch).
 */
@Composable
fun Modifier.phoneClickable(
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val showFocus = tallyFocusVisible()
    return this
        .drawWithContent {
            drawContent()
            if (focused && showFocus) {
                val w = PhoneDimens.focusBorder.toPx()
                drawRect(
                    color = TallyColors.accent,
                    topLeft = Offset(w / 2f, w / 2f),
                    size = Size(size.width - w, size.height - w),
                    style = Stroke(width = w),
                )
            }
        }.combinedClickable(
            interactionSource = interactionSource,
            indication = TallyPressIndication,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** A drawer glyph ([TallyGlyph]) [size] high: Font Awesome text, or a tinted drawable. */
@Composable
internal fun PhoneGlyph(
    glyph: TallyGlyph,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (glyph) {
        is TallyGlyph.Font -> {
            Text(
                text = stringResource(glyph.resId),
                fontFamily = FontAwesome,
                fontSize = size.value.sp,
                lineHeight = size.value.sp,
                color = color,
                textAlign = TextAlign.Center,
                // Font Awesome glyphs are up to 1.25em wide
                modifier = modifier.width(size * 1.25f),
            )
        }

        is TallyGlyph.Image -> {
            Icon(
                painter = painterResource(glyph.resId),
                contentDescription = null,
                tint = color,
                modifier = modifier.size(size),
            )
        }
    }
}
