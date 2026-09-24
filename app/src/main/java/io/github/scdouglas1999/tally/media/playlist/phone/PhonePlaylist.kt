package io.github.scdouglas1999.tally.media.playlist.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.detail.PlaylistDetailsState
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneIconButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.media.kit.phone.PhoneMediaGrid
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.library.LibraryPageViewModel
import io.github.scdouglas1999.tally.media.pages.joinMeta
import io.github.scdouglas1999.tally.media.pages.rundownNumber
import io.github.scdouglas1999.tally.media.pages.totalRuntimeTicks
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Up to this many items the header sums their running times, as on the TV. */
private const val META_SAMPLE = 200

/**
 * A playlist on a phone (`PlaylistLoaded`'s phone branch, with the page's actions): a top bar with back; the header
 * (PLAYLIST, the name, count and total time, PLAY and SHUFFLE side by side, MORE, then the sort and filter chips);
 * then the items as rundown rows (thumbnail, number, title, duration) with the TV's move up / move down as 40dp icon
 * buttons at the row's right while the playlist can be reordered. A tap plays from the row; a long-press opens the
 * item's menu (remove from the playlist is in it).
 */
@Composable
internal fun PhonePlaylistLoaded(
    playlist: BaseItem?,
    state: PlaylistDetailsState,
    playingId: UUID?,
    canMove: Boolean,
    onPlayAll: (Boolean) -> Unit,
    onMore: () -> Unit,
    onClickItem: (Int, BaseItem) -> Unit,
    onMenu: (Int, BaseItem, Boolean) -> Unit,
    onMove: (Int, UUID, MoveDirection) -> Unit,
    sortControl: @Composable (onFocused: () -> Unit) -> Unit,
    filterControl: @Composable (onFocused: () -> Unit) -> Unit,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
) {
    val items = state.items
    val listState = rememberLazyListState()
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    Column(modifier = Modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = if (listState.phoneScrolled) playlist?.name else null,
            onBack = { pageViewModel.navigationManager.goBack() },
            scrolled = listState.phoneScrolled,
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottom + PhoneDimens.rowGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                PlaylistHeader(
                    playlist = playlist,
                    items = items,
                    onPlayAll = onPlayAll,
                    onMore = onMore,
                    sortControl = sortControl,
                    filterControl = filterControl,
                )
            }
            when (val loading = state.loading) {
                is LoadingState.Error -> {
                    item(key = "error") {
                        PhoneEmptyState(
                            title = stringResource(R.string.tally_media_error_title),
                            subtitle = loading.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                        )
                    }
                }

                LoadingState.Loading, LoadingState.Pending -> {
                    item(key = "loading") { PhoneLoading(Modifier.fillMaxWidth().height(120.dp)) }
                }

                LoadingState.Success -> {
                    if (items.isEmpty()) {
                        item(key = "empty") {
                            PhoneEmptyState(
                                title = stringResource(R.string.tally_pages_playlist_empty),
                                subtitle = stringResource(R.string.tally_pages_playlist_empty_body),
                            )
                        }
                    } else {
                        // Keyed by position, as on the TV (a playlist may hold an item twice).
                        itemsIndexed(items) { index, item ->
                            RundownRow(
                                index = index,
                                item = item,
                                playing = playingId != null && playingId == item?.id,
                                canMove = canMove,
                                moveUpAllowed = index > 0,
                                moveDownAllowed = index < items.lastIndex,
                                onClick = { item?.let { onClickItem(index, it) } },
                                onLongClick = { item?.let { onMenu(index, it, true) } },
                                onMove = { direction -> item?.let { onMove(index, it.id, direction) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: BaseItem?,
    items: List<BaseItem?>,
    onPlayAll: (Boolean) -> Unit,
    onMore: () -> Unit,
    sortControl: @Composable (onFocused: () -> Unit) -> Unit,
    filterControl: @Composable (onFocused: () -> Unit) -> Unit,
) {
    val count = items.size
    val runtime =
        if (items.isNotEmpty() && items.size <= META_SAMPLE) {
            totalRuntimeTicks(items.map { it?.data?.runTimeTicks }).takeIf { it > 0L }
        } else {
            playlist?.data?.runTimeTicks?.takeIf { it > 0L }
        }
    val meta = joinMeta(pluralStringResource(R.plurals.tally_pages_count_items, count, count), runtime?.let(::formatRuntime))
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(bottom = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_pages_playlist).tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
        Text(
            text = playlist?.name ?: "",
            style = PhoneType.display,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = meta.tallyUppercase(),
            style = PhoneType.meta,
            color = TallyColors.textSecondary,
            maxLines = 1,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            PhoneButton(
                label = stringResource(R.string.tally_media_play),
                glyph = stringResource(R.string.fa_play),
                primary = true,
                onClick = { onPlayAll(false) },
                modifier = Modifier.weight(1f),
            )
            PhoneButton(
                label = stringResource(R.string.tally_pages_shuffle),
                glyph = stringResource(R.string.fa_shuffle),
                onClick = { onPlayAll(true) },
                modifier = Modifier.weight(1f),
            )
            if (playlist != null) {
                io.github.scdouglas1999.tally.media.music.phone.PhoneDownloadSquare(
                    downloads =
                        io.github.scdouglas1999.tally.downloads.ui
                            .rememberDownloadUi(),
                    subject =
                        io.github.scdouglas1999.tally.downloads.ui.DownloadSubject
                            .Playlist(playlist.id, playlist.name ?: ""),
                )
            }
            // As tall as the buttons beside it (the album page's MORE).
            io.github.scdouglas1999.tally.media.music.phone.PhoneIconSquare(
                glyph = stringResource(R.string.fa_ellipsis),
                label = stringResource(R.string.tally_media_more),
                onClick = onMore,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            sortControl {}
            filterControl {}
        }
    }
}

private val RowHeight = 72.dp
private val ThumbWidth = 96.dp

@Composable
private fun RundownRow(
    index: Int,
    item: BaseItem?,
    playing: Boolean,
    canMove: Boolean,
    moveUpAllowed: Boolean,
    moveDownAllowed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (MoveDirection) -> Unit,
) {
    val imageService = LocalImageUrlService.current
    val imageUrl =
        remember(item) {
            val type = if (item != null && ImageType.THUMB in item.data.imageTags.orEmpty()) ImageType.THUMB else ImageType.PRIMARY
            imageService.getItemImageUrl(item, type)
        }
    val percent = resumePercent(item?.data?.userData?.playbackPositionTicks ?: 0L, item?.data?.runTimeTicks ?: 0L)
    val progress = if (item?.played != true && percent in 1..99) percent / 100f else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(RowHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .phoneClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(start = PhoneDimens.margin, end = 8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ThumbWidth, ThumbWidth * 9 / 16)
                        .background(TallyColors.screen)
                        .border(PhoneDimens.hairline, TallyColors.rule),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = item?.name,
                        contentScale = ContentScale.Crop,
                        onError = { logCoilError(imageUrl, it.result) },
                        modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
                    )
                }
                if (progress != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(TallyColors.ruleStrong),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(TallyColors.accent))
                    }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = rundownNumber(index),
                        style = PhoneType.meta,
                        color = if (playing) TallyColors.accent else TallyColors.muted,
                        maxLines = 1,
                    )
                    if (playing) IndicatorSquare(color = TallyColors.accent, size = 6.dp)
                    val runtime = item?.data?.runTimeTicks?.takeIf { it > 0L }
                    if (runtime != null) {
                        Text(
                            text = "·",
                            style = PhoneType.meta,
                            color = TallyColors.muted,
                            maxLines = 1,
                        )
                        Text(
                            text = formatRuntime(runtime),
                            style = PhoneType.meta,
                            color = TallyColors.muted,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = item?.name ?: "",
                    style = PhoneType.headline,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canMove) {
            PhoneIconButton(
                glyph = stringResource(R.string.tally_pages_fa_arrow_up),
                label = stringResource(R.string.tally_pages_move_up),
                enabled = moveUpAllowed,
                onClick = { onMove(MoveDirection.UP) },
            )
            PhoneIconButton(
                glyph = stringResource(R.string.tally_pages_fa_arrow_down),
                label = stringResource(R.string.tally_pages_move_down),
                enabled = moveDownAllowed,
                onClick = { onMove(MoveDirection.DOWN) },
            )
        }
        Box(Modifier.width(PhoneDimens.margin - 4.dp))
    }
}

/**
 * The playlists library's header on a phone (`PlaylistsHeader`'s phone branch): a top bar with the library's name and
 * the count, then the sort and filter chips and the random button.
 */
@Composable
internal fun PhonePlaylistsHeader(
    title: String,
    countText: String?,
    onRandom: () -> Unit,
    randomEnabled: Boolean,
    sortControl: @Composable () -> Unit,
    filterControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
) {
    Column(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
        PhoneTopBar(
            title = title,
            onBack = { pageViewModel.navigationManager.goBack() },
            actions = {
                if (countText != null) {
                    Text(
                        text = countText.tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.muted,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin),
        ) {
            sortControl()
            filterControl()
            Box(Modifier.weight(1f))
            PhoneIconButton(
                glyph = stringResource(R.string.fa_dice),
                label = stringResource(R.string.tally_pages_random),
                enabled = randomEnabled,
                onClick = onRandom,
            )
        }
    }
}

/** The playlists as a 2-column grid of 16:9 playlist cards (`3 ITEMS · 4m 30s` over the name). */
@Composable
internal fun PhonePlaylistGrid(
    items: List<BaseItem?>,
    onClick: (BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    PhoneMediaGrid(
        items = items,
        columns = 2,
        topPadding = 12.dp,
        bottomPadding = bottom + PhoneDimens.rowGap,
        key = { index, item -> "$index-${item?.id}" },
        modifier = modifier,
    ) { item, index, width ->
        val count = item?.data?.childCount
        val runtime = item?.data?.runTimeTicks?.takeIf { it > 0L }
        val kicker =
            joinMeta(
                count?.let { pluralStringResource(R.plurals.tally_pages_count_items, it, it) },
                runtime?.let(::formatRuntime),
            )
        LandscapeCard(
            title = item?.name ?: "",
            kicker = kicker.ifBlank { null },
            imageUrl = item?.let { rememberWideImageUrl(it) },
            onClick = { item?.let(onClick) },
            onLongClick = { item?.let { onLongClick(index, it) } },
            favorite = item?.favorite == true,
            width = width,
        )
    }
}
