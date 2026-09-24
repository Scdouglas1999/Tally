package io.github.scdouglas1999.tally.dvr.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.DvrJob
import io.github.scdouglas1999.tally.dvr.DvrRule
import io.github.scdouglas1999.tally.dvr.DvrState
import io.github.scdouglas1999.tally.dvr.DvrViewModel
import io.github.scdouglas1999.tally.dvr.GameRecordingView
import io.github.scdouglas1999.tally.dvr.recordingStateText
import io.github.scdouglas1999.tally.dvr.ui.RecordingsSections
import io.github.scdouglas1999.tally.dvr.ui.RecordingsTabEffects
import io.github.scdouglas1999.tally.dvr.ui.jobTitle
import io.github.scdouglas1999.tally.dvr.ui.leagueForChange
import io.github.scdouglas1999.tally.dvr.ui.playRecorded
import io.github.scdouglas1999.tally.dvr.ui.recordedMeta
import io.github.scdouglas1999.tally.dvr.ui.recordingImageUrl
import io.github.scdouglas1999.tally.dvr.ui.recordingNowMeta
import io.github.scdouglas1999.tally.dvr.ui.rememberSecondTick
import io.github.scdouglas1999.tally.dvr.ui.ruleMeta
import io.github.scdouglas1999.tally.dvr.ui.scheduledMeta
import io.github.scdouglas1999.tally.dvr.ui.storageLine
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowHeader
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.phone.SheetActionRow
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.settings.TallyConfirmDeleteDialog
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetTitle
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * RECORDINGS on a phone (the Sports tab after MULTIVIEW, only when the server records): the storage line, then
 * RECORDING NOW (a tap watches from the start, a long-press offers STOP), SCHEDULED (tap for CANCEL), RECORDED (a
 * row of 16:9 cards: a tap plays, a long-press offers delete), FAILED (the reason, DISMISS at the right) and TEAM
 * RULES (keep-last; a tap changes it, DELETE at the right). Without the permission to record, nothing is offered
 * but watching. Never a score anywhere.
 */
@Composable
fun PhoneRecordingsTab(modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<DvrViewModel>()
    RecordingsTabEffects(viewModel)
    val list by viewModel.list.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    var menuJob by remember { mutableStateOf<DvrJob?>(null) }
    var keepRule by remember { mutableStateOf<DvrRule?>(null) }
    var confirmDelete by remember { mutableStateOf<DvrJob?>(null) }
    var confirmRule by remember { mutableStateOf<DvrRule?>(null) }

    val current = list
    if (current == null) {
        PhoneEmptyState(
            title = stringResource(if (error != null) R.string.tally_dvr_list_failed else R.string.tally_dvr_loading),
            subtitle = error,
            modifier = modifier,
        )
        return
    }
    val sections = RecordingsSections.of(current)
    val canManage = current.canManage
    val now = rememberSecondTick(active = sections.recordingNow.isNotEmpty())
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    LazyColumn(
        contentPadding = PaddingValues(top = 14.dp, bottom = bottom + 16.dp),
        modifier = modifier,
    ) {
        item(key = "storage") {
            Text(
                text = storageLine(storage)?.tallyUppercase().orEmpty(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = PhoneDimens.margin),
            )
        }
        if (sections.isEmpty) {
            item(key = "empty") {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_dvr_empty_title),
                    subtitle = stringResource(if (canManage) R.string.tally_dvr_empty_sub_phone else R.string.tally_dvr_empty_sub_viewer),
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        section(R.string.tally_dvr_section_now, sections.recordingNow) { job ->
            val startOver = job.startOverPath
            val title = jobTitle(job)
            PhoneDvrRow(
                title = title,
                meta = recordingNowMeta(job, now),
                indicator = TallyColors.live,
                onClick = { if (startOver != null) viewModel.watchFromStart(job.id, startOver, title) else menuJob = job },
                onLongClick = { menuJob = job },
            )
        }
        section(R.string.tally_dvr_section_scheduled, sections.scheduled) { job ->
            PhoneDvrRow(
                title = jobTitle(job),
                meta = scheduledMeta(job, current.rules),
                indicator = TallyColors.ruleStrong,
                onClick = { if (canManage) menuJob = job },
                onLongClick = { if (canManage) menuJob = job },
            )
        }
        if (sections.recorded.isNotEmpty()) {
            item(key = "recorded") {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    PhoneRowHeader(
                        title = stringResource(R.string.tally_dvr_section_recorded),
                        count = sections.recorded.size,
                        modifier = Modifier.padding(horizontal = PhoneDimens.margin),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
                        contentPadding = PaddingValues(horizontal = PhoneDimens.margin),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(sections.recorded, key = { it.id }) { job ->
                            LandscapeCard(
                                title = jobTitle(job),
                                kicker = recordedMeta(job),
                                imageUrl = recordingImageUrl(viewModel, job),
                                onClick = { playRecorded(viewModel, job, context) },
                                onLongClick = { menuJob = job },
                                width = PhoneDimens.landscapeCardWidth,
                            )
                        }
                    }
                }
            }
        }
        section(R.string.tally_dvr_section_failed, sections.failed) { job ->
            PhoneDvrRow(
                title = jobTitle(job),
                meta = recordingStateText(GameRecordingView(job.state, job.id, reason = job.reason)),
                metaColor = TallyColors.liveText,
                indicator = TallyColors.live,
                action = if (canManage) stringResource(R.string.tally_dvr_dismiss) else null,
                onAction = { viewModel.dismiss(job.id) },
            )
        }
        section(R.string.tally_dvr_section_rules, sections.rules, key = { it.id }) { rule ->
            PhoneDvrRow(
                title = rule.title,
                meta = ruleMeta(rule, current),
                indicator = TallyColors.muted,
                onClick = { if (canManage) keepRule = rule },
                action = if (canManage) stringResource(R.string.tally_dvr_delete) else null,
                onAction = { confirmRule = rule },
            )
        }
    }

    menuJob?.let { job ->
        PhoneJobSheet(job = job, canManage = canManage, viewModel = viewModel, onDelete = { confirmDelete = it }, onDismiss = {
            menuJob =
                null
        })
    }
    keepRule?.let { rule ->
        PhoneKeepLastSheet(
            team = TallyTeam(id = rule.teamId.orEmpty(), name = rule.teamName.orEmpty()),
            rule = rule,
            onChoose = { keepLast -> viewModel.changeKeepLast(rule, rule.leagueForChange(), keepLast) },
            onDelete = { viewModel.deleteRule(rule.id) },
            onDismiss = { keepRule = null },
        )
    }
    confirmDelete?.let { job ->
        TallyConfirmDeleteDialog(
            itemTitle = jobTitle(job),
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                viewModel.deleteRecording(job.id)
            },
        )
    }
    confirmRule?.let { rule ->
        TallyConfirmDeleteDialog(
            itemTitle = rule.title,
            onCancel = { confirmRule = null },
            onConfirm = {
                confirmRule = null
                viewModel.deleteRule(rule.id)
            },
        )
    }
}

