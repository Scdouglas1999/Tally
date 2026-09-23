package io.github.scdouglas1999.tally.media.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.MusicContextActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.music.AlbumViewModel
import com.github.damontecres.wholphin.ui.detail.music.SongViewModel
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardKickerStyle
import io.github.scdouglas1999.tally.media.kit.CardTitleText
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.MediaRow
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.arrivalFocus
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.library.LoadingMark
import io.github.scdouglas1999.tally.media.music.phone.PhoneAlbumRow
import io.github.scdouglas1999.tally.media.music.phone.PhoneMusicActions
import io.github.scdouglas1999.tally.media.music.phone.PhoneMusicHeader
import io.github.scdouglas1999.tally.media.music.phone.PhoneMusicPageFrame
import io.github.scdouglas1999.tally.media.music.phone.PhoneRundownHeader
import io.github.scdouglas1999.tally.media.music.phone.ReportMusicScroll
import io.github.scdouglas1999.tally.media.music.phone.musicListBottom
import io.github.scdouglas1999.tally.media.series.ExtraCard
import io.github.scdouglas1999.tally.media.series.MinScrollBringIntoViewSpec
import io.github.scdouglas1999.tally.media.series.RowGround
import io.github.scdouglas1999.tally.media.series.TopScrim
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/** Focus positions on the album page that are not tracks (tracks use their index, 0 and up). */
private const val POS_ACTIONS = -1
private const val POS_VIDEOS = -2
private const val POS_EXTRAS = -3
private const val POS_SIMILAR = -4

/** Items of the page's list before the first track: the header and the rundown's heading. */
private const val ITEMS_BEFORE_TRACKS = 2

/**
 * An album in the Tally look (upstream's `AlbumDetailsPage` and its [AlbumViewModel]): a header with the cover as a
 * square at the left (kicker `ALBUM`, title, the artist as a link to the artist page, the mono meta line, the
 * overview) and the actions PLAY, SHUFFLE, INSTANT MIX, FAVORITE and MORE; then the tracks as a rundown of
 * [TrackRow]s (disc headers when the album has several discs), then upstream's music videos, extras and similar
 * albums rows. Track OK plays the album from that track; MENU or a long press opens upstream's music menu.
 */
