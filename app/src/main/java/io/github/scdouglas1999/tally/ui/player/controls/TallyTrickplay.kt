package io.github.scdouglas1999.tally.ui.player.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.TrickplayInfo

/** Height of the trickplay thumbnail (Tally canvas dp). */
private val ThumbHeight = 126.dp

/** Height of the label bar under the thumbnail. */
private val TimeBarHeight = 28.dp

/**
 * The seek preview: the trickplay thumbnail for [positionMs] in a 1dp `ruleStrong` frame (square corners), with the
 * target time in a black mono label bar under it and, inside a chapter, the chapter's title in `muted`. Without
 * trickplay images only the label bar is drawn. The tile arithmetic is upstream's (`SeekPreviewImage`).
 */
@Composable
fun TallyTrickplayPreview(
    positionMs: Long,
    trickplayInfo: TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    chapterName: String?,
    modifier: Modifier = Modifier,
) {
    val info = trickplayInfo?.takeIf { it.width > 0 && it.height > 0 && it.tileWidth > 0 && it.tileHeight > 0 }
    val imageUrl =
        info?.let {
            val tilesPerImage = it.tileWidth * it.tileHeight
            val index = (positionMs / it.interval.coerceAtLeast(1)).toInt() / tilesPerImage
            remember(index, it) { trickplayUrlFor(index) }
        }
    val thumbWidth: Dp? = info?.let { ThumbHeight * (it.width.toFloat() / it.height) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .width(IntrinsicSize.Max)
                .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape),
    ) {
        if (info != null && imageUrl != null && thumbWidth != null) {
            TrickplayTile(
                imageUrl = imageUrl,
                positionMs = positionMs,
                info = info,
                width = thumbWidth,
                modifier = Modifier.padding(TallyDimens.hairline),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .height(TimeBarHeight)
                    .background(TallyColors.labelBar)
                    .padding(horizontal = 10.dp),
        ) {
            Text(
                text = PlayerFormat.clock(positionMs),
                style = TallyType.label.copy(letterSpacing = TimeSpacing),
                color = TallyColors.text,
                maxLines = 1,
                modifier = Modifier.offset(y = (-1).dp),
            )
            if (!chapterName.isNullOrBlank()) {
                Text(
                    text = chapterName,
                    style = TallyType.hint.copy(fontSize = TallyType.label.fontSize),
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun TrickplayTile(
    imageUrl: String,
    positionMs: Long,
    info: TrickplayInfo,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scale = with(LocalDensity.current) { width.toPx() / info.width }
    val model =
        remember(imageUrl) {
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .size(coil3.size.Size.ORIGINAL)
                .build()
        }
    val painter = rememberAsyncImagePainter(model = model, contentScale = ContentScale.None)
    val index = (positionMs.toDouble() / info.interval.coerceAtLeast(1)).toInt()
    val tileIndex = index % (info.tileWidth * info.tileHeight)
    val x = tileIndex % info.tileWidth
    val y = tileIndex / info.tileWidth
    Box(
        modifier =
            modifier
                .size(width, ThumbHeight)
                .background(TallyColors.screen)
                .clipToBounds(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            with(painter) {
                scale(scale, scale, pivot = Offset.Zero) {
                    translate(
                        left = -x.toFloat() * info.width,
                        top = -y.toFloat() * info.height,
                    ) {
                        draw(
                            size =
                                Size(
                                    info.width * info.tileWidth.toFloat(),
                                    info.height * info.tileHeight.toFloat(),
                                ),
                        )
                    }
                }
            }
        }
    }
}
