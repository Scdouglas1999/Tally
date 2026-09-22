package com.github.damontecres.wholphin.jellytv.media.series

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.jellytv.media.kit.CapsLift
import com.github.damontecres.wholphin.jellytv.media.kit.ItemDialogsHost
import com.github.damontecres.wholphin.jellytv.media.kit.ItemDialogsState
import com.github.damontecres.wholphin.jellytv.media.kit.MediaRow
import com.github.damontecres.wholphin.jellytv.media.kit.PersonCard
import com.github.damontecres.wholphin.jellytv.media.kit.formatEndsAt
import com.github.damontecres.wholphin.jellytv.media.kit.formatRuntime
import com.github.damontecres.wholphin.jellytv.media.kit.resumePercent
import com.github.damontecres.wholphin.jellytv.ui.components.EmptyState
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.series.EpisodeList
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewPosition
import com.github.damontecres.wholphin.ui.detail.series.SeriesPageType
import com.github.damontecres.wholphin.ui.detail.series.SeriesViewModel
import com.github.damontecres.wholphin.ui.detail.series.buildDialogForSeason
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration

/**
 * The season rundown: the Tally `SeriesOverview`. A fixed head (series name, season tabs, the
 * season's summary) over a numbered list of the season's episodes with their state. Data and
 * actions are upstream's [SeriesViewModel] in [SeriesPageType.OVERVIEW] mode.
 */
@Composable
fun JtvSeasonRundown(
    destination: Destination.SeriesOverview,
    preferences: UserPreferences,
    initialSeasonEpisode: SeasonEpisodeIds?,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel =
        hiltViewModel<SeriesViewModel, SeriesViewModel.Factory>(
            creationCallback = {
                it.create(destination.itemId, initialSeasonEpisode, SeriesPageType.OVERVIEW)
            },
        ),
    extras: JtvSeriesExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val nextUp by extras.nextUp.collectAsState()
    val position by viewModel.position.collectAsState()
    val currentPosition by rememberUpdatedState(position)
    val episodeList =
        remember(state.episodes) { (state.episodes as? EpisodeList.Success)?.episodes }
    val dialogs = remember { ItemDialogsState() }
    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)

    // As upstream: coming back to the page (after playback) reloads the season's episodes.
    LaunchedEffect(Unit) {
        if (state.seasons.isNotEmpty()) {
            state.seasons.getOrNull(position.seasonTabIndex)?.let {
                viewModel.loadEpisodes(it.id, it.indexNumber)
            }
        }
    }

    val contextActions =
        remember(dialogs) {
            ContextMenuActions(
                navigateTo = viewModel::navigateTo,
                onClickWatch = { itemId, watched ->
                    viewModel.setWatched(itemId, watched, currentPosition.episodeRowIndex)
                },
                onClickFavorite = { itemId, favorite ->
                    viewModel.setFavorite(itemId, favorite, currentPosition.episodeRowIndex)
                },
                onClickAddPlaylist = { dialogs.playlistItemId = it },
                onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                onDeleteItem = viewModel::deleteItem,
                onChooseVersion = { item, source ->
                    viewModel.savePlayVersion(item, source.id!!.toUUID())
                },
                onChooseTracks = { result ->
                    viewModel.saveTrackSelection(
                        result.item,
                        result.itemPlayback,
                        result.trackIndex,
                        result.streamType,
                    )
                },
                onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
                onClearChosenStreams = {
                    val focusedEpisode =
                        (state.episodes as? EpisodeList.Success)
                            ?.episodes
                            ?.getOrNull(currentPosition.episodeRowIndex)
                    if (focusedEpisode != null) {
                        viewModel.clearChosenStreams(focusedEpisode, it)
                    }
                },
                // "Go to" from an episode's menu opens that episode's own page.
                onClickGoTo = { viewModel.navigateTo(Destination.MediaItem(it)) },
            )
        }

    LaunchedEffect(position, state.episodes) {
        val focusedEpisode =
            (state.episodes as? EpisodeList.Success)
                ?.episodes
                ?.getOrNull(position.episodeRowIndex)
        focusedEpisode?.let {
            viewModel.lookUpChosenTracks(it.id, it)
            viewModel.lookupPeopleInEpisode(it)
        }
    }

    JtvScale {
        CompositionLocalProvider(LocalContentColor provides JtvColors.text) {
            ProvideTextStyle(JtvType.body) {
                Box(modifier = modifier.fillMaxSize()) {
                    when (val loading = state.series) {
                        is DataLoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.jtv_media_error_title),
                                subtitle =
                                    loading.localizedMessage.ifBlank {
                                        stringResource(R.string.jtv_media_error_body)
                                    },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(JtvDimens.marginHorizontal),
                            )
                        }

                        DataLoadingState.Loading,
                        DataLoadingState.Pending,
                        -> {
                            LoadingMark(Modifier.fillMaxSize(), "jtv-rundown-loading")
                        }

                        is DataLoadingState.Success -> {
                            LifecycleResumeEffect(destination.itemId) {
                                viewModel.onResumePage()
                                extras.load(destination.itemId, withSeasons = false)
                                // Back from playback: the focused episode's progress has moved.
                                val eps = viewModel.state.value.episodes as? EpisodeList.Success
                                eps?.episodes?.getOrNull(currentPosition.episodeRowIndex)?.let {
                                    viewModel.refreshEpisode(it.id, currentPosition.episodeRowIndex)
                                }
                                onPauseOrDispose {
                                    viewModel.release()
                                }
                            }
                            RundownLoaded(
                                preferences = preferences,
                                series = loading.data,
                                viewModel = viewModel,
                                position = position,
                                nextUp = nextUp,
                                episodeList = episodeList,
                                dialogs = dialogs,
                                contextActions = contextActions,
                            )
                        }
                    }
                }
            }
        }
    }
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = viewModel.streamChoiceService::chooseSource,
        preferredSubtitleLanguage = userDto?.configuration?.subtitleLanguagePreference,
        showFilePath = userDto?.policy?.isAdministrator == true,
        onConfirmDelete = viewModel::deleteItem,
    )
}

