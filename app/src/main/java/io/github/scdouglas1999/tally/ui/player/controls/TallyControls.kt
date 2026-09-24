package io.github.scdouglas1999.tally.ui.player.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.observeState
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.playback.overlay.OverlayViewState
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackAction
import com.github.damontecres.wholphin.ui.seekBack
import com.github.damontecres.wholphin.ui.seekForward
import com.github.damontecres.wholphin.ui.skipStringRes
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration

/** The Tally player controls replace upstream's while the Tally theme is selected. Used by the seams. */
@Composable
fun tallyPlayerActive(): Boolean = LocalTheme.current == AppThemeColors.TALLY

/** Gap between control buttons. */
private val ButtonGap = 10.dp

/** Size of a control button. */
internal val ControlSize = 40.dp

private val captionStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )

/**
 * A square glyph button in the kit's [io.github.scdouglas1999.tally.media.kit.TallyIconButton] style: 1dp
 * `ruleStrong` border, focused 3dp accent; [primary] is accent-filled with a 3dp `text` border when focused. While
 * focused its [label] shows under it as a muted caption, centered on the button, or lined up with its left or right
 * edge ([captionAlign]) for the first and last button of the row, so the caption stays inside the margins.
 */
@Composable
fun PlayerIconButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onFocused: () -> Unit = {},
    captionAlign: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val fill = if (primary) TallyColors.accent else Color.Transparent
    val content = if (primary) TallyColors.onAccent else TallyColors.text
    val focusColor = if (primary) TallyColors.text else TallyColors.accent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
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
                        Border(
                            border = BorderStroke(TallyDimens.focusBorder, focusColor),
                            shape = RectangleShape,
                        ),
                    pressedBorder =
                        Border(
                            border = BorderStroke(TallyDimens.focusBorder, focusColor),
                            shape = RectangleShape,
                        ),
                ),
            glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
            interactionSource = interactionSource,
            modifier = Modifier.size(ControlSize),
        ) {
            // tv-material3 Surface lays content out top-start: fill it so the glyph is centered.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 17.sp,
                    color = content,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = if (focused) label.tallyUppercase() else "",
            style = captionStyle,
            color = TallyColors.muted,
            maxLines = 1,
            softWrap = false,
            // The caption may be wider than the button; it must not widen the column (the row would move).
            modifier =
                Modifier
                    .padding(top = 5.dp)
                    .height(16.dp)
                    .width(ControlSize)
                    .wrapContentWidth(align = captionAlign, unbounded = true),
        )
    }
}

/**
 * The row under the seek bar: chapters and queue at the left, previous / rewind / play-pause / fast-forward / next in
 * the center, the skip-segment button, subtitles, audio and settings at the right. Actions upstream disables are
 * hidden. The actions are upstream's own.
 */
