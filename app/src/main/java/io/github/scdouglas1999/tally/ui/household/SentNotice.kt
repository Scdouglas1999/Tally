package io.github.scdouglas1999.tally.ui.household

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.ui.components.LowerThird
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay

/** One result of Send to another screen: [sent] or not, to [deviceName]. */
data class SentNotice(
    val id: Long,
    val deviceName: String,
    val sent: Boolean,
)

/**
 * The latest Send result, shown as a lower third by [SentNoticeHost]. The Send dialog posts here;
 * the host lives in the global overlays (and inside the Send dialog while it is open, so a
 * failure is not drawn under the dialog's scrim).
 */
object SentNotices {
    private var nextId = 0L

    var current by mutableStateOf<SentNotice?>(null)
        private set

    fun post(
        deviceName: String,
        sent: Boolean,
    ) {
        current = SentNotice(nextId++, deviceName, sent)
    }

    internal fun clear(notice: SentNotice) {
        if (current == notice) current = null
    }
}

private const val SENT_NOTICE_MS = 3_000L

private val deviceNameStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    )

/** Draws [SentNotices.current] as a lower third for [SENT_NOTICE_MS], then closes it. */
@Composable
fun SentNoticeHost(modifier: Modifier = Modifier) {
    val notice = SentNotices.current ?: return
    key(notice.id) {
        var visible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(SENT_NOTICE_MS)
            visible = false
        }
        LowerThird(
            visible = visible,
            onExited = { SentNotices.clear(notice) },
            modifier = modifier,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .background(TallyColors.ground)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(if (notice.sent) R.string.tally_motion_sent else R.string.tally_motion_not_sent).uppercase(),
                    style = TallyType.label,
                    color = if (notice.sent) TallyColors.accent else TallyColors.live,
                    maxLines = 1,
                )
                Text(
                    text = notice.deviceName,
                    style = deviceNameStyle,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
