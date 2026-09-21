package com.github.damontecres.wholphin.jellytv.ui.components

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
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

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
                    .border(JtvDimens.hairline, JtvColors.ruleStrong)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = key.uppercase(),
                style = JtvType.label,
                color = JtvColors.text,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = JtvType.hint,
            color = JtvColors.textSecondary,
            maxLines = 1,
        )
    }
}

@PreviewTvSpec
@Composable
private fun KeyHintPreview() {
    JtvSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyHint(key = "OK", label = "Watch")
            KeyHint(key = "HOLD", label = "Add to multiview")
        }
    }
}
