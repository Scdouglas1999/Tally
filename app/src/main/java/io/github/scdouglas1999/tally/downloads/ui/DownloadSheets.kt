package io.github.scdouglas1999.tally.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.downloads.DownloadOption
import io.github.scdouglas1999.tally.downloads.DownloadQuality
import io.github.scdouglas1999.tally.downloads.DownloadState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButtonKind
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneConfirmContent
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhonePanelFrame
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetDivider
import io.github.scdouglas1999.tally.ui.settings.phone.phoneSheetListMaxHeight
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.launch

/**
 * The quality sheet: for a show first how much of it (NEXT 1 · NEXT 3 · NEXT 5 · ALL UNWATCHED · SEASON 2), then the
 * qualities from `options()`, each row the label in Sans and its rate and size in mono (`Original` `14.8 GB`,
 * `1080p` `8 Mbps · ~6.1 GB`), not allowed ones muted with their reason. The settings' default is chosen at first; a
 * converted choice adds the note that the server converts first; a group adds the total. CANCEL and DOWNLOAD at the
 * foot. [onStarted] when the download is queued.
 */
@Composable
fun DownloadQualitySheet(
    subject: DownloadSubject,
    viewModel: DownloadUiViewModel,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val show = subject as? DownloadSubject.Show
    val scopes =
        remember(show) {
            ShowScope.entries.filter { it != ShowScope.SEASON || show?.seasonId != null }
        }
    var showScope by remember { mutableStateOf(ShowScope.NEXT_3) }
    val target = remember(subject, showScope) { targetOf(subject, showScope) }
    var options by remember(target) { mutableStateOf<List<DownloadOption>?>(null) }
    var loadError by remember(target) { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<DownloadQuality?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val offlineText = stringResource(R.string.tally_dl_error_offline)

    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        viewModel
            .options(t)
            .onSuccess { list ->
                options = list
                // keep the viewer's choice across a change of scope; else the settings' default, else the first allowed
                val keep = chosen?.takeIf { c -> list.any { it.quality == c && it.allowed } }
                chosen = keep
                    ?: settings.defaultQuality?.takeIf { d -> list.any { it.quality == d && it.allowed } }
                    ?: list.firstOrNull { it.allowed }?.quality
            }.onFailure { loadError = offlineText }
    }

    PhoneSheet(onDismiss = onDismiss) {
        PhonePanelFrame(title = stringResource(R.string.tally_dlui_sheet_title, subject.title)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = phoneSheetListMaxHeight())
                        .verticalScroll(rememberScrollState()),
            ) {
                if (show != null) {
                    ScopeChips(
                        scopes = scopes,
                        chosen = showScope,
                        seasonNumber = show.seasonNumber,
                        onChoose = { showScope = it },
                    )
                    PhoneSheetDivider()
                }
                val list = options
                when {
                    loadError != null -> {
                        SheetMessage(loadError!!, error = true)
                    }

                    list == null -> {
                        SheetMessage(stringResource(R.string.tally_dlui_checking))
                    }

                    list.isEmpty() -> {
                        SheetMessage(stringResource(R.string.tally_dl_error_nothing))
                    }

                    else -> {
                        // one reason for every row (no download permission) is said once, under the list
                        val sharedReason =
                            list
                                .map { it.reason }
                                .distinct()
                                .singleOrNull()
                                ?.takeIf { list.none { o -> o.allowed } }
                        list.forEach { option ->
                            val (name, detail) = optionParts(option)
                            PhoneDialogRow(
                                onClick = {
                                    chosen = option.quality
                                    error = null
                                },
                                enabled = option.allowed,
                                headline = { Text(name, maxLines = 1) },
                                supporting = option.reason?.takeIf { sharedReason == null }?.let { reason -> { Text(reason) } },
                                // the chosen row's accent square in a fixed slot, so the sizes line up
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Text(detail, maxLines = 1)
                                        Box(Modifier.size(8.dp)) {
                                            if (option.quality == chosen) IndicatorSquare(color = TallyColors.accent, size = 8.dp)
                                        }
                                    }
                                },
                            )
                        }
                        if (sharedReason != null) SheetMessage(sharedReason, error = true)
                    }
                }
            }
            val selected = options?.firstOrNull { it.quality == chosen }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 12.dp),
            ) {
                if (selected?.quality is DownloadQuality.Converted) {
                    Text(
                        text = stringResource(R.string.tally_dlui_convert_note),
                        style = PhoneType.bodySmall,
                        color = TallyColors.textSecondary,
                    )
                }
                if (subject.isGroup && selected?.estimatedBytes != null) {
                    Text(
                        text = stringResource(R.string.tally_dlui_total, formatBytes(selected.estimatedBytes)).tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                    )
                }
                error?.let {
                    Text(text = it, style = PhoneType.bodySmall, color = TallyColors.liveText)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PhoneDimens.margin)
                        .padding(top = 16.dp, bottom = 8.dp),
            ) {
                PhoneButton(
                    label = stringResource(R.string.tally_dlui_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PhoneButton(
                    label = stringResource(R.string.tally_dlui_download),
                    kind = PhoneButtonKind.PRIMARY,
                    enabled = selected?.allowed == true && target != null && !busy,
                    onClick = {
                        val t = target ?: return@PhoneButton
                        val quality = selected?.quality ?: return@PhoneButton
                        busy = true
                        scope.launch {
                            val result = viewModel.enqueue(t, quality)
                            busy = false
                            if (result.error != null) {
                                error = result.error
                            } else {
                                onStarted()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A show's scope as a row of mono chips; the chosen one is lit (accent frame and square). */
@Composable
private fun ScopeChips(
    scopes: List<ShowScope>,
    chosen: ShowScope,
    seasonNumber: Int?,
    onChoose: (ShowScope) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin, vertical = 10.dp),
    ) {
        scopes.forEach { scope ->
            val on = scope == chosen
            val label =
                when (scope) {
                    ShowScope.NEXT_1 -> {
                        stringResource(R.string.tally_dlui_next_n, 1)
                    }

                    ShowScope.NEXT_3 -> {
                        stringResource(R.string.tally_dlui_next_n, 3)
                    }

                    ShowScope.NEXT_5 -> {
                        stringResource(R.string.tally_dlui_next_n, 5)
                    }

                    ShowScope.ALL_UNWATCHED -> {
                        stringResource(R.string.tally_dlui_all_unwatched)
                    }

                    ShowScope.SEASON -> {
                        when (seasonNumber) {
                            null -> stringResource(R.string.tally_dlui_this_season)
                            0 -> stringResource(R.string.tally_series_specials)
                            else -> stringResource(R.string.tally_series_season_n, seasonNumber)
                        }
                    }
                }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .height(PhoneDimens.touchTarget)
                        .border(PhoneDimens.hairline, if (on) TallyColors.accent else TallyColors.ruleStrong)
                        .phoneClickable(onClick = { onChoose(scope) })
                        .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = label.tallyUppercase(),
                    style = PhoneType.labelLarge,
                    color = if (on) TallyColors.text else TallyColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SheetMessage(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        style = PhoneType.body,
        color = if (error) TallyColors.liveText else TallyColors.muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin, vertical = 16.dp),
    )
}

/** `Original` + `14.8 GB`; `1080p` + `8 Mbps · ~6.1 GB` (a converted size is an estimate). */
internal fun optionParts(option: DownloadOption): Pair<String, String> {
    val size = option.estimatedBytes?.let(::formatBytes)
    return when (val quality = option.quality) {
        DownloadQuality.Original -> {
            option.label to (size ?: "")
        }

        is DownloadQuality.Converted -> {
            val name = quality.rung.label.substringBefore(" · ")
            val rate = quality.rung.label.substringAfter(" · ", "")
            name to listOfNotNull(rate.ifBlank { null }, size?.let { "~$it" }).joinToString(" · ")
        }
    }
}

/**
 * A download's status sheet. Under way: its progress, PAUSE or RESUME, CANCEL DOWNLOAD, and Downloads. Done: PLAY
 * (from the device), REMOVE DOWNLOAD (confirmed), for a group DOWNLOAD MORE…, and Downloads.
 */
@Composable
fun DownloadStatusSheet(
    subject: DownloadSubject,
    viewModel: DownloadUiViewModel,
    onDownloadMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val status = remember(entries, subject) { statusOf(subject, entries) }
    var confirmRemove by remember { mutableStateOf(false) }
    val ids =
        (status as? DownloadStatus.Active)?.entries?.map { it.itemId }
            ?: (status as? DownloadStatus.Done)?.entries?.map { it.itemId }.orEmpty()
    PhoneSheet(onDismiss = onDismiss) {
        if (confirmRemove) {
            PhoneConfirmContent(
                kicker =
                    stringResource(
                        if (subject.isGroup) R.string.tally_dlui_remove_downloads_q else R.string.tally_dlui_remove_download_q,
                    ),
                message = subject.title,
                detail = (status as? DownloadStatus.Done)?.let { summaryLine(it) },
                confirmLabel =
                    stringResource(
                        if (subject.isGroup) R.string.tally_dlui_remove_downloads else R.string.tally_dlui_remove_download,
                    ),
                cancelLabel = stringResource(R.string.tally_dlui_cancel),
                onCancel = { confirmRemove = false },
                onConfirm = {
                    viewModel.delete(ids)
                    onDismiss()
                },
            )
            return@PhoneSheet
        }
        PhonePanelFrame(title = subject.title) {
            when (status) {
                DownloadStatus.None -> {
                    SheetMessage(stringResource(R.string.tally_dlui_not_downloaded))
                    PhoneDialogRow(
                        onClick = onDownloadMore,
                        headline = { Text(stringResource(R.string.tally_dlui_download_ellipsis)) },
                    )
                }

                is DownloadStatus.Active -> {
                    val unfinished = status.entries.filter { !it.isDone }
                    Text(
                        text = activeLine(status).tallyUppercase(),
                        style = PhoneType.label,
                        color = if (status.failed) TallyColors.liveText else TallyColors.textSecondary,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 14.dp),
                    )
                    (unfinished.firstNotNullOfOrNull { (it.state as? DownloadState.Failed)?.reason })?.let {
                        Text(
                            text = it,
                            style = PhoneType.bodySmall,
                            color = TallyColors.liveText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 4.dp),
                        )
                    }
                    ProgressLine(status.progress, Modifier.padding(horizontal = PhoneDimens.margin).padding(top = 10.dp, bottom = 8.dp))
                    if (status.paused || status.failed) {
                        PhoneDialogRow(
                            onClick = {
                                viewModel.resume(unfinished.map { it.itemId })
                                onDismiss()
                            },
                            headline = { Text(stringResource(R.string.tally_dlui_resume)) },
                        )
                    } else {
                        PhoneDialogRow(
                            onClick = {
                                viewModel.pause(unfinished.map { it.itemId })
                                onDismiss()
                            },
                            headline = { Text(stringResource(R.string.tally_dlui_pause)) },
                        )
                    }
                    PhoneDialogRow(
                        onClick = {
                            viewModel.delete(unfinished.map { it.itemId })
                            onDismiss()
                        },
                        destructive = true,
                        headline = { Text(stringResource(R.string.tally_dlui_cancel_download)) },
                    )
                    PhoneSheetDivider()
                    PhoneDialogRow(
                        onClick = {
                            onDismiss()
                            viewModel.openDownloads()
                        },
                        headline = { Text(stringResource(R.string.tally_dlui_open_downloads)) },
                    )
                }

                is DownloadStatus.Done -> {
                    Text(
                        text = summaryLine(status).tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 14.dp, bottom = 8.dp),
                    )
                    PhoneDialogRow(
                        onClick = {
                            onDismiss()
                            viewModel.play(status.entries)
                        },
                        headline = { Text(stringResource(R.string.tally_dlui_play_offline)) },
                    )
                    if (subject.isGroup) {
                        PhoneDialogRow(
                            onClick = onDownloadMore,
                            headline = { Text(stringResource(R.string.tally_dlui_download_more)) },
                        )
                    }
                    PhoneDialogRow(
                        onClick = { confirmRemove = true },
                        destructive = true,
                        headline = {
                            Text(
                                stringResource(
                                    if (subject.isGroup) R.string.tally_dlui_remove_downloads else R.string.tally_dlui_remove_download,
                                ),
                            )
                        },
                    )
                    PhoneSheetDivider()
                    PhoneDialogRow(
                        onClick = {
                            onDismiss()
                            viewModel.openDownloads()
                        },
                        headline = { Text(stringResource(R.string.tally_dlui_open_downloads)) },
                    )
                }
            }
        }
    }
}

/** `42% · 1.2 GB of 3.0 GB`, `PAUSED · 42%`, `3 of 5 downloaded`. */
@Composable
private fun activeLine(status: DownloadStatus.Active): String {
    val count = status.entries.size
    val done = status.entries.count { it.isDone }
    val percent = stringResource(R.string.tally_dlui_percent, status.percent)
    val head =
        when {
            status.failed -> stringResource(R.string.tally_dlui_failed)
            status.paused -> stringResource(R.string.tally_dlui_paused)
            else -> stringResource(R.string.tally_dlui_downloading)
        }
    return listOfNotNull(
        head,
        percent,
        if (count > 1) stringResource(R.string.tally_dlui_n_of_m_done, done, count) else null,
    ).joinToString(" · ")
}

/** `Original · 14.8 GB` for one item, `5 items · 3.2 GB` for a group. */
@Composable
private fun summaryLine(status: DownloadStatus.Done): String {
    val entries = status.entries
    val size = formatBytes(status.bytes)
    return if (entries.size == 1) {
        listOf(entries.first().qualityLabel, size).joinToString(" · ")
    } else {
        listOf(pluralStringResource(R.plurals.tally_dlui_items, entries.size, entries.size), size).joinToString(" · ")
    }
}

/** A 3dp progress line: accent on `ruleStrong`, full width. */
@Composable
internal fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(TallyColors.ruleStrong),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(TallyColors.accent),
        )
    }
}

