package io.github.scdouglas1999.tally.media.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultPlaylistItemsOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.MusicContextActions
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.BoxSetSortOptions
import com.github.damontecres.wholphin.ui.detail.ConfirmMediaTypeDialog
import com.github.damontecres.wholphin.ui.detail.PlaylistViewModel
import com.github.damontecres.wholphin.ui.equalsNotNull
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.TallyIconButton
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.kit.revealWhenResized
import io.github.scdouglas1999.tally.media.library.FilterDialog
import io.github.scdouglas1999.tally.media.library.LibraryControlButton
import io.github.scdouglas1999.tally.media.library.SortDialog
import io.github.scdouglas1999.tally.media.library.directionArrow
import io.github.scdouglas1999.tally.media.library.sortLabel
import io.github.scdouglas1999.tally.media.pages.joinMeta
import io.github.scdouglas1999.tally.media.pages.rundownMeta
import io.github.scdouglas1999.tally.media.pages.rundownNumber
import io.github.scdouglas1999.tally.media.pages.totalRuntimeTicks
import io.github.scdouglas1999.tally.media.search.IconSlot
import io.github.scdouglas1999.tally.media.search.PagesLoading
import io.github.scdouglas1999.tally.media.search.rememberPageScrollSpec
import io.github.scdouglas1999.tally.media.series.WatchedTick
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType

/** Up to this many items the header sums their running times; a longer playlist shows the server's total. */
private const val META_SAMPLE = 200

/**
 * The Tally playlist page: upstream's [PlaylistViewModel] (play from an index as video or music,
 * shuffle, sort, filter, move up/down, remove, item menus) drawn as a header and a numbered rundown. Sort and
 * filter are the library page's controls and panels, with the same view-model calls as upstream's buttons.
 */
