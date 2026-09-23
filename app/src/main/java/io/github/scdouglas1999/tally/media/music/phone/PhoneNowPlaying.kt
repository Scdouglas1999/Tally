package io.github.scdouglas1999.tally.media.music.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberNextButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import androidx.media3.ui.compose.state.rememberRepeatButtonState
import androidx.media3.ui.compose.state.rememberShuffleButtonState
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.services.rememberQueue
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import io.github.scdouglas1999.tally.media.music.MusicFormat
import io.github.scdouglas1999.tally.media.music.TrackRow
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBarAction
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetTitle
import io.github.scdouglas1999.tally.ui.settings.phone.phoneSheetListMaxHeight
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.delay
import java.util.UUID

private const val POSITION_POLL_MS = 250L
private val PlayButton = 64.dp
private val SeekHeight = 32.dp
private val SeekTrack = 2.dp
private val SeekThumb = 12.dp
private val VisualizerHeight = 32.dp

/** What the phone's now playing needs from upstream's view model; the actions are the TV page's own. */
internal class PhoneNowPlayingQueue(
    val version: Long,
    val size: Int,
    val currentIndex: Int,
    val onPlay: (Int) -> Unit,
    val onMenu: (Int, AudioItem) -> Unit,
    val onMove: (Int, MoveDirection) -> Unit,
)

/**
 * Now playing on a phone (portrait, full screen, no bottom bar): the top bar with STOP; the cover square across the
 * width (or, with lyrics on and a song that has them, the synced lines in its place, following the song); the title
 * and artist · album; the draggable seek bar with its times; shuffle, previous, play/pause (64dp, accent frame), next
 * and repeat (with its ONE/ALL tag); then LYRICS and QUEUE. The queue opens as a sheet of track rows.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PhoneNowPlaying(
    player: Player,
    current: AudioItem?,
    lyricsOn: Boolean,
    showLyrics: Boolean,
    lyrics: @Composable (Modifier) -> Unit,
    visualizer: IntArray?,
    queue: PhoneNowPlayingQueue,
    onLyrics: () -> Unit,
    onStop: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var queueOpen by remember { mutableStateOf(false) }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(TallyColors.ground)
                .navigationBarsPadding(),
    ) {
        PhoneTopBar(
            kicker = stringResource(R.string.tally_music_now_playing),
            onBack = dispatcher?.let { { it.onBackPressed() } },
        ) {
            PhoneTopBarAction(
                glyph = R.string.tally_music_glyph_stop,
                label = stringResource(R.string.tally_music_stop),
                onClick = onStop,
            )
        }
        // The cover (or the lyrics) at the top, the song and its controls at the bottom.
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = PhoneDimens.margin),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .align(Alignment.CenterHorizontally),
                ) {
                    if (showLyrics) {
                        lyrics(Modifier.fillMaxSize())
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(TallyColors.screen)
                                    .border(PhoneDimens.hairline, TallyColors.rule),
                        ) {
                            if (current?.imageUrl != null) {
                                coil3.compose.AsyncImage(
                                    model = current.imageUrl,
                                    contentDescription = current.title,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
                                )
                            }
                        }
                    }
                }
                if (visualizer != null && visualizer.isNotEmpty()) {
                    PhoneVisualizer(visualizer, Modifier.fillMaxWidth().padding(top = 8.dp).height(VisualizerHeight))
                }
            }
            Column {
                Text(
                    text = current?.title ?: stringResource(R.string.tally_music_nothing_playing),
                    style = PhoneType.title,
                    color = if (current != null) TallyColors.text else TallyColors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 20.dp),
                )
                val byline = listOfNotNull(current?.artistNames, current?.albumTitle).filter { it.isNotBlank() }
                if (byline.isNotEmpty()) {
                    Text(
                        text = byline.joinToString(" · "),
                        style = PhoneType.body,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                PhoneSeekBar(player = player, onInteraction = onInteraction, modifier = Modifier.padding(top = 12.dp))
                PhoneTransport(player = player, onInteraction = onInteraction, modifier = Modifier.padding(top = 8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                ) {
                    PhoneButton(
                        label = stringResource(R.string.tally_music_lyrics),
                        onClick = onLyrics,
                        glyph = { if (lyricsOn) IndicatorSquare(color = TallyColors.accent, size = 8.dp) },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneButton(
                        label = stringResource(R.string.tally_music_queue),
                        onClick = { queueOpen = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (queueOpen) {
        PhoneQueueSheet(
            player = player,
            queue = queue,
            currentId = current?.id,
            onDismiss = { queueOpen = false },
        )
    }
}

/** Where the player is, polled while it is on screen. */
@Composable
private fun rememberPosition(player: Player): Pair<Long, Long> {
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration
            delay(POSITION_POLL_MS)
        }
    }
    return position to duration
}

/**
 * The phone's music seek bar: a 2dp `ruleStrong` track with the played part in accent and a square accent thumb; tap
 * or drag to seek (the thumb follows the finger and the player seeks when it lifts). The elapsed and total times in
 * mono under it.
 */
