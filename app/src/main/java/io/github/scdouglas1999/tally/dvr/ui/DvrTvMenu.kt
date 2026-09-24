package io.github.scdouglas1999.tally.dvr.ui

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.DvrRule
import io.github.scdouglas1999.tally.dvr.KeepLastChoices
import io.github.scdouglas1999.tally.dvr.keepLastText
import io.github.scdouglas1999.tally.dvr.recordingStateText
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay

/**
 * One line of a TV menu: a row ([label] with [onClick]; [marked] carries the accent square, a disabled row shows
 * its [description] under the label) and, above it, an optional mono [info] line (a job's state, the storage
 * estimate), red when [infoLive]. A line without [onClick] is the info line alone. [dismiss] closes the menu after
 * the row runs.
 */
data class DvrMenuLine(
    val label: String? = null,
    val info: String? = null,
    val infoLive: Boolean = false,
    val enabled: Boolean = true,
    val description: String? = null,
    val marked: Boolean = false,
    val dismiss: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * The DVR lines of a game's TV menu, where RECORD and a job's state go (after Watch and multiview): the storage
 * estimate and RECORD (disabled with the server's reason when it won't fit); a job's state in words with
 * WATCH FROM THE START and STOP while it records, CANCEL while it waits. Actions only for [GameDvr.canManage].
 */
@Composable
fun dvrGameMenuLines(dvr: GameDvr): List<DvrMenuLine> {
    val title = matchupTitle(dvr.game)
    val recording = dvr.recording
    val lines = mutableListOf<DvrMenuLine>()
    // A job's state, unless it is only a recording that ended without anything (a canceled job says nothing).
    if (recording != null && recording.state != io.github.scdouglas1999.tally.dvr.DvrState.CANCELED) {
        val state = recordingStateText(recording).tallyUppercase()
        when {
            recording.isRecording -> {
                val startOver = dvr.startOverPath
                val stopLabel = stringResource(R.string.tally_dvr_stop)
                val watchLabel = stringResource(R.string.tally_dvr_watch_from_start)
                if (startOver != null) {
                    lines += DvrMenuLine(label = watchLabel, info = state, infoLive = true, dismiss = true) { dvr.watchFromStart(title) }
                    if (dvr.canManage) {
                        lines +=
                            DvrMenuLine(
                                label = stopLabel,
                                description = stringResource(R.string.tally_dvr_stop_keeps),
                            ) { dvr.cancelOrStop() }
                    }
                } else if (dvr.canManage) {
                    lines +=
                        DvrMenuLine(
                            label = stopLabel,
                            info = state,
                            infoLive = true,
                            description = stringResource(R.string.tally_dvr_stop_keeps),
                        ) {
                            dvr.cancelOrStop()
                        }
                } else {
                    lines += DvrMenuLine(info = state, infoLive = true)
                }
            }

            recording.isPending && dvr.canManage -> {
                lines += DvrMenuLine(label = stringResource(R.string.tally_dvr_cancel), info = state) { dvr.cancelOrStop() }
            }

            // Done: "Watch the recording" leads the menu. Failed: the reason, then RECORD again below if possible.
            recording.state == io.github.scdouglas1999.tally.dvr.DvrState.DONE && dvr.watchableItemId != null -> {}

            else -> {
                lines += DvrMenuLine(info = state, infoLive = recording.isFailed)
            }
        }
    }
    if (dvr.canRecord) {
        val refusal = dvr.refusal
        lines +=
            DvrMenuLine(
                label = stringResource(R.string.tally_dvr_record),
                info = estimateLine(dvr.estimate)?.tallyUppercase(),
                enabled = refusal == null,
                description = dvr.refusalLine,
            ) { dvr.record() }
    }
    return lines
}

/**
 * "Record every Otters game" (or, when the team already has a rule, "Recording every Otters game" with the accent
 * square): opens the keep-last choice. Null without the permission.
 */
@Composable
fun dvrTeamMenuLine(
    dvr: GameDvr,
    team: TallyTeam,
    onOpen: (TallyTeam) -> Unit,
): DvrMenuLine? {
    if (!dvr.canManage || team.id.isBlank()) return null
    val rule = dvr.ruleFor(team)
    val name = teamName(team)
    return DvrMenuLine(
        label =
            if (rule != null) {
                stringResource(R.string.tally_dvr_recording_every, name)
            } else {
                stringResource(R.string.tally_dvr_record_every, name)
            },
        marked = rule != null,
        onClick = { onOpen(team) },
    )
}

internal fun teamName(team: TallyTeam): String = team.shortName.ifBlank { team.abbr.ifBlank { team.name } }

/**
 * A small TV menu in the game menu's look: kicker and title over a column of rows (the first takes focus; clicks are
 * ignored until the long OK that may have opened it is over), BACK closes it. Scrolls when taller than the screen.
 */
@Composable
fun DvrTvMenuDialog(
    kicker: String?,
    title: String,
    lines: List<DvrMenuLine>,
    onDismiss: () -> Unit,
) {
    val firstRow = remember { FocusRequester() }
    var acceptClicks by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(CLICK_ARM_MS)
        acceptClicks = true
        var attempts = 0
        while (!firstRow.tryRequestFocus("tally-dvr-menu") && attempts < 8) {
            attempts++
            delay(50)
        }
    }
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.decorView.elevation = 0f
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier =
                    Modifier
                        .width(560.dp)
                        .heightIn(max = MENU_MAX_HEIGHT)
                        .background(TallyColors.ground)
                        .border(TallyDimens.hairline, TallyColors.rule)
                        .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (kicker != null) {
                        Text(
                            text = kicker.tallyUppercase(),
                            style = TallyType.label,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(text = title, style = menuTitle, color = TallyColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DvrTvMenuRows(
                    lines = lines,
                    firstRowFocus = firstRow,
                    acceptClicks = { acceptClicks },
                    onDismiss = onDismiss,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** The rows of a TV menu ([DvrMenuLine]s): focus stays inside the column; the first row gets [firstRowFocus]. */
@Composable
fun DvrTvMenuRows(
    lines: List<DvrMenuLine>,
    firstRowFocus: FocusRequester?,
    acceptClicks: () -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = lines.filter { it.onClick != null && it.enabled }
    val firstFocusable = rows.firstOrNull()
    val lastFocusable = rows.lastOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        lines.forEach { line ->
            DvrTvMenuLine(
                line = line,
                acceptClicks = acceptClicks,
                onDismiss = onDismiss,
                modifier =
                    Modifier
                        .then(if (line === firstFocusable && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier)
                        .focusProperties {
                            if (line === firstFocusable) up = FocusRequester.Cancel
                            if (line === lastFocusable) down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
            )
        }
    }
}

/** One [DvrMenuLine] on a TV: the info line, then the row with the OK key hint (or the accent square when marked). */
@Composable
fun DvrTvMenuLine(
    line: DvrMenuLine,
    acceptClicks: () -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (line.info != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (line.infoLive) IndicatorSquare(color = TallyColors.live, size = 8.dp)
                Text(
                    text = line.info,
                    style = TallyType.label,
                    color = if (line.infoLive) TallyColors.liveText else TallyColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val onClick = line.onClick
        if (line.label != null && onClick != null) {
            TallyRow(
                label = line.label,
                enabled = line.enabled,
                description = line.description,
                onClick = {
                    if (!acceptClicks()) return@TallyRow
                    onClick()
                    if (line.dismiss) onDismiss()
                },
                trailing = {
                    if (line.marked) {
                        IndicatorSquare(color = TallyColors.accent, size = 10.dp)
                    }
                    if (line.enabled) KeyHint(key = stringResource(R.string.tally_key_ok), label = "")
                },
                modifier = modifier,
            )
        }
    }
}

/**
 * "Keep the last N games" for a team rule, on a TV: all / 5 / 10 / 20 (the rule's choice marked); choosing one
 * records every [team] game (or changes the rule), and with a rule, a last row stops recording them.
 */
@Composable
fun DvrKeepLastTvDialog(
    team: TallyTeam,
    rule: DvrRule?,
    onChoose: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = teamName(team)
    val lines =
        KeepLastChoices.map { n ->
            DvrMenuLine(label = keepLastText(n), marked = rule != null && rule.keepLast == n, dismiss = true) { onChoose(n) }
        } +
            listOfNotNull(
                rule?.let {
                    DvrMenuLine(label = stringResource(R.string.tally_dvr_stop_every, name), dismiss = true, onClick = onDelete)
                },
            )
    DvrTvMenuDialog(
        kicker = null,
        title = stringResource(R.string.tally_dvr_record_every, name),
        lines = lines,
        onDismiss = onDismiss,
    )
}

private const val CLICK_ARM_MS = 400L
private val MENU_MAX_HEIGHT = 500.dp

private val menuTitle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )
