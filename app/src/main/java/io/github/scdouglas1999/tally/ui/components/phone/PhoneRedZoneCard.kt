package io.github.scdouglas1999.tally.ui.components.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.data.RedZoneTile
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.redZoneReasonRes
import io.github.scdouglas1999.tally.ui.components.redZoneTitle
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * The RedZone tile on a phone ([io.github.scdouglas1999.tally.ui.components.RedZoneCard] in a [PhoneGameCard]'s
 * frame): `■ TALLY REDZONE` and the clock, ON NOW and the game, the muted "lighter than multiview" line, and the label
 * bar with why RedZone is on that game. A tap plays the channel full screen.
 */
@Composable
fun PhoneRedZoneCard(
    tile: RedZoneTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .background(TallyColors.ground)
                .phoneClickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(horizontal = 12.dp),
        ) {
            IndicatorSquare(color = TallyColors.live, size = 6.dp)
            Text(
                text = stringResource(R.string.tally_23_redzone).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            tile.game?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail.tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.liveText,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
        ) {
            Text(
                text = redZoneTitle(tile),
                style = PhoneType.headline,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tally_23_redzone_desc),
                style = PhoneType.bodySmall,
                color = TallyColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoneGameFooter(
            text = tile.channel.name.ifBlank { stringResource(R.string.tally_23_redzone) },
            live = true,
            trailing = redZoneReasonRes(tile.status.reason)?.let { stringResource(it) },
        )
    }
}
