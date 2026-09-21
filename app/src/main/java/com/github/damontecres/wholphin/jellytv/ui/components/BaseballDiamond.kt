package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import kotlin.math.sqrt

/**
 * A baseball base indicator: three 45 degree-rotated squares, second base on top,
 * third on the left, first on the right. Occupied bases are filled accent,
 * empty bases get a 1dp muted outline.
 */
@Composable
fun BaseballDiamond(
    onFirst: Boolean,
    onSecond: Boolean,
    onThird: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension / 3.4f
        val diagonal = side * sqrt(2f)
        val strokeWidth = JtvDimens.hairline.toPx()

        fun drawBase(
            center: Offset,
            occupied: Boolean,
        ) {
            rotate(degrees = 45f, pivot = center) {
                val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
                val square = Size(side, side)
                if (occupied) {
                    drawRect(color = JtvColors.accent, topLeft = topLeft, size = square)
                } else {
                    drawRect(
                        color = JtvColors.muted,
                        topLeft = topLeft,
                        size = square,
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
        }

        drawBase(Offset(this.size.width / 2f, diagonal / 2f), onSecond)
        drawBase(Offset(diagonal / 2f, this.size.height - diagonal / 2f), onThird)
        drawBase(Offset(this.size.width - diagonal / 2f, this.size.height - diagonal / 2f), onFirst)
    }
}

@PreviewTvSpec
@Composable
private fun BaseballDiamondPreview() {
    JtvSurface {
        BaseballDiamond(onFirst = true, onSecond = true, onThird = false)
    }
}
