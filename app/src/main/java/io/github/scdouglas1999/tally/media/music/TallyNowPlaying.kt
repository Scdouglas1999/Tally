package io.github.scdouglas1999.tally.media.music

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberCurrentMediaItemState
import androidx.media3.ui.compose.state.rememberNextButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import androidx.media3.ui.compose.state.rememberRepeatButtonState
import androidx.media3.ui.compose.state.rememberShuffleButtonState
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.updateMusicPreferences
import com.github.damontecres.wholphin.services.rememberQueue
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.QueueContextActions
import com.github.damontecres.wholphin.ui.detail.music.NowPlayingViewModel
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.playback.PlaybackKeyHandler
import com.github.damontecres.wholphin.ui.playback.isMedia
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.library.LoadingMark
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.player.controls.TallySeekBar
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the panel under the controls shows. */
private enum class NowPlayingPanel { QUEUE, LYRICS }

/**
 * Now playing in the Tally look (upstream's `NowPlayingPage` and its [NowPlayingViewModel]): the cover large and
 * square at the left; at the right the kicker `NOW PLAYING`, the title, artist and album, the Tally seek bar and
 * times, and square controls (previous, play/pause, next, shuffle, repeat with its `ONE`/`ALL` tag, lyrics, queue,
 * stop). Under them the queue (a rundown of [TrackRow]s, the playing one lit, upstream's move up/down beside each)
 * or, with lyrics on and a song that has them, the lyrics ([TallyLyrics]). The media keys do what upstream's key
 * handler does (seek 10 s back / 30 s forward, previous, next, stop). Upstream's visualizer, when it is on, runs as
 * thin bars under the cover.
 */
