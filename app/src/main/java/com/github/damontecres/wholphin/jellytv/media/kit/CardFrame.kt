package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytv.ui.components.IndicatorSquare
import com.github.damontecres.wholphin.jellytv.ui.components.tallyUppercase
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp

internal val CardTitleStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 16.sp,
    )

internal val CardDetailStyle =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.sp,
    )

internal val CardKickerStyle = CardDetailStyle

/**
 * Shared square frame for poster, landscape and person cards: picture, optional progress,
 * corner tags, and a black label bar. Focus is a 3dp accent border drawn inside the card.
 */
@Composable
fun CardFrame(
    imageUrl: String?,
    width: Dp,
    height: Dp,
    contentDescription: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    progress: Float? = null,
    tag: String? = null,
    tagAccent: Boolean = false,
    favorite: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    tagGlyph: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
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
                containerColor = JtvColors.ground,
                contentColor = JtvColors.text,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = JtvColors.text,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = JtvColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .width(width)
                .onPreviewKeyEvent { event ->
                    if (onPlay != null && isPlayKeyUp(event)) {
                        onPlay()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .width(width)
                        .height(height)
                        .background(JtvColors.screen),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        onError = { logCoilError(imageUrl, it.result) },
                        modifier = Modifier.fillMaxSize(),
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
                if (tag != null && tagGlyph != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .background(JtvColors.labelBar)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = tagGlyph,
                            fontFamily = FontAwesome,
                            fontSize = 9.sp,
                            color = JtvColors.text,
                            maxLines = 1,
                        )
                        Text(
                            text = tag.tallyUppercase(),
                            style = CardDetailStyle,
                            color = if (tagAccent) JtvColors.accent else JtvColors.muted,
                            maxLines = 1,
                        )
                    }
                } else if (tag != null) {
                    Text(
                        text = tag.tallyUppercase(),
                        style = CardDetailStyle,
                        color = if (tagAccent) JtvColors.accent else JtvColors.muted,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .background(JtvColors.labelBar)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (favorite) {
                    IndicatorSquare(
                        color = JtvColors.accent,
                        size = 8.dp,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                    )
                }
            }
            if (label != null) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(JtvColors.labelBar)
                            .drawBehind {
                                val stroke = JtvDimens.hairline.toPx()
                                drawLine(
                                    color = JtvColors.rule,
                                    start = Offset(0f, stroke / 2f),
                                    end = Offset(size.width, stroke / 2f),
                                    strokeWidth = stroke,
                                )
                            }.padding(horizontal = 8.dp),
                ) {
                    label()
                }
            }
        }
    }
}

@Composable
internal fun CardTitleText(
    text: String,
    style: TextStyle = CardTitleStyle,
) {
    Text(
        text = text,
        style = style,
        color = JtvColors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun CardDetailText(text: String) {
    Text(
        text = text.tallyUppercase(),
        style = CardDetailStyle,
        color = JtvColors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
