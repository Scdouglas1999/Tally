@file:OptIn(markerClass = [UnstableApi::class])

package io.github.scdouglas1999.tally.dvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.dvr.DvrViewModel
import io.github.scdouglas1999.tally.dvr.ui.phone.PhoneStartOverPage
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.player.controls.BottomBand
import io.github.scdouglas1999.tally.ui.player.controls.PlayerFormat
import io.github.scdouglas1999.tally.ui.player.controls.PlayerIconButton
import io.github.scdouglas1999.tally.ui.player.controls.TrackCanvas
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Back and forward by these on a TV (the D-pad with the controls hidden, and the buttons). */
internal const val START_OVER_BACK_MS = 10_000L
internal const val START_OVER_FORWARD_MS = 30_000L

/** Closer than this to the newest segment counts as live. */
private const val LIVE_EDGE_MS = 30_000L
private const val CONTROLS_MS = 5_000L
private const val START_SLACK_MS = 5_000L
private const val FADE_MS = 150

/**
 * WATCH FROM THE START: a game that is being recorded, played from its first minute, from the server's start-over
 * playlist (an HLS EVENT playlist that keeps growing while the game is recorded). Played like a channel, but seekable:
 * the seek bar spans what has been recorded so far and LIVE jumps to the newest minute. No score bug, no event
 * banners: this is a recording. Its own player, so nothing is reported to Jellyfin.
 */
@Composable
fun StartOverPage(
    destination: Destination.TallyStartOver,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<DvrViewModel>()
    val url = remember(destination.path) { viewModel.absoluteUrl(destination.path) }
    val playback = rememberStartOverPlayback(url)
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhoneStartOverPage(playback = playback, title = destination.title, modifier = modifier)
    } else {
        TvStartOverPage(playback = playback, title = destination.title, modifier = modifier)
    }
}

/** The start-over player and what the controls read from it (polled like the other players' seek bars). */
class StartOverPlayback internal constructor(
    val player: ExoPlayer?,
) {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var firstFrame by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)

    fun fraction(ms: Long): Float = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f

    /** At the newest minute (or there is nothing newer to go to). */
    val atLive: Boolean get() = duration <= 0 || duration - position < LIVE_EDGE_MS

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, (p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE))
        p.seekTo(target)
        position = target
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        position = ms
    }

    fun jumpToLive() {
        val p = player ?: return
        p.seekToDefaultPosition()
        p.play()
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
    }
}

/** An ExoPlayer on [url] from its first segment, released with the page, paused while the app is in the background. */
@Composable
fun rememberStartOverPlayback(url: String?): StartOverPlayback {
    val context = LocalContext.current
    val playback =
        remember(url) {
            StartOverPlayback(
                url?.let {
                    ExoPlayer.Builder(context.applicationContext).build().apply {
                        setMediaItem(
                            MediaItem
                                .Builder()
                                .setUri(it)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                // A recording plays at its own pace: no speeding up to catch the live edge.
                                .setLiveConfiguration(
                                    MediaItem.LiveConfiguration
                                        .Builder()
                                        .setMinPlaybackSpeed(1f)
                                        .setMaxPlaybackSpeed(1f)
                                        .build(),
                                ).build(),
                            0L,
                        )
                        prepare()
                        playWhenReady = true
                    }
                },
            ).also { if (url == null) it.failed = true }
        }
    DisposableEffect(playback) {
        val player = playback.player
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    playback.firstFrame = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    playback.failed = true
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playback.playing = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    playback.ended = playbackState == Player.STATE_ENDED
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        playback.aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
            }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(playback) {
        val player = playback.player ?: return@LaunchedEffect
        var fromTheStart = false
        while (isActive) {
            // The first real timeline of a live playlist puts the player at the live edge: start at the first minute.
            if (!fromTheStart && player.playbackState == Player.STATE_READY && !player.currentTimeline.isEmpty) {
                fromTheStart = true
                if (player.currentPosition > START_SLACK_MS) player.seekTo(0L)
            }
            playback.playing = player.isPlaying
            playback.position = player.currentPosition
            playback.duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            playback.buffered = player.bufferedPosition
            delay(250L)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.player?.pause() }
    return playback
}

/** The picture, letterboxed to the stream's shape, under a lamp card until the first frame. */
@Composable
fun BoxScope.StartOverPicture(
    playback: StartOverPlayback,
    title: String,
) {
    val player = playback.player
    if (player != null) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.align(Alignment.Center).aspectRatio(playback.aspect),
        )
    }
    if (!playback.firstFrame && !playback.failed) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center),
        ) {
            TallyLamp(state = LampState.Sputtering, size = 20.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tally_lamp_tuning_in),
                style = TallyType.label,
                color = TallyColors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = title, style = tuneInTitle, color = TallyColors.text, maxLines = 1)
        }
    }
    if (playback.failed) {
        Text(
            text = stringResource(R.string.tally_dvr_start_over_failed),
            style = tuneInTitle,
            color = TallyColors.textSecondary,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
        )
    }
}

