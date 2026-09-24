package io.github.scdouglas1999.tally.media.series.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ConfirmDialog
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.TrailerDialog
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.detail.series.EpisodeList
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewPosition
import com.github.damontecres.wholphin.ui.detail.series.SeriesState
import com.github.damontecres.wholphin.ui.detail.series.SeriesViewModel
import com.github.damontecres.wholphin.ui.detail.series.buildDialogForSeason
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import io.github.scdouglas1999.tally.downloads.ui.DownloadStatus
import io.github.scdouglas1999.tally.downloads.ui.DownloadSubject
import io.github.scdouglas1999.tally.downloads.ui.DownloadUi
import io.github.scdouglas1999.tally.downloads.ui.DownloadedSquare
import io.github.scdouglas1999.tally.downloads.ui.downloadEdge
import io.github.scdouglas1999.tally.downloads.ui.downloadMark
import io.github.scdouglas1999.tally.downloads.ui.phoneAction
import io.github.scdouglas1999.tally.downloads.ui.rememberDownloadUi
import io.github.scdouglas1999.tally.downloads.ui.status
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.movie.phone.PhoneAbout
import io.github.scdouglas1999.tally.media.movie.phone.PhoneAction
import io.github.scdouglas1999.tally.media.movie.phone.PhoneCenteredLabel
import io.github.scdouglas1999.tally.media.movie.phone.PhoneDetailBackdrop
import io.github.scdouglas1999.tally.media.movie.phone.PhoneDetailHeading
import io.github.scdouglas1999.tally.media.movie.phone.PhoneItemActions
import io.github.scdouglas1999.tally.media.movie.phone.PhoneStatusBarGround
import io.github.scdouglas1999.tally.media.movie.phone.phoneDiscoverRow
import io.github.scdouglas1999.tally.media.movie.phone.phoneExtrasRow
import io.github.scdouglas1999.tally.media.movie.phone.phonePeopleRow
import io.github.scdouglas1999.tally.media.movie.phone.phoneSimilarRow
import io.github.scdouglas1999.tally.media.series.NextUpLabel
import io.github.scdouglas1999.tally.media.series.WatchedTick
import io.github.scdouglas1999.tally.media.series.airDate
import io.github.scdouglas1999.tally.media.series.createdByLine
import io.github.scdouglas1999.tally.media.series.episodeNumber
import io.github.scdouglas1999.tally.media.series.nextUpLabel
import io.github.scdouglas1999.tally.media.series.seasonSummary
import io.github.scdouglas1999.tally.media.series.seriesMeta
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.extensions.ticks
import java.util.Locale

/** Which page hands its state here: the show page (`TallySeriesPage`) or the season rundown (`TallySeasonRundown`). */
enum class PhoneSeriesMode { SHOW, RUNDOWN }

/**
 * The show page and the season rundown on a phone, one layout: the show's backdrop, heading, NEXT UP · S1 E6 and the
 * action buttons, the overview, then the seasons as a mono tab strip that stays pinned under the top once scrolled,
 * the chosen season's episodes (one 88dp row each), and the TV pages' rows. The season shown is upstream's
 * [SeriesViewModel] position and episode list (`loadEpisodes`), as on the TV rundown. [mode] RUNDOWN (reached from an
 * episode's EPISODES button) opens scrolled to the tabs. [onWatchSeries] asks to confirm marking the whole show; null
 * (the rundown, whose TV page has no such button) shows the same confirmation here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneSeriesLoaded(
    mode: PhoneSeriesMode,
    preferences: UserPreferences,
    series: BaseItem,
    state: SeriesState,
    nextUp: BaseItem?,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
    viewModel: SeriesViewModel,
    onWatchSeries: (() -> Unit)?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val seriesNow by rememberUpdatedState(series)
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val position by viewModel.position.collectAsState()
    val seasons = state.seasons
    val episodes = state.episodes
    var showTrailers by remember { mutableStateOf(false) }
    var confirmWatch by remember { mutableStateOf(false) }
    val downloads = rememberDownloadUi()
    val shownSeason = seasons.getOrNull(position.seasonTabIndex)
    val showSubject =
        remember(series.id, series.name, shownSeason?.id) {
            DownloadSubject.Show(series.id, shownSeason?.id, shownSeason?.indexNumber, series.name ?: "")
        }

    fun selectSeason(index: Int) {
        val season = seasons.getOrNull(index) ?: return
        viewModel.loadEpisodes(season.id, season.indexNumber)
        viewModel.position.update { SeriesOverviewPosition(index, 0) }
    }

    // The show page loads no episodes by itself: start on the season of the next-up episode, else the first. Until
    // the viewer picks a tab, a next-up that arrives later moves it there.
    var chosenByViewer by rememberSaveable { mutableStateOf(mode == PhoneSeriesMode.RUNDOWN) }
    LaunchedEffect(seasons.size, nextUp?.id) {
        if (seasons.isEmpty() || chosenByViewer) return@LaunchedEffect
        val target =
            nextUp
                ?.data
                ?.seasonId
                ?.let { id -> seasons.indexOfFirst { it?.id == id } }
                ?.takeIf { it >= 0 } ?: 0
        val loaded = (episodes as? EpisodeList.Success)?.seasonId
        if (loaded == null || loaded != seasons.getOrNull(target)?.id) selectSeason(target)
    }
    // Back from playback: the season's progress has moved.
    LifecycleResumeEffect(Unit) {
        val loaded = viewModel.state.value.episodes as? EpisodeList.Success
        if (loaded != null) {
            viewModel.state.value.seasons
                .firstOrNull { it?.id == loaded.seasonId }
                ?.let { viewModel.loadEpisodes(it.id, it.indexNumber) }
        }
        onPauseOrDispose { }
    }

    fun openSeriesMenu() {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = false,
                item = seriesNow,
                chosenStreams = null,
                showGoTo = false,
                showStreamChoices = false,
                canDelete = state.canDeleteSeries,
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
            )
    }

    fun openItemMenu(item: BaseItem) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = item,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = false,
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
            )
    }

    fun openEpisodeMenu(
        episode: BaseItem,
        index: Int,
    ) {
        viewModel.position.update { it.copy(episodeRowIndex = index) }
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = episode,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = true,
                canDelete = viewModel.canDelete(episode, preferences.appPreferences),
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions =
                    contextActions.copy(
                        onClickWatch = { id, watched -> viewModel.setWatched(id, watched, index) },
                        onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite, index) },
                        // "Go to" from an episode's menu opens that episode's own page, as on the TV rundown.
                        onClickGoTo = { viewModel.navigateTo(Destination.MediaItem(it)) },
                    ),
            )
    }

    fun openSeasonMenu(season: BaseItem) {
        dialogs.dialog =
            buildDialogForSeason(
                resources = resources,
                s = season,
                canDelete = viewModel.canDelete(season, preferences.appPreferences),
                onClickItem = { viewModel.navigateTo(it.destination()) },
                markPlayed = { played -> viewModel.setSeasonWatched(season.id, played) },
                onClickPlay = { shuffle ->
                    viewModel.navigateTo(Destination.PlaybackList(itemId = season.id, shuffle = shuffle))
                },
                onClickDelete = { dialogs.deleteItem = it },
            )
    }

    val onTrailer: (Trailer) -> Unit = { trailer -> TrailerService.onClick(context, trailer, viewModel::navigateTo) }
    val special = stringResource(R.string.tally_series_special)
    val primaryLabel =
        when (val label = nextUpLabel(nextUp?.data, special)) {
            NextUpLabel.Play -> stringResource(R.string.tally_media_play)
            is NextUpLabel.NextUp -> stringResource(R.string.tally_series_next_up_code, label.code)
            is NextUpLabel.Resume -> stringResource(R.string.tally_series_resume_code, label.code, label.percent)
        }
    val nextUpProgress =
        nextUp?.let { up ->
            val ticks = up.data.userData?.playbackPositionTicks ?: 0L
            if (!up.played && ticks > 0L) resumePercent(ticks, up.data.runTimeTicks ?: 0L) / 100f else null
        }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val images = LocalImageUrlService.current
    val backdropUrl = remember(series.id) { images.getItemImageUrl(series, ImageType.BACKDROP) }
    val tabsIndex = 4
    val statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current)

    // The rundown opens on its season: scroll the tab strip to the top once the season is in.
    var scrolledToTabs by rememberSaveable { mutableStateOf(mode != PhoneSeriesMode.RUNDOWN) }
    LaunchedEffect(episodes, seasons.size) {
        if (!scrolledToTabs && episodes is EpisodeList.Success && seasons.isNotEmpty()) {
            scrolledToTabs = true
            listState.scrollToItem(tabsIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(TallyColors.ground)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottom + 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Keep these four items first: [tabsIndex] counts them.
            item(key = "backdrop") {
                PhoneDetailBackdrop(imageUrl = backdropUrl, onBack = { backDispatcher?.onBackPressed() })
            }
            item(key = "heading") {
                val logoUrl = rememberLogoUrl(series)
                PhoneDetailHeading(
                    kicker = stringResource(R.string.tally_series_kicker),
                    title = series.name ?: "",
                    logoUrl = if (preferences.appPreferences.interfacePreferences.showLogos) logoUrl else null,
                    meta = seriesMeta(series, seasons.size),
                    genres = series.data.genres.orEmpty(),
                )
            }
            item(key = "actions") {
                PhoneItemActions(
                    item = series,
                    primaryLabel = primaryLabel,
                    primaryProgress = nextUpProgress,
                    extra =
                        buildList {
                            if (state.trailers.isNotEmpty()) {
                                add(
                                    PhoneAction(
                                        key = "trailer",
                                        glyph = stringResource(R.string.fa_film),
                                        label = stringResource(R.string.tally_media_trailer),
                                        onClick = {
                                            if (state.trailers.size == 1) {
                                                onTrailer(state.trailers.first())
                                            } else {
                                                showTrailers = true
                                            }
                                        },
                                    ),
                                )
                            }
                        },
                    trailing =
                        buildList {
                            add(downloads.phoneAction(showSubject))
                            state.discoverSeries?.let { discover ->
                                add(
                                    PhoneAction(
                                        key = "discover",
                                        glyph = stringResource(R.string.fa_magnifying_glass_plus),
                                        label = stringResource(R.string.discover),
                                        onClick = { viewModel.navigateTo(Destination.DiscoveredItem(discover)) },
                                    ),
                                )
                            }
                        },
                    onPlay = { viewModel.playNextUp() },
                    onWatch = onWatchSeries ?: { confirmWatch = true },
                    onFavorite = { viewModel.setFavorite(series.id, !series.favorite, null) },
                    onMore = ::openSeriesMenu,
                )
            }
            item(key = "about") {
                PhoneAbout(
                    overview = series.data.overview,
                    caption = createdByLine(series),
                    tech = emptyList(),
                    bottomGap = if (seasons.isNotEmpty()) 12.dp else PhoneDimens.rowGap,
                )
            }
            if (seasons.isNotEmpty()) {
                stickyHeader(key = "tabs") {
                    SeasonTabs(
                        topRoom = { tabsTopRoom(listState, tabsIndex, statusBarPx) },
                        seasons = seasons,
                        current = position.seasonTabIndex,
                        onSelect = { index ->
                            chosenByViewer = true
                            if (index != position.seasonTabIndex || episodes !is EpisodeList.Success) {
                                selectSeason(index)
                            }
                        },
                        onMenu = ::openSeasonMenu,
                        download = {
                            val season = shownSeason
                            if (season != null) {
                                SeasonDownloadButton(
                                    downloads = downloads,
                                    subject =
                                        DownloadSubject.Season(
                                            seriesId = series.id,
                                            seasonId = season.id,
                                            seasonNumber = season.indexNumber,
                                            title = "${series.name ?: ""} · ${seasonTabLabel(season)}",
                                        ),
                                    total =
                                        (episodes as? EpisodeList.Success)
                                            ?.takeIf { it.seasonId == season.id }
                                            ?.episodes
                                            ?.size,
                                    label =
                                        when (val n = season.indexNumber) {
                                            null -> stringResource(R.string.tally_dlui_download_ellipsis)
                                            0 -> stringResource(R.string.tally_dlui_download_specials)
                                            else -> stringResource(R.string.tally_dlui_download_season, n)
                                        },
                                )
                            }
                        },
                    )
                }
                when (episodes) {
                    EpisodeList.Loading -> {
                        item(key = "episodes-loading") {
                            PhoneCenteredLabel(
                                text = stringResource(R.string.tally_media_loading),
                                modifier = Modifier.height(88.dp),
                            )
                        }
                    }

                    is EpisodeList.Error -> {
                        item(key = "episodes-error") {
                            PhoneCenteredLabel(
                                text = episodes.message ?: stringResource(R.string.tally_media_error_title),
                                modifier = Modifier.height(88.dp),
                            )
                        }
                    }

                    is EpisodeList.Success -> {
                        val list: List<BaseItem?> = episodes.episodes
                        item(key = "summary-${episodes.seasonId}") { SeasonSummaryLine(list) }
                        if (list.isEmpty()) {
                            item(key = "episodes-empty") {
                                PhoneCenteredLabel(
                                    text = stringResource(R.string.tally_series_no_episodes),
                                    modifier = Modifier.height(88.dp),
                                )
                            }
                        }
                        itemsIndexed(list, key = { index, ep -> ep?.id ?: "placeholder-$index" }) { index, episode ->
                            if (episode == null) {
                                Spacer(Modifier.fillMaxWidth().height(RowHeight))
                            } else {
                                PhoneEpisodeRow(
                                    episode = episode,
                                    nextUp = nextUp?.id == episode.id,
                                    onClick = {
                                        val resumeMs =
                                            (episode.data.userData?.playbackPositionTicks ?: 0L).ticks.inWholeMilliseconds
                                        viewModel.navigateTo(Destination.Playback(episode.id, resumeMs))
                                    },
                                    onLongClick = { openEpisodeMenu(episode, index) },
                                )
                            }
                        }
                        item(key = "episodes-end") { Spacer(Modifier.height(PhoneDimens.rowGap)) }
                    }
                }
            }
            phoneExtrasRow(
                extras = state.extras,
                onOpen = { viewModel.navigateTo(it.destination) },
                // Upstream's extras rows have no long-press menu.
                onMenu = null,
            )
            phonePeopleRow(
                people = state.people,
                onOpen = { viewModel.navigateTo(Destination.MediaItem(it.id, BaseItemKind.PERSON)) },
                onMenu = { person ->
                    dialogs.contextMenu =
                        ContextMenu.ForPerson(
                            fromLongClick = true,
                            person = person,
                            actions =
                                PersonContextActions(
                                    navigateTo = contextActions.navigateTo,
                                    onClickFavorite = contextActions.onClickFavorite,
                                ),
                        )
                },
            )
            phoneSimilarRow(
                items = state.similar,
                onOpen = { viewModel.navigateTo(it.destination()) },
                onMenu = ::openItemMenu,
                onPlay = { if (it.type.playable) viewModel.navigateTo(Destination.Playback(it)) },
            )
            phoneDiscoverRow(state.discovered) { viewModel.navigateTo(it.destination) }
        }
        PhoneStatusBarGround(visible = scrolled)
    }
    if (showTrailers) {
        TrailerDialog(
            onDismissRequest = { showTrailers = false },
            trailers = state.trailers,
            onClick = onTrailer,
        )
    }
    if (confirmWatch) {
        val played = series.played
        ConfirmDialog(
            title = series.name ?: "",
            body =
                stringResource(
                    if (played) R.string.mark_entire_series_as_unplayed else R.string.mark_entire_series_as_played,
                ),
            onCancel = { confirmWatch = false },
            onConfirm = {
                viewModel.setWatchedSeries(!played)
                confirmWatch = false
            },
        )
    }
}

/**
 * Room above the tab strip, in px: none while it scrolls with the page, growing to the status bar's height as it
 * reaches the top, so once pinned it sits under the status bar (and it reaches that place without a jump).
 */
