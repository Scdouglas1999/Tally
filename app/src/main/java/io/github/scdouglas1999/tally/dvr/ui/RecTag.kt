package io.github.scdouglas1999.tally.dvr.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGameRecording
import io.github.scdouglas1999.tally.dvr.DvrState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * A game card's recording tag: `■ REC` in `live` red, framed in red, while the game is being recorded; `REC`
 * outlined in `ruleStrong` while it is scheduled or waiting for a stream. Nothing otherwise.
 */
@Composable
fun RecTag(
    recording: TallyGameRecording?,
    style: TextStyle,
    dot: Dp,
    modifier: Modifier = Modifier,
) {
    val state = recording?.state ?: return
    val live = state == DvrState.RECORDING
    if (!live && state != DvrState.SCHEDULED && state != DvrState.WAITING) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .border(1.dp, if (live) TallyColors.live else TallyColors.ruleStrong)
                .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        if (live) IndicatorSquare(color = TallyColors.live, size = dot)
        Text(
            text = stringResource(R.string.tally_dvr_rec_tag).tallyUppercase(),
            style = style,
            color = if (live) TallyColors.liveText else TallyColors.textSecondary,
            maxLines = 1,
        )
    }
}
