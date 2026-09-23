package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallySurface

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
    TallySurface {
        IndicatorSquare(color = TallyColors.live)
    }
}
