package com.github.damontecres.wholphin.jellytv.media.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytv.media.kit.FocusEdge
import com.github.damontecres.wholphin.jellytv.media.kit.ItemDialogsHost
import com.github.damontecres.wholphin.jellytv.media.kit.ItemDialogsState
import com.github.damontecres.wholphin.jellytv.media.kit.MediaRow
import com.github.damontecres.wholphin.jellytv.media.kit.PosterWidth
import com.github.damontecres.wholphin.jellytv.media.kit.rememberFocusEdgeSpec
import com.github.damontecres.wholphin.jellytv.together.ui.TogetherRow
import com.github.damontecres.wholphin.jellytv.ui.components.EmptyState
import com.github.damontecres.wholphin.jellytv.ui.components.RowHeader
import com.github.damontecres.wholphin.jellytv.ui.home.JellyTvHomeFocus
import com.github.damontecres.wholphin.jellytv.ui.home.JellyTvHomeHeader
import com.github.damontecres.wholphin.jellytv.ui.home.JellyTvHomeHeaderState
import com.github.damontecres.wholphin.jellytv.ui.home.JellyTvHomeRow
import com.github.damontecres.wholphin.jellytv.ui.household.HouseholdRow
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.main.HomeViewModel
import com.github.damontecres.wholphin.ui.main.isContinueWatchingNextUp
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * The home page in the Tally look. Data and behavior are upstream's `HomePage`, line for line: the same
 * [HomeViewModel], `init()` on start, loading/error states, remembered position, click / long-click /
 * play / view-more / backdrop, and every JellyTV seam (the Sports row that takes the initial focus, the
 * game header, the household and watch-party rows). Only what is drawn changes.
 */
@Composable
fun JtvHomePage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    LifecycleStartEffect(Unit) {
        viewModel.init()
        onStopOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val unscaled = LocalDensity.current

    JtvScale {
        CompositionLocalProvider(LocalContentColor provides JtvColors.text) {
            ProvideTextStyle(JtvType.body) {
                Box(modifier = modifier.fillMaxSize()) {
                    when (val loading = state.loadingState) {
                        is LoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.jtv_media_error_title),
                                subtitle =
                                    loading.localizedMessage.ifBlank {
                                        stringResource(R.string.jtv_media_error_body)
                                    },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(JtvDimens.marginHorizontal),
                            )
                        }

                        LoadingState.Loading,
                        LoadingState.Pending,
                        -> {
                            LoadingMark(Modifier.fillMaxSize())
                        }

                        LoadingState.Success -> {
                            HomeLoaded(
                                preferences = preferences,
                                homeRows = state.homeRows,
                                rowOptions = state.settings.rows.map { it.config },
                                refreshing = state.refreshState,
                                dialogs = dialogs,
                                viewModel = viewModel,
                                unscaled = unscaled,
                            )
                        }
                    }
                }
            }
        }
    }
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = false,
        onConfirmDelete = {},
    )
}

@Composable
private fun LoadingMark(modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.tryRequestFocus("jtv-home-loading") }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.jtv_media_loading).uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
            modifier =
                Modifier
                    .focusRequester(requester)
                    .focusable(),
        )
    }
}

/** The home rows in the lazy list sit after the JellyTV, household and watch-party items. */
private sealed interface HomeCell {
    data class Item(
        val item: BaseItem?,
    ) : HomeCell