/** The kicker over the title: `■ REC · FROM THE START`. */
@Composable
fun StartOverKicker(style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IndicatorSquare(color = TallyColors.live, size = 8.dp)
        Text(
            text = stringResource(R.string.tally_dvr_start_over_kicker).tallyUppercase(),
            style = style,
            color = TallyColors.liveText,
            maxLines = 1,
        )
    }
}

/** "-12:30 behind live", or LIVE at the newest minute. */
@Composable
fun behindLiveText(playback: StartOverPlayback): String =
    if (playback.atLive) {
        stringResource(R.string.tally_dvr_live)
    } else {
        stringResource(R.string.tally_dvr_behind_live, PlayerFormat.clock(playback.duration - playback.position))
    }

/**
 * The TV layout: the picture full screen; any key shows the controls (hidden again after a few seconds of
 * playing): kicker and title at the top, and in the bottom band the seek bar over the recorded span with the
 * position and how far behind live, then back 10 s / play-pause / forward 30 s and LIVE. With the controls hidden,
 * LEFT and RIGHT seek; BACK hides the controls first.
 */
@Composable
private fun TvStartOverPage(
    playback: StartOverPlayback,
    title: String,
    modifier: Modifier,
) {
    var controls by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pageFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(controls, lastKeyAt, playback.playing) {
        if (controls && playback.playing) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(controls) {
        if (controls) playFocus.tryRequestFocus("tally-start-over") else pageFocus.tryRequestFocus("tally-start-over-page")
    }
    BackHandler(enabled = controls) { controls = false }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) lastKeyAt = System.currentTimeMillis()
                    if (controls) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (event.type == KeyEventType.KeyDown) playback.seekBy(-START_OVER_BACK_MS)
                            if (event.type == KeyEventType.KeyUp) controls = true
                            true
                        }

                        Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) playback.seekBy(START_OVER_FORWARD_MS)
                            if (event.type == KeyEventType.KeyUp) controls = true
                            true
                        }

                        Key.Back, Key.Escape -> {
                            false
                        }

                        else -> {
                            // Any other key only brings the controls up (its key-up must not press a button).
                            if (event.type == KeyEventType.KeyUp) controls = true
                            true
                        }
                    }
                }.focusRequester(pageFocus)
                .focusable(),
    ) {
        StartOverPicture(playback, title)
        TallyScale {
            Box(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = controls,
                    enter = fadeIn(tween(FADE_MS)),
                    exit = fadeOut(tween(FADE_MS)),
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                                .padding(horizontal = TallyDimens.marginHorizontal)
                                .padding(top = TallyDimens.marginVertical, bottom = 36.dp),
                    ) {
                        StartOverKicker(TallyType.label)
                        Text(text = title, style = titleStyle, color = TallyColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                AnimatedVisibility(
                    visible = controls,
                    enter = fadeIn(tween(FADE_MS)),
                    exit = fadeOut(tween(FADE_MS)),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                ) {
                    BottomBand {
                        Column(Modifier.fillMaxWidth()) {
                            TrackCanvas(
                                progress = playback.fraction(playback.position),
                                buffered = playback.fraction(playback.buffered),
                                ticks = emptyList(),
                                scrubber = 12.dp,
                                modifier = Modifier.fillMaxWidth().height(20.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(20.dp),
                            ) {
                                Text(
                                    text = PlayerFormat.clock(playback.position),
                                    style = timeStyle,
                                    color = TallyColors.text,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = behindLiveText(playback).tallyUppercase(),
                                    style = timeStyle,
                                    color = if (playback.atLive) TallyColors.accent else TallyColors.textSecondary,
                                    maxLines = 1,
                                )
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.align(Alignment.TopCenter),
                                ) {
                                    PlayerIconButton(
                                        glyph = stringResource(R.string.tally_player_glyph_rewind),
                                        label = stringResource(R.string.tally_dvr_back_10),
                                        onClick = { playback.seekBy(-START_OVER_BACK_MS) },
                                    )
                                    PlayerIconButton(
                                        glyph = stringResource(playGlyph(playback.playing)),
                                        label =
                                            stringResource(
                                                if (playback.playing) R.string.tally_player_pause else R.string.tally_player_play,
                                            ),
                                        primary = true,
                                        onClick = playback::togglePlay,
                                        modifier = Modifier.focusRequester(playFocus),
                                    )
                                    PlayerIconButton(
                                        glyph = stringResource(R.string.tally_player_glyph_fast_forward),
                                        label = stringResource(R.string.tally_dvr_forward_30),
                                        onClick = { playback.seekBy(START_OVER_FORWARD_MS) },
                                    )
                                }
                                if (!playback.atLive) {
                                    TallyButton(
                                        label = stringResource(R.string.tally_dvr_live),
                                        onClick = playback::jumpToLive,
                                        modifier = Modifier.align(Alignment.TopEnd),
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

private fun playGlyph(playing: Boolean): Int = if (playing) R.string.tally_player_glyph_pause else R.string.tally_player_glyph_play

private val titleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    )

private val tuneInTitle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    )

private val timeStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 1.sp,
    )
