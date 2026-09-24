package io.github.scdouglas1999.tally.dvr.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.DvrRule
import io.github.scdouglas1999.tally.dvr.DvrState
import io.github.scdouglas1999.tally.dvr.KeepLastChoices
import io.github.scdouglas1999.tally.dvr.keepLastText
import io.github.scdouglas1999.tally.dvr.recordingStateText
import io.github.scdouglas1999.tally.dvr.ui.GameDvr
import io.github.scdouglas1999.tally.dvr.ui.estimateLine
import io.github.scdouglas1999.tally.dvr.ui.matchupTitle
import io.github.scdouglas1999.tally.dvr.ui.teamName
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.phone.SheetActionRow
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetTitle
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * The game sheet's recording block, under WATCH: a job's state in words (`■ RECORDING SINCE 7:12 PM` in `live`
 * red) with WATCH FROM THE START and STOP while it records, CANCEL while it waits; or, for a game that can be
 * recorded, the storage estimate as a mono line and RECORD (disabled, with the server's reason under it, when it
 * won't fit). Actions only with the permission; nothing at all when there is nothing to say.
 */
@Composable
fun PhoneGameDvrBlock(
    dvr: GameDvr,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = dvr.recording
    val title = matchupTitle(dvr.game)
    val showState =
        recording != null &&
            recording.state != DvrState.CANCELED &&
            !(recording.state == DvrState.DONE && dvr.watchableItemId != null)
    if (!showState && !dvr.canRecord) return
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 16.dp),
    ) {
        if (showState && recording != null) {
            val live = recording.isRecording
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) IndicatorSquare(color = TallyColors.live, size = 8.dp)
                Text(
                    text = recordingStateText(recording).tallyUppercase(),
                    style = PhoneType.labelLarge,
                    color = if (live || recording.isFailed) TallyColors.liveText else TallyColors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                live -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (dvr.startOverPath != null) {
                            PhoneButton(
                                label = stringResource(R.string.tally_dvr_from_start_short),
                                glyph = stringResource(R.string.tally_dvr_fa_start_over),
                                onClick = {
                                    dvr.watchFromStart(title)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (dvr.canManage) {
                            PhoneButton(
                                label = stringResource(R.string.tally_dvr_stop),
                                onClick = dvr::cancelOrStop,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (dvr.canManage) {
                        Text(
                            text = stringResource(R.string.tally_dvr_stop_keeps),
                            style = PhoneType.bodySmall,
                            color = TallyColors.muted,
                        )
                    }
                }

                recording.isPending && dvr.canManage -> {
                    PhoneButton(
                        label = stringResource(R.string.tally_dvr_cancel),
                        onClick = dvr::cancelOrStop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (dvr.canRecord) {
            estimateLine(dvr.estimate)?.let { line ->
                Text(
                    text = line.tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val refusal = dvr.refusal
            PhoneButton(
                label = stringResource(R.string.tally_dvr_record),
                enabled = refusal == null,
                onClick = dvr::record,
                modifier = Modifier.fillMaxWidth(),
            )
            dvr.refusalLine?.let { line ->
                Text(text = line, style = PhoneType.bodySmall, color = TallyColors.textSecondary)
            }
        }
    }
}

/**
 * "Record every Otters game" as a sheet row next to the team's Follow row; the accent square (and "Recording every
 * Otters game") once the team has a rule. Opens the keep-last sheet. Nothing without the permission.
 */
@Composable
fun PhoneTeamRuleRow(
    dvr: GameDvr,
    team: TallyTeam,
    onOpen: (TallyTeam) -> Unit,
) {
    if (!dvr.canManage || team.id.isBlank()) return
    val rule = dvr.ruleFor(team)
    val name = teamName(team)
    SheetActionRow(
        label =
            if (rule != null) {
                stringResource(R.string.tally_dvr_recording_every, name)
            } else {
                stringResource(R.string.tally_dvr_record_every, name)
            },
        on = rule != null,
        onClick = { onOpen(team) },
    )
}

/**
 * "Keep the last N games" for a team rule, as a small sheet: all / 5 / 10 / 20 (the rule's choice marked). A choice
 * records every [team] game in the game's league (or changes the rule); with a rule, a last red row stops it.
 */
@Composable
fun PhoneKeepLastSheet(
    team: TallyTeam,
    rule: DvrRule?,
    onChoose: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = teamName(team)
    PhoneSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            PhoneSheetTitle(stringResource(R.string.tally_dvr_record_every, name))
            Spacer(Modifier.height(4.dp))
            KeepLastChoices.forEach { n ->
                PhoneDialogRow(
                    onClick = {
                        onChoose(n)
                        onDismiss()
                    },
                    marked = rule != null && rule.keepLast == n,
                    headline = { Text(text = keepLastText(n), maxLines = 1) },
                )
            }
            if (rule != null) {
                PhoneDialogRow(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    destructive = true,
                    headline = { Text(text = stringResource(R.string.tally_dvr_stop_every, name), maxLines = 1) },
                )
            }
        }
    }
}
