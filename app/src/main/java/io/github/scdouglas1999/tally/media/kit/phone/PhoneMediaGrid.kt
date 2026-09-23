package io.github.scdouglas1999.tally.media.kit.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.scdouglas1999.tally.media.kit.gridCardWidthDp
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens

/** Columns of a phone grid: three posters (or squares) across a phone, two landscape cards; more on a tablet. */
fun phoneGridColumns(
    aspectRatio: Float,
    availableWidth: Dp,
): Int {
    val wide = aspectRatio > 1.2f
    val target = if (wide) PhoneDimens.landscapeCardWidth * 0.75f else PhoneDimens.posterWidth
    val fit = ((availableWidth + PhoneDimens.cardGap) / (target + PhoneDimens.cardGap)).toInt()
    return fit.coerceAtLeast(if (wide) 2 else 3)
}

/**
 * The phone's vertical grid: [columns] across the width inside the page margins ([endPadding] replaces the right
 * margin, e.g. to leave room for the A–Z index), [PhoneDimens.cardGap] between cards and rows, the card width
 * worked out from the screen width. [header] items span the whole width (controls, a page header) and scroll
 * with the grid; [bottomPadding] ends the content above the bottom bar.
 */
@Composable
fun <T> PhoneMediaGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    endPadding: Dp = PhoneDimens.margin,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    gap: Dp = PhoneDimens.cardGap,
    key: ((index: Int, item: T) -> Any)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
    card: @Composable (item: T, index: Int, width: Dp) -> Unit,
) {
    val count = columns.coerceAtLeast(1)
    BoxWithConstraints(modifier = modifier) {
        val cardWidth = gridCardWidthDp(maxWidth - PhoneDimens.margin - endPadding, count, gap)
        LazyVerticalGrid(
            columns = GridCells.Fixed(count),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            contentPadding =
                PaddingValues(
                    start = PhoneDimens.margin,
                    end = endPadding,
                    top = topPadding,
                    bottom = bottomPadding,
                ),
            modifier = Modifier.fillMaxSize(),
        ) {
            header?.invoke(this)
            items(
                count = items.size,
                key = key?.let { k -> { index: Int -> k(index, items[index]) } },
            ) { index -> card(items[index], index, cardWidth) }
            footer?.invoke(this)
        }
    }
}

/** A full-width item of a phone grid (a header or a message). */
fun LazyGridScope.fullWidthItem(
    key: Any,
    content: @Composable () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
