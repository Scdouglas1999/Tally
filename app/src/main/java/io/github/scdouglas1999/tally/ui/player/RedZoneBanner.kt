package io.github.scdouglas1999.tally.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyRedZone
import io.github.scdouglas1999.tally.ui.components.LowerThird
import io.github.scdouglas1999.tally.ui.components.redZoneMatchup
import io.github.scdouglas1999.tally.ui.components.redZoneReasonRes
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType

private val matchupText =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    )

/**
 * While the RedZone channel plays: ON REDZONE NOW, the game it is on ("Chiefs at Bills") and why (RED ZONE, SCORE, …),
 * as a lower third in the event banner's black panel with a `ruleStrong` hairline (an event's is live red). It comes in
 * when RedZone cuts to another game and when the viewer touches the remote, like the score bug; [visible] = false
 * closes it. Nothing while [status] is null or inactive (the slate, or an older server).
 */
@Composable
fun RedZoneBanner(
    status: TallyRedZone?,
    game: TallyGame?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (status == null || !status.active) return
    val matchup = redZoneMatchup(status, game)
    if (matchup.isBlank()) return
    val phone = LocalTallyFormFactor.current == TallyFormFactor.PHONE
    val label = if (phone) PhoneType.label else TallyType.label
    LowerThird(visible = visible, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = if (phone) 320.dp else 560.dp)
                    .border(if (phone) PhoneDimens.hairline else TallyDimens.hairline, TallyColors.ruleStrong)
                    .background(TallyColors.labelBar)
                    .padding(horizontal = if (phone) 12.dp else 14.dp, vertical = if (phone) 8.dp else 10.dp),
        ) {
            Text(
                text = stringResource(R.string.tally_23_redzone_on_redzone_now).tallyUppercase(),
                style = label,
                color = TallyColors.accent,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = matchup,
                style = if (phone) PhoneType.meta.copy(fontSize = 14.sp, lineHeight = 19.sp) else matchupText,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            redZoneReasonRes(status.reason)?.let { reason ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(reason).tallyUppercase(),
                    style = label,
                    color = TallyColors.liveText,
                    maxLines = 1,
                )
            }
        }
    }
}