@OptIn(UnstableApi::class)
@Composable
fun TallyControlsRow(
    player: Player,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    hasChapters: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    currentSegment: MediaSegmentDto?,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onChangeState: (OverlayViewState) -> Unit,
    initialFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    downFocusRequester: FocusRequester? = null,
) {
    val interaction = { controllerViewState.pulseControls() }
    // DOWN from any button goes to the chapter / queue row (upstream's focusable "Chapters" line under its row).
    val down =
        Modifier.focusProperties {
            if (downFocusRequester != null) this.down = downFocusRequester
        }
    LaunchedEffect(controllerViewState.controlsVisible) {
        if (controllerViewState.controlsVisible) {
            initialFocusRequester.tryRequestFocus()
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGap),
            modifier = Modifier.align(Alignment.TopStart).focusGroup(),
        ) {
            if (hasChapters) {
                PlayerIconButton(
                    glyph = stringResource(R.string.tally_player_glyph_chapters),
                    label = stringResource(R.string.tally_player_chapters),
                    onClick = {
                        interaction()
                        onChangeState(OverlayViewState.CHAPTERS)
                    },
                    onFocused = interaction,
                    modifier = down,
                    captionAlign = Alignment.Start,
                )
            }
            if (nextEnabled) {
                PlayerIconButton(
                    glyph = stringResource(R.string.tally_player_glyph_queue),
                    label = stringResource(R.string.tally_player_queue),
                    onClick = {
                        interaction()
                        onChangeState(OverlayViewState.QUEUE)
                    },
                    onFocused = interaction,
                    modifier = down,
                    captionAlign = if (hasChapters) Alignment.CenterHorizontally else Alignment.Start,
                )
            }
        }

        // Transport: always left to right, as upstream's.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGap),
                modifier = Modifier.align(Alignment.TopCenter).focusGroup(),
            ) {
                if (previousEnabled) {
                    PlayerIconButton(
                        glyph = stringResource(R.string.tally_player_glyph_previous),
                        label = stringResource(R.string.tally_player_previous),
                        onClick = {
                            interaction()
                            onPlaybackActionClick(PlaybackAction.Previous)
                        },
                        onFocused = interaction,
                        modifier = down,
                    )
                }
                PlayerIconButton(
                    glyph = stringResource(R.string.tally_player_glyph_rewind),
                    label = stringResource(R.string.tally_player_rewind),
                    onClick = {
                        interaction()
                        player.seekBack(seekBack)
                    },
                    onFocused = interaction,
                    modifier = down,
                )
                PlayerIconButton(
                    glyph =
                        stringResource(
                            if (showPlay) R.string.tally_player_glyph_play else R.string.tally_player_glyph_pause,
                        ),
                    label = stringResource(if (showPlay) R.string.tally_player_play else R.string.tally_player_pause),
                    primary = true,
                    onClick = {
                        interaction()
                        if (showPlay) {
                            player.play()
                            skipBackOnResume?.let { player.seekBack(it) }
                        } else {
                            player.pause()
                        }
                    },
                    onFocused = interaction,
                    modifier = down.focusRequester(initialFocusRequester),
                )
                PlayerIconButton(
                    glyph = stringResource(R.string.tally_player_glyph_fast_forward),
                    label = stringResource(R.string.tally_player_fast_forward),
                    onClick = {
                        interaction()
                        player.seekForward(seekForward)
                    },
                    onFocused = interaction,
                    modifier = down,
                )
                if (nextEnabled) {
                    PlayerIconButton(
                        glyph = stringResource(R.string.tally_player_glyph_next),
                        label = stringResource(R.string.tally_player_next),
                        onClick = {
                            interaction()
                            onPlaybackActionClick(PlaybackAction.Next)
                        },
                        onFocused = interaction,
                        modifier = down,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGap),
            modifier = Modifier.align(Alignment.TopEnd).focusGroup(),
        ) {
            // The live player of a game that is being recorded: WATCH FROM THE START.
            io.github.scdouglas1999.tally.dvr.ui.LocalStartOverAction.current?.let { startOver ->
                TallyButton(
                    label = stringResource(R.string.tally_dvr_from_start_short),
                    glyph = stringResource(R.string.tally_dvr_fa_start_over),
                    onClick = startOver,
                    onFocused = interaction,
                    modifier = down.padding(end = 14.dp),
                )
            }
            currentSegment?.let { segment ->
                TallyButton(
                    label = stringResource(segment.type.skipStringRes),
                    onClick = { player.seekTo(segment.endTicks.ticks.inWholeMilliseconds) },
                    onFocused = interaction,
                    modifier = down.padding(end = 14.dp),
                )
            }
            PlayerIconButton(
                glyph = stringResource(R.string.tally_player_glyph_subtitles),
                label = stringResource(R.string.tally_player_subtitles),
                onClick = {
                    interaction()
                    onClickPlaybackDialogType(PlaybackDialogType.CAPTIONS)
                },
                onFocused = interaction,
                modifier = down,
            )
            PlayerIconButton(
                glyph = stringResource(R.string.tally_player_glyph_audio),
                label = stringResource(R.string.tally_player_audio),
                onClick = {
                    interaction()
                    onClickPlaybackDialogType(PlaybackDialogType.AUDIO)
                },
                onFocused = interaction,
                modifier = down,
            )
            PlayerIconButton(
                glyph = stringResource(R.string.tally_player_glyph_settings),
                label = stringResource(R.string.tally_player_settings),
                onClick = {
                    interaction()
                    onClickPlaybackDialogType(PlaybackDialogType.SETTINGS)
                },
                onFocused = interaction,
                modifier = down,
                captionAlign = Alignment.End,
            )
        }
    }
}

/**
 * While the player is paused and the controls are hidden: a small `PAUSED` label bar at the top center. Nothing is
 * drawn over the middle of the picture. Called by the PlaybackPage seam in place of upstream's pause icon.
 */
@OptIn(UnstableApi::class)
@Composable
fun TallyPausedLabel(
    player: Player,
    modifier: Modifier = Modifier,
) {
    var paused by remember(player) { mutableStateOf(player.isPausedForLabel()) }
    LaunchedEffect(player) {
        player
            .observeState(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            ) { paused = player.isPausedForLabel() }
            .observe()
    }
    if (!paused) return
    TallyScale {
        Box(modifier = modifier.padding(top = TallyDimens.marginVertical)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .height(30.dp)
                        .background(TallyColors.labelBar)
                        .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.tally_player_paused).tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    // Plex Mono capitals sit low in their line box: lift them to the optical center.
                    modifier = Modifier.offset(y = (-1).dp),
                )
            }
        }
    }
}

/** Paused, not stopped: the player could play if asked (same test as upstream's pause indicator). */
private fun Player.isPausedForLabel(): Boolean =
    !playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
