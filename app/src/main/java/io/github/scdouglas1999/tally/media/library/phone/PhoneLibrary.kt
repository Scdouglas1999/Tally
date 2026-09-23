package io.github.scdouglas1999.tally.media.library.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewModel
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.RecommendedViewModel
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.github.scdouglas1999.tally.media.home.HomeItemCard
import io.github.scdouglas1999.tally.media.home.phoneHomeCardHeight
import io.github.scdouglas1999.tally.media.home.phoneHomeImageHeight
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleStyle
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.media.kit.phone.PhoneChip
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneIconButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneMediaGrid
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowMessage
import io.github.scdouglas1999.tally.media.kit.phone.fullWidthItem
import io.github.scdouglas1999.tally.media.kit.phone.phoneGridColumns
import io.github.scdouglas1999.tally.media.library.FolderSpec
import io.github.scdouglas1999.tally.media.library.FolderUi
import io.github.scdouglas1999.tally.media.library.GenreGrid
import io.github.scdouglas1999.tally.media.library.JUMP_LETTERS
import io.github.scdouglas1999.tally.media.library.LibraryItemCard
import io.github.scdouglas1999.tally.media.library.LibraryPageViewModel
import io.github.scdouglas1999.tally.media.library.NameCell
import io.github.scdouglas1999.tally.media.library.PanelRow
import io.github.scdouglas1999.tally.media.library.StudioGrid
import io.github.scdouglas1999.tally.media.library.directionArrow
import io.github.scdouglas1999.tally.media.library.jumpBarShown
import io.github.scdouglas1999.tally.media.library.jumpLetterFor
import io.github.scdouglas1999.tally.media.library.playAll
import io.github.scdouglas1999.tally.media.library.sortLabel
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/**
 * A library on a phone (`LibraryScaffold`'s phone branch): a top bar with the library's name and its count (a back
 * arrow on a library without tabs: a collection, genre or studio page), then the tabs as a horizontally scrolling mono
 * strip pinned under it (the current tab `text` over a 2dp accent underline), then the tab's content.
 */
@Composable
internal fun PhoneLibraryScaffold(
    kicker: String,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    count: @Composable () -> Unit,
    body: @Composable () -> Unit,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
) {
    Column(modifier = Modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = kicker,
            onBack = if (tabs.isEmpty()) ({ pageViewModel.navigationManager.goBack() }) else null,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) { count() }
            },
        )
        if (tabs.isNotEmpty()) {
            PhoneTabStrip(tabs = tabs, selected = selectedTab, onSelect = onSelectTab)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { body() }
    }
}