@Composable
private fun RundownLoaded(
    preferences: UserPreferences,
    series: BaseItem,
    viewModel: SeriesViewModel,
    position: SeriesOverviewPosition,
    nextUp: BaseItem?,
    episodeList: List<BaseItem?>?,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val seasons = state.seasons
    val eps = state.episodes as? EpisodeList.Success
    val seasonId = eps?.seasonId

    // Focus bookkeeping. `pendingRow` is the row to focus once it is composed; `lastRow` is the
    // row that last had focus in this season, restored when focus comes back from the tabs.
    var pendingRow by remember { mutableStateOf<Int?>(viewModel.position.value.episodeRowIndex) }
    var lastRow by remember { mutableStateOf<Pair<UUID, Int>?>(null) }
    var reloadAfterSeasons by remember { mutableStateOf(false) }
    val tabRequesters = remember(seasons.size) { List(seasons.size) { FocusRequester() } }

    LaunchedEffect(state.seasons) {
        if (reloadAfterSeasons) {
            reloadAfterSeasons = false
            seasons.getOrNull(position.seasonTabIndex)?.let {
                viewModel.loadEpisodes(it.id, it.indexNumber)
            }
        }
    }

    val listState =
        remember(seasonId) {
            LazyListState(
                firstVisibleItemIndex =
                    if (eps != null && eps.seasonId == seasonId) {
                        (pendingRow ?: 0).coerceAtLeast(0)
                    } else {
                        0
                    },
            )
        }

    fun onChangeSeason(index: Int) {
        if (index != position.seasonTabIndex) {
            seasons.getOrNull(index)?.let { season ->
                viewModel.loadEpisodes(season.id, season.indexNumber)
                viewModel.position.update { SeriesOverviewPosition(index, 0) }
            }
        }
    }

    /** DOWN from the tabs: the row last focused in this season, else next up / in progress, else the first. */
    fun rowForDown(): Int? {
        val list = episodeList ?: return null
        if (list.isEmpty()) return null
        val last = lastRow
        if (last != null && last.first == seasonId && last.second in list.indices) return last.second
        val nextUpIndex = nextUp?.let { up -> list.indexOfFirst { it?.id == up.id } } ?: -1
        if (nextUpIndex >= 0) return nextUpIndex
        val inProgress =
            list.indexOfFirst {
                it != null && !it.played && (it.data.userData?.playbackPositionTicks ?: 0L) > 0L
            }
        return if (inProgress >= 0) inProgress else 0
    }

    fun focusRow(index: Int) {
        scope.launch(ExceptionHandler()) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) listState.scrollToItem(index)
            pendingRow = index
        }
    }

    fun openSeasonMenu(season: BaseItem) {
        dialogs.dialog =
            buildDialogForSeason(
                resources = resources,
                s = season,
                canDelete = viewModel.canDelete(season, preferences.appPreferences),
                onClickItem = { viewModel.navigateTo(it.destination()) },
                markPlayed = { played ->
                    reloadAfterSeasons = true
                    viewModel.setSeasonWatched(season.id, played)
                },
                onClickPlay = { shuffle ->
                    viewModel.navigateTo(Destination.PlaybackList(itemId = season.id, shuffle = shuffle))
                },
                onClickDelete = { dialogs.deleteItem = it },
            )
    }

    fun openEpisodeMenu(
        episode: BaseItem,
        fromLongClick: Boolean,
    ) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
                item = episode,
                chosenStreams = state.chosenStreams,
                showGoTo = true,
                showStreamChoices = true,
                canDelete = viewModel.canDelete(episode, preferences.appPreferences),
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
            )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // Image scrim over the app backdrop: the rundown stays readable on it.
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                0f to JtvColors.ground.copy(alpha = 0.72f),
                                0.35f to JtvColors.ground.copy(alpha = 0.94f),
                                1f to JtvColors.ground,
                            ),
                    )
                },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .padding(horizontal = JtvDimens.marginHorizontal),
            ) {
                Text(
                    text = (series.name ?: "").uppercase(),
                    style = JtvType.label,
                    color = JtvColors.accent,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .drawBehind {
                                val stroke = JtvDimens.hairline.toPx()
                                drawLine(
                                    color = JtvColors.rule,
                                    start = Offset(0f, size.height - stroke / 2f),
                                    end = Offset(size.width, size.height - stroke / 2f),
                                    strokeWidth = stroke,
                                )
                            },
                ) {
                    Row(
                        modifier =
                            Modifier
                                .focusRestorer(
                                    tabRequesters.getOrNull(position.seasonTabIndex) ?: FocusRequester.Default,
                                ).focusGroup()
                                .onPreviewKeyEvent { event ->
                                    if (event.key == Key.DirectionDown) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            rowForDown()?.let { focusRow(it) }
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                    ) {
                        seasons.forEachIndexed { index, season ->
                            SeasonTab(
                                label = seasonTabLabel(season),
                                current = index == position.seasonTabIndex,
                                onFocusSettled = { onChangeSeason(index) },
                                onClick = {
                                    if (index == position.seasonTabIndex) {
                                        rowForDown()?.let { focusRow(it) }
                                    } else {
                                        onChangeSeason(index)
                                    }
                                },
                                onMenu = { season?.let { openSeasonMenu(it) } },
                                modifier = Modifier.focusRequester(tabRequesters[index]),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    SummaryText(episodeList)
                }
            }
            when (val episodes = state.episodes) {
                EpisodeList.Loading -> {
                    Box(Modifier.weight(1f))
                }

                is EpisodeList.Error -> {
                    EmptyState(
                        title = stringResource(R.string.jtv_media_error_title),
                        subtitle =
                            episodes.message ?: episodes.exception?.localizedMessage
                                ?: stringResource(R.string.jtv_media_error_body),
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(JtvDimens.marginHorizontal),
                    )
                }

                is EpisodeList.Success -> {
                    RundownList(
                        episodes = episodes,
                        listState = listState,
                        nextUp = nextUp,
                        peopleInEpisode =
                            state.peopleInEpisode.people.filter {
                                it.type == PersonKind.GUEST_STAR &&
                                    state.peopleInEpisode.itemId ==
                                    episodes.episodes.getOrNull(position.episodeRowIndex)?.id
                            },
                        extras = state.extras,
                        pendingRow = pendingRow,
                        onPendingDone = { pendingRow = null },
                        onFocusEpisode = { index ->
                            lastRow = episodes.seasonId to index
                            viewModel.position.update { it.copy(episodeRowIndex = index) }
                        },
                        onClick = { episode ->
                            val resumePosition =
                                episode.data.userData
                                    ?.playbackPositionTicks
                                    ?.ticks ?: Duration.ZERO
                            viewModel.navigateTo(
                                Destination.Playback(episode.id, resumePosition.inWholeMilliseconds),
                            )
                        },
                        onLongClick = { openEpisodeMenu(it, fromLongClick = true) },
                        onClickPerson = { person ->
                            viewModel.navigateTo(Destination.MediaItem(person.id, BaseItemKind.PERSON))
                        },
                        personActions =
                            PersonContextActions(
                                navigateTo = contextActions.navigateTo,
                                onClickFavorite = contextActions.onClickFavorite,
                            ),
                        onClickExtra = { viewModel.navigateTo(it.destination) },
                        dialogs = dialogs,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RundownList(
    episodes: EpisodeList.Success,
    listState: LazyListState,
    nextUp: BaseItem?,
    peopleInEpisode: List<Person>,
    extras: List<ExtrasItem>,
    pendingRow: Int?,
    onPendingDone: () -> Unit,
    onFocusEpisode: (Int) -> Unit,
    onClick: (BaseItem) -> Unit,
    onLongClick: (BaseItem) -> Unit,
    onClickPerson: (Person) -> Unit,
    personActions: PersonContextActions,
    onClickExtra: (ExtrasItem) -> Unit,
    dialogs: ItemDialogsState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val is24h = DateFormat.is24HourFormat(context)
    val special = stringResource(R.string.jtv_series_special)
    val imageService = LocalImageUrlService.current
    if (episodes.episodes.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.jtv_series_no_episodes).uppercase(),
                style = JtvType.label,
                color = JtvColors.muted,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding =
            PaddingValues(
                start = JtvDimens.marginHorizontal,
                end = JtvDimens.marginHorizontal,
                top = 12.dp,
                bottom = JtvDimens.marginVertical,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    // Rows scrolled up fade out under the tab rule instead of being cut.
                    if (listState.canScrollBackward) {
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0f to JtvColors.ground,
                                    1f to Color.Transparent,
                                    endY = ListTopFade.toPx(),
                                ),
                            size = Size(size.width, ListTopFade.toPx()),
                        )
                    }
                },
    ) {
        itemsIndexed(episodes.episodes, key = { index, ep -> ep?.id ?: "placeholder-$index" }) { index, episode ->
            val requester = remember { FocusRequester() }
            if (pendingRow == index && episode != null) {
                LaunchedEffect(Unit) {
                    requester.tryRequestFocus("jtv-rundown-row")
                    onPendingDone()
                }
            }
            if (episode == null) {
                Box(Modifier.fillMaxWidth().height(EpisodeRowHeight))
            } else {
                val dto = episode.data
                val runtime = dto.runTimeTicks ?: 0L
                val positionTicks = dto.userData?.playbackPositionTicks ?: 0L
                val percent = resumePercent(positionTicks, runtime)
                val inProgress = !episode.played && positionTicks > 0L
                val isNextUp = nextUp?.id == episode.id
                val meta =
                    listOfNotNull(
                        runtime.takeIf { it > 0L }?.let { formatRuntime(it) },
                        airDate(dto.premiereDate),
                        dto.communityRating?.let {
                            stringResource(R.string.jtv_media_community, String.format(Locale.US, "%.1f", it))
                        },
                    ).joinToString(" · ")
                val remainingMs = ((runtime - positionTicks).coerceAtLeast(0L)) / 10_000L
                val ends =
                    if (remainingMs > 0L) {
                        formatEndsAt(Instant.now(), remainingMs, ZoneId.systemDefault(), is24h)
                    } else {
                        null
                    }
                val imageUrl =
                    remember(episode.id, dto.imageTags) {
                        imageService.getItemImageUrl(episode, ImageType.PRIMARY)
                    }
                EpisodeRow(
                    number =
                        if (dto.parentIndexNumber == 0) {
                            special.uppercase()
                        } else {
                            episodeNumber(episode.indexNumber)
                        },
                    title = episode.name ?: "",
                    meta = meta,
                    focusedMeta = ends,
                    overview = dto.overview,
                    imageUrl = imageUrl,
                    progress = if (inProgress && percent in 1..99) percent / 100f else null,
                    played = episode.played,
                    nextUp = isNextUp,
                    status =
                        when {
                            inProgress -> EpisodeStatus.InProgress(percent.coerceAtLeast(1))
                            isNextUp -> EpisodeStatus.NextUp
                            else -> EpisodeStatus.None
                        },
                    onClick = { onClick(episode) },
                    onLongClick = { onLongClick(episode) },
                    onFocused = { onFocusEpisode(index) },
                    modifier = Modifier.focusRequester(requester),
                )
            }
        }
        if (peopleInEpisode.isNotEmpty()) {
            item(key = "guests") {
                MediaRow(
                    title = stringResource(R.string.jtv_series_guest_stars),
                    items = peopleInEpisode,
                    key = { index, person -> "$index-${person.id}" },
                    modifier = Modifier.padding(top = 16.dp),
                    card = { person, _, cardModifier, onFocused ->
                        PersonCard(
                            name = person.name ?: "",
                            role = person.role,
                            imageUrl = person.imageUrl,
                            onClick = { onClickPerson(person) },
                            onLongClick = {
                                dialogs.contextMenu =
                                    ContextMenu.ForPerson(
                                        fromLongClick = true,
                                        person = person,
                                        actions = personActions,
                                    )
                            },
                            onFocused = onFocused,
                            modifier = cardModifier,
                        )
                    },
                )
            }
        }
        if (extras.isNotEmpty()) {
            item(key = "extras") {
                MediaRow(
                    title = stringResource(R.string.jtv_media_extras),
                    items = extras,
                    key = { index, extra -> "${extra.type}-$index-${extra.title}" },
                    modifier = Modifier.padding(top = 16.dp),
                    card = { extra, _, cardModifier, onFocused ->
                        ExtraCard(
                            extra = extra,
                            onClick = { onClickExtra(extra) },
                            onFocused = onFocused,
                            modifier = cardModifier,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun seasonTabLabel(season: BaseItem?): String {
    val number = season?.indexNumber
    return when {
        number == 0 -> stringResource(R.string.jtv_series_specials)
        number != null -> stringResource(R.string.jtv_series_season_n, number)
        else -> season?.name ?: ""
    }
}

/** `7 EPISODES · 5 LEFT · 4M 35S` for the loaded season; nothing until every episode is in. */
@Composable
private fun SummaryText(episodes: List<BaseItem?>?) {
    val loaded = episodes?.takeIf { list -> list.isNotEmpty() && list.all { it != null } } ?: return
    val summary = seasonSummary(loaded.map { it!!.data })
    val text =
        buildList {
            add(pluralStringResource(R.plurals.jtv_series_episodes, summary.episodes, summary.episodes))
            add(stringResource(R.string.jtv_series_left, summary.left))
            if (summary.left > 0 && summary.remainingTicks > 0L) add(formatRuntime(summary.remainingTicks))
        }.joinToString(" · ").uppercase()
    Text(
        text = text,
        style = JtvType.label,
        color = JtvColors.muted,
        maxLines = 1,
        modifier = Modifier.padding(start = 24.dp),
    )
}

/**
 * One season tab: mono label; the current season in `text` over a 3dp accent bar; focus is the
 * [JtvColors.groundRaised] fill and a 3dp accent border. The list follows a tab once focus has
 * rested on it for 300ms. MENU or a long press opens the season's menu.
 */
@Composable
private fun SeasonTab(
    label: String,
    current: Boolean,
    onFocusSettled: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val settle by rememberUpdatedState(onFocusSettled)
    LaunchedEffect(focused) {
        if (focused) {
            delay(TAB_DEBOUNCE_MS)
            settle()
        }
    }
    val color = if (current || focused) JtvColors.text else JtvColors.muted
    Surface(
        onClick = onClick,
        onLongClick = onMenu,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = color,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = color,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = color,
            ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .height(TabHeight)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Menu) {
                        if (event.type == KeyEventType.KeyUp) onMenu()
                        true
                    } else {
                        false
                    }
                }.drawWithContent {
                    drawContent()
                    if (current) {
                        val bar = wholePx(JtvDimens.focusBorder.toPx())
                        drawRect(
                            color = JtvColors.accent,
                            topLeft = Offset(0f, size.height - bar),
                            size = Size(size.width, bar),
                        )
                    }
                    if (focused) {
                        val stroke = wholePx(JtvDimens.focusBorder.toPx())
                        val inset = stroke / 2f
                        drawRect(
                            color = JtvColors.accent,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                },
    ) {
        // tv-material3 Surface lays content out top-start: fill the height and center the label.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = JtvType.label,
                color = color,
                maxLines = 1,
                modifier = Modifier.offset(y = CapsLift),
            )
        }
    }
}

private const val TAB_DEBOUNCE_MS = 300L
private val ListTopFade = 28.dp
private val TabHeight = 40.dp
