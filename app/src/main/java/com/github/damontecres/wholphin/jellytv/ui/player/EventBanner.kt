package com.github.damontecres.wholphin.jellytv.ui.player

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
import com.github.damontecres.wholphin.jellytv.api.JtvEvent
import com.github.damontecres.wholphin.jellytv.api.JtvWatch
import com.github.damontecres.wholphin.jellytv.ui.components.LowerThird
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

private val bannerText =
    TextStyle(
        fontFamily = JtvType.Mono,
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
    event: JtvEvent,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    LowerThird(visible = visible, modifier = modifier) {
        EventBannerPanel(event)
    }
}

@Composable
private fun EventBannerPanel(event: JtvEvent) {
    Column(
        modifier =
            Modifier
                .widthIn(max = 560.dp)
                .border(JtvDimens.hairline, JtvColors.live, RectangleShape)
                .background(JtvColors.labelBar)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (event.title.isNotBlank()) {
            Text(
                text = event.title.uppercase(),
                style = JtvType.label,
                color = JtvColors.liveText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (event.text.isNotBlank()) {
            Text(
                text = event.text,
                style = bannerText,
                color = JtvColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = stringResource(R.string.jtv_player_press_down_for_games),
            style = JtvType.hint,
            color = JtvColors.muted,
            maxLines = 1,
        )
    }
}

@PreviewTvSpec
@Composable
private fun EventBannerPreview() {
    JtvSurface {
        EventBanner(
            event =
                JtvEvent(
                    id = 42,
                    source = "scores",
                    kind = "score",
                    gameId = "401817017",
                    title = "Touchdown",
                    text = "PHI 24 – DAL 17 · Hurts 12 yd pass to Brown",
                    watch =
                        JtvWatch(
                            channelId = "bb22cc33dd44ee55",
                            channelName = "FOX",
                        ),
                ),
        )
    }
}
