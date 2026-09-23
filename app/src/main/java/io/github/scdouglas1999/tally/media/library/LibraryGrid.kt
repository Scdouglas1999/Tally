package io.github.scdouglas1999.tally.media.library

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.playback.isBackwardButton
import com.github.damontecres.wholphin.ui.playback.isForwardButton
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.github.scdouglas1999.tally.media.kit.CardDetailStyle
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleStyle
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.MediaGrid
import io.github.scdouglas1999.tally.media.kit.MediaGridState
import io.github.scdouglas1999.tally.media.kit.posterDetail
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.series.wholePx
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The library grid: [MediaGrid] of the library's items drawn as the view options ask (image type, aspect ratio,
 * content scale, titles, columns, spacing), with the A–Z jump bar at the right edge while the list is sorted by
 * name ([jumpBarAllowed] false keeps it off, e.g. on the Collections tab). The remote does what upstream's
 * `CardGrid` does: BACK goes back to the top first (long press: back to where you were), PLAY plays the focused item,
 * fast-forward / rewind page through, and letter keys jump.
 */
@Composable
internal fun LibraryGrid(
    items: List<BaseItem?>,
    viewOptions: ViewOptions,
    sortAndDirection: SortAndDirection,
    jumpBarAllowed: Boolean,
    gridState: MediaGridState,
    onClickItem: (Int, BaseItem) -> Unit,
    onLongClickItem: (Int, BaseItem) -> Unit,
    onClickPlay: (Int, BaseItem) -> Unit,
    letterPosition: suspend (Char) -> Int,
    onFocusIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val columns = viewOptions.columns.coerceAtLeast(1)
    val showBar = jumpBarAllowed && jumpBarShown(sortAndDirection.sort, items.size)
    var previousIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastIndex by rememberSaveable { mutableIntStateOf(gridState.focusedIndex) }
    val currentItems by rememberUpdatedState(items)
    val currentLetterPosition by rememberUpdatedState(letterPosition)

    // Upstream's page jump sizes: six rows, more for very large libraries.
    val jump =
        when {
            items.size >= 25_000 -> columns * 500
            items.size >= 7_000 -> columns * 50
            items.size >= 2_000 -> columns * 15
            else -> columns * 6
        }

    fun jumpBy(amount: Int) {
        scope.launch(ExceptionHandler()) {
            val target =
                (gridState.lazyState.firstVisibleItemIndex + amount).coerceIn(0, (currentItems.size - 1).coerceAtLeast(0))
            gridState.jumpTo(target)
        }
    }

    fun jumpToLetter(letter: Char) {
        scope.launch(ExceptionHandler()) {
            val position = withContext(WholphinDispatchers.IO) { currentLetterPosition(letter) }
            Timber.d("Letter jump %s to %s", letter, position)
            if (position >= 0) gridState.jumpTo(position)
        }
    }

    // BACK while a card (or the bar) has focus goes back to the top first, as upstream's grid does. BACK reaches the
    // app as a back press, not as a key event, so it is taken here rather than in the key handler below.
    var hasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = hasFocus && gridState.focusedIndex > 0) {
        scope.launch(ExceptionHandler()) {
            gridState.jumpTo(0, animate = gridState.focusedIndex < columns * 6)
        }
    }

    var longPressing by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxSize()
                .onFocusChanged { hasFocus = it.hasFocus }
                .onKeyEvent {
                    if (it.key == Key.Back && it.nativeKeyEvent.isLongPress) {
                        longPressing = true
                        val target = previousIndex
                        if (target > 0) scope.launch(ExceptionHandler()) { gridState.jumpTo(target) }
                        return@onKeyEvent true
                    } else if (it.type == KeyEventType.KeyUp) {
                        if (longPressing && it.key == Key.Back) {
                            longPressing = false
                            return@onKeyEvent true
                        }
                        longPressing = false
                    }
                    if (it.type != KeyEventType.KeyUp) {
                        false
                    } else if (isPlayKeyUp(it)) {
                        val index = gridState.focusedIndex
                        val item = currentItems.getOrNull(index)
                        if (item?.playable == true) onClickPlay(index, item)
                        true
                    } else if (isForwardButton(it)) {
                        jumpBy(jump)
                        true
                    } else if (isBackwardButton(it)) {
                        jumpBy(-jump)
                        true
                    } else if (showBar && it.nativeKeyEvent.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                        jumpToLetter(
                            it.nativeKeyEvent.unicodeChar
                                .toChar()
                                .uppercaseChar(),
                        )
                        true
                    } else {
                        false
                    }
                },
    ) {
        MediaGrid(
            items = items,
            columns = columns,
            state = gridState,
            gap = viewOptions.spacing.dp,
            bottomPadding = TallyDimens.marginVertical,
            key = { index, item -> "$index-${item?.id}" },
            onFocusIndex = { index ->
                if (index != lastIndex) {
                    previousIndex = lastIndex
                    lastIndex = index
                }
                onFocusIndex(index)
            },
            modifier = Modifier.weight(1f),
            card = { item, index, cardModifier, width ->
                LibraryItemCard(
                    item = item,
                    viewOptions = viewOptions,
                    width = width,
                    onClick = { if (item != null) onClickItem(index, item) },
                    onLongClick = { if (item != null) onLongClickItem(index, item) },
                    modifier = cardModifier,
                )
            },
        )
        if (showBar) {
            val letter = jumpLetterFor(items.getOrNull(gridState.focusedIndex)?.sortName)
            JumpBar(
                currentLetter = letter,
                onLetter = ::jumpToLetter,
                gridRequester = gridState.gridRequester,
                modifier = Modifier.padding(start = JumpBarGap - FocusEdge),
            )
        } else {
            // Same room as the bar, so cards keep their size when the sort changes.
            Spacer(Modifier.width(JumpBarGap - FocusEdge + JumpBarWidth))
        }
    }
}

