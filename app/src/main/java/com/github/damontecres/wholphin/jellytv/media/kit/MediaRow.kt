package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.jellytv.ui.components.RowHeader
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch

/**
 * Room a focused card's border needs outside the card: tv-material3 centers the 3dp stroke on the
 * card's edge, so half of it lies outside, and a lazy list clips at its bounds.
 */
val FocusEdge: Dp = JtvDimens.focusBorder + 1.dp

/**
 * Scrolls a lazy list only as far as needed to show the focused child with [marginPx] of room
 * on both ends, so its focus border (half of which lies outside it) stays inside the list's clip.
 */
@OptIn(ExperimentalFoundationApi::class)
class FocusEdgeBringIntoViewSpec(
    private val marginPx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val start = offset - marginPx
        val end = offset + size + marginPx
        return when {
            start >= 0f && end <= containerSize -> 0f
            end - start > containerSize -> start
            start < 0f -> start
            else -> end - containerSize
        }
    }
}

/** [FocusEdgeBringIntoViewSpec] with a [FocusEdge] margin, for the current density. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberFocusEdgeSpec(): BringIntoViewSpec {
    val margin = with(LocalDensity.current) { FocusEdge.toPx() }
    return remember(margin) { FocusEdgeBringIntoViewSpec(margin) }
}

/**
 * Widens a horizontally scrolling list by [amount] on both sides and shifts it left by the same,
 * so content padded by [amount] still starts at the parent's edge while the padding gives focus
 * borders room inside the list's clip.
 */
fun Modifier.bleedHorizontal(amount: Dp = FocusEdge): Modifier =
    layout { measurable, constraints ->
        val bleed = amount.roundToPx()
        val wide =
            if (constraints.hasBoundedWidth) {
                constraints.copy(
                    minWidth = constraints.minWidth + 2 * bleed,
                    maxWidth = constraints.maxWidth + 2 * bleed,
                )
            } else {
                constraints
            }
        val placeable = measurable.measure(wide)
        val width = (placeable.width - 2 * bleed).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) {
            placeable.place(-bleed, 0)
        }
    }

/**
 * [RowHeader] over a horizontal row of cards. The last focused card is restored when focus
 * re-enters the row. Put a [FocusRequester] on [modifier] to enter the row from outside;
 * [up] is where DPAD up leaves it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    up: FocusRequester? = null,
    onRowFocused: () -> Unit = {},
    key: (index: Int, item: T) -> Any = { index, _ -> index },
    card: @Composable (item: T, index: Int, modifier: Modifier, onFocused: () -> Unit) -> Unit,
) {
    val lazyFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    var position by rememberInt()
    val saved = if (items.isEmpty()) 0 else position.coerceIn(0, items.lastIndex)
    val bringRow = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .bringIntoViewRequester(bringRow)
                .focusProperties {
                    onEnter = {
                        lazyFocus.tryRequestFocus()
                    }
                },
    ) {
        RowHeader(title = title, count = items.size)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(JtvDimens.cardGap / 2),
                contentPadding = PaddingValues(horizontal = FocusEdge, vertical = FocusEdge),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .bleedHorizontal()
                        .focusRequester(lazyFocus)
                        .focusGroup()
                        .focusRestorer(cardFocus)
                        .focusProperties {
                            if (up != null) {
                                this.up = up
                            }
                        },
            ) {
                itemsIndexed(items, key = key) { index, item ->
                    val cardModifier =
                        if (index == saved) {
                            Modifier.focusRequester(cardFocus)
                        } else {
                            Modifier
                        }
                    card(item, index, cardModifier) {
                        position = index
                        onRowFocused()
                        // Reveal the whole row (header and the padding round the focus border),
                        // not only the card.
                        scope.launch(ExceptionHandler()) { bringRow.bringIntoView() }
                    }
                }
            }
        }
    }
}