@Composable
private fun PhoneSeekBar(
    player: Player,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (position, duration) = rememberPosition(player)
    val known = duration != C.TIME_UNSET && duration > 0
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (known) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    val thumb = with(density) { SeekThumb.toPx() }

    fun fractionAt(x: Float): Float = ((x - thumb / 2f) / (width - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun seek(to: Float) {
        if (!known) return
        onInteraction()
        player.seekTo((to * duration).toLong())
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SeekHeight)
                    .onSizeChanged { width = it.width }
                    .pointerInput(known, duration) {
                        detectTapGestures { seek(fractionAt(it.x)) }
                    }.pointerInput(known, duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = fractionAt(it.x) },
                            onDragEnd = {
                                dragging?.let(::seek)
                                dragging = null
                            },
                            onDragCancel = { dragging = null },
                        ) { change, _ -> dragging = fractionAt(change.position.x) }
                    },
        ) {
            val travel = with(density) { ((width - thumb).coerceAtLeast(0f) * fraction).toDp() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SeekThumb / 2)
                    .height(SeekTrack)
                    .background(TallyColors.ruleStrong),
            )
            Box(
                Modifier
                    .padding(start = SeekThumb / 2)
                    .width(travel)
                    .height(SeekTrack)
                    .background(TallyColors.accent),
            )
            Box(
                Modifier
                    .offset(x = travel)
                    .size(SeekThumb)
                    .background(TallyColors.accent),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            val shown = if (dragging != null && known) (fraction * duration).toLong() else position
            Text(
                text = MusicFormat.duration(shown.coerceAtLeast(0L) * TICKS_PER_MS),
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (known) MusicFormat.duration(duration * TICKS_PER_MS) else "",
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
            )
        }
    }
}

private const val TICKS_PER_MS = 10_000L

/** Shuffle, previous, play/pause (64dp, accent frame), next and repeat with its ONE/ALL tag. */
@OptIn(UnstableApi::class)
@Composable
private fun PhoneTransport(
    player: Player,
    onInteraction: () -> Unit,
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        PhoneIconSquare(
            glyph = stringResource(R.string.fa_shuffle),
            label = stringResource(R.string.tally_music_shuffle),
            color = if (shuffle.shuffleOn) TallyColors.accent else TallyColors.muted,
            enabled = shuffle.isEnabled,
            frame = androidx.compose.ui.graphics.Color.Transparent,
            onClick = {
                onInteraction()
                shuffle.onClick()
            },
        )
        PhoneIconSquare(
            glyph = stringResource(R.string.tally_player_glyph_previous),
            label = stringResource(R.string.tally_music_previous),
            enabled = previous.isEnabled,
            frame = androidx.compose.ui.graphics.Color.Transparent,
            glyphSize = 22,
            onClick = {
                onInteraction()
                previous.onClick()
            },
        )
        PhoneIconSquare(
            glyph =
                stringResource(
                    if (playPause.showPlay) R.string.tally_player_glyph_play else R.string.tally_player_glyph_pause,
                ),
            label = stringResource(if (playPause.showPlay) R.string.tally_music_play else R.string.tally_music_pause),
            size = PlayButton,
            frame = TallyColors.accent,
            frameWidth = 2.dp,
            glyphSize = 26,
            onClick = {
                onInteraction()
                playPause.onClick()
            },
        )
        PhoneIconSquare(
            glyph = stringResource(R.string.tally_player_glyph_next),
            label = stringResource(R.string.tally_music_next),
            enabled = next.isEnabled,
            frame = androidx.compose.ui.graphics.Color.Transparent,
            glyphSize = 22,
            onClick = {
                onInteraction()
                next.onClick()
            },
        )
        PhoneIconSquare(
            glyph = stringResource(R.string.fa_repeat),
            label = stringResource(R.string.tally_music_repeat),
            color = if (repeatTag != null) TallyColors.accent else TallyColors.muted,
            tag = repeatTag,
            enabled = repeat.isEnabled,
            frame = androidx.compose.ui.graphics.Color.Transparent,
            onClick = {
                onInteraction()
                repeat.onClick()
            },
        )
    }
}

/**
 * The queue as a sheet: `QUEUE n`, then the queued tracks as rows (the playing one's lamp lit) with the TV's move up
 * and move down beside each. Tap plays, long-press opens upstream's queue menu. Opens on the playing track.
 */
@Composable
private fun PhoneQueueSheet(
    player: Player,
    queue: PhoneNowPlayingQueue,
    currentId: UUID?,
    onDismiss: () -> Unit,
) {
    val songs = rememberQueue(player, queue.version, queue.size)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = queue.currentIndex.coerceAtLeast(0))
    PhoneSheet(onDismiss = onDismiss) {
        PhoneSheetTitle("${stringResource(R.string.tally_music_queue)}  ${songs.size}")
        if (songs.isEmpty()) {
            Text(
                text = stringResource(R.string.tally_music_queue_empty).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                modifier = Modifier.padding(PhoneDimens.margin),
            )
            return@PhoneSheet
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = phoneSheetListMaxHeight()),
        ) {
            itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().animateItem()) {
                    TrackRow(
                        number = MusicFormat.trackNumber(index + 1),
                        title = song.title ?: "",
                        artist = song.artistNames,
                        duration =
                            song.runtime
                                ?.inWholeSeconds
                                ?.let { MusicFormat.duration(it * 10_000_000L) }
                                .orEmpty(),
                        playing = currentId == song.id && index == queue.currentIndex,
                        onClick = { queue.onPlay(index) },
                        onLongClick = { queue.onMenu(index, song) },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneIconSquare(
                        glyph = stringResource(R.string.fa_caret_up),
                        label = stringResource(R.string.tally_settings_move_up),
                        enabled = index > 0,
                        frame = androidx.compose.ui.graphics.Color.Transparent,
                        onClick = { queue.onMove(index, MoveDirection.UP) },
                    )
                    PhoneIconSquare(
                        glyph = stringResource(R.string.fa_caret_down),
                        label = stringResource(R.string.tally_settings_move_down),
                        enabled = index < songs.lastIndex,
                        frame = androidx.compose.ui.graphics.Color.Transparent,
                        onClick = { queue.onMove(index, MoveDirection.DOWN) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Upstream's bar visualizer, as the TV draws it: thin square bars, levels in `muted` on `rule` tracks. */
@Composable
private fun PhoneVisualizer(
    data: IntArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
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
