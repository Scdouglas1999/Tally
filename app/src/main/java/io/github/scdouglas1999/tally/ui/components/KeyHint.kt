package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * A bordered mono key cap (e.g. "OK", "HOLD") followed by a label.
 */
@Composable
fun KeyHint(
    key: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .border(TallyDimens.hairline, TallyColors.ruleStrong)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = key.uppercase(),
                style = TallyType.label,
                color = TallyColors.text,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = TallyType.hint,
            color = TallyColors.textSecondary,
            maxLines = 1,
        )
    }
}

@PreviewTvSpec
@Composable
private fun KeyHintPreview() {
    TallySurface {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyHint(key = "OK", label = "Watch")
            KeyHint(key = "HOLD", label = "Add to multiview")
        }
    }
}