@OptIn(UnstableApi::class)
@Composable
fun TallyNowPlaying(
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel =
        hiltViewModel<NowPlayingViewModel, NowPlayingViewModel.Factory>(
            creationCallback = { it.create() },
        ),
) {
    val state by viewModel.state.collectAsState()
    val player = viewModel.player
    val currentMediaItem = rememberCurrentMediaItemState(player)
    val current = currentMediaItem.mediaItem?.localConfiguration?.tag as? AudioItem
    val viz by viewModel.viz.collectAsState()
    val preferences =
        viewModel.userPreferencesService.flow
            .collectAsState(UserPreferences(AppPreferences.getDefaultInstance(), null))
            .value.appPreferences
    val musicPrefs = preferences.musicPreferences
    val keyHandler =
        remember(preferences) {
            PlaybackKeyHandler(
                player = player,
                controlsEnabled = true,
                skipWithLeftRight = false,
                seekForward = 30.seconds,
                seekBack = 10.seconds,
                controllerViewState = viewModel.controllerViewState,
                updateSkipIndicator = {},
                clearSkipIndicator = {},
                skipBackOnResume = null,
                onInteraction = viewModel::reportInteraction,
                oneClickPause = preferences.playbackPreferences.oneClickPause,
                onStop = { viewModel.stop() },
                onPlaybackDialogTypeClick = { },
                getDurationMs = { player.duration },
                dpadSeekMode = preferences.playbackPreferences.dpadSeekMode,
            )
        }
    val dialogs = remember { ItemDialogsState() }
    val queueActions =
        remember {
            QueueContextActions(
                onNavigate = { viewModel.navigationManager.navigateTo(it) },
                onClickPlay = { index, _ -> viewModel.play(index) },
                onClickPlayNext = { index, _ -> viewModel.playNext(index) },
                onClickRemoveFromQueue = { index, _ -> viewModel.removeFromQueue(index) },
            )
        }

    var queueRequested by remember { mutableStateOf(false) }
    val lyricsOn = musicPrefs.showLyrics
    val panel =
        if (lyricsOn && !queueRequested && current?.hasLyrics == true) NowPlayingPanel.LYRICS else NowPlayingPanel.QUEUE
    val playFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var panelHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { requestFocusSoon(playFocus, "tally-now-playing") }
    // BACK from the queue or the lyrics comes back to the controls first, as upstream's queue goes back to its top.
    BackHandler(panelHasFocus) { playFocus.tryRequestFocus("tally-now-playing-back") }

    TallyScale {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(TallyColors.ground)
                    .onPreviewKeyEvent { if (isMedia(it)) keyHandler.onKeyEvent(it) else false },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SquareCover(imageUrl = current?.imageUrl, contentDescription = current?.title, size = NowPlayingCover)
                    if (musicPrefs.showVisualizer && state.visualizerPermissions && viz.isNotEmpty()) {
                        TallyVisualizer(data = viz, modifier = Modifier.width(NowPlayingCover).height(VisualizerHeight))
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        text = stringResource(R.string.tally_music_now_playing).tallyUppercase(),
                        style = TallyType.label,
                        color = TallyColors.accent,
                        maxLines = 1,
                    )
                    Text(
                        text = current?.title ?: stringResource(R.string.tally_music_nothing_playing),
                        style = TitleStyle,
                        color = if (current != null) TallyColors.text else TallyColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    current?.artistNames?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = ArtistStyle,
                            color = TallyColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    current?.albumTitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = AlbumStyle,
                            color = TallyColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    TallySeekBar(
                        player = player,
                        controllerViewState = viewModel.controllerViewState,
                        chapters = emptyList(),
                        seekEnabled = false,
                        seekBack = Duration.ZERO,
                        seekForward = Duration.ZERO,
                        onSeekProgress = {},
                        interactionSource = remember { MutableInteractionSource() },
                        trickplayInfo = null,
                        trickplayUrlFor = { null },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    )
                    NowPlayingControls(
                        player = player,
                        lyricsOn = lyricsOn,
                        queueShown = panel == NowPlayingPanel.QUEUE,
                        playFocus = playFocus,
                        down = panelFocus,
                        onInteraction = viewModel::reportInteraction,
                        onLyrics = {
                            when {
                                // Lyrics on, the queue opened over them: back to the lyrics.
                                lyricsOn && queueRequested && current?.hasLyrics == true -> {
                                    queueRequested = false
                                }

                                lyricsOn -> {
                                    viewModel.updatePreferences(preferences.updateMusicPreferences { showLyrics = false })
                                }

                                else -> {
                                    queueRequested = false
                                    viewModel.updatePreferences(preferences.updateMusicPreferences { showLyrics = true })
                                }
                            }
                        },
                        onQueue = {
                            queueRequested = true
                            scope.launch(ExceptionHandler()) { requestFocusSoon(panelFocus, "tally-now-playing-queue") }
                        },
                        onStop = { viewModel.stop() },
                        modifier = Modifier.padding(top = 12.dp).focusRequester(controlsFocus),
                    )
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .onFocusChanged { panelHasFocus = it.hasFocus },
                    ) {
                        when (panel) {
                            NowPlayingPanel.LYRICS -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    RowHeader(title = stringResource(R.string.tally_music_lyrics))
                                    TallyLyrics(
                                        lyrics = state.lyrics,
                                        currentIndex = state.currentLyricIndex,
                                        onSeek = { line ->
                                            line.start
                                                ?.ticks
                                                ?.inWholeMilliseconds
                                                ?.let { player.seekTo(it) }
                                        },
                                        entryFocus = panelFocus,
                                        up = playFocus,
                                        // The lines' focus frames stand off the text: the text itself lines up
                                        // with the heading.
                                        modifier = Modifier.fillMaxSize().padding(top = 8.dp).bleedHorizontal(LyricInset),
                                    )
                                }
                            }

                            NowPlayingPanel.QUEUE -> {
                                QueuePanel(
                                    player = player,
                                    queueVersion = state.musicServiceState.queueVersion,
                                    queueSize = state.musicServiceState.queueSize,
                                    currentIndex = state.musicServiceState.currentIndex,
                                    currentId = current?.id,
                                    entryFocus = panelFocus,
                                    up = playFocus,
                                    onClickSong = { index -> viewModel.play(index) },
                                    onMenu = { index, song ->
                                        dialogs.contextMenu =
                                            ContextMenu.ForQueue(
                                                fromLongClick = true,
                                                item = song,
                                                index = index,
                                                actions = queueActions,
                                            )
                                    },
                                    onMove = { index, direction -> viewModel.moveQueue(index, direction) },
                                )
                            }
                        }
                    }
                }
            }
            if (state.musicServiceState.loadingState is LoadingState.Loading) {
                LoadingMark(Modifier.fillMaxSize().background(TallyColors.ground.copy(alpha = 0.7f)))
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

/** The square controls under the seek bar, in groups: transport, order (shuffle, repeat), panels, stop. */
@OptIn(UnstableApi::class)
@Composable
private fun NowPlayingControls(
    player: Player,
    lyricsOn: Boolean,
    queueShown: Boolean,
    playFocus: FocusRequester,
    down: FocusRequester,
    onInteraction: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPause = rememberPlayPauseButtonState(player)
    val previous = rememberPreviousButtonState(player)
    val next = rememberNextButtonState(player)
    val shuffle = rememberShuffleButtonState(player)
    val repeat = rememberRepeatButtonState(player)
    val repeatTag =
        when (MusicFormat.repeatTag(repeat.repeatModeState)) {
            MusicFormat.RepeatTag.ONE -> stringResource(R.string.tally_music_repeat_one)
            MusicFormat.RepeatTag.ALL -> stringResource(R.string.tally_music_repeat_all)
            MusicFormat.RepeatTag.NONE -> null
        }
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            modifier
                .focusGroup()
                .focusProperties { this.down = down },
    ) {
        MusicControl(
            glyph = stringResource(R.string.tally_player_glyph_previous),
            label = stringResource(R.string.tally_music_previous),
            enabled = previous.isEnabled,
            onClick = {
                onInteraction()
                previous.onClick()
            },
        )
        Spacer(Modifier.width(ButtonGap))
        MusicControl(
            glyph =
                stringResource(
                    if (playPause.showPlay) R.string.tally_player_glyph_play else R.string.tally_player_glyph_pause,
                ),
            label = stringResource(if (playPause.showPlay) R.string.tally_music_play else R.string.tally_music_pause),
            primary = true,
            onClick = {
                onInteraction()
                playPause.onClick()
            },
            modifier = Modifier.focusRequester(playFocus),
        )
        Spacer(Modifier.width(ButtonGap))
        MusicControl(
            glyph = stringResource(R.string.tally_player_glyph_next),
            label = stringResource(R.string.tally_music_next),
            enabled = next.isEnabled,
            onClick = {
                onInteraction()
                next.onClick()
            },
        )
        Spacer(Modifier.width(GroupGap))
        MusicControl(
            glyph = stringResource(R.string.fa_shuffle),
            label = stringResource(R.string.tally_music_shuffle),
            active = shuffle.shuffleOn,
            tag = if (shuffle.shuffleOn) stringResource(R.string.tally_music_shuffle_on) else null,
            enabled = shuffle.isEnabled,
            onClick = {
                onInteraction()
                shuffle.onClick()
            },
        )
        Spacer(Modifier.width(ButtonGap))
        MusicControl(
            glyph = stringResource(R.string.fa_repeat),
            label = stringResource(R.string.tally_music_repeat),
            active = repeatTag != null,
            tag = repeatTag,
            enabled = repeat.isEnabled,
            onClick = {
                onInteraction()
                repeat.onClick()
            },
        )
        Spacer(Modifier.width(GroupGap))
        MusicControl(
            glyph = stringResource(R.string.tally_music_glyph_lyrics),
            label = stringResource(R.string.tally_music_lyrics),
            active = lyricsOn,
            onClick = onLyrics,
        )
        Spacer(Modifier.width(ButtonGap))
        MusicControl(
            glyph = stringResource(R.string.tally_player_glyph_queue),
            label = stringResource(R.string.tally_music_queue),
            active = queueShown,
            onClick = onQueue,
        )
        Spacer(Modifier.width(GroupGap))
        MusicControl(
            glyph = stringResource(R.string.tally_music_glyph_stop),
            label = stringResource(R.string.tally_music_stop),
            onClick = onStop,
        )
    }
}

/**
 * A square control in the Tally player's style: 40dp, 1dp `ruleStrong` border, focused 3dp accent; [primary] is
 * accent-filled with a 3dp `text` border when focused. The glyph is `text` when [active], `muted` when not; a [tag]
 * (`ONE`, `ALL`, `ON`) widens the button with its state in mono. While focused, [label] shows under it as a caption.
 * A disabled control keeps its place, its glyph in `rule`.
 */
@Composable
private fun MusicControl(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    active: Boolean = true,
    tag: String? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fill = if (primary) TallyColors.accent else Color.Transparent
    val content =
        when {
            primary -> TallyColors.onAccent
            !enabled -> TallyColors.rule
            active -> TallyColors.text
            else -> TallyColors.muted
        }
    val focusColor = if (primary) TallyColors.text else TallyColors.accent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = fill,
                    contentColor = content,
                    focusedContainerColor = fill,
                    focusedContentColor = content,
                    pressedContainerColor = fill,
                    pressedContentColor = content,
                    disabledContainerColor = fill,
                    disabledContentColor = content,
                ),
            border =
                ClickableSurfaceDefaults.border(
                    border =
                        Border(
                            border =
                                BorderStroke(
                                    if (primary) 0.dp else TallyDimens.hairline,
                                    if (primary) Color.Transparent else TallyColors.ruleStrong,
                                ),
                            shape = RectangleShape,
                        ),
                    focusedBorder =
                        Border(border = BorderStroke(TallyDimens.focusBorder, focusColor), shape = RectangleShape),
                    pressedBorder =
                        Border(border = BorderStroke(TallyDimens.focusBorder, focusColor), shape = RectangleShape),
                    disabledBorder =
                        Border(border = BorderStroke(TallyDimens.hairline, TallyColors.rule), shape = RectangleShape),
                ),
            glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
            interactionSource = interactionSource,
            modifier = Modifier.sizeIn(minWidth = ControlSize, minHeight = ControlSize).height(ControlSize),
        ) {
            // tv-material3 Surface lays content out top-start: fill the height and center the glyph (and tag).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .sizeIn(minWidth = ControlSize)
                        .padding(horizontal = if (tag != null) 12.dp else 0.dp),
            ) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 17.sp,
                    color = content,
                    maxLines = 1,
                )
                if (tag != null) {
                    Text(
                        text = tag.tallyUppercase(),
                        style = TagStyle,
                        color = content,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Text(
            text = if (focused) label.tallyUppercase() else "",
            style = CaptionStyle,
            color = TallyColors.muted,
            maxLines = 1,
            softWrap = false,
            // The caption may be wider than the button; it must not widen the column (the row would move).
            modifier =
                Modifier
                    .padding(top = 5.dp)
                    .height(16.dp)
                    .width(ControlSize)
                    .wrapContentWidth(unbounded = true),
        )
    }
}

