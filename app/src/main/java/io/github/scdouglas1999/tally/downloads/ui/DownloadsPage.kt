package io.github.scdouglas1999.tally.downloads.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import io.github.scdouglas1999.tally.downloads.DownloadEntry
import io.github.scdouglas1999.tally.downloads.DownloadState
import io.github.scdouglas1999.tally.downloads.StorageInfo
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneConfirmContent
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhonePanelFrame
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind
import java.io.File
import java.util.UUID

/** How long "Back online" stays up before the full app returns. */
private const val BACK_ONLINE_MS = 3_000L

/** One group of the page: a film, a show's episodes, an album's tracks, or one download under way. */
private sealed interface DownloadGroup {
    val key: String
    val entries: List<DownloadEntry>

    data class Single(
        val entry: DownloadEntry,
    ) : DownloadGroup {
        override val key get() = "item-${entry.itemId}"
        override val entries get() = listOf(entry)
    }

    data class Show(
        val seriesId: UUID?,
        val name: String,
        override val entries: List<DownloadEntry>,
    ) : DownloadGroup {
        override val key get() = "show-${seriesId ?: name}"
    }

    data class Album(
        val albumId: UUID?,
        val name: String,
        val artist: String?,
        override val entries: List<DownloadEntry>,
    ) : DownloadGroup {
        override val key get() = "album-${albumId ?: name}"
    }
}

/** The page's sections, from the downloads (oldest first). */
private data class DownloadSections(
    val inProgress: List<DownloadEntry>,
    val films: List<DownloadGroup.Single>,
    val shows: List<DownloadGroup.Show>,
    val music: List<DownloadGroup.Album>,
) {
    val isEmpty get() = inProgress.isEmpty() && films.isEmpty() && shows.isEmpty() && music.isEmpty()

    companion object {
        fun of(entries: List<DownloadEntry>): DownloadSections {
            val done = entries.filter { it.isDone }
            return DownloadSections(
                inProgress = entries.filter { !it.isDone },
                films =
                    done
                        .filter { it.type != BaseItemKind.EPISODE && it.type != BaseItemKind.AUDIO }
                        .sortedBy { it.title.lowercase() }
                        .map { DownloadGroup.Single(it) },
                shows =
                    done
                        .filter { it.type == BaseItemKind.EPISODE }
                        .groupBy { it.seriesId?.toString() ?: it.seriesName.orEmpty() }
                        .map { (_, list) ->
                            DownloadGroup.Show(
                                list.first().seriesId,
                                list.first().seriesName ?: list.first().title,
                                list.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 })),
                            )
                        }.sortedBy { it.name.lowercase() },
                music =
                    done
                        .filter { it.type == BaseItemKind.AUDIO }
                        .groupBy { it.albumId?.toString() ?: it.album.orEmpty() }
                        .map { (_, list) ->
                            DownloadGroup.Album(
                                list.first().albumId,
                                list.first().album ?: list.first().title,
                                list.first().albumArtist ?: list.first().artists.firstOrNull(),
                                list,
                            )
                        }.sortedBy { it.name.lowercase() },
            )
        }
    }
}

/**
 * The Downloads page (`Destination.TallyDownloads`, also where an offline start lands), phones only: a top bar with
 * SELECT, the offline bar while the server cannot be reached ("Back online" for a few seconds when it answers, then the
 * full app returns), the storage bar, then IN PROGRESS (progress, speed, pause / resume / cancel), FILMS, SHOWS (one
 * row per show, opening its episodes) and MUSIC (one row per album). A tap plays from the device; a long-press offers
 * removal (for a show also its watched episodes). SELECT turns on multi-select to remove several.
 */
@Composable
fun DownloadsPage(
    modifier: Modifier = Modifier,
    viewModel: DownloadUiViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val sections = remember(entries) { DownloadSections.of(entries) }
    val speeds = rememberSpeeds(entries)

    var openShow by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<UUID, Boolean>() }
    var menuFor by remember { mutableStateOf<DownloadGroup?>(null) }
    var confirmSelected by remember { mutableStateOf(false) }

    // offline mode: the bar, and the way back to the full app once the server answers
    var wasOffline by remember { mutableStateOf(offline) }
    var backOnline by remember { mutableStateOf(false) }
    LaunchedEffect(offline) {
        if (offline) {
            wasOffline = true
            backOnline = false
        } else if (wasOffline) {
            wasOffline = false
            backOnline = true
            viewModel.completeSession()
            delay(BACK_ONLINE_MS)
            backOnline = false
            // an offline start left this page alone on the back stack: the full app starts at Home
            if (viewModel.navigationManager.backStack.size <= 1) viewModel.returnToFullApp()
        }
    }

    val show = sections.shows.firstOrNull { it.key == openShow }
    if (openShow != null && show == null) openShow = null
    BackHandler(enabled = editing || show != null) {
        if (editing) {
            editing = false
            selected.clear()
        } else {
            openShow = null
        }
    }

    fun toggle(group: DownloadGroup) {
        val ids = group.entries.map { it.itemId }
        val on = ids.all { selected[it] == true }
        ids.forEach { if (on) selected.remove(it) else selected[it] = true }
    }

    fun onTap(group: DownloadGroup) {
        when {
            editing -> toggle(group)
            group is DownloadGroup.Show -> openShow = group.key
            else -> viewModel.play(group.entries)
        }
    }

    fun onLongTap(group: DownloadGroup) {
        if (!editing) menuFor = group
    }

    val listState = rememberLazyListState()
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val canGoBack = viewModel.navigationManager.backStack.size > 1
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        val selectedCount = selected.count { it.value }
        PhoneTopBar(
            title =
                when {
                    editing -> pluralStringResource(R.plurals.tally_dlui_selected, selectedCount, selectedCount)
                    show != null -> show.name
                    else -> stringResource(R.string.tally_dl_page_title)
                },
            kicker = if (show != null && !editing) stringResource(R.string.tally_dl_page_title) else null,
            onBack =
                when {
                    editing -> null
                    show != null -> ({ openShow = null })
                    canGoBack -> ({ viewModel.navigationManager.goBack() })
                    else -> null
                },
            scrolled = listState.phoneScrolled,
        ) {
            if (!sections.isEmpty) {
                if (editing) {
                    TopBarText(
                        label = stringResource(R.string.tally_dlui_delete),
                        enabled = selectedCount > 0,
                        destructive = true,
                        onClick = { confirmSelected = true },
                    )
                    TopBarText(
                        label = stringResource(R.string.tally_dlui_done),
                        onClick = {
                            editing = false
                            selected.clear()
                        },
                    )
                } else {
                    TopBarText(label = stringResource(R.string.tally_dlui_select), onClick = { editing = true })
                }
            }
        }
        OfflineBar(offline = offline, backOnline = backOnline, onRetry = viewModel::retryServer)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottom + PhoneDimens.rowGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (show != null) {
                items(show.entries, key = { "ep-${it.itemId}" }) { entry ->
                    val group = DownloadGroup.Single(entry)
                    EntryRow(
                        entry = entry,
                        editing = editing,
                        selected = selected[entry.itemId] == true,
                        onClick = { onTap(group) },
                        onLongClick = { onLongTap(group) },
                    )
                }
                return@LazyColumn
            }
            item(key = "storage") {
                // the device's numbers take a moment on a cold start: until then the downloads' own sizes
                StorageBar(storage, fallbackUsed = entries.sumOf { it.sizeBytes ?: 0L })
            }
            if (sections.isEmpty) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.tally_dlui_empty),
                        style = PhoneType.body,
                        color = TallyColors.textSecondary,
                        modifier = Modifier.padding(horizontal = PhoneDimens.margin, vertical = 24.dp),
                    )
                }
            }
            section(R.string.tally_dlui_in_progress, sections.inProgress.size) {
                items(sections.inProgress, key = { "active-${it.itemId}" }) { entry ->
                    ActiveRow(
                        entry = entry,
                        speed = speeds[entry.itemId],
                        editing = editing,
                        selected = selected[entry.itemId] == true,
                        onToggle = { toggle(DownloadGroup.Single(entry)) },
                        onPause = { viewModel.pause(listOf(entry.itemId)) },
                        onResume = { viewModel.resume(listOf(entry.itemId)) },
                        onCancel = { viewModel.delete(listOf(entry.itemId)) },
                    )
                }
            }
            section(R.string.tally_dlui_films, sections.films.size) {
                items(sections.films, key = { it.key }) { group ->
                    EntryRow(
                        entry = group.entry,
                        editing = editing,
                        selected = selected[group.entry.itemId] == true,
                        onClick = { onTap(group) },
                        onLongClick = { onLongTap(group) },
                    )
                }
            }
            section(R.string.tally_dlui_shows, sections.shows.size) {
                items(sections.shows, key = { it.key }) { group ->
                    GroupRow(
                        group = group,
                        editing = editing,
                        selected = group.entries.all { selected[it.itemId] == true },
                        onClick = { onTap(group) },
                        onLongClick = { onLongTap(group) },
                    )
                }
            }
            section(R.string.tally_dlui_music, sections.music.size) {
                items(sections.music, key = { it.key }) { group ->
                    GroupRow(
                        group = group,
                        editing = editing,
                        selected = group.entries.all { selected[it.itemId] == true },
                        onClick = { onTap(group) },
                        onLongClick = { onLongTap(group) },
                    )
                }
            }
        }
    }

    menuFor?.let { group ->
        RemoveSheet(
            group = group,
            onRemove = { ids ->
                viewModel.delete(ids)
                menuFor = null
            },
            onPlay = {
                menuFor = null
                viewModel.play(group.entries)
            },
            onDismiss = { menuFor = null },
        )
    }
    if (confirmSelected) {
        val ids = selected.filterValues { it }.keys.toList()
        PhoneSheet(onDismiss = { confirmSelected = false }) {
            PhoneConfirmContent(
                kicker = pluralStringResource(R.plurals.tally_dlui_remove_n_q, ids.size, ids.size),
                message = null,
                detail = formatBytes(entries.filter { it.itemId in ids }.sumOf { it.sizeBytes ?: 0L }),
                confirmLabel = stringResource(R.string.tally_dlui_remove_downloads),
                cancelLabel = stringResource(R.string.tally_dlui_cancel),
                onCancel = { confirmSelected = false },
                onConfirm = {
                    viewModel.delete(ids)
                    confirmSelected = false
                    editing = false
                    selected.clear()
                },
            )
        }
    }
}

/** A section: its mono header with the count, then [content]; nothing when [count] is zero. */
private fun LazyListScope.section(
    title: Int,
    count: Int,
    content: LazyListScope.() -> Unit,
) {
    if (count == 0) return
    item(key = "header-$title") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 20.dp, bottom = 6.dp),
        ) {
            Text(text = stringResource(title).tallyUppercase(), style = PhoneType.labelLarge, color = TallyColors.text, maxLines = 1)
            Text(text = count.toString(), style = PhoneType.labelLarge, color = TallyColors.muted, maxLines = 1)
        }
    }
    content()
}

/** Bytes per second of each download under way, sampled as its progress comes in. */
@Composable
private fun rememberSpeeds(entries: List<DownloadEntry>): Map<UUID, Long> {
    val speeds = remember { mutableStateMapOf<UUID, Long>() }
    val last = remember { HashMap<UUID, Pair<Long, Long>>() }
    LaunchedEffect(entries) {
        val now = SystemClock.elapsedRealtime()
        val live = entries.filter { it.state is DownloadState.Downloading }.associateBy { it.itemId }
        (speeds.keys - live.keys).forEach { speeds.remove(it) }
        (last.keys - live.keys).forEach { last.remove(it) }
        live.forEach { (id, entry) ->
            val bytes = (entry.state as DownloadState.Downloading).bytes
            val prev = last[id]
            if (prev == null) {
                last[id] = bytes to now
            } else if (now - prev.second >= 900) {
                speeds[id] = ((bytes - prev.first) * 1000 / (now - prev.second)).coerceAtLeast(0L)
                last[id] = bytes to now
            }
        }
    }
    return speeds
}

/** `TALLY 3.2 GB · FREE 41.0 GB` over a bar: Tally's share in `textSecondary` on `ruleStrong`. */
@Composable
private fun StorageBar(
    storage: StorageInfo?,
    fallbackUsed: Long,
) {
    val used = storage?.usedBytes ?: fallbackUsed
    val free = storage?.freeBytes
    val total = (used + (free ?: 0L)).coerceAtLeast(1L)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 8.dp, bottom = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.tally_dlui_storage_used, formatBytes(used)).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (free != null) {
                Text(
                    text = stringResource(R.string.tally_dlui_storage_free, formatBytes(free)).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(TallyColors.ruleStrong),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (free == null) 0f else (used.toFloat() / total).coerceIn(if (used > 0) 0.01f else 0f, 1f))
                    .background(TallyColors.textSecondary),
            )
        }
    }
}

/**
 * Offline: `OFFLINE · SHOWING YOUR DOWNLOADS` and RETRY, on `groundRaised` with a `live` square. Back online: `BACK
 * ONLINE` with an accent square, for a few seconds. Nothing otherwise.
 */
@Composable
private fun OfflineBar(
    offline: Boolean,
    backOnline: Boolean,
    onRetry: () -> Unit,
) {
    if (!offline && !backOnline) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PhoneDimens.touchTarget + 8.dp)
                .background(TallyColors.groundRaised)
                .padding(start = PhoneDimens.margin, end = 8.dp),
    ) {
        IndicatorSquare(color = if (offline) TallyColors.live else TallyColors.accent, size = 8.dp)
        Text(
            text = stringResource(if (offline) R.string.tally_dlui_offline_bar else R.string.tally_dlui_back_online).tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.text,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (offline) {
            PhoneButton(label = stringResource(R.string.tally_dlui_retry), onClick = onRetry)
        }
    }
}

/** A top-bar text action (SELECT, DONE, DELETE): mono, a 48dp target. */
@Composable
private fun TopBarText(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = PhoneDimens.touchTarget)
                .then(if (enabled) Modifier.phoneClickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp),
    ) {
        Text(
            text = label.tallyUppercase(),
            style = PhoneType.labelLarge,
            color =
                when {
                    !enabled -> TallyColors.muted
                    destructive -> TallyColors.liveText
                    else -> TallyColors.text
                },
            maxLines = 1,
        )
    }
}

/** Multi-select's square: accent filled when chosen, a `ruleStrong` frame when not. */
@Composable
private fun SelectSquare(selected: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .then(
                if (selected) {
                    Modifier.background(TallyColors.accent)
                } else {
                    Modifier.border(PhoneDimens.hairline, TallyColors.ruleStrong)
                },
            ),
    )
}

/** Local artwork (a file the download keeps), [width] x [height] on `screen` in a 1dp `rule` frame. */
@Composable
private fun LocalArt(
    path: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(width, height)
                .background(TallyColors.screen)
                .border(PhoneDimens.hairline, TallyColors.rule)
                .clipToBounds(),
    ) {
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
            )
        }
    }
}

/** Artwork of an entry: a film's poster, an episode's still, a track's cover. */
@Composable
private fun EntryArt(entry: DownloadEntry) {
    when (entry.type) {
        BaseItemKind.EPISODE -> LocalArt(entry.thumbPath ?: entry.backdropPath, 96.dp, 54.dp)
        BaseItemKind.AUDIO -> LocalArt(entry.posterPath, 54.dp, 54.dp)
        else -> LocalArt(entry.posterPath ?: entry.thumbPath, 48.dp, 72.dp)
    }
}

/** `S1 · E3 · ` for an episode, empty otherwise. */
private fun episodeCode(entry: DownloadEntry): String? {
    val episode = entry.episodeNumber ?: return null
    return entry.seasonNumber?.let { "S$it · E$episode" } ?: "E$episode"
}

/** `ORIGINAL · 14.8 GB` / `720p · 4 Mbps · 1.2 GB`. */
private fun qualityAndSize(entry: DownloadEntry): String =
    listOfNotNull(entry.qualityLabel, entry.sizeBytes?.let(::formatBytes)).joinToString(" · ")