@Composable
fun TallyAlbumPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel =
        hiltViewModel<AlbumViewModel, AlbumViewModel.Factory>(
            creationCallback = { it.create(destination.itemId, destination.initialSongId) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val moreActions =
        remember(dialogs) {
            MusicContextActions(
                navigateTo = { viewModel.navigationManager.navigateTo(it) },
                onClickPlay = { index, _ -> viewModel.play(false, index) },
                onClickPlayNext = { _, song -> viewModel.playNext(song) },
                onClickAddToQueue = { item -> viewModel.addToQueue(item, -1) },
                onClickFavorite = { itemId, favorite -> viewModel.setFavorite(itemId, favorite) },
                onClickAddPlaylist = { itemId -> dialogs.playlistItemId = itemId },
                onClickRemoveFromQueue = { _, _ -> },
                onDeleteItem = viewModel::deleteItem,
            )
        }
    MusicPageFrame(modifier, phoneKicker = stringResource(R.string.tally_music_album)) {
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
                AlbumLoaded(
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
private fun AlbumLoaded(
    preferences: UserPreferences,
    viewModel: AlbumViewModel,
    dialogs: ItemDialogsState,
    moreActions: MusicContextActions,
) {
    val state by viewModel.state.collectAsState()
    val currentMusic by viewModel.currentMusic.collectAsState()
    val album = state.album ?: return
    val songs = state.songs
    val scope = rememberCoroutineScope()
    val entries =
        remember(songs, songs.size) { MusicFormat.tracklist(List(songs.size) { songs.getOrNull(it)?.data?.parentIndexNumber }) }
    val initialSong = state.initialSongIndex
    var position by rememberInt(initialSong ?: POS_ACTIONS)
    val primaryFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val trackFocus = remember { FocusRequester() }
    val firstTrackFocus = remember { FocusRequester() }
    val videosFocus = remember { FocusRequester() }
    val extrasFocus = remember { FocusRequester() }
    val similarFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val entryOfTrack = remember(entries) { entries.indexOfFirst { it is MusicFormat.TrackListEntry.Track && it.index == initialSong } }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = if (initialSong != null && entryOfTrack >= 0) ITEMS_BEFORE_TRACKS + entryOfTrack else 0,
        )

    val arrivalTarget =
        remember {
            when {
                position >= 0 -> trackFocus
                position == POS_VIDEOS && state.musicVideos.isNotEmpty() -> videosFocus
                position == POS_EXTRAS && state.extras.isNotEmpty() -> extrasFocus
                position == POS_SIMILAR && state.similar.isNotEmpty() -> similarFocus
                else -> primaryFocus
            }
        }
    val arrival = arrivalFocus(arrivalTarget, "tally-album")
    LaunchedEffect(Unit) { viewModel.updateBackDrop() }
    // As upstream: BACK from further down the list goes back to the first track before it leaves the page (a TV
    // habit: on a phone Back just goes back).
    val backToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > ITEMS_BEFORE_TRACKS } }
    BackHandler(backToTop && !isPhone()) {
        scope.launch(ExceptionHandler()) {
            listState.animateScrollToItem(ITEMS_BEFORE_TRACKS)
            firstTrackFocus.tryRequestFocus("tally-album-back")
        }
    }

    fun songMenu(
        song: BaseItem,
        index: Int,
        fromLongClick: Boolean,
    ) {
        dialogs.contextMenu =
            ContextMenu.ForMusic(
                fromLongClick = fromLongClick,
                item = song,
                index = index,
                canDelete = viewModel.canDelete(song, preferences.appPreferences),
                canRemoveFromQueue = false,
                actions = moreActions,
            )
    }

    val artistName = album.data.albumArtist ?: album.data.artists?.joinToString(", ")
    val artistId =
        album.data.albumArtists
            ?.firstOrNull()
            ?.id ?: album.data.artistItems
            ?.firstOrNull()
            ?.id
    val year = album.data.productionYear ?: album.data.premiereDate?.year
    val meta =
        listOfNotNull(
            year?.toString(),
            pluralStringResource(R.plurals.tally_music_track_count, songs.size, songs.size).takeIf { songs.isNotEmpty() },
            album.data.runTimeTicks
                ?.takeIf { it > 0L }
                ?.let { formatRuntime(it) },
        ).joinToString(" · ")
    val firstRow =
        when {
            songs.isNotEmpty() -> firstTrackFocus
            state.musicVideos.isNotEmpty() -> videosFocus
            state.extras.isNotEmpty() -> extrasFocus
            state.similar.isNotEmpty() -> similarFocus
            else -> null
        }

    Box(modifier = Modifier.fillMaxSize().then(arrival)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinScrollBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = musicListBottom()),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    MusicHeader(
                        kicker = stringResource(R.string.tally_music_album),
                        title = album.name ?: "",
                        imageUrl = state.imageUrl,
                        link = artistName,
                        onLink =
                            artistId?.let { id ->
                                {
                                    position = POS_ACTIONS
                                    viewModel.navigationManager.navigateTo(
                                        Destination.MediaItem(itemId = id, type = BaseItemKind.MUSIC_ARTIST),
                                    )
                                }
                            },
                        meta = meta,
                        genres = album.data.genres.orEmpty(),
                        overview = album.data.overview,
                        onOverview = { dialogs.overview = ItemDetailsDialogInfo(album) },
                        onFocused = {
                            position = POS_ACTIONS
                            scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
                        },
                        actions = { onFocused ->
                            MusicActions(
                                favorite = album.favorite,
                                primaryFocus = primaryFocus,
                                actionFocus = actionFocus,
                                down = firstRow,
                                onFocused = onFocused,
                                onPlay = { viewModel.play(false, 0) },
                                onShuffle = { viewModel.play(true, 0) },
                                onInstantMix = { viewModel.startInstantMix(album.id) },
                                onFavorite = { viewModel.setFavorite(album.id, !album.favorite) },
                                onMore = {
                                    dialogs.contextMenu =
                                        ContextMenu.ForMusic(
                                            fromLongClick = false,
                                            item = album,
                                            index = 0,
                                            canDelete = viewModel.canDelete(album, preferences.appPreferences),
                                            canRemoveFromQueue = false,
                                            actions = moreActions,
                                        )
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringHeader),
                    )
                }
                if (songs.isNotEmpty()) {
                    item(key = "tracks-heading") {
                        val multiDisc = entries.any { it is MusicFormat.TrackListEntry.Disc }
                        Box(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
                            if (!multiDisc) {
                                if (isPhone()) {
                                    PhoneRundownHeader(title = stringResource(R.string.tally_music_tracks), count = songs.size)
                                } else {
                                    RowHeader(
                                        title = stringResource(R.string.tally_music_tracks),
                                        count = songs.size,
                                        modifier = RundownModifier.padding(bottom = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    rundown(
                        entries = entries,
                        track = { index ->
                            val song = songs.getOrNull(index)
                            TrackRow(
                                number = MusicFormat.trackNumber(song?.data?.indexNumber),
                                title = song?.title ?: song?.name ?: "",
                                artist =
                                    if (state.isVariousArtists) {
                                        MusicFormat.artistIfDifferent(song?.data?.artists, album.data.albumArtist)
                                    } else {
                                        null
                                    },
                                duration = MusicFormat.duration(song?.data?.runTimeTicks),
                                playing = song != null && currentMusic.currentItemId == song.id,
                                queued = song != null && song.id in currentMusic.queuedIds,
                                onClick = {
                                    position = index
                                    viewModel.play(false, index)
                                },
                                onLongClick = {
                                    position = index
                                    if (song != null) songMenu(song, index, fromLongClick = true)
                                },
                                onFocused = { position = index },
                                modifier =
                                    Modifier
                                        .then(if (index == 0) Modifier.focusRequester(firstTrackFocus) else Modifier)
                                        .then(if (index == position) Modifier.focusRequester(trackFocus) else Modifier)
                                        .then(
                                            if (index == 0) {
                                                Modifier.focusProperties { up = primaryFocus }
                                            } else {
                                                Modifier
                                            },
                                        ),
                            )
                        },
                    )
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
                            // Upstream's album page has no menu on its music videos.
                            onLongClick = { },
                        )
                    }
                }
                if (state.extras.isNotEmpty()) {
                    item(key = "extras") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_music_extras),
                                items = state.extras,
                                key = { index, extra -> "${extra.type}-$index-${extra.title}" },
                                modifier = Modifier.focusRequester(extrasFocus),
                                up = if (firstRow == extrasFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_EXTRAS
                                    reveal()
                                },
                                card = { extra, _, cardModifier, onFocused ->
                                    ExtraCard(
                                        extra = extra,
                                        onClick = {
                                            position = POS_EXTRAS
                                            viewModel.navigationManager.navigateTo(extra.destination)
                                        },
                                        onFocused = onFocused,
                                        modifier = cardModifier,
                                    )
                                },
                            )
                        }
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
                                dialogs.contextMenu =
                                    ContextMenu.ForMusic(
                                        fromLongClick = true,
                                        item = item,
                                        index = index,
                                        canDelete = viewModel.canDelete(item, preferences.appPreferences),
                                        canRemoveFromQueue = false,
                                        actions = moreActions,
                                    )
                            },
                        )
                    }
                }
            }
        }
        if (isPhone()) ReportMusicScroll(listState) else TopScrim(listState)
    }
}