/** The tab strip: mono labels in 48dp cells, scrolling sideways; the current tab `text` with a 2dp accent underline. */
@Composable
internal fun PhoneTabStrip(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = PhoneDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.horizontalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin - TabPadding),
    ) {
        tabs.forEachIndexed { index, label ->
            val current = index == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .height(PhoneDimens.touchTarget)
                        .semantics { this.selected = current }
                        .phoneClickable(role = Role.Tab) { if (!current) onSelect(index) }
                        .drawBehind {
                            if (current) {
                                val bar = 2.dp.toPx()
                                drawRect(
                                    color = TallyColors.accent,
                                    topLeft = Offset(0f, size.height - bar),
                                    size =
                                        androidx.compose.ui.geometry
                                            .Size(size.width, bar),
                                )
                            }
                        }.padding(horizontal = TabPadding),
            ) {
                Text(
                    text = label.tallyUppercase(),
                    style = PhoneType.labelLarge,
                    color = if (current) TallyColors.text else TallyColors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

private val TabPadding = 12.dp

/** The library's count in the top bar (`22 FILMS`), mono `label` `muted`. */
@Composable
internal fun PhoneHeaderCount(text: String) {
    Text(
        text = text.tallyUppercase(),
        style = PhoneType.label,
        color = TallyColors.muted,
        maxLines = 1,
    )
}

/** An error in a library's content area. */
@Composable
internal fun PhoneLibraryError(
    message: String,
    modifier: Modifier = Modifier,
) {
    PhoneEmptyState(
        title = stringResource(R.string.tally_media_error_title),
        subtitle = message.ifBlank { stringResource(R.string.tally_media_error_body) },
        modifier = modifier,
    )
}

/** Width of the A–Z index at the right edge of a phone grid. */
private val IndexWidth = 24.dp
private val IndexBubble = 56.dp

/**
 * A folder's items on a phone (`FolderItems`' phone branch, with its click and menu actions): the controls row (SORT,
 * FILTER, VIEW as outline chips opening the same panels as bottom sheets, then random, play and shuffle as 40dp icon
 * squares) at the top of a grid of the TV grid's cards (title and year under each picture as the view options say),
 * three posters or two landscape cards across; while the list is sorted by name, the A–Z index at the right edge.
 */
@Composable
internal fun PhoneFolderItems(
    spec: FolderSpec,
    viewModel: CollectionFolderViewModel,
    ui: FolderUi,
    items: List<BaseItem?>,
    onClickItem: (Int, BaseItem) -> Unit,
    onLongClickItem: (Int, BaseItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val viewOptions = state.viewOptions
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val showIndex = spec.jumpBar && jumpBarShown(state.sortAndDirection.sort, items.size)
    val saved = remember { viewModel.position }
    // Grid index 0 is the controls row.
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = if (saved > 0) saved + 1 else 0)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = phoneGridColumns(viewOptions.aspectRatio.ratio, maxWidth)
        PhoneMediaGrid(
            items = items,
            columns = columns,
            state = gridState,
            endPadding = if (showIndex) IndexWidth + 8.dp else PhoneDimens.margin,
            bottomPadding = bottom + PhoneDimens.rowGap,
            key = { index, item -> "$index-${item?.id}" },
            header = {
                fullWidthItem("controls") { PhoneFolderControls(spec = spec, viewModel = viewModel, ui = ui) }
                if (items.isEmpty()) {
                    fullWidthItem("empty") {
                        val filtered = state.filter.countFilters(spec.filterOptions) > 0
                        PhoneEmptyState(
                            title = stringResource(R.string.tally_library_empty_title),
                            subtitle =
                                stringResource(
                                    if (filtered) R.string.tally_library_empty_filtered else R.string.tally_library_empty_body,
                                ),
                            modifier = Modifier.padding(start = 0.dp),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { item, index, width ->
            LibraryItemCard(
                item = item,
                viewOptions = viewOptions,
                width = width,
                onClick = { if (item != null) onClickItem(index, item) },
                onLongClick = { if (item != null) onLongClickItem(index, item) },
            )
        }
        if (showIndex) {
            val first = (gridState.firstVisibleItemIndex - 1).coerceAtLeast(0)
            PhoneAlphaIndex(
                current = jumpLetterFor(items.getOrNull(first)?.sortName),
                onLetter = { letter ->
                    scope.launch(ExceptionHandler()) {
                        val position = withContext(WholphinDispatchers.IO) { viewModel.positionOfLetter(letter) } ?: -1
                        if (position >= 0) gridState.scrollToItem(position + 1)
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = PhoneDimens.touchTarget + 8.dp, bottom = bottom + 8.dp),
            )
        }
    }
}

/**
 * The controls row of a folder: SORT (long press reverses the order, as on the TV), FILTER (marked, with the number
 * of filters on), VIEW, then random, play and shuffle where the TV has them. Scrolls sideways when it does not fit.
 */
@Composable
internal fun PhoneFolderControls(
    spec: FolderSpec,
    viewModel: CollectionFolderViewModel,
    ui: FolderUi,
) {
    val state by viewModel.state.collectAsState()
    val notEmpty = state.items.let { (it as? com.github.damontecres.wholphin.util.DataLoadingState.Success)?.data?.isNotEmpty() == true }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .bleedHorizontal(PhoneDimens.margin)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin),
    ) {
        if (spec.sortOptions.isNotEmpty()) {
            PhoneChip(
                label = sortLabel(state.sortAndDirection) + " " + directionArrow(state.sortAndDirection.direction),
                onClick = { ui.sortOpen = true },
                onLongClick = { viewModel.onSortChange(state.sortAndDirection.flip(), spec.recursive, state.filter) },
            )
        }
        if (spec.filterOptions.isNotEmpty()) {
            val count = state.filter.countFilters(spec.filterOptions)
            PhoneChip(
                label =
                    if (count > 0) {
                        stringResource(R.string.tally_library_filter_count, count)
                    } else {
                        stringResource(R.string.tally_library_filter)
                    },
                active = count > 0,
                onClick = { ui.filterOpen = true },
            )
        }
        PhoneChip(label = stringResource(R.string.tally_library_view), onClick = { ui.viewOpen = true })
        PhoneIconButton(
            glyph = stringResource(R.string.fa_dice),
            label = stringResource(R.string.tally_library_random),
            enabled = notEmpty,
            onClick = {
                ui.clickedRandom = true
                viewModel.onClickRandom()
            },
        )
        if (spec.playEnabled) {
            PhoneIconButton(
                glyph = stringResource(R.string.fa_play),
                label = stringResource(R.string.tally_library_play),
                enabled = notEmpty,
                onClick = { spec.onPlayAll?.invoke(false) ?: playAll(spec, state, viewModel, shuffle = false) },
            )
            PhoneIconButton(
                glyph = stringResource(R.string.fa_shuffle),
                label = stringResource(R.string.tally_library_shuffle),
                enabled = notEmpty,
                onClick = { spec.onPlayAll?.invoke(true) ?: playAll(spec, state, viewModel, shuffle = true) },
            )
        }
    }
}

/**
 * The A–Z index: `#`, `A`…`Z` in mono `muted` down a 24dp column at the right edge (the letter of the first card on
 * screen in `text`). A tap or a drag jumps to the first item with the letter under the finger; while dragging, that
 * letter shows in a 56dp square beside the thumb.
 */
@Composable
private fun PhoneAlphaIndex(
    current: Char,
    onLetter: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    var height by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableStateOf<Float?>(null) }
    var dragLetter by remember { mutableStateOf<Char?>(null) }
    val density = LocalDensity.current

    fun letterAt(y: Float): Char {
        if (height <= 0f) return JUMP_LETTERS[0]
        val index = (y / height * JUMP_LETTERS.length).toInt().coerceIn(0, JUMP_LETTERS.lastIndex)
        return JUMP_LETTERS[index]
    }
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(IndexWidth)
                .onSizeChanged { height = it.height.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()

                        fun move(y: Float) {
                            dragY = y.coerceIn(0f, height)
                            val letter = letterAt(y)
                            if (letter != dragLetter) {
                                dragLetter = letter
                                onLetter(letter)
                            }
                        }
                        move(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            move(change.position.y)
                        }
                        dragY = null
                        dragLetter = null
                    }
                },
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            JUMP_LETTERS.forEach { letter ->
                val lit = letter == (dragLetter ?: current)
                Text(
                    text = letter.toString(),
                    style = IndexStyle,
                    color = if (lit) TallyColors.text else TallyColors.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        val y = dragY
        val letter = dragLetter
        if (y != null && letter != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .offset {
                            // requiredSize centers the square on the 24dp column; move it to 8dp left of it
                            IntOffset(
                                x = -(IndexBubble / 2 + IndexWidth / 2 + 8.dp).roundToPx(),
                                y = (y - with(density) { (IndexBubble / 2).toPx() }).toInt(),
                            )
                        }.requiredSize(IndexBubble)
                        .background(TallyColors.groundRaised)
                        .border(PhoneDimens.hairline, TallyColors.ruleStrong),
            ) {
                Text(
                    text = letter.toString(),
                    style = PhoneType.title.copy(fontFamily = TallyType.Mono),
                    color = TallyColors.accent,
                    maxLines = 1,
                )
            }
        }
    }
}

private val IndexStyle =
    androidx.compose.ui.text.TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
    )

/**
 * A library panel (sort, filter, view options and their second levels) as a phone bottom sheet: the accent kicker,
 * a rule, then the same rows (56dp: label, the mono value at the right, the accent square on the chosen one). Back,
 * a swipe down or the scrim go back one level, as BACK does on the TV panel.
 */
@Composable
internal fun PhonePanelSheet(
    kicker: String,
    rows: List<PanelRow>,
    onBack: () -> Unit,
    message: String?,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    key(kicker) {
        PhoneSheet(onDismiss = onBack) {
            Text(
                text = kicker.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(top = 4.dp, bottom = 12.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PhoneDimens.hairline)
                    .background(TallyColors.rule),
            )
            if (message != null) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = PhoneDimens.margin),
                ) {
                    Text(
                        text = message.tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.muted,
                        maxLines = 1,
                    )
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            ) {
                itemsIndexed(rows) { _, row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .phoneClickable(onClick = row.onClick)
                                .padding(horizontal = PhoneDimens.margin),
                    ) {
                        Text(
                            text = row.label,
                            style = PhoneType.headline,
                            color = TallyColors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (row.value != null) {
                            Text(
                                text = row.value.tallyUppercase(),
                                style = PhoneType.label,
                                color = if (row.valueAccent) TallyColors.accent else TallyColors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        }
                        if (row.marked) IndicatorSquare(color = TallyColors.accent, size = 8.dp)
                    }
                }
            }
        }
    }
}

/** The Genres / Studios tab on a phone: two 16:9 tiles across with the name in the label bar. */
@Composable
internal fun PhoneNameGrid(
    names: List<NameCell>,
    onClick: (NameCell) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    if (names.isEmpty()) {
        PhoneEmptyState(
            title = stringResource(R.string.tally_library_empty_title),
            subtitle = stringResource(R.string.tally_library_empty_body),
            modifier = modifier,
        )
        return
    }
    PhoneMediaGrid(
        items = names,
        columns = 2,
        topPadding = 12.dp,
        bottomPadding = bottom + PhoneDimens.rowGap,
        key = { index, cell -> "$index-${cell.id}" },
        modifier = modifier.fillMaxSize(),
    ) { cell, _, width ->
        CardFrame(
            imageUrl = cell.imageUrl,
            width = width,
            height = width * 9 / 16,
            contentDescription = cell.name,
            onClick = { onClick(cell) },
            onLongClick = {},
            label = {
                Text(
                    text = cell.name,
                    style = CardTitleStyle,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

/** The genre (or studio) tiles of a library, for a home row's "view all". */
@Composable
internal fun PhoneGenreGrid(
    itemId: UUID,
    includeItemTypes: List<BaseItemKind>?,
    collectionType: CollectionType,
    studios: Boolean,
    modifier: Modifier = Modifier,
) {
    if (studios) {
        StudioGrid(itemId = itemId, includeItemTypes = includeItemTypes, modifier = modifier)
    } else {
        GenreGrid(itemId = itemId, includeItemTypes = includeItemTypes, collectionType = collectionType, modifier = modifier)
    }
}

/**
 * The Recommended tab on a phone (`RecommendedLoaded`'s phone branch, with its view model and item menu): the rows
 * as on home, phone cards, ALL where a row is full.
 */
@Composable
internal fun PhoneRecommendedRows(
    preferences: UserPreferences,
    rows: List<HomeRowLoadingState>,
    viewModel: RecommendedViewModel,
    watchingRows: Set<Int>,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
    onPosition: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    LazyColumn(
        contentPadding = PaddingValues(top = 16.dp, bottom = bottom + PhoneDimens.rowGap),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(rows) { rowIndex, row ->
            val rowModifier = Modifier.padding(bottom = PhoneDimens.rowGap)
            when (row) {
                is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                    PhoneRowMessage(
                        title = row.title.getString(),
                        message = stringResource(R.string.tally_library_loading),
                        height = phoneHomeCardHeight(HomeRowViewOptions(), namesOnly = false),
                        modifier = rowModifier,
                    )
                }

                is HomeRowLoadingState.Error -> {
                    PhoneRowMessage(
                        title = row.title.getString(),
                        message = row.localizedMessage,
                        failure = true,
                        modifier = rowModifier,
                    )
                }

                is HomeRowLoadingState.Success -> {
                    if (row.items.isNotEmpty()) {
                        PhoneCardRow(
                            title = row.title.getString(),
                            items = row.items,
                            count = row.items.size,
                            onAll =
                                if (row.showViewMore) {
                                    { viewModel.onClickViewMore(RowColumn(rowIndex, row.items.size), row) }
                                } else {
                                    null
                                },
                            key = { index, item -> "$index-${item?.id}" },
                            modifier = rowModifier,
                        ) { item, index ->
                            HomeItemCard(
                                item = item,
                                viewOptions = row.viewOptions,
                                watchingRow = rowIndex in watchingRows,
                                onClick = { if (item != null) viewModel.navigationManager.navigateTo(item.destination()) },
                                onLongClick = {
                                    if (item != null) {
                                        onPosition(RowColumn(rowIndex, index))
                                        dialogs.contextMenu =
                                            ContextMenu.ForBaseItem(
                                                fromLongClick = true,
                                                item = item,
                                                chosenStreams = null,
                                                showGoTo = true,
                                                showStreamChoices = false,
                                                canDelete = viewModel.canDelete(item, preferences.appPreferences),
                                                canRemoveContinueWatching = false,
                                                canRemoveNextUp = false,
                                                actions = contextActions,
                                            )
                                    }
                                },
                                onPlay =
                                    if (item != null && item.type.playable) {
                                        { viewModel.navigationManager.navigateTo(Destination.Playback(item)) }
                                    } else {
                                        null
                                    },
                                imageHeight = phoneHomeImageHeight(item, row.viewOptions),
                            )
                        }
                    }
                }
            }
        }
    }
}
