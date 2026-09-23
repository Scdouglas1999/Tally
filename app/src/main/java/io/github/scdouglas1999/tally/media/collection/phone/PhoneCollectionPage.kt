package io.github.scdouglas1999.tally.media.collection.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewModel
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptionsDialog
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.collection.collectionMeta
import io.github.scdouglas1999.tally.media.home.phone.PhoneHeroFrame
import io.github.scdouglas1999.tally.media.home.phone.PhoneHeroKicker
import io.github.scdouglas1999.tally.media.home.phone.PhoneHeroTitle
import io.github.scdouglas1999.tally.media.kit.DetailMetaPart
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneBackOverImage
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneChip
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneItemCard
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowHeader
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowMessage
import io.github.scdouglas1999.tally.media.kit.phone.PhoneToolButton
import io.github.scdouglas1999.tally.media.kit.phone.phoneGridColumns
import io.github.scdouglas1999.tally.media.library.FilterDialog
import io.github.scdouglas1999.tally.media.library.LibraryPageViewModel
import io.github.scdouglas1999.tally.media.library.SortDialog
import io.github.scdouglas1999.tally.media.library.directionArrow
import io.github.scdouglas1999.tally.media.library.sortLabel
import io.github.scdouglas1999.tally.media.movie.phone.PhoneMetaLine
import io.github.scdouglas1999.tally.media.search.typeTitle
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Items the header's count, years and running time are read from (the first page of each row), as on the TV. */
private const val META_SAMPLE = 100

/**
 * A collection (box set) on a phone: the same [CollectionViewModel] as the TV page (sort, filter, view options, play
 * all / shuffle, watched, favorite, delete, the collection's and its items' menus). The film page's header (backdrop
 * 16:9 under the status bar with a back button over it, kicker, logo or title, meta, genres, overview), PLAY, the
 * TV's other actions as icon-over-label buttons, the sort and filter chips, then the items in a three-column grid
 * (one grid per type, headed, while the view options separate the types).
 */
@Composable
fun PhoneCollectionPage(
    preferences: UserPreferences,
    itemId: UUID,
    modifier: Modifier,
    viewModel: CollectionViewModel,
    playlistViewModel: AddPlaylistViewModel,
) {
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    var showViewOptions by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)
    val pageViewModel: LibraryPageViewModel = hiltViewModel()

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

    val onPlayAll = { shuffle: Boolean ->
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
    }
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()

    Box(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        val collection = state.collection
        when (val loading = state.loadingState) {
            is LoadingState.Error -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_media_error_title),
                    subtitle = loading.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                    modifier = Modifier.padding(top = 96.dp),
                )
            }

            LoadingState.Loading, LoadingState.Pending -> {
                PhoneLoading(Modifier.fillMaxSize())
            }

            LoadingState.Success -> {
                if (collection != null) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val columns = phoneGridColumns(2f / 3f, maxWidth)
                        val cellWidth =
                            (maxWidth - PhoneDimens.margin * 2 - PhoneDimens.cardGap * (columns - 1)) / columns
                        val rows = state.separateItems.entries.toList()
                        val sample =
                            if (state.viewOptions.separateTypes) {
                                rows.flatMap { (_, row) -> (row as? HomeRowLoadingState.Success)?.items?.take(META_SAMPLE).orEmpty() }
                            } else {
                                state.items.take(META_SAMPLE)
                            }.filterNotNull()
                        val empty =
                            if (state.viewOptions.separateTypes) {
                                rows.isNotEmpty() &&
                                    rows.all { (_, row) -> row is HomeRowLoadingState.Success && row.items.isEmpty() }
                            } else {
                                state.items.isEmpty()
                            }
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = bottom + PhoneDimens.rowGap),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item(key = "header") {
                                CollectionHeader(
                                    collection = collection,
                                    firstItem = sample.firstOrNull { !it.data.backdropImageTags.isNullOrEmpty() },
                                    logoUrl =
                                        if (preferences.appPreferences.interfacePreferences.showLogos) state.logoImageUrl else null,
                                    meta = collectionMeta(collection, sample),
                                    canDelete = viewModel.canDelete(collection, preferences.appPreferences),
                                    onPlayAll = onPlayAll,
                                    onWatched = { viewModel.setWatched(collection.id, !collection.played, null) },
                                    onFavorite = { viewModel.setFavorite(collection.id, !collection.favorite, null) },
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
                                )
                            }
                            item(key = "chips") {
                                val filters = state.itemFilter.countFilters(DefaultFilterOptions)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(horizontal = PhoneDimens.margin)
                                            .padding(top = 8.dp, bottom = 8.dp),
                                ) {
                                    PhoneChip(
                                        label = sortLabel(state.sortAndDirection) + " " + directionArrow(state.sortAndDirection.direction),
                                        onClick = { sortOpen = true },
                                        onLongClick = { viewModel.changeSort(state.sortAndDirection.flip()) },
                                    )
                                    PhoneChip(
                                        label =
                                            if (filters > 0) {
                                                stringResource(R.string.tally_library_filter_count, filters)
                                            } else {
                                                stringResource(R.string.tally_library_filter)
                                            },
                                        active = filters > 0,
                                        onClick = { filterOpen = true },
                                    )
                                }
                            }
                            val onClickItem = { item: BaseItem -> viewModel.navigateTo(item.destination()) }
                            when {
                                empty -> {
                                    item(key = "empty") {
                                        PhoneEmptyState(
                                            title = stringResource(R.string.tally_pages_collection_empty),
                                            subtitle = stringResource(R.string.tally_pages_collection_empty_body),
                                        )
                                    }
                                }

                                state.viewOptions.separateTypes -> {
                                    rows.forEachIndexed { rowIndex, (type, row) ->
                                        val title = typeTitle(type)
                                        when (row) {
                                            is HomeRowLoadingState.Success -> {
                                                if (row.items.isNotEmpty()) {
                                                    item(key = "title-${type.serialName}") {
                                                        PhoneRowHeader(
                                                            title = stringResource(title),
                                                            count = row.items.size,
                                                            modifier =
                                                                Modifier
                                                                    .padding(horizontal = PhoneDimens.margin)
                                                                    .padding(top = 16.dp, bottom = 8.dp),
                                                        )
                                                    }
                                                    gridRows(
                                                        keyPrefix = type.serialName,
                                                        items = row.items,
                                                        columns = columns,
                                                        cellWidth = cellWidth,
                                                        onClick = onClickItem,
                                                        onLongClick = { index, item -> openItemMenu(RowColumn(rowIndex, index), item) },
                                                    )
                                                }
                                            }

                                            is HomeRowLoadingState.Error -> {
                                                item(key = "error-${type.serialName}") {
                                                    PhoneRowMessage(
                                                        title = stringResource(title),
                                                        message = row.localizedMessage,
                                                        failure = true,
                                                        modifier = Modifier.padding(top = 16.dp),
                                                    )
                                                }
                                            }

                                            else -> {
                                                item(key = "loading-${type.serialName}") {
                                                    PhoneRowMessage(
                                                        title = stringResource(title),
                                                        message = stringResource(R.string.tally_media_loading),
                                                        modifier = Modifier.padding(top = 16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    gridRows(
                                        keyPrefix = "all",
                                        items = state.items,
                                        columns = columns,
                                        cellWidth = cellWidth,
                                        onClick = onClickItem,
                                        onLongClick = { index, item -> openItemMenu(RowColumn(0, index), item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        PhoneBackOverImage(onBack = { pageViewModel.navigationManager.goBack() })
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
            onDismiss = { filterOpen = false },
        )
    }
}

/** [items] as rows of [columns] phone cards, [cellWidth] wide, inside the page margins. */
private fun LazyListScope.gridRows(
    keyPrefix: String,
    items: List<BaseItem?>,
    columns: Int,
    cellWidth: Dp,
    onClick: (BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
) {
    val rowCount = (items.size + columns - 1) / columns
    items(count = rowCount, key = { "$keyPrefix-row-$it" }) { r ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(bottom = PhoneDimens.cardGap),
        ) {
            (r * columns until minOf(items.size, (r + 1) * columns)).forEach { index ->
                val item = items.getOrNull(index)
                PhoneItemCard(
                    item = item,
                    width = cellWidth,
                    onClick = { item?.let(onClick) },
                    onLongClick = { item?.let { onLongClick(index, it) } },
                )
            }
        }
    }
}

@Composable
private fun CollectionHeader(
    collection: BaseItem,
    firstItem: BaseItem?,
    logoUrl: String?,
    meta: List<DetailMetaPart>,
    canDelete: Boolean,
    onPlayAll: (Boolean) -> Unit,
    onWatched: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onViewOptions: () -> Unit,
    onMore: () -> Unit,
) {
    val imageService = LocalImageUrlService.current
    val backdrop =
        remember(collection.id, firstItem?.id) {
            // A collection without art of its own shows its first film's, as the TV page's backdrop follows its films.
            imageService.getItemImageUrl(collection, ImageType.BACKDROP)
                ?: firstItem?.let { imageService.getItemImageUrl(it, ImageType.BACKDROP) }
                ?: imageService.getItemImageUrl(collection, ImageType.PRIMARY)
        }
    val title = collection.name ?: ""
    PhoneHeroFrame(url = backdrop, description = title, modifier = Modifier.padding(bottom = 8.dp)) {
        PhoneHeroKicker(stringResource(R.string.tally_pages_collection))
        var logoFailed by remember(logoUrl) { mutableStateOf(false) }
        if (logoUrl != null && !logoFailed) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = {
                    logCoilError(logoUrl, it.result)
                    logoFailed = true
                },
                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 72.dp),
            )
        } else {
            PhoneHeroTitle(title)
        }
        // The detail pages' meta line (film, show): mono uppercase, units in their own case.
        PhoneMetaLine(meta)
        val genres = collection.data.genres.orEmpty()
        if (genres.isNotEmpty()) {
            Text(
                text = genres.joinToString(" / "),
                style = PhoneType.bodySmall,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val overview = collection.data.overview
        if (!overview.isNullOrBlank()) {
            var expanded by rememberSaveable(overview) { mutableStateOf(false) }
            Text(
                text = overview,
                style = PhoneType.body,
                color = TallyColors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().phoneClickable { expanded = !expanded },
            )
        }
        Spacer(Modifier.height(4.dp))
        PhoneButton(
            label = stringResource(R.string.tally_media_play),
            glyph = stringResource(R.string.fa_play),
            primary = true,
            onClick = { onPlayAll(false) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            PhoneToolButton(
                glyph = stringResource(R.string.fa_shuffle),
                label = stringResource(R.string.tally_pages_shuffle),
                onClick = { onPlayAll(true) },
                modifier = Modifier.weight(1f),
            )
            PhoneToolButton(
                glyph = stringResource(if (collection.played) R.string.fa_eye else R.string.fa_eye_slash),
                label =
                    stringResource(if (collection.played) R.string.tally_media_watched else R.string.tally_media_unwatched),
                onClick = onWatched,
                modifier = Modifier.weight(1f),
            )
            PhoneToolButton(
                glyph = stringResource(R.string.fa_heart),
                label =
                    stringResource(if (collection.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite),
                marked = collection.favorite,
                onClick = onFavorite,
                modifier = Modifier.weight(1f),
            )
            if (canDelete) {
                PhoneToolButton(
                    glyph = stringResource(R.string.tally_pages_fa_trash),
                    label = stringResource(R.string.tally_pages_delete),
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
            PhoneToolButton(
                glyph = stringResource(R.string.fa_sliders),
                label = stringResource(R.string.tally_pages_view),
                onClick = onViewOptions,
                modifier = Modifier.weight(1f),
            )
            PhoneToolButton(
                glyph = stringResource(R.string.fa_ellipsis),
                label = stringResource(R.string.tally_media_more),
                onClick = onMore,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
