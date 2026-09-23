package io.github.scdouglas1999.tally.quality.phone

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.quality.QualityOption
import io.github.scdouglas1999.tally.quality.QualityStatus
import io.github.scdouglas1999.tally.ui.player.controls.phone.PhonePanelRow
import io.github.scdouglas1999.tally.ui.player.controls.phone.PhoneSidePanel

/** Ignore a tap that lands in the same instant the panel opens (as the TV ignores the key-up that opened it). */
private const val OPEN_GRACE_MS = 400L

/**
 * The in-player quality picker on a phone: the TV's ladder in the player's side panel. What is playing now (method,
 * resolution, bitrate) and why it is transcoded head the panel; the rungs follow, the chosen one marked NOW, then
 * "Use as my default on this phone". Choosing does what the TV dialog does ([onChoose]).
 */
@Composable
fun PhoneQualityPanel(
    now: QualityStatus.Playing?,
    options: List<QualityOption>,
    choice: Int?,
    onChoose: (bitsPerSecond: Int?, megabits: Int?, saveAsDefault: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var saveAsDefault by remember { mutableStateOf(false) }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val methodLabel =
        when (now?.method) {
            null -> stringResource(R.string.tally_quality_loading)
            QualityStatus.Method.DIRECT_PLAY -> stringResource(R.string.tally_quality_direct_play)
            QualityStatus.Method.DIRECT_STREAM -> stringResource(R.string.tally_quality_direct_stream)
            QualityStatus.Method.TRANSCODING -> stringResource(R.string.tally_quality_transcoding)
        }
    val nowLine = if (now == null) methodLabel else QualityStatus.formatNowLine(methodLabel, now.resolution, now.bitrateLabel)
    val reasonLine =
        now
            ?.reasons
            ?.map { reason ->
                QualityStatus.reasonRes(reason)?.let { stringResource(it) } ?: QualityStatus.enumWords(reason.name)
            }?.distinct()
            ?.joinToString(", ")
            .orEmpty()
    val nowTag = stringResource(R.string.tally_quality_now)
    val rows =
        buildList {
            options.forEach { option ->
                val selected = option.bitsPerSecond == choice
                add(
                    PhonePanelRow(
                        key = option.bitsPerSecond ?: "original",
                        label = option.label,
                        value = if (selected) nowTag else null,
                        current = selected,
                        onClick = {
                            if (SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS) {
                                onChoose(option.bitsPerSecond, option.megabits, saveAsDefault)
                            }
                        },
                    ),
                )
            }
            add(
                PhonePanelRow(
                    key = "default",
                    label = stringResource(R.string.tally_phone_quality_default),
                    value = stringResource(if (saveAsDefault) R.string.tally_quality_on else R.string.tally_quality_off),
                    current = saveAsDefault,
                    onClick = { saveAsDefault = !saveAsDefault },
                ),
            )
        }
    PhoneSidePanel(
        kicker = stringResource(R.string.tally_quality_kicker),
        rows = rows,
        lines = listOfNotNull(nowLine, reasonLine.ifEmpty { null }),
        onDismiss = onDismiss,
    )
}
