package io.github.scdouglas1999.tally.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.skipStringRes
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.LowerThird
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import org.jellyfin.sdk.model.api.MediaSegmentType

/**
 * Upstream's "ask to skip" prompt (intro, credits, recap, preview) as a [LowerThird]: `SKIP INTRO` in mono, focusable,
 * OK skips. Upstream shows it bottom-right, takes focus and dismisses it after 10 seconds; it never skips on its own
 * (an automatic skip happens at once, with no prompt), so there is no countdown bar. [modifier] carries upstream's
 * focus requester.
 */
@Composable
fun TallySkipSegment(
    type: MediaSegmentType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TallyScale {
        LowerThird(visible = true) {
            Box(
                modifier =
                    Modifier
                        .background(TallyColors.ground.copy(alpha = BAND_ALPHA))
                        .padding(10.dp),
            ) {
                TallyButton(
                    label = stringResource(type.skipStringRes),
                    onClick = onClick,
                    modifier = modifier,
                )
            }
        }
    }
}