private fun tabsTopRoom(
    listState: LazyListState,
    tabsIndex: Int,
    statusBarPx: Int,
): Int {
    if (listState.firstVisibleItemIndex > tabsIndex) return statusBarPx
    val offset =
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == tabsIndex }
            ?.offset ?: return 0
    return (statusBarPx - offset).coerceIn(0, statusBarPx)
}

/**
 * The seasons as a horizontally scrolling mono tab strip (SEASON 1, SEASON 2, SPECIALS): the current one in `text`
 * over a 2dp accent bar, the others `muted`; a 1dp `rule` under the strip. A `ground` band ([topRoom] px) grows above it
 * as it reaches the top, so once pinned it stays clear of the status bar. Long-press opens the
 * season's menu, as a long OK does on the TV.
 */
@Composable
private fun SeasonTabs(
    topRoom: () -> Int,
    seasons: List<BaseItem?>,
    current: Int,
    onSelect: (Int) -> Unit,
    onMenu: (BaseItem) -> Unit,
    download: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().background(TallyColors.ground)) {
        Spacer(
            Modifier.fillMaxWidth().layout { measurable, constraints ->
                val room = topRoom()
                val placeable = measurable.measure(constraints.copy(minHeight = room, maxHeight = room))
                layout(placeable.width, room) { placeable.place(0, 0) }
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PhoneDimens.touchTarget)
                    .drawBehind {
                        val stroke = PhoneDimens.hairline.toPx()
                        drawLine(
                            color = TallyColors.rule,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    },
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = PhoneDimens.margin - 12.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                itemsIndexed(seasons, key = { index, season -> season?.id ?: "season-$index" }) { index, season ->
                    val selected = index == current
                    val label = seasonTabLabel(season)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .phoneClickable(onLongClick = season?.let { { onMenu(it) } }) { onSelect(index) }
                                .drawWithContent {
                                    drawContent()
                                    if (selected) {
                                        val bar = 2.dp.toPx()
                                        drawRect(
                                            color = TallyColors.accent,
                                            topLeft = Offset(0f, size.height - bar),
                                            size = Size(size.width, bar),
                                        )
                                    }
                                }.padding(horizontal = 12.dp),
                    ) {
                        Text(
                            text = label.tallyUppercase(),
                            style = PhoneType.labelLarge,
                            color = if (selected) TallyColors.text else TallyColors.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
            download()
        }
    }
}

/**
 * The download glyph at the end of the season tabs (`Download season 2`): a 48dp target with the download glyph,
 * a thin progress bar while the season downloads, a check once it is downloaded. Tap and long-press as DOWNLOAD.
 */
@Composable
private fun SeasonDownloadButton(
    downloads: DownloadUi,
    subject: DownloadSubject.Season,
    total: Int?,
    label: String,
) {
    val status = downloads.status(subject)
    // the check only once every episode of the season is on the device
    val complete = status is DownloadStatus.Done && (total == null || status.entries.size >= total)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxHeight()
                .width(PhoneDimens.touchTarget + 8.dp)
                .semantics { contentDescription = label }
                .phoneClickable(onLongClick = { downloads.onLongPress(subject) }) { downloads.onTap(subject, status) },
    ) {
        when (status) {
            is DownloadStatus.Active -> {
                Box(Modifier.size(width = 20.dp, height = 3.dp).background(TallyColors.ruleStrong)) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(status.progress.coerceIn(0.02f, 1f))
                            .background(TallyColors.accent),
                    )
                }
            }

            else -> {
                Text(
                    text = stringResource(if (complete) R.string.fa_check else R.string.fa_download),
                    fontFamily = FontAwesome,
                    fontSize = 16.sp,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun seasonTabLabel(season: BaseItem?): String {
    val number = season?.indexNumber
    return when {
        number == 0 -> stringResource(R.string.tally_series_specials)
        number != null -> stringResource(R.string.tally_series_season_n, number)
        else -> season?.name ?: ""
    }
}

/** `5 EPISODES · 5 LEFT · 5M` for the loaded season, as the TV rundown's head; nothing until every episode is in. */
@Composable
private fun SeasonSummaryLine(episodes: List<BaseItem?>) {
    val loaded = episodes.takeIf { list -> list.isNotEmpty() && list.all { it != null } }
    if (loaded == null) {
        Spacer(Modifier.height(12.dp))
        return
    }
    val summary = seasonSummary(loaded.map { it!!.data })
    val text =
        buildList {
            add(pluralStringResource(R.plurals.tally_series_episodes, summary.episodes, summary.episodes))
            add(stringResource(R.string.tally_series_left, summary.left))
            if (summary.left > 0 && summary.remainingTicks > 0L) add(formatRuntime(summary.remainingTicks))
        }.joinToString(" · ").tallyUppercase()
    Text(
        text = text,
        style = PhoneType.label,
        color = TallyColors.muted,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(top = 12.dp, bottom = 4.dp),
    )
}

private val RowHeight = 88.dp
private val ThumbWidth = 128.dp
private val ThumbHeight = 72.dp

/**
 * One episode of the rundown, full width, 88dp: the 16:9 still (128dp) with its progress line and the WATCHED tag,
 * then `E02` and the title, the mono meta (runtime · air date · rating) and two lines of overview. NEXT UP (and the
 * share watched of one in progress) sits at the end of the title line, as the TV's status column.
 */
@Composable
private fun PhoneEpisodeRow(
    episode: BaseItem,
    nextUp: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dto = episode.data
    val runtime = dto.runTimeTicks ?: 0L
    val positionTicks = dto.userData?.playbackPositionTicks ?: 0L
    val percent = resumePercent(positionTicks, runtime)
    val inProgress = !episode.played && positionTicks > 0L
    val special = stringResource(R.string.tally_series_special)
    val number = if (dto.parentIndexNumber == 0) special.uppercase(Locale.US) else episodeNumber(episode.indexNumber)
    val meta =
        listOfNotNull(
            runtime.takeIf { it > 0L }?.let { formatRuntime(it) },
            airDate(dto.premiereDate),
            dto.communityRating?.let {
                stringResource(R.string.tally_media_community, String.format(Locale.US, "%.1f", it))
            },
        ).joinToString(" · ")
    val images = LocalImageUrlService.current
    val imageUrl = remember(episode.id, dto.imageTags) { images.getItemImageUrl(episode, ImageType.PRIMARY) }
    val download = downloadMark(episode.id)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .phoneClickable(onLongClick = onLongClick, onClick = onClick)
                .downloadEdge(download)
                .padding(horizontal = PhoneDimens.margin, vertical = 6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(ThumbWidth, ThumbHeight)
                    .background(TallyColors.screen)
                    .border(PhoneDimens.hairline, TallyColors.rule),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    onError = { logCoilError(imageUrl, it.result) },
                    modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
                )
            }
            if (inProgress && percent in 1..99) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(TallyColors.ruleStrong),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(percent / 100f)
                            .background(TallyColors.accent),
                    )
                }
            }
            if (episode.played) WatchedTick(modifier = Modifier.align(Alignment.TopEnd))
            if (download?.done == true) {
                DownloadedSquare(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 5.dp, bottom = if (inProgress && percent in 1..99) 8.dp else 5.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number != null) {
                    Text(
                        text = number,
                        style = PhoneType.labelLarge,
                        color = if (nextUp) TallyColors.accent else TallyColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = episode.name ?: "",
                    style = PhoneType.headline,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (nextUp || inProgress) {
                    Spacer(Modifier.weight(1f, fill = true).width(6.dp))
                    StatusMark(nextUp = nextUp, percent = if (inProgress) percent.coerceAtLeast(1) else null)
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = PhoneType.meta,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!dto.overview.isNullOrBlank()) {
                Text(
                    text = dto.overview!!,
                    style = PhoneType.bodySmall,
                    color = TallyColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `NEXT UP` in a 1dp accent box, and/or the share watched in mono. */
@Composable
private fun StatusMark(
    nextUp: Boolean,
    percent: Int?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (nextUp) {
            Text(
                text = stringResource(R.string.tally_series_next_up).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
                softWrap = false,
                modifier =
                    Modifier
                        .border(PhoneDimens.hairline, TallyColors.accent)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (percent != null) {
            Text(
                text = "$percent%",
                style = PhoneType.label,
                color = TallyColors.text,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