/**
 * A song without an album (upstream's `SongDetailsPage` and its [SongViewModel]): the music header with the song's
 * picture, kicker `SONG`, and the actions PLAY, INSTANT MIX, FAVORITE and MORE (no shuffle, as upstream).
 */
@Composable
fun TallySongPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: SongViewModel =
        hiltViewModel<SongViewModel, SongViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val moreActions =
        remember(dialogs) {
            MusicContextActions(
                navigateTo = { viewModel.navigationManager.navigateTo(it) },
                onClickPlay = { _, song -> viewModel.play(song) },
                onClickPlayNext = { _, song -> viewModel.playNext(song) },
                onClickAddToQueue = { item -> viewModel.addToQueue(item, -1) },
                onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
                onClickAddPlaylist = { id -> dialogs.playlistItemId = id },
                onClickRemoveFromQueue = { _, _ -> },
                onDeleteItem = viewModel::deleteItem,
            )
        }
    MusicPageFrame(modifier, phoneKicker = stringResource(R.string.tally_music_song)) {
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
                val song = state.song ?: return@MusicPageFrame
                val primaryFocus = remember { FocusRequester() }
                val actionFocus = remember { FocusRequester() }
                val arrival = arrivalFocus(primaryFocus, "tally-song")
                val artistId =
                    song.data.artistItems
                        ?.firstOrNull()
                        ?.id
                MusicHeader(
                    kicker = stringResource(R.string.tally_music_song),
                    title = song.title ?: song.name ?: "",
                    imageUrl = state.imageUrl,
                    link = song.data.artists?.joinToString(", "),
                    onLink =
                        artistId?.let { id ->
                            {
                                viewModel.navigationManager.navigateTo(
                                    Destination.MediaItem(itemId = id, type = BaseItemKind.MUSIC_ARTIST),
                                )
                            }
                        },
                    meta =
                        listOfNotNull(
                            song.data.productionYear?.toString(),
                            MusicFormat.duration(song.data.runTimeTicks).ifBlank { null },
                        ).joinToString(" · "),
                    genres = song.data.genres.orEmpty(),
                    overview = song.data.overview,
                    onOverview = { dialogs.overview = ItemDetailsDialogInfo(song) },
                    onFocused = {},
                    actions = { onFocused ->
                        MusicActions(
                            favorite = song.favorite,
                            primaryFocus = primaryFocus,
                            actionFocus = actionFocus,
                            down = null,
                            onFocused = onFocused,
                            onPlay = { viewModel.play(song) },
                            onShuffle = null,
                            onInstantMix = { viewModel.startInstantMix(song.id) },
                            onFavorite = { viewModel.setFavorite(song.id, !song.favorite) },
                            onMore = {
                                dialogs.contextMenu =
                                    ContextMenu.ForMusic(
                                        fromLongClick = false,
                                        item = song,
                                        index = 0,
                                        canDelete = viewModel.canDelete(song, preferences.appPreferences),
                                        canRemoveFromQueue = false,
                                        actions = moreActions,
                                    )
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().then(arrival),
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

private const val FOCUS_ATTEMPTS = 8

/** Focus [target] as soon as it is composed (a lazy list places its items a frame or two after the page). */
internal suspend fun requestFocusSoon(
    target: FocusRequester,
    tag: String,
) {
    for (attempt in 0 until FOCUS_ATTEMPTS) {
        if (target.tryRequestFocus(tag)) return
        withFrameNanos { }
    }
}

/** Page root of the music pages: the Tally scale, text color and body style. The app backdrop shows through. */
@Composable
internal fun MusicPageFrame(
    modifier: Modifier,
    phoneKicker: String? = null,
    content: @Composable () -> Unit,
) {
    if (isPhone()) {
        PhoneMusicPageFrame(kicker = phoneKicker.orEmpty(), modifier = modifier, content = content)
        return
    }
    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(modifier = modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun MusicError(message: String) {
    EmptyState(
        title = stringResource(R.string.tally_media_error_title),
        subtitle = message.ifBlank { stringResource(R.string.tally_media_error_body) },
        modifier = Modifier.fillMaxSize().padding(TallyDimens.marginHorizontal),
    )
}

/** The rundown's rows sit on the page margin; wide enough for long titles, not so wide the duration drifts off. */
internal val RundownMaxWidth = 820.dp

internal val RundownModifier =
    Modifier
        .padding(horizontal = TallyDimens.marginHorizontal)
        .widthIn(max = RundownMaxWidth)

/** The rundown of an album: [MusicFormat.TrackListEntry.Disc] headers and one [track] row per track. */
internal fun LazyListScope.rundown(
    entries: List<MusicFormat.TrackListEntry>,
    track: @Composable (index: Int) -> Unit,
) {
    entries.forEach { entry ->
        when (entry) {
            is MusicFormat.TrackListEntry.Disc -> {
                item(key = "disc-${entry.number}") {
                    Box(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
                        DiscHeader(entry.number)
                    }
                }
            }

            is MusicFormat.TrackListEntry.Track -> {
                item(key = "track-${entry.index}") {
                    Box(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
                        Box(modifier = if (isPhone()) Modifier else RundownModifier) { track(entry.index) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscHeader(number: Int) {
    if (isPhone()) {
        PhoneRundownHeader(title = stringResource(R.string.tally_music_disc, number))
        return
    }
    RowHeader(
        title = stringResource(R.string.tally_music_disc, number),
        modifier = RundownModifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/**
 * The top of the album, artist and song pages: the square picture at the left in a 1dp frame, then the kicker,
 * the title, [link] (the artist, focusable when [onLink] is set), the mono [meta] line, the genres, the overview
 * (4 lines; focusable and OK opens it all when it is cut) and [actions] 24dp under the text. The app backdrop sits
 * top-right; a scrim keeps the text readable on it.
 */
@Composable
internal fun MusicHeader(
    kicker: String,
    title: String,
    imageUrl: String?,
    link: String?,
    onLink: (() -> Unit)?,
    meta: String,
    genres: List<String>,
    overview: String?,
    onOverview: () -> Unit,
    onFocused: () -> Unit,
    actions: @Composable (onFocused: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isPhone()) {
        PhoneMusicHeader(
            title = title,
            imageUrl = imageUrl,
            link = link,
            onLink = onLink,
            meta = meta,
            genres = genres,
            overview = overview,
            actions = { actions {} },
            modifier = modifier,
        )
        return
    }
    Box(
        modifier =
            modifier.drawBehind {
                drawRect(
                    brush =
                        Brush.linearGradient(
                            colorStops =
                                arrayOf(
                                    0f to TallyColors.ground.copy(alpha = 0.88f),
                                    0.45f to TallyColors.ground.copy(alpha = 0.5f),
                                    0.85f to Color.Transparent,
                                ),
                            start = Offset.Zero,
                            end = Offset(size.width * 0.95f, size.height * 0.15f),
                        ),
                )
                // The rows under the header sit on opaque ground: fade the backdrop out before them, so there is
                // no hard edge where the header ends.
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to TallyColors.ground,
                            startY = 0f,
                            endY = size.height,
                        ),
                )
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            modifier =
                Modifier
                    .padding(horizontal = TallyDimens.marginHorizontal)
                    .padding(top = TallyDimens.marginVertical, bottom = 24.dp),
        ) {
            SquareCover(imageUrl = imageUrl, contentDescription = title, size = CoverSize)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).widthIn(max = HeaderTextWidth),
            ) {
                Text(
                    text = kicker.tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
                Text(
                    text = title,
                    style = TitleStyle,
                    color = TallyColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!link.isNullOrBlank()) {
                    FocusFrameText(
                        text = link,
                        style = LinkStyle,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        onClick = onLink,
                        onFocused = onFocused,
                    )
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta.tallyUppercase(),
                        style = TallyType.label,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (genres.isNotEmpty()) {
                    Text(
                        text = genres.joinToString(" / "),
                        style = GenreStyle,
                        color = TallyColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!overview.isNullOrBlank()) {
                    OverviewText(text = overview, onClick = onOverview, onFocused = onFocused)
                }
                Box(modifier = Modifier.padding(top = 16.dp)) {
                    actions(onFocused)
                }
            }
        }
    }
}

/** A square picture on `screen` with a 1dp `rule` frame; the picture fills it (crop). */
@Composable
internal fun SquareCover(
    imageUrl: String?,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .background(TallyColors.screen)
                .border(TallyDimens.hairline, TallyColors.rule),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { logCoilError(imageUrl, it.result) },
                modifier = Modifier.fillMaxSize().padding(TallyDimens.hairline),
            )
        }
    }
}

/**
 * Text that takes focus only when it does something: [onClick] set (a link), or [truncateOnly] and the text is cut
 * (then OK shows all of it). Focused: a 3dp accent frame standing off the text, nothing moves.
 */
@Composable
private fun FocusFrameText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    onClick: (() -> Unit)?,
    onFocused: () -> Unit,
    truncateOnly: Boolean = false,
) {
    var truncated by remember(text) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val enabled = onClick != null && (!truncateOnly || truncated)
    Text(
        text = text,
        style = style,
        color = if (focused) TallyColors.text else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { truncated = it.hasVisualOverflow },
        modifier =
            Modifier
                .drawBehind {
                    if (focused) {
                        val stroke = kotlin.math.floor(TallyDimens.focusBorder.toPx())
                        val out = FrameOutset.toPx()
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(-out + stroke / 2f, stroke / 2f),
                            size = Size(size.width + 2 * out - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                }.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = { onClick?.invoke() },
                ).padding(vertical = if (truncateOnly) 8.dp else 4.dp),
    )
}

@Composable
private fun OverviewText(
    text: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    FocusFrameText(
        text = text,
        style = TallyType.body,
        color = TallyColors.text,
        maxLines = 4,
        onClick = onClick,
        onFocused = onFocused,
        truncateOnly = true,
    )
}

/**
 * The action row of the music pages: PLAY (primary), SHUFFLE (when [onShuffle] is set), INSTANT MIX, FAVORITE (an
 * accent square when on) and MORE. Upstream's delete is in MORE's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MusicActions(
    favorite: Boolean,
    primaryFocus: FocusRequester,
    actionFocus: FocusRequester,
    down: FocusRequester?,
    onFocused: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onInstantMix: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
) {
    if (isPhone()) {
        PhoneMusicActions(
            favorite = favorite,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onInstantMix = onInstantMix,
            onFavorite = onFavorite,
            onMore = onMore,
        )
        return
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = FocusEdge, end = 24.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bleedHorizontal()
                    .focusRequester(actionFocus)
                    .focusGroup()
                    .focusRestorer(primaryFocus)
                    .focusProperties {
                        if (down != null) this.down = down
                    },
        ) {
            item(key = "play") {
                TallyButton(
                    label = stringResource(R.string.tally_music_play),
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    onClick = onPlay,
                    onFocused = onFocused,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            if (onShuffle != null) {
                item(key = "shuffle") {
                    TallyButton(
                        label = stringResource(R.string.tally_music_shuffle),
                        glyph = stringResource(R.string.fa_shuffle),
                        onClick = onShuffle,
                        onFocused = onFocused,
                    )
                }
            }
            item(key = "mix") {
                TallyButton(
                    label = stringResource(R.string.tally_music_instant_mix),
                    glyph = stringResource(R.string.fa_compass),
                    onClick = onInstantMix,
                    onFocused = onFocused,
                )
            }
            item(key = "favorite") {
                TallyButton(
                    label = stringResource(if (favorite) R.string.tally_music_favorited else R.string.tally_music_favorite),
                    glyph = stringResource(R.string.fa_heart),
                    onClick = onFavorite,
                    onFocused = onFocused,
                    trailing =
                        if (favorite) {
                            { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                        } else {
                            null
                        },
                )
            }
            item(key = "more") {
                TallyButton(
                    label = stringResource(R.string.tally_music_more),
                    glyph = stringResource(R.string.fa_ellipsis),
                    onClick = onMore,
                    onFocused = onFocused,
                )
            }
        }
    }
}

/**
 * A square album card: the cover, then the label bar with the year as a mono accent kicker over the title.
 * A null [item] (a page still loading) is an empty frame of the same size.
 */
@Composable
internal fun AlbumCard(
    item: BaseItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageUrl = LocalImageUrlService.current.rememberImageUrl(item)
    val year = item?.data?.productionYear ?: item?.data?.premiereDate?.year
    CardFrame(
        imageUrl = imageUrl,
        width = AlbumCardWidth,
        height = AlbumCardWidth,
        contentDescription = item?.name,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        favorite = item?.favorite == true,
        modifier = modifier,
        label = {
            if (year != null) {
                Text(
                    text = year.toString(),
                    style = CardKickerStyle,
                    color = TallyColors.accent,
                    maxLines = 1,
                )
            }
            CardTitleText(item?.name ?: "")
        },
    )
}

/** A [MediaRow] of [AlbumCard]s on the page's ground. */
@Composable
internal fun AlbumRow(
    title: String,
    items: List<BaseItem?>,
    onRowFocused: () -> Unit,
    onClick: (BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    up: FocusRequester? = null,
) {
    if (isPhone()) {
        PhoneAlbumRow(title = title, items = items, onClick = onClick, onLongClick = onLongClick)
        return
    }
    RowGround { reveal ->
        MediaRow(
            title = title,
            items = items,
            key = { index, item -> "$index-${item?.id}" },
            modifier = modifier,
            up = up,
            onRowFocused = {
                onRowFocused()
                reveal()
            },
            card = { item, index, cardModifier, onFocused ->
                AlbumCard(
                    item = item,
                    onClick = { item?.let(onClick) },
                    onLongClick = { item?.let { onLongClick(index, it) } },
                    onFocused = onFocused,
                    modifier = cardModifier,
                )
            },
        )
    }
}

/** A [MediaRow] of music videos as landscape cards (year as the kicker), as upstream's wide cards. */
@Composable
internal fun MusicVideosRow(
    title: String,
    items: List<BaseItem?>,
    onRowFocused: () -> Unit,
    onClick: (BaseItem) -> Unit,
    onLongClick: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    up: FocusRequester? = null,
) {
    if (isPhone()) {
        PhoneAlbumRow(
            title = title,
            items = items,
            onClick = onClick,
            onLongClick = { _, item -> onLongClick(item) },
            wide = true,
        )
        return
    }
    RowGround { reveal ->
        MediaRow(
            title = title,
            items = items,
            key = { index, item -> "$index-${item?.id}" },
            modifier = modifier,
            up = up,
            onRowFocused = {
                onRowFocused()
                reveal()
            },
            card = { item, _, cardModifier, onFocused ->
                LandscapeCard(
                    title = item?.name ?: "",
                    kicker = item?.data?.productionYear?.toString(),
                    imageUrl = item?.let { rememberWideImageUrl(it) },
                    onClick = { item?.let(onClick) },
                    onLongClick = { item?.let(onLongClick) },
                    favorite = item?.favorite == true,
                    onFocused = onFocused,
                    modifier = cardModifier,
                )
            },
        )
    }
}

internal val CoverSize = 232.dp
private val HeaderTextWidth = 600.dp
private val AlbumCardWidth = 150.dp
private val FrameOutset = 10.dp

private val TitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    )

private val LinkStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )

private val GenreStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
