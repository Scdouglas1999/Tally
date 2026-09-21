package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.ui.PreviewTvSpec

/**
 * A plain filled square used as a live/selected/possession indicator.
 */
@Composable
fun IndicatorSquare(
    color: Color,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .background(color),
    )
}

@PreviewTvSpec
@Composable
private fun IndicatorSquarePreview() {
    JtvSurface {
        IndicatorSquare(color = JtvColors.live)
    }
}
