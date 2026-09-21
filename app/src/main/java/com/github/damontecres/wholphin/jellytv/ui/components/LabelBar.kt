package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

/**
 * Black channel label bar: a 1dp top rule, an indicator square, and the channel name in mono.
 * [trailing] renders right-aligned in accent.
 */
@Composable
fun LabelBar(
    text: String,
    live: Boolean,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(JtvColors.labelBar)
                .drawBehind {
                    val strokeWidth = JtvDimens.hairline.toPx()
                    drawLine(
                        color = JtvColors.rule,
                        start = Offset(0f, strokeWidth / 2f),
                        end = Offset(size.width, strokeWidth / 2f),
                        strokeWidth = strokeWidth,
                    )
                }.padding(horizontal = 10.dp),
    ) {
        IndicatorSquare(color = if (live) JtvColors.live else JtvColors.ruleStrong)
        Text(
            text = text.uppercase(),
            style = JtvType.label,
            color = JtvColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing.uppercase(),
                style = JtvType.label,
                color = JtvColors.accent,
                maxLines = 1,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun LabelBarPreview() {
    JtvSurface {
        LabelBar(
            text = "Indianapolis Colts Kansas City Chiefs",
            live = true,
            trailing = "NBC",
        )
    }
}
