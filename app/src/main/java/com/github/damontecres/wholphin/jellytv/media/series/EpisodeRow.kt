package com.github.damontecres.wholphin.jellytv.media.series

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
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
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp

/** Status at the right end of a rundown row. */
sealed interface EpisodeStatus {
    data object None : EpisodeStatus

    data object NextUp : EpisodeStatus

    data class InProgress(
        val percent: Int,
    ) : EpisodeStatus
}

/**
 * One line of the season rundown: still, number, title/meta/overview, status. Idle rows have no
 * fill; the focused row is [JtvColors.groundRaised] with a 3dp accent border inside its bounds.
 * MENU (or a long press) calls [onLongClick]; the play key calls [onClick].
 */
@Composable
fun EpisodeRow(
    number: String?,
    title: String,
    meta: String,
    focusedMeta: String?,
    overview: String?,
    imageUrl: String?,
    progress: Float?,
    played: Boolean,
    nextUp: Boolean,
    status: EpisodeStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
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
                contentColor = JtvColors.text,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = JtvColors.text,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = JtvColors.text,
            ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .fillMaxWidth()
                .height(EpisodeRowHeight)
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
                        // Inside the row's bounds, so no list clip can cut it.
                        val stroke = wholePx(JtvDimens.focusBorder.toPx())
                        val inset = stroke / 2f
                        drawRect(
                            color = JtvColors.accent,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 7.dp),
        ) {
            EpisodeStill(
                imageUrl = imageUrl,
                title = title,
                progress = progress,
                played = played,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .width(NumberColumnWidth)
                        .fillMaxHeight(),
            ) {
                if (number != null) {
                    Text(
                        text = number,
                        style = numberStyle,
                        color = if (nextUp) JtvColors.accent else JtvColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = JtvColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaLine =
                    if (focused && focusedMeta != null) {
                        listOf(meta, focusedMeta).filter { it.isNotBlank() }.joinToString(" · ")
                    } else {
                        meta
                    }
                if (metaLine.isNotBlank()) {
                    Text(
                        text = metaLine,
                        style = metaStyle,
                        color = JtvColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        style = overviewStyle,
                        color = JtvColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier =
                    Modifier
                        .padding(start = 16.dp, end = 13.dp)
                        .width(StatusWidth),
            ) {
                when (status) {
                    EpisodeStatus.NextUp -> {
                        Text(
                            text = stringResource(R.string.jtv_series_next_up).uppercase(),
                            style = statusStyle,
                            color = JtvColors.accent,
                            maxLines = 1,
                            softWrap = false,
                            modifier =
                                Modifier
                                    .border(JtvDimens.hairline, JtvColors.accent)
                                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 3.dp),
                        )
                    }

                    is EpisodeStatus.InProgress -> {
                        Text(
                            text = "${status.percent}%",
                            style = statusStyle,
                            color = JtvColors.text,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    EpisodeStatus.None -> {}
                }
            }
        }
    }
}

/** 16:9 still with a 1dp rule frame, the progress bar at its foot and a watched tick. */
@Composable
private fun EpisodeStill(
    imageUrl: String?,
    title: String,
    progress: Float?,
    played: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(StillWidth, StillHeight)
                .background(JtvColors.screen)
                .border(JtvDimens.hairline, JtvColors.rule),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                onError = { logCoilError(imageUrl, it.result) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(JtvDimens.hairline),
            )
        }
        val fraction = progress?.coerceIn(0f, 1f)
        if (fraction != null && fraction > 0f) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(JtvColors.ruleStrong),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(JtvColors.accent),
                )
            }
        }
        if (played) {
            WatchedTick(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Black tag: a check and `WATCHED`, mono 11sp muted. */
@Composable
fun WatchedTick(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .background(JtvColors.labelBar)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.fa_check),
            fontFamily = FontAwesome,
            fontSize = 9.sp,
            color = JtvColors.text,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.jtv_series_watched_tag).uppercase(),
            style = tagStyle,
            color = JtvColors.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

val EpisodeRowHeight = 104.dp
private val StillWidth = 160.dp
private val StillHeight = 90.dp
private val NumberColumnWidth = 56.dp
private val StatusWidth = 72.dp

private val numberStyle =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
    )

private val titleStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )

private val metaStyle =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    )

private val overviewStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    )

private val statusStyle =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )

private val tagStyle =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.sp,
    )

/**
 * A stroke width rounded down to whole pixels, so a focus border drawn by hand lands on the pixel
 * grid and reads the same on every side (a 4.8px stroke antialiases unevenly).
 */
internal fun wholePx(px: Float): Float = kotlin.math.floor(px).coerceAtLeast(1f)
