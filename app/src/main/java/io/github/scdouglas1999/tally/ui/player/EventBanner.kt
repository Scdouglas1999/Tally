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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyEvent
import io.github.scdouglas1999.tally.api.TallyWatch
import io.github.scdouglas1999.tally.ui.components.LowerThird
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

private val bannerText =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    )

/**
 * A transient, non-focusable banner for a notable event in another game — top-start of
 * the picture, black with a live-red hairline. Title in small red mono, the event text
 * in 20sp mono, and a muted hint pointing at the switcher.
 *
 * It enters and leaves as a [LowerThird]: composing it enters it, and [visible] = false
 * closes it again.
 */
@Composable
fun EventBanner(
    event: TallyEvent,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    LowerThird(visible = visible, modifier = modifier) {
        EventBannerPanel(event)
    }
}

@Composable
private fun EventBannerPanel(event: TallyEvent) {
    Column(
        modifier =
            Modifier
                .widthIn(max = 560.dp)
                .border(TallyDimens.hairline, TallyColors.live, RectangleShape)
                .background(TallyColors.labelBar)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (event.title.isNotBlank()) {
            Text(
                text = event.title.uppercase(),
                style = TallyType.label,
                color = TallyColors.liveText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (event.text.isNotBlank()) {
            Text(
                text = event.text,
                style = bannerText,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = stringResource(R.string.tally_player_press_down_for_games),
            style = TallyType.hint,
            color = TallyColors.muted,
            maxLines = 1,
        )
    }
}

@PreviewTvSpec
@Composable
private fun EventBannerPreview() {
    TallySurface {
        EventBanner(
            event =
                TallyEvent(
                    id = 42,
                    source = "scores",
                    kind = "score",
                    gameId = "401817017",
                    title = "Touchdown",
                    text = "PHI 24 – DAL 17 · Hurts 12 yd pass to Brown",
                    watch =
                        TallyWatch(
                            channelId = "bb22cc33dd44ee55",
                            channelName = "FOX",
                        ),
                ),
        )
    }
}
