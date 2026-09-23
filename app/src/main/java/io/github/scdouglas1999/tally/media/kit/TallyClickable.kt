package io.github.scdouglas1999.tally.media.kit

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.launch

/**
 * Taps for a Tally control. On a phone: a tap calls [onClick] and a long-press [onLongClick] (the same actions as
 * OK and a long OK on the TV), with a flat pressed state ([TallyPressIndication]). It is not a focus target: the
 * tv-material `Surface` it sits on stays the one element keys focus and click, so a D-pad or keyboard on a phone
 * works as on the TV. On a TV it adds nothing (the Surface's key handling stays the only path).
 */
@Composable
fun Modifier.tallyClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    if (LocalTallyFormFactor.current != TallyFormFactor.PHONE) return this
    val interactionSource = remember { MutableInteractionSource() }
    return this
        // applies to the focus target of combinedClickable only (the nearest one after it), not the Surface's
        .focusProperties { canFocus = false }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = TallyPressIndication,
            enabled = enabled,
            role = Role.Button,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * The flat pressed state of every touch control: an 8% [TallyColors.text] overlay over the whole element while
 * the finger is down. Square, no ripple, nothing moves.
 */
object TallyPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressOverlayNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}

private const val PRESSED_ALPHA = 0.08f

private class PressOverlayNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(),
    DrawModifierNode {
    private val presses = mutableListOf<PressInteraction.Press>()

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (presses.isNotEmpty()) drawRect(color = TallyColors.text.copy(alpha = PRESSED_ALPHA))
    }
}
