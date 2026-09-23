package io.github.scdouglas1999.tally.media.music

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import io.github.scdouglas1999.tally.media.series.wholePx
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** Height of a [TrackRow]. */
val TrackRowHeight = 44.dp

/**
 * One line of a music rundown (an album's tracks, an artist's top songs, the queue): the mono track [number]
 * (`01`), the [title] in Sans Medium 17sp, the [artist] in `muted` after it when there is one, and the [duration]
 * in mono at the right end. The [playing] track shows a lit lamp in place of its number; a [queued] one a small
 * `muted` square before it. Idle rows have no fill; the focused row is `groundRaised` with a 3dp accent border inside
 * its bounds (so no list clip can cut it). MENU or a long press calls [onLongClick]; the play key calls [onClick].
 */
@Composable
fun TrackRow(
    number: String,
    title: String,
    artist: String?,
    duration: String,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    queued: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .fillMaxWidth()
                .height(TrackRowHeight)
                .onPreviewKeyEvent { event ->
                    when {
                        isPlayKeyUp(event) -> {
                            onClick()
                            true
                        }

                        event.key == Key.Menu -> {
                            if (event.type == KeyEventType.KeyUp) onLongClick()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.drawWithContent {
                    drawContent()
                    if (focused) {
                        val stroke = wholePx(TallyDimens.focusBorder.toPx())
                        val inset = stroke / 2f
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                },
    ) {
        // tv-material3 Surface lays content out top-start: fill the row so everything sits on its center line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = RowInset),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                modifier =
                    Modifier
                        .width(NumberWidth)
                        .fillMaxHeight(),
            ) {
                if (playing) {
                    // Composed only while playing, so it starts lit: a steady lamp, no switch-on animation.
                    TallyLamp(state = LampState.Lit, size = LampSize, glow = false)
                } else {
                    if (queued) IndicatorSquare(color = TallyColors.muted, size = 6.dp)
                    Text(
                        text = number,
                        style = NumberStyle,
                        color = TallyColors.muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = TitleStyle,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!artist.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = artist,
                        style = ArtistStyle,
                        color = TallyColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The title comes first: the artist takes its own width up to a limit, the title the rest.
                        modifier = Modifier.widthIn(max = ArtistMaxWidth),
                    )
                }
            }
            if (duration.isNotBlank()) {
                Spacer(Modifier.width(16.dp))
                Box(contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = duration,
                        style = NumberStyle,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private val RowInset = 12.dp
private val NumberWidth = 40.dp
private val LampSize = 10.dp
private val ArtistMaxWidth = 240.dp

private val NumberStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 1.sp,
    )

private val TitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

private val ArtistStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
