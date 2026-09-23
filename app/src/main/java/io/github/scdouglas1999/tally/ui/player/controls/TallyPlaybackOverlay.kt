package io.github.scdouglas1999.tally.ui.player.controls

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.components.HiddenFocusBox
import com.github.damontecres.wholphin.ui.playback.AnalyticsState
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.playback.overlay.OverlayViewState
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackAction
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackDebugOverlay
import com.github.damontecres.wholphin.ui.util.LocalClock
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.time.Duration

/** Opacity of the bottom band's `ground`. */
internal const val BAND_ALPHA = 0.85f

/** Show / hide and state changes of the controls. */
private const val FADE_MS = 150

private val titleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )

private val clockStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 1.sp,
    )

/**
 * The player controls in the Tally look, drawn in place of upstream's `PlaybackOverlay` (same parameters) while the
 * Tally theme is selected. No full-screen gradient: a bottom band on `ground` at 85% with a 1dp `rule` top edge holds
 * the seek bar, times and buttons (or the chapter / queue row); the title block and the clock sit top-left and
 * top-right on the picture over a soft top-only scrim. Upstream's state machine (controller, chapters, queue), key
 * handling, auto-hide and actions are kept as they are.
 */
@Composable
fun TallyPlaybackOverlay(
    item: BaseItem?,
    chapters: List<Chapter>,
    player: Player,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    showClock: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    seekEnabled: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onSeekBarChange: (Long) -> Unit,
    showDebugInfo: Boolean,
    currentPlayback: CurrentPlayback?,
    currentSegment: MediaSegmentDto?,
    analyticsState: AnalyticsState,
    queue: List<PlaylistItem>,
    modifier: Modifier = Modifier,
    trickplayInfo: TrickplayInfo? = null,
    trickplayUrlFor: (Int) -> String? = { null },
    onClickPlaylist: (BaseItem) -> Unit = {},
    seekBarInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    var state by remember(controllerViewState.controlsVisible) {
        mutableStateOf(if (controllerViewState.controlsVisible) OverlayViewState.CONTROLLER else OverlayViewState.HIDDEN)
    }
    val onChangeState = { newState: OverlayViewState -> state = newState }
    val kind = PlayerFormat.kind(item?.data)
    var live by remember(player) { mutableStateOf(player.isCurrentMediaItemLive) }
    LaunchedEffect(player) {
        while (isActive) {
            live = player.isCurrentMediaItemLive
            delay(1000L)
        }
    }
    val isLive = live || kind == PlayerFormat.Kind.LIVE

    Box(modifier = modifier) {
        TallyScale {
            Box(Modifier.fillMaxSize()) {
                // Top: title block and clock on the picture.
                AnimatedVisibility(
                    visible = controllerViewState.controlsVisible && !showDebugInfo,
                    enter = fadeIn(tween(FADE_MS)),
                    exit = fadeOut(tween(FADE_MS)),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    TopBlock(item = item, isLive = isLive, showClock = showClock)
                }

                // Bottom band.
                AnimatedVisibility(
                    visible = state != OverlayViewState.HIDDEN,
                    enter = fadeIn(tween(FADE_MS)),
                    exit = fadeOut(tween(FADE_MS)),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                ) {
                    BottomBand {
                        AnimatedContent(
                            targetState = state,
                            label = "tally controls",
                            transitionSpec = {
                                (fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS)))
                                    .using(SizeTransform(clip = false) { _, _ -> tween(FADE_MS) })
                            },
                        ) { target ->
                            when (target) {
                                OverlayViewState.HIDDEN -> {
                                    Box(Modifier.fillMaxWidth())
                                }

                                OverlayViewState.CONTROLLER -> {
                                    Controller(
                                        player = player,
                                        controllerViewState = controllerViewState,
                                        chapters = chapters,
                                        isLive = isLive,
                                        liveName = item?.name,
                                        showPlay = showPlay,
                                        previousEnabled = previousEnabled,
                                        nextEnabled = nextEnabled,
                                        seekEnabled = seekEnabled,
                                        seekBack = seekBack,
                                        skipBackOnResume = skipBackOnResume,
                                        seekForward = seekForward,
                                        currentSegment = currentSegment,
                                        onPlaybackActionClick = onPlaybackActionClick,
                                        onClickPlaybackDialogType = onClickPlaybackDialogType,
                                        onSeekBarChange = onSeekBarChange,
                                        onChangeState = onChangeState,
                                        trickplayInfo = trickplayInfo,
                                        trickplayUrlFor = trickplayUrlFor,
                                        seekBarInteractionSource = seekBarInteractionSource,
                                    )
                                }

                                OverlayViewState.CHAPTERS -> {
                                    if (chapters.isNotEmpty()) {
                                        TallyChapterRow(
                                            player = player,
                                            controllerViewState = controllerViewState,
                                            chapters = chapters,
                                            hasNext = nextEnabled,
                                            onChangeState = onChangeState,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }

                                OverlayViewState.QUEUE -> {
                                    if (nextEnabled) {
                                        TallyQueueRow(
                                            queue = queue,
                                            controllerViewState = controllerViewState,
                                            nextState =
                                                if (chapters.isNotEmpty()) {
                                                    OverlayViewState.CHAPTERS
                                                } else {
                                                    OverlayViewState.CONTROLLER
                                                },
                                            onChangeState = onChangeState,
                                            onClickPlaylist = onClickPlaylist,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Upstream's debug overlay, as upstream draws it.
                AnimatedVisibility(
                    visible = showDebugInfo && controllerViewState.controlsVisible,
                    enter = fadeIn(tween(FADE_MS)),
                    exit = fadeOut(tween(FADE_MS)),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    val configuration = LocalConfiguration.current
                    PlaybackDebugOverlay(
                        analyticsState = analyticsState,
                        currentPlayback = currentPlayback,
                        modifier =
                            Modifier
                                .heightIn(max = (configuration.screenHeightDp * 0.6f).dp)
                                .padding(start = TallyDimens.marginHorizontal, top = TallyDimens.marginVertical)
                                .background(AppColors.TransparentBlack50)
                                .padding(8.dp)
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        controllerViewState.pulseControls(Long.MAX_VALUE)
                                    } else {
                                        controllerViewState.pulseControls()
                                    }
                                },
                    )
                }
            }
        }
    }
}

/** The bottom band: `ground` at 85% with a 1dp `rule` top edge, content on the page margins. */
@Composable
internal fun BottomBand(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(TallyColors.ground.copy(alpha = BAND_ALPHA))
                .drawBehind {
                    val stroke = TallyDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, stroke / 2f),
                        end = Offset(size.width, stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = 18.dp, bottom = 16.dp),
        content = content,
    )
}

@Composable
private fun Controller(
    player: Player,
    controllerViewState: ControllerViewState,
    chapters: List<Chapter>,
    isLive: Boolean,
    liveName: String?,
    showPlay: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    seekEnabled: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    currentSegment: MediaSegmentDto?,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onSeekBarChange: (Long) -> Unit,
    onChangeState: (OverlayViewState) -> Unit,
    trickplayInfo: TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    seekBarInteractionSource: MutableInteractionSource,
) {
    val playFocus = remember { FocusRequester() }
    val downFocus = remember { FocusRequester() }
    // As upstream: DOWN from the buttons goes to the chapters, or the queue when there are no chapters.
    val nextState =
        when {
            chapters.isNotEmpty() -> OverlayViewState.CHAPTERS
            nextEnabled -> OverlayViewState.QUEUE
            else -> null
        }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            if (isLive) {
                TallyLiveBar(name = liveName)
            } else {
                TallySeekBar(
                    player = player,
                    controllerViewState = controllerViewState,
                    chapters = chapters,
                    seekEnabled = seekEnabled,
                    seekBack = seekBack,
                    seekForward = seekForward,
                    onSeekProgress = onSeekBarChange,
                    interactionSource = seekBarInteractionSource,
                    trickplayInfo = trickplayInfo,
                    trickplayUrlFor = trickplayUrlFor,
                )
            }
            TallyControlsRow(
                player = player,
                controllerViewState = controllerViewState,
                showPlay = showPlay,
                previousEnabled = previousEnabled,
                nextEnabled = nextEnabled,
                hasChapters = chapters.isNotEmpty(),
                seekBack = seekBack,
                skipBackOnResume = skipBackOnResume,
                seekForward = seekForward,
                currentSegment = currentSegment,
                onPlaybackActionClick = onPlaybackActionClick,
                onClickPlaybackDialogType = onClickPlaybackDialogType,
                onChangeState = onChangeState,
                initialFocusRequester = playFocus,
                downFocusRequester = if (nextState != null) downFocus else null,
                modifier = Modifier.padding(top = 14.dp),
            )
            if (nextState != null) {
                HiddenFocusBox(downFocus) { onChangeState(nextState) }
            }
        }
    }
}

/** Kicker, title and meta line at the top left; the clock at the top right; a soft top-only scrim behind them. */
@Composable
private fun TopBlock(
    item: BaseItem?,
    isLive: Boolean,
    showClock: Boolean,
) {
    val dto = item?.data
    val kicker =
        PlayerFormat.kicker(
            dto,
            film = stringResource(R.string.tally_player_kicker_film),
            live = stringResource(R.string.tally_player_kicker_live),
            isLive = isLive,
        )
    val title = PlayerFormat.title(dto)
    val meta = PlayerFormat.meta(dto)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TopScrimHeight)
                .background(
                    Brush.verticalGradient(
                        0f to TallyColors.ground.copy(alpha = 0.72f),
                        0.55f to TallyColors.ground.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = TallyDimens.marginHorizontal, top = TallyDimens.marginVertical)
                    .widthIn(max = 640.dp),
        ) {
            if (kicker != null) {
                Text(
                    text = kicker,
                    style = TallyType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (title != null) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (meta != null) {
                Text(
                    text = meta,
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (showClock) {
            val time by LocalClock.current.timeString
            Text(
                text = time.tallyUppercase(),
                style = clockStyle,
                color = TallyColors.text,
                maxLines = 1,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = TallyDimens.marginHorizontal, top = TallyDimens.marginVertical),
            )
        }
    }
}

private val TopScrimHeight = 170.dp