/** Bytes as the downloads UI writes them: `14.8 GB`, `612 MB`, `3.4 MB`, `820 KB` (decimal units). */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L).toDouble()
    return when {
        b >= 1e9 -> String.format(java.util.Locale.US, "%.1f GB", b / 1e9)
        b >= 1e8 -> String.format(java.util.Locale.US, "%.0f MB", b / 1e6)
        b >= 1e6 -> String.format(java.util.Locale.US, "%.1f MB", b / 1e6)
        else -> String.format(java.util.Locale.US, "%.0f KB", b / 1e3)
    }
}

/** "Remove download?" for [subject] as a sheet (from an item's menu): removes all of its downloads. */
@Composable
fun DownloadRemoveSheet(
    subject: DownloadSubject,
    viewModel: DownloadUiViewModel,
    onDismiss: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val mine = remember(entries, subject) { entries.filter(subject::matches) }
    PhoneSheet(onDismiss = onDismiss) {
        PhoneConfirmContent(
            kicker =
                stringResource(
                    if (subject.isGroup) R.string.tally_dlui_remove_downloads_q else R.string.tally_dlui_remove_download_q,
                ),
            message = subject.title,
            detail = formatBytes(mine.sumOf { it.sizeBytes ?: 0L }),
            confirmLabel =
                stringResource(
                    if (subject.isGroup) R.string.tally_dlui_remove_downloads else R.string.tally_dlui_remove_download,
                ),
            cancelLabel = stringResource(R.string.tally_dlui_cancel),
            onCancel = onDismiss,
            onConfirm = {
                viewModel.delete(mine.map { it.itemId })
                onDismiss()
            },
        )
    }
}
