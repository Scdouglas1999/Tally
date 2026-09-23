package io.github.scdouglas1999.tally.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.formatSleepClock
import io.github.scdouglas1999.tally.sleepChipUrgent
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val SLEEP_MINUTES = intArrayOf(15, 30, 45, 60, 90)

/** Ignore the key-up that opened the dialog so it does not activate the focused row. */
private const val OPEN_GRACE_MS = 400L

/**
 * Pick 15 / 30 / 45 / 60 / 90 minutes or "when this ends". A running timer adds
 * "Cancel (23:10 left)" as the first row. Takes focus on open, keeps the D-pad inside,
 * and closes on BACK.
 */
@Composable
fun SleepTimerDialog(
    remaining: Duration?,
    onStart: (Duration) -> Unit,
    onStartUntilEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Firing (or cancel from outside) clears [remaining]. Close so the dialog does not
    // stay up over whatever screen the timer just left.
    var sawTimer by remember { mutableStateOf(remaining != null) }
    LaunchedEffect(remaining) {
        if (remaining != null) {
            sawTimer = true
        } else if (sawTimer) {
            onDismiss()
        }
    }
    // The key-up that opened this dialog otherwise lands on the first row and chooses it.
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val rows =
        sleepTimerRows(remaining, onStart, onStartUntilEnd, onCancel, onDismiss).map { choice ->
            SleepChoice(choice.label) {
                if (SystemClock.elapsedRealtime() - openedAt < OPEN_GRACE_MS) return@SleepChoice
                choice.onChoose()
            }
        }
    LaunchedEffect(Unit) { Timber.i("Sleep timer dialog open; remaining=%s", remaining) }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.62f)
        }
        // A dialog window does not inherit the overlay's TallyScale, so the panel was laid out at the
        // raw TV density and ran off the bottom of the screen. Scale it here, same as every other
        // Tally surface.
        TallyScale {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                SleepTimerPanel(rows = rows, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun sleepTimerRows(
    remaining: Duration?,
    onStart: (Duration) -> Unit,
    onStartUntilEnd: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
): List<SleepChoice> {
    val cancelLabel =
        if (remaining == null) {
            null
        } else if (remaining.isInfinite()) {
            stringResource(R.string.tally_sleep_cancel_plain)
        } else {
            stringResource(R.string.tally_sleep_cancel, formatSleepClock(remaining))
        }
    return buildList {
        if (cancelLabel != null) {
            add(SleepChoice(cancelLabel) { onCancel() })
        }
        SLEEP_MINUTES.forEach { minutes ->
            add(
                SleepChoice(stringResource(R.string.tally_sleep_minutes, minutes)) {
                    onStart(minutes.minutes)
                },
            )
        }
        add(SleepChoice(stringResource(R.string.tally_sleep_when_ends)) { onStartUntilEnd() })
    }.map { choice ->
        SleepChoice(choice.label) {
            choice.onChoose()
            onDismiss()
        }
    }
}

@Composable
private fun SleepTimerPanel(
    rows: List<SleepChoice>,
    onDismiss: () -> Unit,
) {
    val requesters = remember(rows.size) { List(rows.size) { FocusRequester() } }
    LaunchedEffect(rows.size) {
        val first = requesters.firstOrNull() ?: return@LaunchedEffect
        repeat(8) {
            if (first.tryRequestFocus("sleep-timer")) return@LaunchedEffect
            delay(40)
        }
    }
    Column(
        modifier =
            Modifier
                .width(600.dp)
                .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                .background(TallyColors.ground, RectangleShape)
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.Back, Key.Escape, Key.ButtonB -> {
                            if (event.type == KeyEventType.KeyUp) onDismiss()
                            true
                        }

                        Key.DirectionLeft, Key.DirectionRight -> {
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.focusGroup(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 22.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.tally_sleep_dialog_title),
                style = TallyType.teamCard.copy(fontSize = 28.sp),
                color = TallyColors.text,
                maxLines = 1,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TallyDimens.hairline)
                    .background(TallyColors.rule),
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val last = rows.lastIndex
                rows.forEachIndexed { index, choice ->
                    TallyRow(
                        label = choice.label,
                        onClick = choice.onChoose,
                        modifier =
                            Modifier
                                .focusRequester(requesters[index])
                                .focusProperties {
                                    start = FocusRequester.Cancel
                                    end = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                    up = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
                                    down = if (index == last) FocusRequester.Cancel else FocusRequester.Default
                                },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(TallyColors.labelBar)
                    .drawBehind {
                        val stroke = TallyDimens.hairline.toPx()
                        drawLine(
                            color = TallyColors.rule,
                            start = Offset(0f, stroke / 2f),
                            end = Offset(size.width, stroke / 2f),
                            strokeWidth = stroke,
                        )
                    }.padding(horizontal = 14.dp),
        ) {
            KeyHint(
                key = stringResource(R.string.tally_sleep_key_back),
                label = stringResource(R.string.tally_sleep_close),
            )
        }
    }
}

private data class SleepChoice(
    val label: String,
    val onChoose: () -> Unit,
)

/** Small mono chip ("SLEEP 23:10") drawn over the picture while a timer is running. */
@Composable
fun SleepTimerChip(
    remaining: Duration,
    modifier: Modifier = Modifier,
) {
    val urgent = sleepChipUrgent(remaining)
    val label =
        if (remaining.isInfinite()) {
            stringResource(R.string.tally_sleep_chip_end)
        } else {
            stringResource(R.string.tally_sleep_chip, formatSleepClock(remaining))
        }
    val background = if (urgent) TallyColors.accent else TallyColors.labelBar
    val content = if (urgent) TallyColors.onAccent else TallyColors.text
    val border = if (urgent) TallyColors.accent else TallyColors.ruleStrong
    Text(
        text = label,
        style = TallyType.label.copy(fontSize = 16.sp, letterSpacing = 1.5.sp),
        color = content,
        maxLines = 1,
        modifier =
            modifier
                .border(TallyDimens.hairline, border, RectangleShape)
                .background(background, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
