package io.github.scdouglas1999.tally.media.music

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.MusicContextActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.music.ArtistViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.arrivalFocus
import io.github.scdouglas1999.tally.media.library.LoadingMark
import io.github.scdouglas1999.tally.media.series.MinScrollBringIntoViewSpec
import io.github.scdouglas1999.tally.media.series.TopScrim
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import kotlinx.coroutines.launch

private const val POS_ACTIONS = -1
private const val POS_ALBUMS = -2
private const val POS_APPEARS = -3
private const val POS_VIDEOS = -4
private const val POS_SIMILAR = -5

/**
 * An artist in the Tally look (upstream's `ArtistDetailsPage` and its [ArtistViewModel]): a header with the square
 * portrait at the left, kicker `ARTIST`, the name, the mono counts (`12 ALBUMS · 140 SONGS`), the biography
 * (4 lines, OK opens the rest) and the actions PLAY, SHUFFLE, INSTANT MIX, FAVORITE and MORE; then the rows upstream
 * loads: `ALBUMS` and `APPEARS ON` (square cards, the year as the kicker), `TOP SONGS` ([TrackRow]s), music videos and
 * similar artists. Each row shows only when upstream has items for it.
 */
@Composable
fun TallyArtistPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel =
        hiltViewModel<ArtistViewModel, ArtistViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val moreActions =
        remember(dialogs) {
            MusicContextActions(
                navigateTo = { viewModel.navigationManager.navigateTo(it) },
                onClickPlay = { _, item -> viewModel.play(item) },
                onClickPlayNext = { _, item -> viewModel.playNext(item) },
                onClickAddToQueue = { item -> viewModel.addToQueue(item, -1) },
                onClickFavorite = { itemId, favorite -> viewModel.setFavorite(itemId, favorite) },
                onClickAddPlaylist = { itemId -> dialogs.playlistItemId = itemId },
                onClickRemoveFromQueue = { _, _ -> },
                onDeleteItem = viewModel::deleteItem,
            )
        }
    MusicPageFrame(modifier) {
        when (val loading = state.loading) {
            is LoadingState.Error -> {
                MusicError(loading.localizedMessage)
            }

            LoadingState.Loading,
            LoadingState.Pending,
            -> {
                LoadingMark(Modifier.fillMaxSize())
            }

            LoadingState.Success -> {
                ArtistLoaded(
                    preferences = preferences,
                    viewModel = viewModel,
                    dialogs = dialogs,
                    moreActions = moreActions,
                )
            }
        }
    }
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = false,
        onConfirmDelete = viewModel::deleteItem,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistLoaded(
    preferences: UserPreferences,
    viewModel: ArtistViewModel,
    dialogs: ItemDialogsState,
    moreActions: MusicContextActions,
) {
    val state by viewModel.state.collectAsState()
    val currentMusic by viewModel.currentMusic.collectAsState()
    val artist = state.artist ?: return
    val scope = rememberCoroutineScope()
    var position by rememberInt(POS_ACTIONS)
    val primaryFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val albumsFocus = remember { FocusRequester() }
    val appearsFocus = remember { FocusRequester() }
    val songFocus = remember { FocusRequester() }
    val firstSongFocus = remember { FocusRequester() }
    val videosFocus = remember { FocusRequester() }
    val similarFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val listState = rememberLazyListState()

    val arrivalTarget =
        remember {
            when {
                position >= 0 && state.topSongs.isNotEmpty() -> songFocus
                position == POS_ALBUMS && state.albums.isNotEmpty() -> albumsFocus
                position == POS_APPEARS && state.appearsOnAlbums.isNotEmpty() -> appearsFocus
                position == POS_VIDEOS && state.musicVideos.isNotEmpty() -> videosFocus
                position == POS_SIMILAR && state.similar.isNotEmpty() -> similarFocus
                else -> primaryFocus
            }
        }
    val arrival = arrivalFocus(arrivalTarget, "tally-artist")
    LaunchedEffect(Unit) { viewModel.refresh() }

    fun menuFor(
        item: BaseItem,
        index: Int,
        fromLongClick: Boolean,
    ) {
        dialogs.contextMenu =
            ContextMenu.ForMusic(
                fromLongClick = fromLongClick,
                item = item,
                index = index,
                canDelete = viewModel.canDelete(item, preferences.appPreferences),
                canRemoveFromQueue = false,
                actions = moreActions,
            )
    }

    val albumCount = artist.data.albumCount
    val songCount = artist.data.songCount
    val meta =
        listOfNotNull(
            albumCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tally_music_album_count, it, it) },
            songCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tally_music_song_count, it, it) },
        ).joinToString(" · ")
    val firstRow =
        when {
            state.albums.isNotEmpty() -> albumsFocus
            state.appearsOnAlbums.isNotEmpty() -> appearsFocus
            state.topSongs.isNotEmpty() -> firstSongFocus
            state.musicVideos.isNotEmpty() -> videosFocus
            state.similar.isNotEmpty() -> similarFocus
            else -> null
        }

    Box(modifier = Modifier.fillMaxSize().then(arrival)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinScrollBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    MusicHeader(
                        kicker = stringResource(R.string.tally_music_artist),
                        title = artist.name ?: "",
                        imageUrl = state.imageUrl,
                        link = null,
                        onLink = null,
                        meta = meta,
                        genres = artist.data.genres.orEmpty(),
                        overview = artist.data.overview,
                        onOverview = { dialogs.overview = ItemDetailsDialogInfo(artist) },
                        onFocused = {
                            position = POS_ACTIONS
                            scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
                        },
                        actions = { onFocused ->
                            MusicActions(
                                favorite = artist.favorite,
                                primaryFocus = primaryFocus,
                                actionFocus = actionFocus,
                                down = firstRow,
                                onFocused = onFocused,
                                onPlay = { viewModel.play(artist, shuffled = false) },
                                onShuffle = { viewModel.play(artist, shuffled = true) },
                                onInstantMix = { viewModel.startInstantMix(artist.id) },
                                onFavorite = { viewModel.setFavorite(artist.id, !artist.favorite) },
                                onMore = { menuFor(artist, 0, fromLongClick = false) },
                            )
                        },
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringHeader),
                    )
                }
                if (state.albums.isNotEmpty()) {
                    item(key = "albums") {
                        AlbumRow(
                            title = stringResource(R.string.tally_music_albums_row),
                            items = state.albums,
                            modifier = Modifier.focusRequester(albumsFocus),
                            up = if (firstRow == albumsFocus) actionFocus else null,
                            onRowFocused = { position = POS_ALBUMS },
                            onClick = { album ->
                                position = POS_ALBUMS
                                viewModel.navigationManager.navigateTo(album.destination())
                            },
                            onLongClick = { index, album ->
                                position = POS_ALBUMS
                                menuFor(album, index, fromLongClick = true)
                            },
                        )
                    }
                }
                if (state.appearsOnAlbums.isNotEmpty()) {
                    item(key = "appears") {
                        AlbumRow(
                            title = stringResource(R.string.tally_music_appears_on),
                            items = state.appearsOnAlbums,
                            modifier = Modifier.focusRequester(appearsFocus),
                            up = if (firstRow == appearsFocus) actionFocus else null,
                            onRowFocused = { position = POS_APPEARS },
                            onClick = { album ->
                                position = POS_APPEARS
                                viewModel.navigationManager.navigateTo(album.destination())
                            },
                            onLongClick = { index, album ->
                                position = POS_APPEARS
                                menuFor(album, index, fromLongClick = true)
                            },
                        )
                    }
                }
                if (state.topSongs.isNotEmpty()) {
                    item(key = "top-songs-heading") {
                        Box(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
                            RowHeader(
                                title = stringResource(R.string.tally_music_top_songs),
                                count = state.topSongs.size,
                                modifier = RundownModifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(state.topSongs, key = { index, song -> "song-$index-${song?.id}" }) { index, song ->
                        Box(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
                            Box(modifier = RundownModifier) {
                                TrackRow(
                                    number = MusicFormat.trackNumber(index + 1),
                                    title = song?.title ?: song?.name ?: "",
                                    artist = null,
                                    duration = MusicFormat.duration(song?.data?.runTimeTicks),
                                    playing = song != null && currentMusic.currentItemId == song.id,
                                    queued = song != null && song.id in currentMusic.queuedIds,
                                    onClick = {
                                        position = index
                                        song?.let { viewModel.play(it) }
                                    },
                                    onLongClick = {
                                        position = index
                                        song?.let { menuFor(it, index, fromLongClick = true) }
                                    },
                                    onFocused = { position = index },
                                    modifier =
                                        Modifier
                                            .then(if (index == 0) Modifier.focusRequester(firstSongFocus) else Modifier)
                                            .then(if (index == position) Modifier.focusRequester(songFocus) else Modifier)
                                            .then(
                                                if (index == 0 && firstRow == firstSongFocus) {
                                                    Modifier.focusProperties { up = actionFocus }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                )
                            }
                        }
                    }
                    item(key = "top-songs-gap") {
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
                    }
                }
                if (state.musicVideos.isNotEmpty()) {
                    item(key = "videos") {
                        MusicVideosRow(
                            title = stringResource(R.string.tally_music_music_videos),
                            items = state.musicVideos,
                            modifier = Modifier.focusRequester(videosFocus),
                            up = if (firstRow == videosFocus) actionFocus else null,
                            onRowFocused = { position = POS_VIDEOS },
                            onClick = { item ->
                                position = POS_VIDEOS
                                viewModel.navigationManager.navigateTo(item.destination())
                            },
                            onLongClick = { item ->
                                position = POS_VIDEOS
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
                                        actions =
                                            ContextMenuActions(
                                                navigateTo = viewModel.navigationManager::navigateTo,
                                                onShowOverview = {},
                                                onClickWatch = viewModel::setWatched,
                                                onClickFavorite = viewModel::setFavorite,
                                                onClickAddPlaylist = { itemId -> dialogs.playlistItemId = itemId },
                                                onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                                                onDeleteItem = viewModel::deleteItem,
                                                onChooseVersion = { _, _ -> },
                                                onChooseTracks = { },
                                                onClearChosenStreams = {},
                                            ),
                                    )
                            },
                        )
                    }
                }
                if (state.similar.isNotEmpty()) {
                    item(key = "similar") {
                        AlbumRow(
                            title = stringResource(R.string.tally_music_more_like_this),
                            items = state.similar,
                            modifier = Modifier.focusRequester(similarFocus),
                            up = if (firstRow == similarFocus) actionFocus else null,
                            onRowFocused = { position = POS_SIMILAR },
                            onClick = { item ->
                                position = POS_SIMILAR
                                viewModel.navigationManager.navigateTo(item.destination())
                            },
                            onLongClick = { index, item ->
                                position = POS_SIMILAR
                                menuFor(item, index, fromLongClick = true)
                            },
                        )
                    }
                }
            }
        }
        TopScrim(listState)
    }
}
