package io.github.scdouglas1999.tally.media.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewModel
import com.github.damontecres.wholphin.ui.components.ViewOptionsSquare
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.PlaylistSortOptions
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.LandscapeWidth
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.library.FilterDialog
import io.github.scdouglas1999.tally.media.library.HeaderCount
import io.github.scdouglas1999.tally.media.library.LibraryControlButton
import io.github.scdouglas1999.tally.media.library.SortDialog
import io.github.scdouglas1999.tally.media.library.directionArrow
import io.github.scdouglas1999.tally.media.library.sortLabel
import io.github.scdouglas1999.tally.media.pages.joinMeta
import io.github.scdouglas1999.tally.media.playlist.phone.PhonePlaylistGrid
import io.github.scdouglas1999.tally.media.playlist.phone.PhonePlaylistsHeader
import io.github.scdouglas1999.tally.media.search.PagesLoading
import io.github.scdouglas1999.tally.media.search.providerContextMenu
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import java.util.UUID

/**
 * The Tally playlists library: upstream's `CollectionFolderPlaylist` view model (same key and
 * arguments), its sort, filter, random and item menus, drawn as a grid of 16:9 playlist cards. Sort and filter
 * are the library page's controls and panels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TallyPlaylistsPage(
    preferences: UserPreferences,
    itemId: UUID,
    modifier: Modifier = Modifier,
    viewModel: CollectionFolderViewModel =
        hiltViewModel<CollectionFolderViewModel, CollectionFolderViewModel.Factory>(
            key = itemId.toServerString(),
        ) {
            it.create(
                itemId = itemId.toServerString(),
                initialSortAndDirection = null,
                recursive = true,
                collectionFilter = CollectionFolderFilter(),
                useSeriesForPrimary = true,
                defaultViewOptions = ViewOptionsSquare,
            )
        },
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    var menuIndex by remember { mutableIntStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val filterButton = remember { FocusRequester() }
    LifecycleResumeEffect(itemId) {
        viewModel.onResumePage()
        onPauseOrDispose { viewModel.release() }
    }
    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
                    when (val header = state.item) {
                        is DataLoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.tally_media_error_title),
                                subtitle = header.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                                modifier = Modifier.fillMaxSize().padding(TallyDimens.marginHorizontal),
                            )
                        }

                        DataLoadingState.Loading,
                        DataLoadingState.Pending,
                        -> {
                            PagesLoading("tally-playlists-loading", Modifier.fillMaxSize())
                        }

                        is DataLoadingState.Success -> {
                            val folder = header.data
                            val items = (state.items as? DataLoadingState.Success)?.data
                            val headerFocus = remember { FocusRequester() }
                            PlaylistsHeader(
                                title = folder?.name ?: stringResource(R.string.tally_pages_type_playlists),
                                count = items?.size,
                                onRandom = { viewModel.onClickRandom() },
                                randomEnabled = items?.isNotEmpty() == true,
                                sortControl = {
                                    LibraryControlButton(
                                        label = sortLabel(state.sortAndDirection),
                                        suffix = directionArrow(state.sortAndDirection.direction),
                                        onClick = { sortOpen = true },
                                        // As upstream's sort button: a long press reverses the order.
                                        onLongClick = {
                                            viewModel.onSortChange(state.sortAndDirection.flip(), true, state.filter)
                                        },
                                    )
                                },
                                filterControl = {
                                    val filters = state.filter.countFilters(DefaultFilterOptions)
                                    LibraryControlButton(
                                        label =
                                            if (filters > 0) {
                                                stringResource(R.string.tally_library_filter_count, filters)
                                            } else {
                                                stringResource(R.string.tally_library_filter)
                                            },
                                        onClick = { filterOpen = true },
                                        modifier = Modifier.focusRequester(filterButton),
                                    )
                                },
                                modifier = Modifier.focusRequester(headerFocus),
                            )
                            when (val list = state.items) {
                                is DataLoadingState.Error -> {
                                    LaunchedEffect(Unit) { headerFocus.tryRequestFocus("tally-playlists-error") }
                                    EmptyState(
                                        title = stringResource(R.string.tally_media_error_title),
                                        subtitle = list.localizedMessage,
                                        takeFocus = false,
                                        modifier = Modifier.fillMaxSize().padding(TallyDimens.marginHorizontal),
                                    )
                                }

                                DataLoadingState.Loading,
                                DataLoadingState.Pending,
                                -> {
                                    PagesLoading("tally-playlists-items", Modifier.fillMaxSize())
                                }

                                is DataLoadingState.Success -> {
                                    if (list.data.isEmpty()) {
                                        EmptyState(
                                            title = stringResource(R.string.tally_pages_playlists_empty),
                                            subtitle = stringResource(R.string.tally_pages_playlists_empty_body),
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = TallyDimens.marginHorizontal)
                                                    .padding(bottom = TallyDimens.marginVertical),
                                        )
                                    } else {
                                        PlaylistGrid(
                                            items = list.data,
                                            initialPosition = viewModel.position,
                                            onFocus = { index, item ->
                                                viewModel.position = index
                                                if (item != null && state.viewOptions.showBackdrop) viewModel.updateBackdrop(item)
                                            },
                                            onClick = { item -> viewModel.navigateTo(item.destination()) },
                                            onLongClick = { index, item ->
                                                menuIndex = index
                                                dialogs.contextMenu =
                                                    providerContextMenu(
                                                        provider = viewModel,
                                                        position = index,
                                                        item = item,
                                                        preferences = preferences,
                                                        dialogs = dialogs,
                                                        onAddToQueue = playlistViewModel::addToQueue,
                                                    )
                                            },
                                            onPlay = { item -> viewModel.navigateTo(Destination.Playback(item)) },
                                            modifier = Modifier.fillMaxSize(),
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
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = viewModel.isAdministrator(),
        onConfirmDelete = { viewModel.deleteItem(menuIndex, it) },
        playlistViewModel = playlistViewModel,
    )
    if (sortOpen) {
        SortDialog(
            sortOptions = PlaylistSortOptions,
            current = state.sortAndDirection,
            onSortChange = { viewModel.onSortChange(it, true, state.filter) },
            onDismiss = { sortOpen = false },
        )
    }
    if (filterOpen) {
        FilterDialog(
            filterOptions = DefaultFilterOptions,
            current = state.filter,
            onFilterChange = { viewModel.onFilterChange(it, true) },
            getPossibleValues = { viewModel.getFilterOptionValues(it) },
            onDismiss = {
                filterOpen = false
                filterButton.tryRequestFocus("tally-playlists-filter")
            },
        )
    }
}

/** Kicker (the library's name) with a muted count, then SORT and FILTER, and RANDOM at the right. */
@Composable
private fun PlaylistsHeader(
    title: String,
    count: Int?,
    onRandom: () -> Unit,
    randomEnabled: Boolean,
    sortControl: @Composable () -> Unit,
    filterControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhonePlaylistsHeader(
            title = title,
            countText = count?.let { pluralStringResource(R.plurals.tally_qa_count_playlists, it, it) },
            onRandom = onRandom,
            randomEnabled = randomEnabled,
            sortControl = sortControl,
            filterControl = filterControl,
            modifier = modifier,
        )
        return
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = TallyDimens.marginVertical),
    ) {
        // `PLAYLISTS · 3 PLAYLISTS`, as the library pages put their count on the kicker line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
            )
            if (count != null) HeaderCount(pluralStringResource(R.plurals.tally_qa_count_playlists, count, count))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .focusGroup(),
        ) {
            sortControl()
            filterControl()
            Spacer(Modifier.weight(1f))
            TallyButton(
                label = stringResource(R.string.tally_pages_random),
                glyph = stringResource(R.string.fa_dice),
                enabled = randomEnabled,
                onClick = onRandom,
            )
        }
    }
}