@Composable
fun TallyPlaylistPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel =
        hiltViewModel<PlaylistViewModel, PlaylistViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    addToPlaylistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val musicState by viewModel.musicState.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    var confirmType by remember { mutableStateOf<Triple<Int, BaseItem, Boolean>?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val filterButton = remember { FocusRequester() }

    fun play(
        index: Int,
        item: BaseItem,
        shuffle: Boolean,
        mediaTypeOverride: MediaType? = null,
    ) {
        when (mediaTypeOverride ?: state.mediaType) {
            MediaType.VIDEO -> {
                viewModel.navigationManager.navigateTo(
                    Destination.PlaybackList(
                        itemId = destination.itemId,
                        startIndex = index,
                        shuffle = shuffle,
                        filter = state.filterAndSort.filter,
                        sortAndDirection = state.filterAndSort.sortAndDirection,
                    ),
                )
            }

            MediaType.AUDIO -> {
                viewModel.play(item, index, shuffle)
            }

            else -> {
                confirmType = Triple(index, item, shuffle)
            }
        }
    }

    val musicActions =
        remember(dialogs) {
            MusicContextActions(
                navigateTo = { viewModel.navigationManager.navigateTo(it) },
                onClickPlay = { index, item -> play(index, item, false, MediaType.AUDIO) },
                onClickPlayNext = { _, item -> viewModel.playNext(item) },
                onClickAddToQueue = { item -> viewModel.addToQueue(item, Int.MAX_VALUE) },
                onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
                onClickAddPlaylist = { dialogs.playlistItemId = it },
                onClickRemoveFromQueue = { _, _ -> },
                onDeleteItem = viewModel::deleteItem,
                onRemoveFromPlaylist = viewModel::removeFromPlaylist,
            )
        }
    val contextActions =
        remember(dialogs) {
            ContextMenuActions(
                navigateTo = { viewModel.navigationManager.navigateTo(it) },
                onClickWatch = { id, watched -> viewModel.setWatched(id, watched) },
                onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
                onClickAddPlaylist = { dialogs.playlistItemId = it },
                onSendMediaInfo = viewModel::sendMediaReport,
                onDeleteItem = viewModel::deleteItem,
                onClickAddToQueue = { viewModel.addToQueue(it, 0) },
                onShowOverview = {},
                onChooseVersion = { _, _ -> },
                onChooseTracks = {},
                onClearChosenStreams = {},
                onClickRemoveFromNextUp = {},
                onRemoveFromPlaylist = viewModel::removeFromPlaylist,
            )
        }

    fun openMenu(
        index: Int,
        item: BaseItem,
        fromLongClick: Boolean,
    ) {
        dialogs.contextMenu =
            if (item.type == BaseItemKind.AUDIO) {
                ContextMenu.ForMusic(
                    fromLongClick = fromLongClick,
                    item = item,
                    index = index,
                    canDelete = viewModel.canDelete(item, preferences.appPreferences),
                    canRemoveFromQueue = false,
                    actions = musicActions,
                    showRemoveFromPlaylist = state.canEdit,
                )
            } else {
                ContextMenu.ForBaseItem(
                    fromLongClick = fromLongClick,
                    item = item,
                    index = index,
                    chosenStreams = null,
                    showGoTo = true,
                    showStreamChoices = false,
                    canDelete = viewModel.canDelete(item, preferences.appPreferences),
                    canRemoveContinueWatching = false,
                    canRemoveNextUp = false,
                    actions = contextActions,
                    showRemoveFromPlaylist = state.canEdit,
                )
            }
    }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .drawBehind {
                                // Image scrim over the app backdrop (the focused item's): the rundown stays readable.
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            0f to TallyColors.ground.copy(alpha = 0.72f),
                                            0.35f to TallyColors.ground.copy(alpha = 0.94f),
                                            1f to TallyColors.ground,
                                        ),
                                )
                            },
                ) {
                    val playlist = state.playlist
                    if (playlist == null && state.loading !is LoadingState.Error) {
                        PagesLoading("tally-playlist-loading", Modifier.fillMaxSize())
                    } else {
                        PlaylistLoaded(
                            playlist = playlist,
                            state = state,
                            playingId = musicState.currentItemId,
                            onPlayAll = { shuffle -> playlist?.let { play(0, it, shuffle) } },
                            onMore = {
                                playlist?.let {
                                    dialogs.contextMenu =
                                        ContextMenu.ForBaseItem(
                                            fromLongClick = false,
                                            item = it,
                                            chosenStreams = null,
                                            showGoTo = false,
                                            showStreamChoices = false,
                                            canDelete = viewModel.canDelete(it, preferences.appPreferences),
                                            canRemoveContinueWatching = false,
                                            canRemoveNextUp = false,
                                            actions = contextActions,
                                        )
                                }
                            },
                            onClickItem = { index, item -> play(index, item, false) },
                            onMenu = ::openMenu,
                            onMove = viewModel::onMoveItem,
                            onFocusItem = viewModel::updateBackdrop,
                            sortControl = { onFocused ->
                                val sort = state.filterAndSort.sortAndDirection
                                LibraryControlButton(
                                    label = sortLabel(sort),
                                    suffix = directionArrow(sort.direction),
                                    onClick = { sortOpen = true },
                                    // As upstream's sort button: a long press reverses the order.
                                    onLongClick = { viewModel.loadItems(state.filterAndSort.filter, sort.flip()) },
                                    modifier =
                                        Modifier
                                            .revealWhenResized()
                                            .onFocusChanged { if (it.isFocused) onFocused() },
                                )
                            },
                            filterControl = { onFocused ->
                                val count = state.filterAndSort.filter.countFilters(DefaultPlaylistItemsOptions)
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
                            },
                        )
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
        onConfirmDelete = viewModel::deleteItem,
        playlistViewModel = addToPlaylistViewModel,
    )
    if (sortOpen) {
        SortDialog(
            sortOptions = BoxSetSortOptions,
            current = state.filterAndSort.sortAndDirection,
            onSortChange = { viewModel.loadItems(state.filterAndSort.filter, it) },
            onDismiss = { sortOpen = false },
        )
    }
    if (filterOpen) {
        FilterDialog(
            filterOptions = DefaultPlaylistItemsOptions,
            current = state.filterAndSort.filter,
            onFilterChange = { viewModel.loadItems(it, state.filterAndSort.sortAndDirection) },
            getPossibleValues = viewModel::getFilterOptionValues,
            onDismiss = {
                filterOpen = false
                filterButton.tryRequestFocus("tally-playlist-filter")
            },
        )
    }
    confirmType?.let { (index, item, shuffle) ->
        ConfirmMediaTypeDialog(
            onConfirm = { mediaType ->
                confirmType = null
                play(index, item, shuffle, mediaType)
            },
            onCancel = { confirmType = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistLoaded(
    playlist: BaseItem?,
    state: com.github.damontecres.wholphin.ui.detail.PlaylistDetailsState,
    playingId: java.util.UUID?,
    onPlayAll: (Boolean) -> Unit,
    onMore: () -> Unit,
    onClickItem: (Int, BaseItem) -> Unit,
    onMenu: (Int, BaseItem, Boolean) -> Unit,
    onMove: (Int, java.util.UUID, MoveDirection) -> Unit,
    onFocusItem: (BaseItem) -> Unit,
    sortControl: @Composable (onFocused: () -> Unit) -> Unit,
    filterControl: @Composable (onFocused: () -> Unit) -> Unit,
) {
    val items = state.items
    val focusManager = LocalFocusManager.current
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val filterCount = remember(state.filterAndSort) { state.filterAndSort.filter.countFilters(DefaultPlaylistItemsOptions) }
    val canMove = state.canEdit && state.filterAndSort.sortAndDirection.sort == ItemSortBy.DEFAULT && filterCount == 0
    // As upstream: once the items load, focus the list (the saved row), or PLAY when there is nothing in it.
    LaunchedEffect(state.loading) {
        if (state.loading is LoadingState.Success || state.loading is LoadingState.Error) {
            if (items.isEmpty() || !rowFocus.tryRequestFocus("tally-playlist-row")) {
                playFocus.tryRequestFocus("tally-playlist-play")
            }
        }
    }
    val onHeaderFocused: () -> Unit = { scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPageScrollSpec()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                PlaylistHeader(
                    playlist = playlist,
                    items = items,
                    playFocus = playFocus,
                    actionFocus = actionFocus,
                    down = if (items.isEmpty()) null else rowFocus,
                    onFocused = onHeaderFocused,
                    onPlayAll = onPlayAll,
                    onMore = onMore,
                    sortControl = sortControl,
                    filterControl = filterControl,
                    modifier = Modifier.bringIntoViewRequester(bringHeader),
                )
            }
            when (state.loading) {
                is LoadingState.Error -> {
                    item(key = "error") {
                        EmptyState(
                            title = stringResource(R.string.tally_media_error_title),
                            subtitle =
                                (state.loading as LoadingState.Error).localizedMessage.ifBlank {
                                    stringResource(R.string.tally_media_error_body)
                                },
                            takeFocus = false,
                            modifier =
                                Modifier
                                    .padding(horizontal = TallyDimens.marginHorizontal)
                                    .fillMaxWidth()
                                    .height(140.dp),
                        )
                    }
                }

                LoadingState.Loading,
                LoadingState.Pending,
                -> {
                    item(key = "loading") {
                        Text(
                            text = stringResource(R.string.tally_media_loading).tallyUppercase(),
                            style = TallyType.label,
                            color = TallyColors.muted,
                            modifier = Modifier.padding(horizontal = TallyDimens.marginHorizontal),
                        )
                    }
                }

                LoadingState.Success -> {
                    if (items.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                title = stringResource(R.string.tally_pages_playlist_empty),
                                subtitle = stringResource(R.string.tally_pages_playlist_empty_body),
                                takeFocus = false,
                                modifier =
                                    Modifier
                                        .padding(horizontal = TallyDimens.marginHorizontal)
                                        .fillMaxWidth()
                                        .height(140.dp),
                            )
                        }
                    } else {
                        // Keyed by position, as upstream: after a move the focus stays on the row at that
                        // position, which now holds the moved item (a playlist may also hold an item twice).
                        itemsIndexed(items) { index, item ->
                            PlaylistRow(
                                index = index,
                                item = item,
                                playing = equalsNotNull(playingId, item?.id),
                                canMove = canMove,
                                moveUpAllowed = index > 0,
                                moveDownAllowed = index < items.lastIndex,
                                onClick = {
                                    savedIndex = index
                                    item?.let { onClickItem(index, it) }
                                },
                                onLongClick = {
                                    savedIndex = index
                                    item?.let { onMenu(index, it, true) }
                                },
                                onMore = {
                                    savedIndex = index
                                    item?.let { onMenu(index, it, false) }
                                },
                                onMove = { direction ->
                                    item?.let { onMove(index, it.id, direction) }
                                    focusManager.moveFocus(
                                        if (direction == MoveDirection.UP) FocusDirection.Up else FocusDirection.Down,
                                    )
                                },
                                onFocused = {
                                    savedIndex = index
                                    item?.let(onFocusItem)
                                },
                                modifier =
                                    Modifier
                                        .padding(horizontal = TallyDimens.marginHorizontal - RowInset)
                                        .then(if (index == savedIndex) Modifier.focusRequester(rowFocus) else Modifier)
                                        .focusProperties { if (index == 0) up = actionFocus },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kicker `PLAYLIST`, the name, `3 ITEMS · 4m 30s`, then PLAY, SHUFFLE, MORE, SORT and FILTER. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistHeader(
    playlist: BaseItem?,
    items: List<BaseItem?>,
    playFocus: FocusRequester,
    actionFocus: FocusRequester,
    down: FocusRequester?,
    onFocused: () -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onMore: () -> Unit,
    sortControl: @Composable (onFocused: () -> Unit) -> Unit,
    filterControl: @Composable (onFocused: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = items.size
    // The loaded items' sum follows moves and removals; a long playlist falls back to the server's total.
    val runtime =
        if (items.isNotEmpty() && items.size <= META_SAMPLE) {
            totalRuntimeTicks(items.map { it?.data?.runTimeTicks }).takeIf { it > 0L }
        } else {
            playlist?.data?.runTimeTicks?.takeIf { it > 0L }
        }
    val meta =
        joinMeta(
            pluralStringResource(R.plurals.tally_pages_count_items, count, count),
            runtime?.let(::formatRuntime),
        )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = TallyDimens.marginVertical, bottom = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_pages_playlist).tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
        Text(
            text = playlist?.name ?: "",
            style = TitleStyle,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        Text(
            text = meta.tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.textSecondary,
            maxLines = 1,
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(start = FocusEdge, end = 24.dp, top = FocusEdge, bottom = FocusEdge),
                modifier =
                    Modifier
                        .padding(top = 16.dp)
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
                item(key = "more") {
                    TallyButton(
                        label = stringResource(R.string.tally_media_more),
                        glyph = stringResource(R.string.fa_ellipsis),
                        onClick = onMore,
                        onFocused = onFocused,
                    )
                }
                item(key = "sort") { sortControl(onFocused) }
                item(key = "filter") { filterControl(onFocused) }
            }
        }
    }
}

/**
 * One line of the rundown: number, 16:9 still (progress bar and watched tick), title and mono meta; at the
 * right upstream's move up / move down (when the playlist can be reordered) and more buttons. While a move
 * button has focus the row is in "move" mode: an accent bar at its left edge.
 */
@Composable
private fun PlaylistRow(
    index: Int,
    item: BaseItem?,
    playing: Boolean,
    canMove: Boolean,
    moveUpAllowed: Boolean,
    moveDownAllowed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    onMove: (MoveDirection) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (focused) onFocused() }
    val imageService = LocalImageUrlService.current
    val imageUrl =
        remember(item) {
            val type = if (item != null && ImageType.THUMB in item.data.imageTags.orEmpty()) ImageType.THUMB else ImageType.PRIMARY
            imageService.getItemImageUrl(item, type)
        }
    val meta = item?.let { rowMeta(it) }.orEmpty()
    val percent =
        resumePercent(item?.data?.userData?.playbackPositionTicks ?: 0L, item?.data?.runTimeTicks ?: 0L)
    val progress = if (item?.played != true && percent in 1..99) percent / 100f else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(RowHeight)
                .drawBehind {
                    if (moving) {
                        drawRect(color = TallyColors.accent, size = Size(MoveBarWidth.toPx(), size.height))
                    }
                },
    ) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = TallyColors.text,
                    focusedContainerColor = TallyColors.groundRaised,
                    focusedContentColor = TallyColors.text,
                    pressedContainerColor = TallyColors.groundRaised,
                    pressedContentColor = TallyColors.text,
                ),
            border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
            glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onPreviewKeyEvent { event ->
                        when {
                            isPlayKeyUp(event) -> {
                                onClick()
                                true
                            }

                            event.key == Key.Menu -> {
                                if (event.type == KeyEventType.KeyUp) onLongClick()
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    }.drawWithContent {
                        drawContent()
                        if (focused) {
                            // Inside the row's bounds, so no list clip can cut it.
                            val stroke = kotlin.math.floor(TallyDimens.focusBorder.toPx()).coerceAtLeast(1f)
                            drawRect(
                                color = TallyColors.accent,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke),
                            )
                        }
                    },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = RowInset + 3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.width(NumberWidth),
                ) {
                    Text(
                        text = rundownNumber(index),
                        style = NumberStyle,
                        color = if (playing) TallyColors.accent else TallyColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (playing) IndicatorSquare(color = TallyColors.accent, size = 8.dp)
                }
                Still(imageUrl = imageUrl, title = item?.name ?: "", progress = progress, played = item?.played == true)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    modifier =
                        Modifier
                            .padding(start = 20.dp, end = 12.dp)
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    Text(
                        text = item?.name ?: "",
                        style = RowTitleStyle,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta.tallyUppercase(),
                            style = MetaStyle,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(start = 12.dp, end = RowInset, top = 20.dp).fillMaxHeight(),
        ) {
            if (canMove) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.onFocusChanged { moving = it.hasFocus },
                ) {
                    MoveButton(
                        allowed = moveUpAllowed,
                        glyph = stringResource(R.string.tally_pages_fa_arrow_up),
                        label = stringResource(R.string.tally_pages_move_up),
                        onClick = { onMove(MoveDirection.UP) },
                    )
                    MoveButton(
                        allowed = moveDownAllowed,
                        glyph = stringResource(R.string.tally_pages_fa_arrow_down),
                        label = stringResource(R.string.tally_pages_move_down),
                        onClick = { onMove(MoveDirection.DOWN) },
                    )
                }
            }
            IconSlot {
                TallyIconButton(
                    glyph = stringResource(R.string.fa_ellipsis_vertical),
                    label = stringResource(R.string.tally_media_more),
                    onClick = onMore,
                    modifier = it,
                )
            }
        }
    }
}

/** Upstream disables the first row's "up" and the last row's "down": shown disabled, and focus skips it. */
@Composable
private fun MoveButton(
    allowed: Boolean,
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    IconSlot { TallyIconButton(glyph = glyph, label = label, onClick = onClick, enabled = allowed, modifier = it) }
}

@Composable
private fun Still(
    imageUrl: String?,
    title: String,
    progress: Float?,
    played: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(StillWidth, StillHeight)
                .background(TallyColors.screen)
                .border(TallyDimens.hairline, TallyColors.rule),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onError = { logCoilError(imageUrl, it.result) },
                modifier = Modifier.fillMaxSize().padding(TallyDimens.hairline),
            )
        }
        if (progress != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(TallyColors.ruleStrong),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(TallyColors.accent),
                )
            }
        }
        if (played) WatchedTick(modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** `FILM · 2010 · 2h 28m` / `BREAKING BAD · S1 E3 · 47m` / `SONG · 2019 · 3m 12s`. */
@Composable
private fun rowMeta(item: BaseItem): String {
    val typeLabel =
        when (item.type) {
            BaseItemKind.MOVIE -> stringResource(R.string.tally_media_film)
            BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> stringResource(R.string.tally_media_video)
            BaseItemKind.AUDIO -> stringResource(R.string.tally_pages_kind_song)
            BaseItemKind.SERIES -> stringResource(R.string.tally_pages_kind_series)
            BaseItemKind.EPISODE -> stringResource(R.string.tally_pages_kind_episode)
            else -> null
        }
    return rundownMeta(
        type = item.type,
        typeLabel = typeLabel,
        year = item.data.productionYear,
        seriesName = item.data.seriesName,
        episodeCode =
            episodeCode(
                item.data.parentIndexNumber,
                item.indexNumber,
                item.data.indexNumberEnd,
                stringResource(R.string.tally_series_special),
            ),
        runtimeTicks = item.data.runTimeTicks,
    )
}

/** The rows reach [RowInset] into the page margin so their focus frame lines up with the header text. */
private val RowInset = 8.dp
private val RowHeight = 104.dp
private val MoveBarWidth = 4.dp
private val NumberWidth = 56.dp
private val StillWidth = 160.dp
private val StillHeight = 90.dp

private val TitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    )

private val NumberStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
    )

private val RowTitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )

private val MetaStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    )
