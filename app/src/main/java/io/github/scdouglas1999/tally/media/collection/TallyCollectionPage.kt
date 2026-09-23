package io.github.scdouglas1999.tally.media.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewModel
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptionsDialog
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.DetailHeader
import io.github.scdouglas1999.tally.media.kit.DetailMetaPart
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.revealWhenResized
import io.github.scdouglas1999.tally.media.library.FilterDialog
import io.github.scdouglas1999.tally.media.library.LibraryControlButton
import io.github.scdouglas1999.tally.media.library.SortDialog
import io.github.scdouglas1999.tally.media.library.directionArrow
import io.github.scdouglas1999.tally.media.library.sortLabel
import io.github.scdouglas1999.tally.media.pages.CountNoun
import io.github.scdouglas1999.tally.media.pages.countNoun
import io.github.scdouglas1999.tally.media.pages.totalRuntimeTicks
import io.github.scdouglas1999.tally.media.pages.yearRange
import io.github.scdouglas1999.tally.media.search.GridCardWidth
import io.github.scdouglas1999.tally.media.search.PagesItemCard
import io.github.scdouglas1999.tally.media.search.PagesItemRow
import io.github.scdouglas1999.tally.media.search.PagesLoading
import io.github.scdouglas1999.tally.media.search.rememberPageScrollSpec
import io.github.scdouglas1999.tally.media.search.typeTitle
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import java.util.UUID

/** Items the header's count, years and running time are read from (the first page of each row). */
private const val META_SAMPLE = 100

/**
 * The Tally collection (box set) page: upstream's [CollectionViewModel] with its sort, filter, view
 * options, play all / shuffle, favorite, delete and item menus; drawn as a [DetailHeader] and one row
 * per type (or upstream's mixed grid when "separate types" is off).
 */
@Composable
fun TallyCollectionPage(
    preferences: UserPreferences,
    itemId: UUID,
    modifier: Modifier = Modifier,
    viewModel: CollectionViewModel =
        hiltViewModel<CollectionViewModel, CollectionViewModel.Factory>(
            creationCallback = { it.create(itemId) },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    var showViewOptions by remember { mutableStateOf(false) }
    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)

    fun contextActionsFor(position: RowColumn?) =
        ContextMenuActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { id, watched -> viewModel.setWatched(id, watched, position) },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite, position) },
            onClickAddPlaylist = { dialogs.playlistItemId = it },
            onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
            onDeleteItem = { viewModel.deleteItem(it, position) },
            onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
            onChooseVersion = { _, _ -> },
            onChooseTracks = { _ -> },
            onClearChosenStreams = { },
        )

    fun openItemMenu(
        position: RowColumn,
        item: BaseItem,
    ) {
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
                actions = contextActionsFor(position),
            )
    }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .drawBehind {
                                // Image scrim: the backdrop follows the focused film (as upstream), and the
                                // header's controls must stay readable on it.
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            0f to TallyColors.ground.copy(alpha = 0.55f),
                                            0.3f to TallyColors.ground.copy(alpha = 0.8f),
                                            0.65f to TallyColors.ground,
                                        ),
                                )
                            },
                ) {
                    val collection = state.collection
                    when (val loading = state.loadingState) {
                        is LoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.tally_media_error_title),
                                subtitle =
                                    loading.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                                modifier = Modifier.fillMaxSize().padding(TallyDimens.marginHorizontal),
                            )
                        }

                        LoadingState.Loading,
                        LoadingState.Pending,
                        -> {
                            PagesLoading("tally-collection-loading", Modifier.fillMaxSize())
                        }

                        LoadingState.Success -> {
                            if (collection != null) {
                                CollectionLoaded(
                                    preferences = preferences,
                                    collection = collection,
                                    viewModel = viewModel,
                                    onPlayAll = { shuffle ->
                                        viewModel.navigateTo(
                                            Destination.PlaybackList(
                                                itemId = itemId,
                                                startIndex = 0,
                                                shuffle = shuffle,
                                                recursive = true,
                                                sortAndDirection = state.sortAndDirection,
                                                filter = state.itemFilter,
                                            ),
                                        )
                                    },
                                    onOverview = { dialogs.overview = ItemDetailsDialogInfo(collection) },
                                    onDelete = { dialogs.deleteItem = collection },
                                    onViewOptions = { showViewOptions = true },
                                    onMore = {
                                        dialogs.contextMenu =
                                            ContextMenu.ForBaseItem(
                                                fromLongClick = false,
                                                item = collection,
                                                chosenStreams = null,
                                                showGoTo = false,
                                                showStreamChoices = false,
                                                canDelete = viewModel.canDelete(collection, preferences.appPreferences),
                                                canRemoveContinueWatching = false,
                                                canRemoveNextUp = false,
                                                actions = contextActionsFor(null),
                                            )
                                    },
                                    onItemMenu = ::openItemMenu,
                                )
                            }
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
        showFilePath = userDto?.policy?.isAdministrator == true,
        onConfirmDelete = { viewModel.deleteItem(it, null) },
        playlistViewModel = playlistViewModel,
    )
    if (showViewOptions) {
        CollectionViewOptionsDialog(
            viewOptions = state.viewOptions,
            onDismissRequest = { showViewOptions = false },
            onViewOptionsChange = viewModel::changeViewOptions,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionLoaded(
    preferences: UserPreferences,
    collection: BaseItem,
    viewModel: CollectionViewModel,
    onPlayAll: (shuffle: Boolean) -> Unit,
    onOverview: () -> Unit,
    onDelete: () -> Unit,
    onViewOptions: () -> Unit,
    onMore: () -> Unit,
    onItemMenu: (RowColumn, BaseItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val playFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val collectionNow by rememberUpdatedState(collection)
    LaunchedEffect(Unit) { playFocus.tryRequestFocus("tally-collection") }
    val onHeaderFocused: () -> Unit = {
        viewModel.updateBackdrop(collectionNow)
        scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
    }
    val onClickItem = { item: BaseItem -> viewModel.navigateTo(item.destination()) }
    val onPlayItem = { item: BaseItem -> viewModel.navigateTo(Destination.Playback(item = item)) }

    val rows = state.separateItems.entries.toList()
    val sample =
        if (state.viewOptions.separateTypes) {
            rows.flatMap { (_, row) -> (row as? HomeRowLoadingState.Success)?.items?.take(META_SAMPLE).orEmpty() }
        } else {
            state.items.take(META_SAMPLE)
        }.filterNotNull()
    val empty =
        if (state.viewOptions.separateTypes) {
            rows.isNotEmpty() && rows.all { (_, row) -> row is HomeRowLoadingState.Success && row.items.isEmpty() }
        } else {
            state.items.isEmpty()
        }

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPageScrollSpec()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                DetailHeader(
                    kicker = stringResource(R.string.tally_pages_collection),
                    title = collection.name ?: "",
                    logoUrl =
                        if (preferences.appPreferences.interfacePreferences.showLogos) state.logoImageUrl else null,
                    meta = collectionMeta(collection, sample),
                    genres = collection.data.genres.orEmpty(),
                    tagline = collection.data.taglines?.firstOrNull(),
                    overview = collection.data.overview,
                    director = null,
                    tech = emptyList(),
                    onOverviewClick = onOverview,
                    actions = {
                        CollectionActions(
                            collection = collection,
                            viewModel = viewModel,
                            canDelete = viewModel.canDelete(collection, preferences.appPreferences),
                            playFocus = playFocus,
                            actionFocus = actionFocus,
                            down = if (empty) null else firstRowFocus,
                            onFocused = onHeaderFocused,
                            onPlayAll = onPlayAll,
                            onDelete = onDelete,
                            onViewOptions = onViewOptions,
                            onMore = onMore,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringHeader),
                )
            }
            if (empty) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.tally_pages_collection_empty),
                        subtitle = stringResource(R.string.tally_pages_collection_empty_body),
                        takeFocus = false,
                        modifier =
                            Modifier
                                .padding(horizontal = TallyDimens.marginHorizontal)
                                .fillMaxWidth()
                                .height(140.dp),
                    )
                }
            } else if (state.viewOptions.separateTypes) {
                var first = true
                rows.forEachIndexed { rowIndex, (type, row) ->
                    val isFirst = first && !(row is HomeRowLoadingState.Success && row.items.isEmpty())
                    if (isFirst) first = false
                    item(key = "row-${type.serialName}") {
                        val margin =
                            Modifier
                                .padding(horizontal = TallyDimens.marginHorizontal)
                                .padding(bottom = 24.dp)
                        when (row) {
                            is HomeRowLoadingState.Success -> {
                                if (row.items.isNotEmpty()) {
                                    PagesItemRow(
                                        title = stringResource(typeTitle(type)),
                                        items = row.items,
                                        fallbackType = type,
                                        modifier =
                                            margin
                                                .then(if (isFirst) Modifier.focusRequester(firstRowFocus) else Modifier)
                                                .focusProperties { if (isFirst) up = actionFocus },
                                        onFocusItem = { _, item -> item?.let(viewModel::updateBackdrop) },
                                        onClick = { _, item -> onClickItem(item) },
                                        onLongClick = { index, item -> onItemMenu(RowColumn(rowIndex, index), item) },
                                        onPlay = { _, item -> onPlayItem(item) },
                                    )
                                }
                            }

                            is HomeRowLoadingState.Error -> {
                                RowNote(stringResource(typeTitle(type)), row.localizedMessage, true, margin)
                            }

                            is HomeRowLoadingState.Loading,
                            is HomeRowLoadingState.Pending,
                            -> {
                                RowNote(stringResource(typeTitle(type)), stringResource(R.string.tally_media_loading), false, margin)
                            }
                        }
                    }
                }
            } else {
                // Upstream's mixed grid, as rows of poster cards inside the page's list.
                item(key = "grid") {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val cell = GridCardWidth + GridGap
                        val columns =
                            ((maxWidth - TallyDimens.marginHorizontal * 2 + GridGap) / cell).toInt().coerceAtLeast(1)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(GridGap),
                            modifier =
                                Modifier
                                    .focusRequester(firstRowFocus)
                                    .focusGroup()
                                    .focusProperties { up = actionFocus },
                        ) {
                            val items = state.items
                            (0 until (items.size + columns - 1) / columns).forEach { r ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(GridGap),
                                    modifier = Modifier.padding(horizontal = TallyDimens.marginHorizontal),
                                ) {
                                    (r * columns until minOf(items.size, (r + 1) * columns)).forEach { index ->
                                        val item = items.getOrNull(index)
                                        PagesItemCard(
                                            item = item,
                                            fallbackType = null,
                                            kicker = null,
                                            gridSized = true,
                                            onClick = { item?.let(onClickItem) },
                                            onLongClick = { item?.let { onItemMenu(RowColumn(0, index), it) } },
                                            onPlay = { item?.let { if (it.type.playable) onPlayItem(it) } },
                                            onFocused = { item?.let(viewModel::updateBackdrop) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowNote(
    title: String,
    message: String,
    failure: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        RowHeader(title = title)
        Text(
            text = message.tallyUppercase(),
            style = TallyType.label,
            color = if (failure) TallyColors.liveText else TallyColors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `3 FILMS · 1995–2010 · 4h 50m`: the count, the span of release years and the total running time. */
@Composable
private fun collectionMeta(
    collection: BaseItem,
    sample: List<BaseItem>,
): List<DetailMetaPart> {
    val count = collection.data.childCount ?: sample.size
    val noun =
        when (countNoun(sample.map { it.type })) {
            CountNoun.FILMS -> R.plurals.tally_pages_count_films
            CountNoun.SHOWS -> R.plurals.tally_pages_count_shows
            CountNoun.EPISODES -> R.plurals.tally_pages_count_episodes
            CountNoun.ITEMS -> R.plurals.tally_pages_count_items
        }
    val years = yearRange(sample.map { it.data.productionYear })
    val runtime = totalRuntimeTicks(sample.map { it.data.runTimeTicks })
    return buildList {
        if (count > 0) add(DetailMetaPart.Plain(pluralStringResource(noun, count, count)))
        years?.let { add(DetailMetaPart.Plain(it)) }
        collection.data.officialRating
            ?.takeIf { it.isNotBlank() }
            ?.let { add(DetailMetaPart.Boxed(it)) }
        if (runtime > 0L) add(DetailMetaPart.Plain(formatRuntime(runtime)))
    }
}

/**
 * PLAY (the whole collection, as upstream), SHUFFLE, WATCHED, FAVORITE, DELETE (when allowed), VIEW,
 * MORE, then SORT and FILTER (the library page's controls and panels).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionActions(
    collection: BaseItem,
    viewModel: CollectionViewModel,
    canDelete: Boolean,
    playFocus: FocusRequester,
    actionFocus: FocusRequester,
    down: FocusRequester?,
    onFocused: () -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onViewOptions: () -> Unit,
    onMore: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val filterButton = remember { FocusRequester() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = FocusEdge, end = 24.dp, top = FocusEdge, bottom = FocusEdge),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bleedHorizontal()
                    .focusRequester(actionFocus)
                    .focusGroup()
                    .focusRestorer(playFocus)
                    .focusProperties { if (down != null) this.down = down },
        ) {
            item(key = "play") {
                TallyButton(
                    label = stringResource(R.string.tally_media_play),
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    onClick = { onPlayAll(false) },
                    onFocused = onFocused,
                    modifier = Modifier.focusRequester(playFocus),
                )
            }
            item(key = "shuffle") {
                TallyButton(
                    label = stringResource(R.string.tally_pages_shuffle),
                    glyph = stringResource(R.string.fa_shuffle),
                    onClick = { onPlayAll(true) },
                    onFocused = onFocused,
                )
            }
            item(key = "watched") {
                TallyButton(
                    label =
                        stringResource(
                            if (collection.played) R.string.tally_media_watched else R.string.tally_media_unwatched,
                        ),
                    glyph = stringResource(if (collection.played) R.string.fa_eye else R.string.fa_eye_slash),
                    onClick = { viewModel.setWatched(collection.id, !collection.played, null) },
                    onFocused = onFocused,
                )
            }
            item(key = "favorite") {
                TallyButton(
                    label =
                        stringResource(
                            if (collection.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite,
                        ),
                    glyph = stringResource(R.string.fa_heart),
                    onClick = { viewModel.setFavorite(collection.id, !collection.favorite, null) },
                    onFocused = onFocused,
                    trailing =
                        if (collection.favorite) {
                            { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                        } else {
                            null
                        },
                )
            }
            if (canDelete) {
                item(key = "delete") {
                    TallyButton(
                        label = stringResource(R.string.tally_pages_delete),
                        glyph = stringResource(R.string.tally_pages_fa_trash),
                        onClick = onDelete,
                        onFocused = onFocused,
                    )
                }
            }
            item(key = "view") {
                TallyButton(
                    label = stringResource(R.string.tally_pages_view),
                    glyph = stringResource(R.string.fa_sliders),
                    onClick = onViewOptions,
                    onFocused = onFocused,
                )
            }
            item(key = "more") {
                TallyButton(
                    label = stringResource(R.string.tally_media_more),
                    glyph = stringResource(R.string.fa_ellipsis),
                    onClick = onMore,
                    onFocused = onFocused,
                )
            }
            // The library page's sort and filter controls, with upstream's view-model calls.
            item(key = "sort") {
                LibraryControlButton(
                    label = sortLabel(state.sortAndDirection),
                    suffix = directionArrow(state.sortAndDirection.direction),
                    onClick = { sortOpen = true },
                    // As upstream's sort button: a long press reverses the order.
                    onLongClick = { viewModel.changeSort(state.sortAndDirection.flip()) },
                    modifier =
                        Modifier
                            .revealWhenResized()
                            .onFocusChanged { if (it.isFocused) onFocused() },
                )
            }
            item(key = "filter") {
                val count = state.itemFilter.countFilters(DefaultFilterOptions)
                LibraryControlButton(
                    label =
                        if (count > 0) {
                            stringResource(R.string.tally_library_filter_count, count)
                        } else {
                            stringResource(R.string.tally_library_filter)
                        },
                    onClick = { filterOpen = true },
                    modifier =
                        Modifier
                            .revealWhenResized()
                            .focusRequester(filterButton)
                            .onFocusChanged { if (it.isFocused) onFocused() },
                )
            }
        }
    }
    if (sortOpen) {
        SortDialog(
            sortOptions = MovieSortOptions,
            current = state.sortAndDirection,
            onSortChange = viewModel::changeSort,
            onDismiss = { sortOpen = false },
        )
    }
    if (filterOpen) {
        FilterDialog(
            filterOptions = DefaultFilterOptions,
            current = state.itemFilter,
            onFilterChange = viewModel::changeFilter,
            getPossibleValues = viewModel::getPossibleFilterValues,
            onDismiss = {
                filterOpen = false
                filterButton.tryRequestFocus("tally-collection-filter")
            },
        )
    }
}

private val GridGap = 16.dp