    data object ViewAll : HomeCell
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeLoaded(
    preferences: UserPreferences,
    homeRows: List<HomeRowLoadingState>,
    rowOptions: List<HomeRowConfig>,
    refreshing: LoadingState,
    dialogs: ItemDialogsState,
    viewModel: HomeViewModel,
    unscaled: Density,
) {
    var position by rememberPosition()
    val currentRows by rememberUpdatedState(homeRows)
    val currentHomePrefs by rememberUpdatedState(preferences.appPreferences.homePagePreferences)
    val onFocusPosition = remember { { it: RowColumn -> position = it } }
    val onClickItem =
        remember {
            { clickedPosition: RowColumn, item: BaseItem ->
                position = clickedPosition
                if (currentHomePrefs.clickToPlay &&
                    currentRows.getOrNull(clickedPosition.row)?.isContinueWatchingNextUp == true
                ) {
                    viewModel.navigationManager.navigateTo(Destination.Playback(item))
                } else {
                    viewModel.navigationManager.navigateTo(item.destination())
                }
            }
        }
    val onLongClickItem =
        remember(dialogs) {
            { clickedPosition: RowColumn, item: BaseItem ->
                position = clickedPosition
                val row = (currentRows.getOrNull(clickedPosition.row) as? HomeRowLoadingState.Success)
                val canRemoveContinueWatching =
                    row?.rowType is HomeRowConfig.ContinueWatching || row?.rowType is HomeRowConfig.ContinueWatchingCombined
                val canRemoveNextUp =
                    row?.rowType is HomeRowConfig.NextUp || row?.rowType is HomeRowConfig.ContinueWatchingCombined
                dialogs.contextMenu =
                    ContextMenu.ForBaseItem(
                        fromLongClick = true,
                        item = item,
                        chosenStreams = null,
                        showGoTo = true,
                        showStreamChoices = false,
                        canDelete = viewModel.canDelete(item, preferences.appPreferences),
                        canRemoveContinueWatching = canRemoveContinueWatching,
                        canRemoveNextUp = canRemoveNextUp,
                        actions =
                            ContextMenuActions(
                                navigateTo = viewModel.navigationManager::navigateTo,
                                onClickWatch = viewModel::setWatched,
                                onClickFavorite = viewModel::setFavorite,
                                onClickAddPlaylist = { itemId -> dialogs.playlistItemId = itemId },
                                onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                                onDeleteItem = { viewModel.deleteItem(position, it) },
                                onChooseVersion = { _, _ ->
                                    // Not supported on this page
                                },
                                onChooseTracks = {
                                    // Not supported on this page
                                },
                                onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
                                onClearChosenStreams = {},
                                onClickRemoveFromNextUp = viewModel::removeFromNextUp,
                            ),
                    )
            }
        }
    val onClickPlay =
        remember {
            { _: RowColumn, item: BaseItem ->
                viewModel.navigationManager.navigateTo(Destination.Playback(item))
            }
        }
    val onClickViewMore =
        remember {
            { _: RowColumn, row: HomeRowLoadingState.Success ->
                viewModel.navigationManager.navigateTo(
                    Destination.MoreHomeRow(row.title, row.rowType!!, row.items.size),
                )
            }
        }

    HomeContent(
        homeRows = homeRows,
        rowOptions = rowOptions,
        position = position,
        onFocusPosition = onFocusPosition,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        onClickPlay = onClickPlay,
        refreshing = refreshing,
        showClock = preferences.appPreferences.interfacePreferences.showClock,
        onUpdateBackdrop = viewModel::updateBackdrop,
        showLogo = preferences.appPreferences.interfacePreferences.showLogos,
        showViewMore = true,
        onClickViewMore = onClickViewMore,
        unscaled = unscaled,
    )
}

/** Items before the home rows in the lazy list: the JellyTV row, the household row, the watch-party row. */
private const val LEADING_ITEMS = 3

/** Space between rows. */
private val RowGap = 24.dp

/** The JellyTV rows' cards start this far into their lists (their own content padding, inside their JtvScale). */
private val JellyTvRowInset = 20.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    homeRows: List<HomeRowLoadingState>,
    rowOptions: List<HomeRowConfig>,
    position: RowColumn,
    onFocusPosition: (RowColumn) -> Unit,
    onClickItem: (RowColumn, BaseItem) -> Unit,
    onLongClickItem: (RowColumn, BaseItem) -> Unit,
    onClickPlay: (RowColumn, BaseItem) -> Unit,
    refreshing: LoadingState,
    showClock: Boolean,
    onUpdateBackdrop: (BaseItem) -> Unit,
    showLogo: Boolean,
    showViewMore: Boolean,
    onClickViewMore: (RowColumn, HomeRowLoadingState.Success) -> Unit,
    unscaled: Density,
) {
    val focusedItem =
        remember(homeRows, position) {
            (homeRows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(position.column)
        }
    val rowFocusRequesters = remember(homeRows.size) { List(homeRows.size) { FocusRequester() } }
    var firstFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val currentPosition by rememberUpdatedState(position)
    val currentOnFocusPosition by rememberUpdatedState(onFocusPosition)
    val currentOnClickPlay by rememberUpdatedState(onClickPlay)

    LaunchedEffect(homeRows) {
        if (!firstFocused && homeRows.isNotEmpty()) {
            if (position.row >= 0) {
                val index = position.row.coerceIn(0, rowFocusRequesters.lastIndex)
                rowFocusRequesters.getOrNull(index)?.tryRequestFocus()
                firstFocused = true
            } else {
                JellyTvHomeFocus.pageOpened()
                // Waiting for the first home row to load, then focus on it
                homeRows
                    .indexOfFirstOrNull { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
                    ?.let {
                        if (JellyTvHomeFocus.awaitPendingClaim()) {
                            firstFocused = true
                            return@let
                        }
                        rowFocusRequesters[it].tryRequestFocus()
                        firstFocused = true
                        delay(50)
                        listState.scrollToItem(it)
                    }
            }
        }
    }
    LaunchedEffect(onUpdateBackdrop, focusedItem) {
        focusedItem?.let { onUpdateBackdrop.invoke(it) }
    }

    // A focused row comes to rest at the top of the list, as upstream's page does. Requests come from the
    // focused card, so the room above it is the row's header plus the gap to the cards; the JellyTV rows
    // (header, 8dp outside the scale, 10dp inside) need the most, and set it for all.
    val headerPx = rememberTextMeasurer().measure("A", JtvType.labelLarge).size.height
    val density = LocalDensity.current
    val spaceAbovePx =
        remember(headerPx, density, unscaled) {
            headerPx + with(unscaled) { 8.dp.toPx() } + with(density) { 10.dp.toPx() }
        }
    val listSpec = remember(spaceAbovePx) { RowToTopBringIntoViewSpec(spaceAbovePx) }
    val rowSpec = rememberFocusEdgeSpec()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // The film page's scrim (see DetailHeader): the app backdrop sits top-right; the text column
                    // on the left stays readable over it.
                    drawRect(
                        brush =
                            Brush.linearGradient(
                                colorStops =
                                    arrayOf(
                                        0f to JtvColors.ground.copy(alpha = 0.88f),
                                        0.45f to JtvColors.ground.copy(alpha = 0.5f),
                                        0.85f to Color.Transparent,
                                    ),
                                start = Offset.Zero,
                                end = Offset(size.width * 0.95f, size.height * 0.15f),
                            ),
                    )
                },
    ) {
        Column(
            modifier =
                Modifier
                    .focusProperties {
                        onEnter = {
                            rowFocusRequesters.getOrNull(currentPosition.row)?.tryRequestFocus()
                        }
                    }.fillMaxSize(),
        ) {
            // While a game card has focus the header describes the game instead of a library item.
            val game by JellyTvHomeHeaderState.focusedGame
            val focusedGame = game
            if (focusedGame != null) {
                GameHeaderBand(unscaled = unscaled) {
                    JellyTvHomeHeader(
                        game = focusedGame,
                        hideScores = JellyTvHomeHeaderState.hideScores.value,
                        modifier = it,
                    )
                }
            } else {
                HomeHeader(
                    item = focusedItem,
                    rowTitle = homeRows.getOrNull(position.row)?.title?.getString(),
                    showLogo = showLogo,
                )
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides listSpec) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = PosterWidth * 1.5f),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusRestorer(),
                ) {
                    // The JellyTV rows draw themselves outside the scale (their cards carry their own JtvScale):
                    // they get the unscaled density back and are inset so their first card lines up with ours.
                    item(key = "jellytv") {
                        JellyTvRowSlot(unscaled, rowSpec, Modifier.animateItem(placementSpec = null)) {
                            JellyTvHomeRow()
                        }
                    }
                    item(key = "household") {
                        JellyTvRowSlot(unscaled, rowSpec, Modifier.animateItem(placementSpec = null)) {
                            HouseholdRow()
                        }
                    }
                    item(key = "together") {
                        JellyTvRowSlot(unscaled, rowSpec, Modifier.animateItem(placementSpec = null)) {
                            TogetherRow()
                        }
                    }
                    itemsIndexed(homeRows) { rowIndex, row ->
                        val options = rowOptions.getOrNull(rowIndex)
                        val rowModifier =
                            Modifier
                                .animateItem(placementSpec = null)
                                .fillMaxWidth()
                                .padding(horizontal = JtvDimens.marginHorizontal)
                        when (val r = row) {
                            is HomeRowLoadingState.Loading,
                            is HomeRowLoadingState.Pending,
                            -> {
                                RowMessage(
                                    title = r.title.getString(),
                                    message = stringResource(R.string.jtv_media_loading),
                                    height =
                                        homeCardHeight(
                                            options?.viewOptions ?: HomeRowViewOptions(),
                                            options is HomeRowConfig.Genres || options is HomeRowConfig.Studios,
                                        ),
                                    mono = true,
                                    modifier = rowModifier.padding(bottom = RowGap),
                                )
                            }

                            is HomeRowLoadingState.Error -> {
                                RowMessage(
                                    title = r.title.getString(),
                                    message = r.localizedMessage,
                                    height = null,
                                    mono = false,
                                    modifier = rowModifier.padding(bottom = RowGap),
                                )
                            }

                            is HomeRowLoadingState.Success -> {
                                if (r.items.isNotEmpty()) {
                                    LibraryRow(
                                        rowIndex = rowIndex,
                                        row = r,
                                        showViewMore = showViewMore && r.showViewMore,
                                        focusRequester = rowFocusRequesters[rowIndex],
                                        onFocusPosition = { currentOnFocusPosition(it) },
                                        onClickItem = onClickItem,
                                        onLongClickItem = onLongClickItem,
                                        onClickPlay = { item -> currentOnClickPlay(currentPosition, item) },
                                        onClickViewMore = onClickViewMore,
                                        modifier = rowModifier.padding(bottom = RowGap),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        when (refreshing) {
            LoadingState.Pending,
            LoadingState.Loading,
            -> {
                Text(
                    text = stringResource(R.string.jtv_media_loading).uppercase(),
                    style = JtvType.label,
                    color = JtvColors.muted,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                top = if (showClock) RefreshTopWithClock else JtvDimens.marginVertical,
                                end = JtvDimens.marginHorizontal,
                            ),
                )
            }

            else -> {}
        }
    }
}

/** Below the clock (drawn by the drawer, top-right) when it is shown. */
private val RefreshTopWithClock = 72.dp

@Composable
private fun LibraryRow(
    rowIndex: Int,
    row: HomeRowLoadingState.Success,
    showViewMore: Boolean,
    focusRequester: FocusRequester,
    onFocusPosition: (RowColumn) -> Unit,
    onClickItem: (RowColumn, BaseItem) -> Unit,
    onLongClickItem: (RowColumn, BaseItem) -> Unit,
    onClickPlay: (BaseItem) -> Unit,
    onClickViewMore: (RowColumn, HomeRowLoadingState.Success) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells =
        remember(row.items, showViewMore) {
            row.items.map { HomeCell.Item(it) } + if (showViewMore) listOf(HomeCell.ViewAll) else emptyList()
        }
    val viewOptions = row.viewOptions
    val watchingRow = row.isContinueWatchingNextUp
    MediaRow(
        title = row.title.getString(),
        items = cells,
        count = row.items.size,
        revealOnFocus = false,
        key = { index, cell ->
            when (cell) {
                is HomeCell.Item -> "$index-${cell.item?.id}"
                HomeCell.ViewAll -> "view-all"
            }
        },
        modifier = modifier.focusRequester(focusRequester),
        card = { cell, index, cardModifier, onFocused ->
            when (cell) {
                is HomeCell.Item -> {
                    val item = cell.item
                    HomeItemCard(
                        item = item,
                        viewOptions = viewOptions,
                        watchingRow = watchingRow,
                        onClick = { if (item != null) onClickItem(RowColumn(rowIndex, index), item) },
                        onLongClick = { if (item != null) onLongClickItem(RowColumn(rowIndex, index), item) },
                        onPlay =
                            if (item != null && item.type.playable) {
                                {
                                    Timber.v("Clicked play on ${item.id}")
                                    onClickPlay(item)
                                }
                            } else {
                                null
                            },
                        onFocused = {
                            onFocused()
                            onFocusPosition(RowColumn(rowIndex, index))
                        },
                        modifier = cardModifier,
                    )
                }

                HomeCell.ViewAll -> {
                    HomeViewAllCard(
                        lastItem = row.items.lastOrNull(),
                        viewOptions = viewOptions,
                        onClick = { onClickViewMore(RowColumn(rowIndex, row.items.size), row) },
                        onFocused = {
                            onFocused()
                            onFocusPosition(RowColumn(rowIndex, row.items.size))
                        },
                        modifier = cardModifier,
                    )
                }
            }
        },
    )
}

/** A row still loading (mono `LOADING…`, as tall as its cards) or one that failed (the message). */
@Composable
private fun RowMessage(
    title: String,
    message: String,
    height: Dp?,
    mono: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        RowHeader(title = title)
        if (height != null) {
            HomeRowMessage(
                text = message,
                height = height,
                mono = mono,
                modifier = Modifier.padding(vertical = FocusEdge),
            )
        } else {
            Text(
                text = message,
                style = JtvType.body,
                color = JtvColors.muted,
                maxLines = 2,
            )
        }
    }
}

/**
 * Hosts one of the JellyTV rows. They are laid out for upstream's page (outside the scale, their cards
 * inside their own [JtvScale]), so they get the unscaled density back; the inset lines their first card
 * and title up with the library rows. They render nothing when empty, so the row gap is added only
 * when they have height.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JellyTvRowSlot(
    unscaled: Density,
    rowSpec: BringIntoViewSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val gap = RowGap - with(LocalDensity.current) { with(unscaled) { 8.dp.toPx() }.toDp() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .gapBelowWhenShown(gap)
                .padding(horizontal = JtvDimens.marginHorizontal - JellyTvRowInset),
    ) {
        CompositionLocalProvider(
            LocalDensity provides unscaled,
            LocalBringIntoViewSpec provides rowSpec,
            content = content,
        )
    }
}

/** Adds [gap] under the content only when the content has height (the JellyTV rows are empty without data). */
private fun Modifier.gapBelowWhenShown(gap: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val extra = if (placeable.height > 0) gap.roundToPx().coerceAtLeast(0) else 0
        layout(placeable.width, placeable.height + extra) { placeable.place(0, 0) }
    }

/**
 * The game header ([JellyTvHomeHeader], unchanged) in the header band: drawn at the unscaled density it
 * was made for, its text lined up with the page margin, cut to the band's fixed height so rows never move.
 */
@Composable
private fun GameHeaderBand(
    unscaled: Density,
    content: @Composable (Modifier) -> Unit,
) {
    val marginPx = with(LocalDensity.current) { JtvDimens.marginHorizontal.toPx() }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HomeHeaderHeight)
                .clipToBounds(),
    ) {
        CompositionLocalProvider(LocalDensity provides unscaled) {
            val start = with(unscaled) { marginPx.toDp() } - HeaderUtils.startPadding
            content(
                Modifier
                    .padding(start = start)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            )
        }
    }
}

/**
 * Brings the requesting card to [spaceAbovePx] under the top of the list, like upstream's
 * `ScrollToTopBringIntoViewSpec`, so the focused row rests at the top of the rows with its header shown.
 */
@OptIn(ExperimentalFoundationApi::class)
private class RowToTopBringIntoViewSpec(
    private val spaceAbovePx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = offset - spaceAbovePx
}

/**
 * The title of a JellyTV row (games, household, watch parties): in the Tally look the mono [RowHeader] of
 * every home row, indented by [start] (the row's card inset, inside the scale) so it sits over the first
 * card; with a Wholphin theme, upstream's title as before ([upstream]).
 */
@Composable
fun HomeRowTitle(
    title: String,
    count: Int?,
    start: Dp,
    upstream: @Composable () -> Unit,
) {
    if (LocalTheme.current == AppThemeColors.JELLYTV) {
        JtvScale {
            RowHeader(title = title, count = count, modifier = Modifier.padding(start = start))
        }
    } else {
        upstream()
    }
}
