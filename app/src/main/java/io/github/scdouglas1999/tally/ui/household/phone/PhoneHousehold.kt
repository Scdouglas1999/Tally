package io.github.scdouglas1999.tally.ui.household.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.household.HouseholdSession
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.household.SentNotice
import io.github.scdouglas1999.tally.ui.household.progressFraction
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetTitle
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.delay

/**
 * "Playing in the house" on the phone's home: the TV row's cards as phone landscape cards (the landscape card width)
 * in a phone card row. A tap plays the same item here at the other device's position, as OK does on the TV.
 */
@Composable
fun PhoneHouseholdRow(
    sessions: List<HouseholdSession>,
    onJoin: (HouseholdSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhoneCardRow(
        title = stringResource(R.string.tally_household_row_title),
        items = sessions,
        key = { _, session -> session.sessionId },
        modifier = modifier,
    ) { session, _ ->
        PhoneHouseholdCard(session = session, onClick = { onJoin(session) })
    }
}

/**
 * One device: its name in mono (PAUSED in accent at the right), what it plays (`PhoneType.headline`, two lines), the
 * series under it, and how far in as a 2dp accent line on `ruleStrong`.
 */
@Composable
private fun PhoneHouseholdCard(
    session: HouseholdSession,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .width(PhoneDimens.landscapeCardWidth)
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .background(TallyColors.ground)
                .semantics {
                    contentDescription = listOf(session.deviceName, session.itemName).filterNot { it.isNullOrBlank() }.joinToString(" ")
                }.phoneClickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.deviceName.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.isPaused) {
                Text(
                    text = stringResource(R.string.tally_household_paused).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = session.itemName.orEmpty(),
            style = PhoneType.headline,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val series = session.seriesName
        if (!series.isNullOrBlank()) {
            Text(
                text = series,
                style = PhoneType.bodySmall,
                color = TallyColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        val fraction = progressFraction(session.positionMs, session.runtimeMs)
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(TallyColors.ruleStrong),
        ) {
            if (fraction != null && fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(TallyColors.accent),
                )
            }
        }
    }
}

/**
 * Send to another screen as a bottom sheet: the title, then the other screens that take remote control as 56dp rows
 * (device · app · user); a tap sends. "No other screens are on" when there are none. A failure stays in the sheet as
 * a NOT SENT line above the rows (the sheet stays open, as the TV dialog does).
 */
@Composable
fun PhoneSendToSheet(
    targets: List<HouseholdSession>,
    hasFetched: Boolean,
    failure: SentNotice?,
    label: @Composable (HouseholdSession) -> String,
    onSend: (HouseholdSession) -> Unit,
    onDismiss: () -> Unit,
) {
    PhoneSheet(onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            PhoneSheetTitle(stringResource(R.string.tally_household_send_title))
            Spacer(Modifier.height(8.dp))
            if (failure != null && !failure.sent) {
                Text(
                    text =
                        (stringResource(R.string.tally_motion_not_sent) + " · " + failure.deviceName).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.liveText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(bottom = 8.dp),
                )
            }
            when {
                !hasFetched -> {
                    Spacer(Modifier.height(56.dp))
                }

                targets.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.tally_household_send_empty),
                        style = PhoneType.body,
                        color = TallyColors.textSecondary,
                        modifier = Modifier.padding(horizontal = PhoneDimens.margin, vertical = 16.dp),
                    )
                }

                else -> {
                    targets.forEach { session ->
                        PhoneDialogRow(
                            onClick = { onSend(session) },
                            headline = { Text(text = label(session), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailing = { Text(text = stringResource(R.string.tally_household_send_action).tallyUppercase()) },
                        )
                    }
                }
            }
        }
    }
}

private const val SENT_NOTICE_MS = 3_000L
private const val NOTICE_FADE_MS = 200

/**
 * The SENT / NOT SENT notice on a phone: a full-width bar on `groundRaised` with a 1dp `ruleStrong` top edge, sitting
 * just above the bottom bar (in portrait, where the bar is) or the gesture bar, for three seconds.
 */
@Composable
fun PhoneSentNotice(
    notice: SentNotice,
    onGone: () -> Unit,
) {
    key(notice.id) {
        var visible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(SENT_NOTICE_MS)
            visible = false
            delay(NOTICE_FADE_MS.toLong())
            onGone()
        }
        val portrait = LocalConfiguration.current.screenHeightDp > LocalConfiguration.current.screenWidthDp
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottom = navigationBar + if (portrait) PhoneDimens.bottomBarHeight else 0.dp
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(NOTICE_FADE_MS)),
                exit = fadeOut(tween(NOTICE_FADE_MS)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(PhoneDimens.touchTarget)
                            .background(TallyColors.groundRaised)
                            .drawBehind {
                                val stroke = PhoneDimens.hairline.toPx()
                                drawLine(
                                    color = TallyColors.ruleStrong,
                                    start = Offset(0f, stroke / 2f),
                                    end = Offset(size.width, stroke / 2f),
                                    strokeWidth = stroke,
                                )
                            }.padding(horizontal = PhoneDimens.margin),
                ) {
                    IndicatorSquare(color = if (notice.sent) TallyColors.accent else TallyColors.live, size = 6.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text =
                            stringResource(if (notice.sent) R.string.tally_motion_sent else R.string.tally_motion_not_sent)
                                .tallyUppercase(),
                        style = PhoneType.label,
                        color = if (notice.sent) TallyColors.accent else TallyColors.liveText,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = notice.deviceName,
                        style = PhoneType.body,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