/** Width of the jump bar including the room for a focused letter's border. */
internal val JumpBarWidth = 28.dp + FocusEdge * 2
private val JumpBarGap = 16.dp
private val LetterHeight = 18.dp

private val LetterStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    )

/**
 * `#`, `A`…`Z` in mono `muted`; the letter of the focused card in `text` with a 4dp accent square beside it.
 * Entering the bar lands on that letter; OK jumps to the first item with the letter; LEFT goes back to the grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JumpBar(
    currentLetter: Char,
    onLetter: (Char) -> Unit,
    gridRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { List(JUMP_LETTERS.length) { FocusRequester() } }
    val currentIndex by rememberUpdatedState(JUMP_LETTERS.indexOf(currentLetter).coerceAtLeast(0))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxHeight().width(JumpBarWidth),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(FocusEdge)
                        .focusProperties {
                            onEnter = { requesters[currentIndex].tryRequestFocus("tally-jump-bar") }
                        }.focusGroup(),
            ) {
                JUMP_LETTERS.forEachIndexed { index, letter ->
                    JumpLetter(
                        letter = letter,
                        current = letter == currentLetter,
                        onClick = { onLetter(letter) },
                        modifier =
                            Modifier
                                .focusRequester(requesters[index])
                                .focusProperties {
                                    left = gridRequester
                                    right = FocusRequester.Cancel
                                    if (index == JUMP_LETTERS.lastIndex) down = FocusRequester.Cancel
                                },
                    )
                }
            }
        }
    }
}

@Composable
private fun JumpLetter(
    letter: Char,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val color = if (current || focused) TallyColors.text else TallyColors.muted
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = color,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = color,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = color,
            ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .size(width = 28.dp, height = LetterHeight)
                .drawWithContent {
                    drawContent()
                    if (focused) {
                        // Drawn inside the cell, in whole pixels, so all four sides read the same.
                        val stroke = wholePx(TallyDimens.focusBorder.toPx())
                        val inset = stroke / 2f
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                },
    ) {
        // tv-material3 Surface lays content out top-start: fill the cell and center the letter.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.size(4.dp)) {
                    if (current) IndicatorSquare(color = TallyColors.accent, size = 4.dp)
                }
                Text(
                    text = letter.toString(),
                    style = LetterStyle,
                    color = color,
                    maxLines = 1,
                )
                // Balances the square so the letter sits on the cell's center line.
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

/** The kit crops pictures; fit and the other scales keep upstream's meaning. Fill stays the kit's crop. */
private fun PrefContentScale.kitScale(): ContentScale =
    when (this) {
        PrefContentScale.FIT, PrefContentScale.UNRECOGNIZED -> ContentScale.Fit
        PrefContentScale.NONE -> ContentScale.None
        PrefContentScale.Fill_WIDTH -> ContentScale.FillWidth
        PrefContentScale.FILL_HEIGHT -> ContentScale.FillHeight
        PrefContentScale.CROP, PrefContentScale.FILL -> ContentScale.Crop
    }

/**
 * A library item as a kit card of [width]: the picture in the view options' image type and aspect ratio (2:3 a
 * poster, 16:9 a landscape card, 1:1 square), the label bar with the title over a mono detail when titles are on,
 * and the state overlays the kit's cards carry (progress, `SEEN`, `4 NEW`, favorite). A null item (a page still
 * loading) is an empty frame of the same size.
 */
@Composable
internal fun LibraryItemCard(
    item: BaseItem?,
    viewOptions: ViewOptions,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageHeight = width / viewOptions.aspectRatio.ratio
    val imageService = LocalImageUrlService.current
    val fillWidth = with(LocalDensity.current) { width.roundToPx() }
    val imageType = viewOptions.imageType.imageType
    val imageUrl =
        remember(item, imageType, fillWidth) {
            item?.let { imageService.getItemImageUrl(it, imageType, fillWidth = fillWidth) }
        }
    val played = item?.played == true
    val unplayed = item?.data?.userData?.unplayedItemCount ?: 0
    val percent =
        resumePercent(
            item?.data?.userData?.playbackPositionTicks ?: 0L,
            item?.data?.runTimeTicks ?: 0L,
        )
    val tag =
        when {
            played -> stringResource(R.string.tally_media_seen)
            unplayed > 0 -> stringResource(R.string.tally_media_new_count, unplayed)
            else -> null
        }
    val title = (item?.title ?: item?.name).orEmpty()
    val detail = item?.let { posterDetail(it) }
    CardFrame(
        imageUrl = imageUrl,
        width = width,
        height = imageHeight,
        contentDescription = title,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        progress = if (!played && percent in 1..99) percent / 100f else null,
        tag = tag,
        tagAccent = !played && unplayed > 0,
        favorite = item?.favorite == true,
        contentScale = viewOptions.contentScale.kitScale(),
        label =
            if (viewOptions.showTitles) {
                {
                    Text(
                        text = title,
                        style = CardTitleStyle,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (detail != null) {
                        Text(
                            text = detail.uppercase(),
                            style = CardDetailStyle,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                null
            },
    )
}
