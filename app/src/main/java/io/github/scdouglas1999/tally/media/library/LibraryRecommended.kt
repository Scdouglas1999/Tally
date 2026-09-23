package io.github.scdouglas1999.tally.media.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.RecommendedRow
import com.github.damontecres.wholphin.ui.components.RecommendedViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetNextUpRequestHandler
import com.github.damontecres.wholphin.util.GetResumeItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.home.HomeHeader
import io.github.scdouglas1999.tally.media.home.HomeItemCard
import io.github.scdouglas1999.tally.media.home.HomeRowMessage
import io.github.scdouglas1999.tally.media.home.HomeViewAllCard
import io.github.scdouglas1999.tally.media.home.homeCardHeight
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.MediaRow
import io.github.scdouglas1999.tally.media.kit.PosterWidth
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import java.time.LocalDateTime
import java.util.UUID

/**
 * The rows of a movie library's Recommended tab: upstream's `RecommendedMovie` rows, request for request (upstream
 * keeps them private, so they are restated here and must follow it).
 */
private fun movieRows(parentId: UUID): List<RecommendedRow<*>> =
    listOf(
        RecommendedRow(
            title = R.string.continue_watching,
            handler = GetResumeItemsRequestHandler,
            request =
                GetResumeItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    enableUserData = true,
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.recently_released,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
                    enableTotalRecordCount = false,
                    maxPremiereDate = LocalDateTime.now(),
                ),
        ),
        RecommendedRow(
            title = R.string.recently_added,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.top_unwatched,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    isPlayed = false,
                    sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
    )

/** The rows of a TV library's Recommended tab: upstream's `RecommendedTvShow` rows, request for request. */
private fun tvRows(
    parentId: UUID,
    enableRewatching: Boolean,
): List<RecommendedRow<*>> =
    listOf(
        RecommendedRow(
            title = R.string.continue_watching,
            handler = GetResumeItemsRequestHandler,
            request =
                GetResumeItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    enableUserData = true,
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.next_up,
            handler = GetNextUpRequestHandler,
            request =
                GetNextUpRequest(
                    fields = SlimItemFields,
                    imageTypeLimit = 1,
                    parentId = parentId,
                    enableResumable = false,
                    enableUserData = true,
                    enableRewatching = enableRewatching,
                ),
        ),
        RecommendedRow(
            title = R.string.recently_released,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    enableUserData = true,
                    sortBy =
                        listOf(
                            ItemSortBy.PREMIERE_DATE,
                            ItemSortBy.SERIES_SORT_NAME,
                            ItemSortBy.AIRED_EPISODE_ORDER,
                        ),
                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING, SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                    maxPremiereDate = LocalDateTime.now(),
                    isUnaired = false,
                ),
        ),
        RecommendedRow(
            title = R.string.recently_added,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.top_unwatched,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.SERIES),
                    recursive = true,
                    enableUserData = true,
                    isPlayed = false,
                    sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
    )

/** Upstream's [RecommendedViewModel] for a movie library, created as `RecommendedMovie` creates it. */
@Composable
internal fun movieRecommendedViewModel(parentId: UUID): RecommendedViewModel =
    hiltViewModel<RecommendedViewModel, RecommendedViewModel.Factory>(
        creationCallback = {
            it.create(
                parentId = parentId,
                suggestionsType = BaseItemKind.MOVIE,
                recommendedRows = movieRows(parentId),
                viewOptions = HomeRowViewOptions(),
            )
        },
    )

/** Upstream's [RecommendedViewModel] for a TV library, created as `RecommendedTvShow` creates it. */
@Composable
internal fun tvRecommendedViewModel(
    parentId: UUID,
    preferences: UserPreferences,
): RecommendedViewModel =
    hiltViewModel<RecommendedViewModel, RecommendedViewModel.Factory>(
        creationCallback = {
            it.create(
                parentId = parentId,
                suggestionsType = BaseItemKind.SERIES,
                recommendedRows =
                    tvRows(parentId, preferences.appPreferences.homePagePreferences.enableRewatchingNextUp),
                viewOptions = HomeRowViewOptions(),
            )
        },
    )