/** A job's sheet on a phone: watch from the start / stop, cancel, or play / delete a finished recording. */
@Composable
private fun PhoneJobSheet(
    job: DvrJob,
    canManage: Boolean,
    viewModel: DvrViewModel,
    onDelete: (DvrJob) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val title = jobTitle(job)
    PhoneSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            PhoneSheetTitle(listOf(job.game.league, title).filter { it.isNotBlank() }.joinToString(" · "))
            Spacer(Modifier.height(4.dp))
            val startOver = job.startOverPath
            when {
                job.state == DvrState.DONE -> {
                    SheetActionRow(label = stringResource(R.string.tally_dvr_play), onClick = {
                        onDismiss()
                        playRecorded(viewModel, job, context)
                    })
                    if (canManage) {
                        SheetActionRow(label = stringResource(R.string.tally_dvr_delete), onClick = {
                            onDismiss()
                            onDelete(job)
                        })
                    }
                }

                job.isRecording -> {
                    if (startOver != null) {
                        SheetActionRow(label = stringResource(R.string.tally_dvr_watch_from_start), onClick = {
                            onDismiss()
                            viewModel.watchFromStart(job.id, startOver, title)
                        })
                    }
                    if (canManage) {
                        SheetActionRow(label = stringResource(R.string.tally_dvr_stop), onClick = {
                            onDismiss()
                            viewModel.cancel(job.id)
                        })
                        Text(
                            text = stringResource(R.string.tally_dvr_stop_keeps),
                            style = PhoneType.bodySmall,
                            color = TallyColors.muted,
                            modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(bottom = 8.dp),
                        )
                    }
                }

                job.isPending && canManage -> {
                    SheetActionRow(label = stringResource(R.string.tally_dvr_cancel), onClick = {
                        onDismiss()
                        viewModel.cancel(job.id)
                    })
                }
            }
        }
    }
}

private fun <T> LazyListScope.section(
    title: Int,
    items: List<T>,
    key: (T) -> Any = { (it as? DvrJob)?.id ?: it.hashCode() },
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        PhoneRowHeader(
            title = stringResource(title),
            count = items.size,
            modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(top = 20.dp, bottom = 4.dp),
        )
    }
    items(items, key = { "row-$title-" + key(it) }) { row(it) }
}

/**
 * A Recordings row on a phone: the indicator square, the title (`PhoneType.headline`) over a mono meta line, and an
 * optional mono [action] at the right (DISMISS, DELETE) with its own 48dp target; a 1dp `rule` under it.
 */
@Composable
private fun PhoneDvrRow(
    title: String,
    meta: String,
    indicator: Color,
    metaColor: Color = TallyColors.textSecondary,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .drawBehind {
                    val stroke = PhoneDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.then(if (onClick != null) Modifier.phoneClickable(onLongClick = onLongClick, onClick = onClick) else Modifier)
                .padding(start = PhoneDimens.margin, end = if (action != null) 4.dp else PhoneDimens.margin)
                .padding(vertical = 10.dp),
    ) {
        IndicatorSquare(color = indicator, size = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(text = title, style = PhoneType.headline, color = TallyColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = meta.tallyUppercase(),
                style = PhoneType.label,
                color = metaColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (action != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.heightIn(min = PhoneDimens.touchTarget).phoneClickable(onClick = onAction).padding(horizontal = 12.dp),
            ) {
                Text(text = action.tallyUppercase(), style = PhoneType.label, color = TallyColors.textSecondary, maxLines = 1)
            }
        }
    }
}
