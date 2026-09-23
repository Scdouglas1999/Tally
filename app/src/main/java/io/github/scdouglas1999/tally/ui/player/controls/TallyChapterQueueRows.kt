package io.github.scdouglas1999.tally.ui.player.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.HiddenFocusBox
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.overlay.OverlayViewState
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.LandscapeWidth
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import org.jellyfin.sdk.model.api.ImageType

/**
 * Upstream's chapter overlay (`ChapterRowOverlay`) as a Tally media row: [LandscapeCard]s with the kicker
 * `CHAPTER 3 · 12:30`, the chapter playing now marked with an accent [IndicatorSquare] and focused first. OK seeks to
 * the chapter and hides the controls; UP returns to the controls, DOWN goes to the queue when there is one.
 */
@Composable
fun TallyChapterRow(
    player: Player,
    controllerViewState: ControllerViewState,
    chapters: List<Chapter>,
    hasNext: Boolean,
    onChangeState: (OverlayViewState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current =
        remember {
            PlayerFormat
                .chapterAt(chapters.map { it.position.inWholeMilliseconds }, player.currentPosition)
                ?.coerceIn(0, chapters.lastIndex) ?: 0
        }
    val imageUrlService = LocalImageUrlService.current
    val density = LocalDensity.current
    val chapterWord = stringResource(R.string.tally_player_chapter)
    val cardHeightPx = with(density) { (LandscapeWidth * 9 / 16).roundToPx() }
    val hiddenUp = remember { FocusRequester() }
    val hiddenDown = remember { FocusRequester() }
    Column(modifier = modifier) {
        HiddenFocusBox(hiddenUp) { onChangeState(OverlayViewState.CONTROLLER) }
        PlayerCardRow(
            title = stringResource(R.string.tally_player_chapters),
            items = chapters,
            initialIndex = current,
            up = hiddenUp,
            down = if (hasNext) hiddenDown else null,
            controllerViewState = controllerViewState,
        ) { chapter, index, cardModifier, onFocused ->
            val imageUrl =
                remember(chapter, cardHeightPx) {
                    imageUrlService.getItemImageUrl(
                        itemId = chapter.itemId,
                        imageType = ImageType.CHAPTER,
                        tag = chapter.tag,
                        imageIndex = chapter.index,
                        fillHeight = cardHeightPx,
                    )
                }
            Box(modifier = cardModifier) {
                LandscapeCard(
                    title = chapter.name.orEmpty(),
                    imageUrl = imageUrl,
                    kicker = PlayerFormat.chapterKicker(index, chapter.position.inWholeMilliseconds, chapterWord),
                    onClick = {
                        player.seekTo(chapter.position.inWholeMilliseconds)
                        controllerViewState.hideControls()
                    },
                    onLongClick = {},
                    onFocused = onFocused,
                )
                if (index == current) {
                    IndicatorSquare(
                        color = TallyColors.accent,
                        size = 10.dp,
                        // Above the card, which tv-material3 raises (zIndex) while it is focused.
                        modifier = Modifier.align(Alignment.TopEnd).zIndex(2f).padding(10.dp),
                    )
                }
            }
        }
        if (hasNext) {
            HiddenFocusBox(hiddenDown) { onChangeState(OverlayViewState.QUEUE) }
        }
    }
}

/**
 * Upstream's queue overlay (`QueueRowOverlay`) as a Tally media row: [LandscapeCard]s with the kicker `NEXT` for the
 * first item and `E04 · 47m` after it. OK plays that item; UP returns to [nextState].
 */
@Composable
fun TallyQueueRow(
    queue: List<PlaylistItem>,
    controllerViewState: ControllerViewState,
    nextState: OverlayViewState,
    onChangeState: (OverlayViewState) -> Unit,
    onClickPlaylist: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hiddenUp = remember { FocusRequester() }
    val nextWord = stringResource(R.string.tally_player_queue_next)
    Column(modifier = modifier) {
        HiddenFocusBox(hiddenUp) { onChangeState(nextState) }
        PlayerCardRow(
            title = stringResource(R.string.tally_player_queue),
            items = queue,
            initialIndex = 0,
            up = hiddenUp,
            controllerViewState = controllerViewState,
        ) { entry, index, cardModifier, onFocused ->
            val item = entry.item
            LandscapeCard(
                title = item.name.orEmpty(),
                imageUrl = rememberWideImageUrl(item),
                kicker = PlayerFormat.queueKicker(index, item.data, nextWord),
                onClick = {
                    onClickPlaylist(item)
                    controllerViewState.hideControls()
                },
                onLongClick = {},
                onFocused = onFocused,
                modifier = cardModifier,
            )
        }
    }
}

/**
 * The kit's media row ([io.github.scdouglas1999.tally.media.kit.MediaRow]: [RowHeader] over a lazy row of cards,
 * [FocusEdge] room for focus borders, the first card on the page margin) with focus starting on [initialIndex]:
 * the playing chapter can be far along the row, so the row opens scrolled to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> PlayerCardRow(
    title: String,
    items: List<T>,
    initialIndex: Int,
    up: FocusRequester,
    controllerViewState: ControllerViewState,
    down: FocusRequester? = null,
    card: @Composable (item: T, index: Int, modifier: Modifier, onFocused: () -> Unit) -> Unit,
) {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val start = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(start)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp - FocusEdge)) {
        RowHeader(title = title, count = items.size)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(TallyDimens.cardGap / 2),
                contentPadding = PaddingValues(horizontal = FocusEdge, vertical = FocusEdge),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .bleedHorizontal()
                        .focusGroup()
                        .focusRestorer(first)
                        .onFocusChanged { if (it.hasFocus) controllerViewState.pulseControls() }
                        .focusProperties { this.up = up },
            ) {
                itemsIndexed(items) { index, item ->
                    val cardModifier =
                        Modifier
                            .then(if (index == start) Modifier.focusRequester(first) else Modifier)
                            // On the card itself: the lazy row puts a focus group of its own between it and the row.
                            .focusProperties {
                                this.up = up
                                if (down != null) this.down = down
                            }.then(
                                if (index == 0) {
                                    // As upstream: LEFT on the first card goes nowhere (it would leave the row).
                                    Modifier.focusProperties {
                                        left = if (isLtr) FocusRequester.Cancel else FocusRequester.Default
                                        right = if (isLtr) FocusRequester.Default else FocusRequester.Cancel
                                    }
                                } else {
                                    Modifier
                                },
                            )
                    card(item, index, cardModifier) { controllerViewState.pulseControls() }
                }
            }
        }
    }
}