/**
 * The queue: a [RowHeader] with the count over a rundown of [TrackRow]s, the playing track's lamp lit, and
 * upstream's move up / move down beside each row. OK plays the track; MENU or a long press opens upstream's queue
 * menu (play, play next, remove, go to album or artist). Entering it lands on the playing track.
 */
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuePanel(
    player: Player,
    queueVersion: Long,
    queueSize: Int,
    currentIndex: Int,
    currentId: java.util.UUID?,
    entryFocus: FocusRequester,
    up: FocusRequester,
    onClickSong: (Int) -> Unit,
    onMenu: (Int, AudioItem) -> Unit,
    onMove: (Int, MoveDirection) -> Unit,
) {
    val queue = rememberQueue(player, queueVersion, queueSize)
    val currentFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    Column(modifier = Modifier.fillMaxSize()) {
        RowHeader(title = stringResource(R.string.tally_music_queue), count = queue.size)
        if (queue.isEmpty()) {
            Text(
                text = stringResource(R.string.tally_music_queue_empty).tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.muted,
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .focusRequester(entryFocus),
            )
            return@Column
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = FocusEdge),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp - FocusEdge)
                        .focusRequester(entryFocus)
                        .focusProperties {
                            onEnter = {
                                if (!currentFocus.tryRequestFocus("tally-queue")) firstFocus.tryRequestFocus("tally-queue")
                            }
                        }.focusGroup(),
            ) {
                itemsIndexed(queue, key = { _, song -> song.key }) { index, song ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        TrackRow(
                            number = MusicFormat.trackNumber(index + 1),
                            title = song.title ?: "",
                            artist = song.artistNames,
                            duration =
                                song.runtime
                                    ?.inWholeSeconds
                                    ?.let { MusicFormat.duration(it * 10_000_000L) }
                                    .orEmpty(),
                            playing = currentId == song.id && index == currentIndex,
                            onClick = { onClickSong(index) },
                            onLongClick = { onMenu(index, song) },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .then(if (index == currentIndex) Modifier.focusRequester(currentFocus) else Modifier)
                                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .then(if (index == 0) Modifier.focusProperties { this.up = up } else Modifier),
                        )
                        MoveButton(
                            glyph = stringResource(R.string.fa_caret_up),
                            enabled = index > 0,
                            onClick = { onMove(index, MoveDirection.UP) },
                        )
                        MoveButton(
                            glyph = stringResource(R.string.fa_caret_down),
                            enabled = index < queue.lastIndex,
                            onClick = { onMove(index, MoveDirection.DOWN) },
                        )
                    }
                }
            }
        }
    }
}

/** A small square glyph button beside a queue row (36dp, the row's height less its padding). */
@Composable
private fun MoveButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = TallyColors.textSecondary,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = TallyColors.rule,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong), shape = RectangleShape),
                focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                disabledBorder = Border(BorderStroke(TallyDimens.hairline, TallyColors.rule), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = Modifier.size(MoveSize),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = glyph,
                fontFamily = FontAwesome,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * Upstream's bar visualizer in the Tally look: thin square bars, each a full-height track in `rule` with the current
 * level (from the bottom) in `muted`. Never colored.
 */
@Composable
private fun TallyVisualizer(
    data: IntArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        val gap = 1.dp.toPx()
        val bar = (size.width - gap * (data.size - 1)) / data.size
        data.forEachIndexed { index, value ->
            val x = index * (bar + gap)
            drawRect(TallyColors.rule, topLeft = Offset(x, 0f), size = Size(bar, size.height))
            val level = size.height * (value.coerceIn(0, 256) / 256f)
            drawRect(TallyColors.muted, topLeft = Offset(x, size.height - level), size = Size(bar, level))
        }
    }
}

private val NowPlayingCover = 520.dp
private val VisualizerHeight = 56.dp
private val ControlSize = 40.dp
private val MoveSize = 36.dp
private val ButtonGap = 10.dp
private val GroupGap = 24.dp

private val TitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

private val ArtistStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )

private val AlbumStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    )

private val TagStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.5.sp,
    )

private val CaptionStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )
