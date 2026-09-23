package io.github.scdouglas1999.tally.ui.components

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
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

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
                .background(TallyColors.labelBar)
                .drawBehind {
                    val strokeWidth = TallyDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, strokeWidth / 2f),
                        end = Offset(size.width, strokeWidth / 2f),
                        strokeWidth = strokeWidth,
                    )
                }.padding(horizontal = 10.dp),
    ) {
        IndicatorSquare(color = if (live) TallyColors.live else TallyColors.ruleStrong)
        Text(
            text = text.uppercase(),
            style = TallyType.label,
            color = TallyColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing.uppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun LabelBarPreview() {
    TallySurface {
        LabelBar(
            text = "Indianapolis Colts Kansas City Chiefs",
            live = true,
            trailing = "NBC",
        )
    }
}
