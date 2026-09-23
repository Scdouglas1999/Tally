package io.github.scdouglas1999.tally.media.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * State of a [MediaGrid]: the scroll position and the focused card, which is the one focus returns to when it
 * re-enters the grid. [jumpTo] scrolls to a card and focuses it (letter jump, back-to-top).
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
class MediaGridState(
    initialIndex: Int,
) {
    val lazyState =
        LazyGridState(
            cacheWindow = LazyLayoutCacheWindow(aheadFraction = 2f, behindFraction = 0.5f),
            firstVisibleItemIndex = initialIndex.coerceAtLeast(0),
        )

    /** The focused card, or the one focus returns to. */
    var focusedIndex by mutableIntStateOf(initialIndex.coerceAtLeast(0))
        internal set

    /** On the card at [focusedIndex]: requesting it focuses that card (once it is composed). */
    val cardRequester = FocusRequester()

    /** On the grid itself: requesting it moves focus into the grid, to [focusedIndex]. */
    val gridRequester = FocusRequester()

    internal var pendingFocus by mutableStateOf(false)

    /** Scroll [index] into view and focus it. */
    suspend fun jumpTo(
        index: Int,
        animate: Boolean = false,
    ) {
        if (index < 0) return
        if (animate) lazyState.animateScrollToItem(index) else lazyState.scrollToItem(index)
        focusedIndex = index
        pendingFocus = true
    }

    /** Focus the card at [focusedIndex]; false while it is not composed. */
    fun requestFocus(): Boolean = cardRequester.tryRequestFocus("tally-media-grid")
}

@Composable
fun rememberMediaGridState(initialIndex: Int = 0): MediaGridState = remember { MediaGridState(initialIndex) }

/**
 * A focus-safe vertical grid of cards: [columns] across the width with [gap] between them (rows too). The grid
 * bleeds [FocusEdge] past its bounds on the sides and pads its content by the same on every side, so a focused
 * card's border is never clipped while the first column still lines up with the parent's edge; scrolling reveals
 * the focused card with the same room (see [FocusEdgeBringIntoViewSpec]). Focus re-entering the grid returns to
 * the last focused card.
 *
 * [card] gets the card's [Modifier] (it carries the focus plumbing: put it on the focusable card) and the card
 * width. [bottomPadding] is extra room under the last row. [onFocusIndex] reports every focused index.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> MediaGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    state: MediaGridState = rememberMediaGridState(),
    gap: Dp = 16.dp,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    key: ((index: Int, item: T) -> Any)? = null,
    onFocusIndex: (Int) -> Unit = {},
    card: @Composable (item: T, index: Int, modifier: Modifier, width: Dp) -> Unit,
) {
    val count = columns.coerceAtLeast(1)
    LaunchedEffect(state.pendingFocus) {
        if (state.pendingFocus) {
            // The card at the new index is composed on the next frame(s) after the scroll.
            repeat(FOCUS_ATTEMPTS) {
                withFrameNanos { }
                if (state.requestFocus()) {
                    state.pendingFocus = false
                    return@LaunchedEffect
                }
            }
            state.pendingFocus = false
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val cardWidth = gridCardWidthDp(maxWidth, count, gap)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(count),
                state = state.lazyState,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                contentPadding =
                    PaddingValues(
                        start = FocusEdge,
                        end = FocusEdge,
                        top = FocusEdge + topPadding,
                        bottom = FocusEdge + bottomPadding,
                    ),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .bleedHorizontal()
                        .focusRequester(state.gridRequester)
                        .focusProperties {
                            onEnter = {
                                if (state.focusedIndex !in items.indices) state.focusedIndex = 0
                            }
                        }.focusRestorer(state.cardRequester)
                        .focusGroup(),
            ) {
                items(
                    count = items.size,
                    key = key?.let { k -> { index: Int -> k(index, items[index]) } },
                ) { index ->
                    val item = items[index]
                    val focusModifier =
                        if (index == state.focusedIndex) {
                            Modifier.focusRequester(state.cardRequester)
                        } else {
                            Modifier
                        }
                    card(
                        item,
                        index,
                        focusModifier.onFocusChanged {
                            if (it.isFocused) {
                                state.focusedIndex = index
                                onFocusIndex(index)
                            }
                        },
                        cardWidth,
                    )
                }
            }
        }
    }
}

/** Card width for [columns] across [available] with [gap] between them. */
fun gridCardWidthDp(
    available: Dp,
    columns: Int,
    gap: Dp,
): Dp {
    val count = columns.coerceAtLeast(1)
    return ((available - gap * (count - 1)) / count).coerceAtLeast(1.dp)
}

private const val FOCUS_ATTEMPTS = 8
