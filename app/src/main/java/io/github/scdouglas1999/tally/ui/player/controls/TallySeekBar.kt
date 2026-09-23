package io.github.scdouglas1999.tally.ui.player.controls

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.overlay.IntervalSeekBarImpl
import io.github.scdouglas1999.tally.media.kit.formatEndsAt
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration

/** Letter spacing of the mono times. */
internal val TimeSpacing = 1.sp

private val timeStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = TimeSpacing,
    )

private val TrackHeight = 4.dp
private val ScrubberSize = 12.dp
private val ScrubberFocusedSize = 16.dp
private val TickHeight = 10.dp

/** Height of the focusable seek area: room for the focused scrubber. */
private val SeekAreaHeight = 20.dp

/** Gap between the seek preview and the track. */
private val PreviewGap = 14.dp

/** What the player is doing, polled like upstream's seek bar (every 250 ms). */
internal class PlayerProgress(
    player: Player,
) {
    var position by mutableLongStateOf(player.currentPosition)
    var duration by mutableLongStateOf(player.duration)
    var buffered by mutableLongStateOf(player.bufferedPosition)
    var speed by mutableFloatStateOf(player.playbackParameters.speed)

    fun fraction(ms: Long): Float = if (duration > 0) (ms.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
}

@Composable
internal fun rememberPlayerProgress(player: Player): PlayerProgress {
    val progress = remember(player) { PlayerProgress(player) }
    LaunchedEffect(player) {
        while (isActive) {
            progress.position = player.currentPosition
            progress.duration = player.duration
            progress.buffered = player.bufferedPosition
            progress.speed = player.playbackParameters.speed
            delay(250L)
        }
    }
    return progress
}

/**
 * The Tally seek bar: a 4dp square-ended track (`rule`), buffered in `muted`, played in `text`, 1dp `muted` chapter
 * ticks and a 12dp accent square scrubber (16dp while focused); the times under it in mono. While it is focused the
 * trickplay preview rides above the scrubber. D-pad seeking is upstream's own seek bar ([IntervalSeekBarImpl]),
 * composed invisibly under the drawing so its keys, acceleration and debounce stay exactly upstream's.
 */
@Composable
fun TallySeekBar(
    player: Player,
    controllerViewState: ControllerViewState,
    chapters: List<Chapter>,
    seekEnabled: Boolean,
    seekBack: Duration,
    seekForward: Duration,
    onSeekProgress: (Long) -> Unit,
    interactionSource: MutableInteractionSource,
    trickplayInfo: org.jellyfin.sdk.model.api.TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    val progress = rememberPlayerProgress(player)
    val focused by interactionSource.collectIsFocusedAsState()
    // Where the viewer has moved the scrubber to; like upstream's, forgotten when the bar loses focus.
    var target by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(focused) {
        if (!focused) target = null
    }
    val shownMs = target ?: progress.position
    val chapterName =
        remember(chapters, shownMs) {
            PlayerFormat
                .chapterAt(chapters.map { it.position.inWholeMilliseconds }, shownMs)
                ?.let { chapters[it].name }
        }
    Column(modifier = modifier) {
        if (focused && controllerViewState.controlsVisible) {
            PreviewAbove(fraction = progress.fraction(shownMs), gap = PreviewGap) {
                TallyTrickplayPreview(
                    positionMs = shownMs,
                    trickplayInfo = trickplayInfo,
                    trickplayUrlFor = trickplayUrlFor,
                    chapterName = chapterName,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(SeekAreaHeight),
        ) {
            IntervalSeekBarImpl(
                progress = progress.fraction(progress.position),
                durationMs = progress.duration,
                bufferedProgress = progress.fraction(progress.buffered),
                onSeek = {
                    target = it
                    onSeekProgress(it)
                },
                controllerViewState = controllerViewState,
                seekBack = seekBack,
                seekForward = seekForward,
                interactionSource = interactionSource,
                enabled = seekEnabled,
                modifier = Modifier.fillMaxWidth().alpha(0f),
            )
            TrackCanvas(
                progress = progress.fraction(shownMs),
                buffered = progress.fraction(progress.buffered),
                ticks = chapters.map { progress.fraction(it.position.inWholeMilliseconds) },
                scrubber = if (focused) ScrubberFocusedSize else ScrubberSize,
                modifier = Modifier.fillMaxWidth().height(SeekAreaHeight),
            )
        }
        Times(
            positionMs = progress.position,
            durationMs = progress.duration,
            speed = progress.speed,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The live variant of the bar: no track, the lamp lit and `LIVE` in accent at the left, [name] (the game or
 * channel) at the right.
 */
@Composable
fun TallyLiveBar(
    name: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().height(SeekAreaHeight + 6.dp + 20.dp),
    ) {
        TallyLamp(state = LampState.Lit, size = 12.dp, glow = false)
        Text(
            text = stringResource(R.string.tally_player_kicker_live).tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.accent,
            maxLines = 1,
            modifier = Modifier.offset(y = (-1).dp),
        )
        Spacer(Modifier.weight(1f))
        if (!name.isNullOrBlank()) {
            Text(
                text = name.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = (-1).dp),
            )
        }
    }
}

/**
 * Seeking with the D-pad while the controls are hidden (upstream's `DpadSeekOverlay`): only the preview, the bar and
 * the times, on the same bottom band as the controls, no buttons.
 */
@Composable
fun TallyDpadSeekOverlay(
    player: Player,
    seekPositionMs: Long,
    trickplayInfo: org.jellyfin.sdk.model.api.TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    chapters: List<Chapter>,
    modifier: Modifier = Modifier,
) {
    io.github.scdouglas1999.tally.ui.theme.TallyScale {
        val progress = rememberPlayerProgress(player)
        val chapterName =
            remember(chapters, seekPositionMs) {
                PlayerFormat
                    .chapterAt(chapters.map { it.position.inWholeMilliseconds }, seekPositionMs)
                    ?.let { chapters[it].name }
            }
        BottomBand(modifier = modifier) {
            Column(Modifier.fillMaxWidth()) {
                PreviewAbove(fraction = progress.fraction(seekPositionMs), gap = PreviewGap) {
                    TallyTrickplayPreview(
                        positionMs = seekPositionMs,
                        trickplayInfo = trickplayInfo,
                        trickplayUrlFor = trickplayUrlFor,
                        chapterName = chapterName,
                    )
                }
                TrackCanvas(
                    progress = progress.fraction(seekPositionMs),
                    buffered = progress.fraction(progress.buffered),
                    ticks = chapters.map { progress.fraction(it.position.inWholeMilliseconds) },
                    scrubber = ScrubberSize,
                    modifier = Modifier.fillMaxWidth().height(SeekAreaHeight),
                )
                Times(
                    positionMs = seekPositionMs,
                    durationMs = progress.duration,
                    speed = progress.speed,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * D-pad seeking in upstream's default "Minimal" mode while the controls are hidden: only the 4dp track (played in
 * `text`) with the accent scrubber square at the target, and the target time in mono on a black label bar riding
 * above the scrubber, at the bottom of the picture. No spinner, no buttons, no preview. [skippedMs] is upstream's
 * running skip amount: every press changes it and restarts the linger; after [MINIMAL_LINGER_MS] without a press
 * [onFinish] clears it (upstream's spinner did that before).
 */
@Composable
fun TallyDpadSeekMinimal(
    player: Player,
    seekPositionMs: Long,
    skippedMs: Long,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(skippedMs) {
        delay(MINIMAL_LINGER_MS)
        onFinish()
    }
    io.github.scdouglas1999.tally.ui.theme.TallyScale {
        val progress = rememberPlayerProgress(player)
        val fraction = progress.fraction(seekPositionMs)
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = TallyDimens.marginHorizontal)
                    .padding(bottom = TallyDimens.marginVertical),
        ) {
            PreviewAbove(fraction = fraction, gap = 4.dp) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .height(26.dp)
                            .background(TallyColors.labelBar)
                            .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = PlayerFormat.clock(seekPositionMs),
                        style = timeStyle,
                        color = TallyColors.text,
                        maxLines = 1,
                    )
                }
            }
            TrackCanvas(
                progress = fraction,
                buffered = 0f,
                ticks = emptyList(),
                scrubber = ScrubberSize,
                modifier = Modifier.fillMaxWidth().height(ScrubberSize),
            )
        }
    }
}

/** How long the minimal seek bar stays after the last press (as long as upstream's spinner turned). */
internal const val MINIMAL_LINGER_MS = 800L

/** Elapsed at the left; time left and the end time at the right (`-34:28 · ENDS 9:41 PM`). */
@Composable
private fun Times(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val known = durationMs > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(20.dp),
    ) {
        Text(
            text = PlayerFormat.clock(positionMs),
            style = timeStyle,
            color = TallyColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (known) {
            val ends =
                formatEndsAt(
                    now = Instant.now(),
                    remainingMs = PlayerFormat.remainingRealMs(positionMs, durationMs, speed),
                    zone = ZoneId.systemDefault(),
                    is24h = DateFormat.is24HourFormat(context),
                )
            Text(
                text = PlayerFormat.remaining(positionMs, durationMs) + " · " + ends,
                style = timeStyle,
                color = TallyColors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The track, buffered and played spans, chapter ticks and the square scrubber. The scrubber stays inside the track: at
 * 0% its left edge is the track's start, at 100% its right edge is the track's end, and the played span ends under its
 * center.
 */
@Composable
internal fun TrackCanvas(
    progress: Float,
    buffered: Float,
    ticks: List<Float>,
    scrubber: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val track = TrackHeight.toPx()
        val top = (size.height - track) / 2f
        val square = scrubber.toPx()
        val center = scrubberCenter(progress, size.width, square)
        drawRect(TallyColors.rule, topLeft = Offset(0f, top), size = Size(size.width, track))
        drawRect(TallyColors.muted, topLeft = Offset(0f, top), size = Size(size.width * buffered, track))
        if (progress > 0f) {
            drawRect(TallyColors.text, topLeft = Offset(0f, top), size = Size(center, track))
        }
        val tick = TickHeight.toPx()
        val tickWidth = 1.dp.toPx()
        ticks.filter { it > 0f && it < 1f }.forEach { at ->
            drawRect(
                TallyColors.muted,
                topLeft = Offset(size.width * at - tickWidth / 2f, (size.height - tick) / 2f),
                size = Size(tickWidth, tick),
            )
        }
        drawRect(
            TallyColors.accent,
            topLeft = Offset(center - square / 2f, (size.height - square) / 2f),
            size = Size(square, square),
        )
    }
}

/** Center of a [square] scrubber at [progress] on a track [width] wide, kept whole inside the track. */
internal fun scrubberCenter(
    progress: Float,
    width: Float,
    square: Float,
): Float = square / 2f + (width - square).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)

/**
 * Places [content] above this point, centered on [fraction] of the available width and kept inside it, [gap] above
 * the next item. Takes no height itself, so the band does not grow while seeking.
 */
@Composable
internal fun PreviewAbove(
    fraction: Float,
    gap: Dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
        val width = constraints.maxWidth
        layout(width, 0) {
            val x = ((width * fraction).toInt() - placeable.width / 2).coerceIn(0, (width - placeable.width).coerceAtLeast(0))
            placeable.place(x, -placeable.height - gap.roundToPx())
        }
    }
}