@Composable
private fun EntryRow(
    entry: DownloadEntry,
    editing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    RowFrame(editing = editing, selected = selected, onClick = onClick, onLongClick = onLongClick) {
        EntryArt(entry)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            episodeCode(entry)?.let {
                Text(text = it, style = PhoneType.label, color = TallyColors.muted, maxLines = 1)
            }
            Text(
                text = entry.title,
                style = PhoneType.headline,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = qualityAndSize(entry).tallyUppercase(),
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.played) {
            Text(
                text = stringResource(R.string.tally_media_seen).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GroupRow(
    group: DownloadGroup,
    editing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val first = group.entries.first()
    val size = formatBytes(group.entries.sumOf { it.sizeBytes ?: 0L })
    val (name, meta) =
        when (group) {
            is DownloadGroup.Show -> {
                group.name to
                    listOf(pluralStringResource(R.plurals.tally_dlui_episodes, group.entries.size, group.entries.size), size)
                        .joinToString(" · ")
            }

            is DownloadGroup.Album -> {
                group.name to
                    listOfNotNull(
                        group.artist,
                        pluralStringResource(R.plurals.tally_dlui_tracks, group.entries.size, group.entries.size),
                        size,
                    ).joinToString(" · ")
            }

            is DownloadGroup.Single -> {
                first.title to qualityAndSize(first)
            }
        }
    RowFrame(editing = editing, selected = selected, onClick = onClick, onLongClick = onLongClick) {
        if (group is DownloadGroup.Album) {
            LocalArt(first.posterPath, 54.dp, 54.dp)
        } else {
            LocalArt(first.posterPath ?: first.thumbPath, 48.dp, 72.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(text = name, style = PhoneType.headline, color = TallyColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = meta.tallyUppercase(),
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group is DownloadGroup.Show && !editing) {
            Text(
                text = "›",
                style = PhoneType.headline,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
    }
}

/** A download under way: artwork, title, the state line and progress, then pause / resume and cancel. */
@Composable
private fun ActiveRow(
    entry: DownloadEntry,
    speed: Long?,
    editing: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val state = entry.state
    val percent = stringResource(R.string.tally_dlui_percent, (entry.fraction() * 100).toInt().coerceIn(0, 99))
    val line =
        when (state) {
            is DownloadState.Downloading -> {
                listOfNotNull(percent, speed?.takeIf { it > 0 }?.let { stringResource(R.string.tally_dlui_speed, formatBytes(it)) })
                    .joinToString(" · ")
            }

            is DownloadState.Paused -> {
                listOf(stringResource(R.string.tally_dlui_paused), percent).joinToString(" · ")
            }

            is DownloadState.Queued -> {
                state.waitingFor ?: stringResource(R.string.tally_dlui_queued)
            }

            is DownloadState.Failed -> {
                state.reason
            }

            DownloadState.Done -> {
                ""
            }
        }
    RowFrame(editing = editing, selected = selected, onClick = { if (editing) onToggle() }, onLongClick = null) {
        EntryArt(entry)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(
                text =
                    listOfNotNull(
                        entry.seriesName,
                        episodeCode(entry),
                    ).joinToString(" · ").ifEmpty { entry.qualityLabel }.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = entry.title, style = PhoneType.headline, color = TallyColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (state is DownloadState.Failed) line else line.tallyUppercase(),
                style = if (state is DownloadState.Failed) PhoneType.bodySmall else PhoneType.meta,
                color = if (state is DownloadState.Failed) TallyColors.liveText else TallyColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ProgressLine(entry.fraction(), Modifier.padding(top = 3.dp))
        }
        if (!editing) {
            val paused = state is DownloadState.Paused || state is DownloadState.Failed
            GlyphButton(
                glyph = stringResource(if (paused) R.string.fa_play else R.string.fa_pause),
                label = stringResource(if (paused) R.string.tally_dlui_resume else R.string.tally_dlui_pause),
                onClick = if (paused) onResume else onPause,
            )
            GlyphButton(
                glyph = stringResource(R.string.fa_xmark),
                label = stringResource(R.string.tally_dlui_cancel_download),
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun GlyphButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(PhoneDimens.touchTarget)
                .semantics { contentDescription = label }
                .phoneClickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).border(PhoneDimens.hairline, TallyColors.ruleStrong),
        ) {
            Text(text = glyph, fontFamily = FontAwesome, fontSize = 15.sp, color = TallyColors.text, maxLines = 1)
        }
    }
}

/** A page row: at least 72dp, the whole row the touch target; in multi-select the square at its start. */
@Composable
private fun RowFrame(
    editing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .phoneClickable(onLongClick = onLongClick, onClick = onClick)
                .padding(start = PhoneDimens.margin, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (editing) SelectSquare(selected)
        content()
        Spacer(Modifier.width(4.dp))
    }
}

/** A long-press on a download: PLAY, REMOVE DOWNLOAD(S), and for a show REMOVE WATCHED EPISODES. */
@Composable
private fun RemoveSheet(
    group: DownloadGroup,
    onRemove: (List<UUID>) -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        when (group) {
            is DownloadGroup.Show -> group.name
            is DownloadGroup.Album -> group.name
            is DownloadGroup.Single -> group.entry.title
        }
    val watched = group.entries.filter { it.played }
    PhoneSheet(onDismiss = onDismiss) {
        PhonePanelFrame(title = title) {
            PhoneDialogRow(onClick = onPlay, headline = { Text(stringResource(R.string.tally_dlui_play_offline)) })
            if (group is DownloadGroup.Show && watched.isNotEmpty()) {
                PhoneDialogRow(
                    onClick = { onRemove(watched.map { it.itemId }) },
                    destructive = true,
                    headline = {
                        Text(pluralStringResource(R.plurals.tally_dlui_remove_watched, watched.size, watched.size))
                    },
                )
            }
            PhoneDialogRow(
                onClick = { onRemove(group.entries.map { it.itemId }) },
                destructive = true,
                headline = {
                    Text(
                        stringResource(
                            if (group.entries.size > 1) R.string.tally_dlui_remove_downloads else R.string.tally_dlui_remove_download,
                        ),
                    )
                },
                supporting = { Text(formatBytes(group.entries.sumOf { it.sizeBytes ?: 0L })) },
            )
        }
    }
}