/**
 * The Recommended tab: the home page's look for upstream's `RecommendedContent`. The focused item's header band
 * (as on home), then one [MediaRow] per row with its mono [RowHeader]; a full row ends in a VIEW ALL card. Click
 * opens, PLAY plays, a long press opens the item menu. [watchingRows] are the rows whose cards show how much is
 * watched (Continue watching, Next up).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryRecommended(
    preferences: UserPreferences,
    viewModel: RecommendedViewModel,
    watchingRows: Set<Int>,
    dialogs: ItemDialogsState,
    modifier: Modifier = Modifier,
) {
    OneTimeLaunchedEffect { viewModel.init() }
    val state by viewModel.state.collectAsState()
    when (val loading = state.loading) {
        is LoadingState.Error -> {
            LibraryError(loading.localizedMessage, modifier)
        }

        LoadingState.Loading, LoadingState.Pending -> {
            LoadingMark(modifier.fillMaxSize())
        }

        LoadingState.Success -> {
            RecommendedLoaded(
                preferences = preferences,
                rows = state.rows,
                viewModel = viewModel,
                watchingRows = watchingRows,
                dialogs = dialogs,
                modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendedLoaded(
    preferences: UserPreferences,
    rows: List<HomeRowLoadingState>,
    viewModel: RecommendedViewModel,
    watchingRows: Set<Int>,
    dialogs: ItemDialogsState,
    modifier: Modifier = Modifier,
) {
    var position by rememberPosition()
    val currentPosition by rememberUpdatedState(position)
    val rowRequesters = remember(rows.size) { List(rows.size) { FocusRequester() } }
    var firstFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val contextActions =
        remember(dialogs) {
            ContextMenuActions(
                navigateTo = viewModel.navigationManager::navigateTo,
                onClickWatch = { itemId, watched -> viewModel.setWatched(currentPosition, itemId, watched) },
                onClickFavorite = { itemId, favorite -> viewModel.setFavorite(currentPosition, itemId, favorite) },
                onClickAddPlaylist = { itemId -> dialogs.playlistItemId = itemId },
                onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                onDeleteItem = { viewModel.deleteItem(currentPosition, it) },
                onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
                onChooseVersion = { _, _ ->
                    // Not supported on this page
                },
                onChooseTracks = {
                    // Not supported on this page
                },
                onClearChosenStreams = {
                    // Not supported on this page
                },
            )
        }

    LaunchedEffect(rows) {
        if (!firstFocused && rows.isNotEmpty()) {
            val target =
                if (position.row >= 0) {
                    position.row.coerceIn(0, rowRequesters.lastIndex)
                } else {
                    rows.indexOfFirstOrNull { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
                }
            if (target != null && rowRequesters[target].tryRequestFocus("tally-library-recommended")) {
                firstFocused = true
            }
        }
    }

    val focusedItem =
        remember(rows, position) {
            (rows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(position.column)
        }
    LaunchedEffect(focusedItem) { focusedItem?.let(viewModel::updateBackdrop) }

    // Room above a focused card: its row header, the 8dp to the cards, and the focus edge.
    val headerPx = rememberTextMeasurer().measure("A", TallyType.labelLarge).size.height
    val density = LocalDensity.current
    val listSpec =
        remember(headerPx, density) {
            RowToTopSpec(headerPx + with(density) { (8.dp + FocusEdge).toPx() })
        }

    Column(modifier = modifier.fillMaxSize()) {
        HomeHeader(
            item = focusedItem,
            rowTitle = rows.getOrNull(position.row)?.title?.getString(),
            showLogo = preferences.appPreferences.interfacePreferences.showLogos,
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides listSpec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = PosterWidth * 1.5f),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRestorer()
                        .focusProperties {
                            onEnter = { rowRequesters.getOrNull(currentPosition.row)?.tryRequestFocus() }
                        },
            ) {
                itemsIndexed(rows) { rowIndex, row ->
                    val rowModifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TallyDimens.marginHorizontal)
                            .padding(bottom = RowGap)
                    when (row) {
                        is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = rowModifier) {
                                RowHeader(title = row.title.getString())
                                HomeRowMessage(
                                    text = stringResource(R.string.tally_library_loading),
                                    height = homeCardHeight(HomeRowViewOptions(), namesOnly = false),
                                    modifier = Modifier.padding(vertical = FocusEdge),
                                )
                            }
                        }

                        is HomeRowLoadingState.Error -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = rowModifier) {
                                RowHeader(title = row.title.getString())
                                HomeRowMessage(
                                    text = row.localizedMessage,
                                    height = 40.dp,
                                    mono = false,
                                )
                            }
                        }

                        is HomeRowLoadingState.Success -> {
                            if (row.items.isNotEmpty()) {
                                RecommendedRowView(
                                    rowIndex = rowIndex,
                                    row = row,
                                    watching = rowIndex in watchingRows,
                                    focusRequester = rowRequesters[rowIndex],
                                    onFocusPosition = { position = it },
                                    onClickItem = { _, item ->
                                        viewModel.navigationManager.navigateTo(item.destination())
                                    },
                                    onLongClickItem = { clicked, item ->
                                        position = clicked
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
                                    },
                                    onClickPlay = { item ->
                                        viewModel.navigationManager.navigateTo(Destination.Playback(item))
                                    },
                                    onClickViewMore = { viewModel.onClickViewMore(it, row) },
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface RecommendedCell {
    data class Item(
        val item: BaseItem?,
    ) : RecommendedCell

    data object ViewAll : RecommendedCell
}

@Composable
private fun RecommendedRowView(
    rowIndex: Int,
    row: HomeRowLoadingState.Success,
    watching: Boolean,
    focusRequester: FocusRequester,
    onFocusPosition: (RowColumn) -> Unit,
    onClickItem: (RowColumn, BaseItem) -> Unit,
    onLongClickItem: (RowColumn, BaseItem) -> Unit,
    onClickPlay: (BaseItem) -> Unit,
    onClickViewMore: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells =
        remember(row.items, row.showViewMore) {
            row.items.map { RecommendedCell.Item(it) } +
                if (row.showViewMore) listOf(RecommendedCell.ViewAll) else emptyList()
        }
    MediaRow(
        title = row.title.getString(),
        items = cells,
        count = row.items.size,
        revealOnFocus = false,
        key = { index, cell ->
            when (cell) {
                is RecommendedCell.Item -> "$index-${cell.item?.id}"
                RecommendedCell.ViewAll -> "view-all"
            }
        },
        modifier = modifier.focusRequester(focusRequester),
        card = { cell, index, cardModifier, onFocused ->
            when (cell) {
                is RecommendedCell.Item -> {
                    val item = cell.item
                    HomeItemCard(
                        item = item,
                        viewOptions = row.viewOptions,
                        watchingRow = watching,
                        onClick = { if (item != null) onClickItem(RowColumn(rowIndex, index), item) },
                        onLongClick = { if (item != null) onLongClickItem(RowColumn(rowIndex, index), item) },
                        onPlay = if (item != null && item.type.playable) ({ onClickPlay(item) }) else null,
                        onFocused = {
                            onFocused()
                            onFocusPosition(RowColumn(rowIndex, index))
                        },
                        modifier = cardModifier,
                    )
                }

                RecommendedCell.ViewAll -> {
                    HomeViewAllCard(
                        lastItem = row.items.lastOrNull(),
                        viewOptions = row.viewOptions,
                        onClick = { onClickViewMore(RowColumn(rowIndex, row.items.size)) },
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

private val RowGap = 24.dp

/** Brings the requesting card to just under its row header, so the focused row rests at the top of the list. */
@OptIn(ExperimentalFoundationApi::class)
private class RowToTopSpec(
    private val spaceAbovePx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = offset - spaceAbovePx
}
