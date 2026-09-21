package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

/**
 * Header above a row of game cards, e.g. "NFL / LIVE" with a muted count.
 */
@Composable
fun RowHeader(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Text(
            text = title.uppercase(),
            style = JtvType.labelLarge,
            color = JtvColors.text,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = JtvType.labelLarge,
                color = JtvColors.muted,
                maxLines = 1,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun RowHeaderPreview() {
    JtvSurface {
        RowHeader(title = "NFL / LIVE", count = 4)
    }
}