/** 16:9 playlist cards; label bar kicker `3 ITEMS · 4m 30s` over the name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistGrid(
    items: List<BaseItem?>,
    initialPosition: Int,
    onFocus: (Int, BaseItem?) -> Unit,
    onClick: (BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    onPlay: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhonePlaylistGrid(items = items, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        return
    }
    var focused by rememberSaveable { mutableIntStateOf(initialPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    val restore = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { gridFocus.tryRequestFocus("tally-playlists-grid") }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(LandscapeWidth + FocusEdge * 2),
            horizontalArrangement = Arrangement.spacedBy(GridGap - FocusEdge * 2),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding =
                PaddingValues(
                    start = TallyDimens.marginHorizontal - FocusEdge,
                    end = TallyDimens.marginHorizontal - FocusEdge,
                    top = 20.dp,
                    bottom = TallyDimens.marginVertical,
                ),
            modifier =
                modifier
                    .focusRequester(gridFocus)
                    .focusGroup()
                    .focusRestorer(restore),
        ) {
            itemsIndexed(items, key = { index, item -> "$index-${item?.id}" }) { index, item ->
                val count = item?.data?.childCount
                val runtime = item?.data?.runTimeTicks?.takeIf { it > 0L }
                val kicker =
                    joinMeta(
                        count?.let { pluralStringResource(R.plurals.tally_pages_count_items, it, it) },
                        runtime?.let(::formatRuntime),
                    )
                Box(modifier = Modifier.padding(horizontal = FocusEdge)) {
                    LandscapeCard(
                        title = item?.name ?: "",
                        kicker = kicker.ifBlank { null },
                        imageUrl = item?.let { rememberWideImageUrl(it) },
                        onClick = { item?.let(onClick) },
                        onLongClick = { item?.let { onLongClick(index, it) } },
                        onPlay = { item?.let(onPlay) },
                        favorite = item?.favorite == true,
                        onFocused = {
                            focused = index
                            onFocus(index, item)
                        },
                        modifier = if (index == focused) Modifier.focusRequester(restore) else Modifier,
                    )
                }
            }
        }
    }
}

private val GridGap = 16.dp
