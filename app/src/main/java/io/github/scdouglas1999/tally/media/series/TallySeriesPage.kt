package io.github.scdouglas1999.tally.media.series

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.stringRes
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ConfirmDialog
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.TrailerDialog
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.detail.series.SeriesPageType
import com.github.damontecres.wholphin.ui.detail.series.SeriesState
import com.github.damontecres.wholphin.ui.detail.series.SeriesViewModel
import com.github.damontecres.wholphin.ui.detail.series.buildDialogForSeason
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.ExceptionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.media.kit.CardDetailText
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleText
import io.github.scdouglas1999.tally.media.kit.DetailHeader
import io.github.scdouglas1999.tally.media.kit.DetailMetaPart
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.MediaRow
import io.github.scdouglas1999.tally.media.kit.PersonCard
import io.github.scdouglas1999.tally.media.kit.PosterCard
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.arrivalFocus
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * What the Tally series pages need beyond upstream's [SeriesViewModel]: the server's Next Up
 * episode for the series (the same `getNextUp` call upstream's `playNextUp` makes), and each
 * season's share watched (the seasons query with `ChildCount`, which makes the server fill in
 * `PlayedPercentage`).
 */
@HiltViewModel
class TallySeriesExtrasViewModel
    @Inject
    constructor(
        private val api: ApiClient,
    ) : ViewModel() {
        private val _nextUp = MutableStateFlow<BaseItem?>(null)
        val nextUp: StateFlow<BaseItem?> = _nextUp

        private val _seasonWatched = MutableStateFlow<Map<UUID, Float>>(emptyMap())
        val seasonWatched: StateFlow<Map<UUID, Float>> = _seasonWatched

        fun load(
            seriesId: UUID,
            withSeasons: Boolean,
        ) {
            viewModelScope.launchIO {
                try {
                    val result by api.tvShowsApi.getNextUp(seriesId = seriesId)
                    _nextUp.value = result.items.firstOrNull()?.let { BaseItem(it) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Next up for %s", seriesId)
                }
            }
            if (withSeasons) {
                viewModelScope.launchIO {
                    try {
                        val result by
                            api.itemsApi.getItems(
                                GetItemsRequest(
                                    parentId = seriesId,
                                    recursive = false,
                                    includeItemTypes = listOf(BaseItemKind.SEASON),
                                    sortBy = listOf(ItemSortBy.INDEX_NUMBER),
                                    sortOrder = listOf(SortOrder.ASCENDING),
                                    enableUserData = true,
                                    fields = listOf(ItemFields.CHILD_COUNT),
                                ),
                            )
                        _seasonWatched.value =
                            result.items
                                .mapNotNull { season ->
                                    seasonWatchedFraction(season)?.let { season.id to it }
                                }.toMap()
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Season progress for %s", seriesId)
                    }
                }
            }
        }
    }

private const val POS_ACTIONS = 0
private const val POS_SEASONS = 1
private const val POS_PEOPLE = 2
private const val POS_EXTRAS = 3
private const val POS_SIMILAR = 4
private const val POS_DISCOVER = 5

@Composable
fun TallySeriesPage(
    destination: Destination.MediaItem,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel =
        hiltViewModel<SeriesViewModel, SeriesViewModel.Factory>(
            creationCallback = {
                it.create(destination.itemId, null, SeriesPageType.DETAILS)
            },
        ),
    extras: TallySeriesExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val nextUp by extras.nextUp.collectAsState()
    val seasonWatched by extras.seasonWatched.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)
    var showWatchConfirmation by remember { mutableStateOf(false) }
    val contextActions =
        remember(dialogs) {
            ContextMenuActions(
                navigateTo = viewModel::navigateTo,
                onClickWatch = { itemId, watched ->
                    if (itemId == destination.itemId) {
                        // Confirm if marking whole series
                        showWatchConfirmation = true
                    } else {
                        viewModel.setWatched(itemId, watched, null)
                    }
                },
                onClickFavorite = { itemId, favorite ->
                    viewModel.setFavorite(itemId, favorite, null)
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
                onClearChosenStreams = {},
            )
        }

    LifecycleResumeEffect(destination.itemId) {
        viewModel.refresh()
        extras.load(destination.itemId, withSeasons = true)
        onPauseOrDispose {
            viewModel.release()
        }
    }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(modifier = modifier.fillMaxSize()) {
                    when (val loading = state.series) {
                        is DataLoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.tally_media_error_title),
                                subtitle =
                                    loading.localizedMessage.ifBlank {
                                        stringResource(R.string.tally_media_error_body)
                                    },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(TallyDimens.marginHorizontal),
                            )
                        }

                        DataLoadingState.Loading,
                        DataLoadingState.Pending,
                        -> {
                            LoadingMark(Modifier.fillMaxSize(), "jtv-series-loading")
                        }

                        is DataLoadingState.Success -> {
                            LifecycleResumeEffect(destination.itemId) {
                                viewModel.onResumePage()
                                onPauseOrDispose {}
                            }
                            val series = loading.data
                            // Watched / favorite changes reload the series: refresh Next Up and
                            // the seasons' share watched with it.
                            LaunchedEffect(series.data.userData) {
                                extras.load(destination.itemId, withSeasons = true)
                            }
                            SeriesLoaded(
                                preferences = preferences,
                                series = series,
                                state = state,
                                nextUp = nextUp,
                                seasonWatched = seasonWatched,
                                dialogs = dialogs,
                                contextActions = contextActions,
                                viewModel = viewModel,
                                onWatch = { showWatchConfirmation = true },
                            )
                            if (showWatchConfirmation) {
                                val played = series.played
                                ConfirmDialog(
                                    title = series.name ?: "",
                                    body =
                                        stringResource(
                                            if (played) {
                                                R.string.mark_entire_series_as_unplayed
                                            } else {
                                                R.string.mark_entire_series_as_played
                                            },
                                        ),
                                    onCancel = { showWatchConfirmation = false },
                                    onConfirm = {
                                        viewModel.setWatchedSeries(!played)
                                        showWatchConfirmation = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = viewModel.streamChoiceService::chooseSource,
        preferredSubtitleLanguage = null,
        showFilePath = userDto?.policy?.isAdministrator == true,
        onConfirmDelete = viewModel::deleteItem,
    )
}

@Composable
internal fun LoadingMark(
    modifier: Modifier = Modifier,
    debugKey: String,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.tryRequestFocus(debugKey) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.tally_media_loading).uppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
            modifier =
                Modifier
                    .focusRequester(requester)
                    .focusable(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesLoaded(
    preferences: UserPreferences,
    series: BaseItem,
    state: SeriesState,
    nextUp: BaseItem?,
    seasonWatched: Map<UUID, Float>,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
    viewModel: SeriesViewModel,
    onWatch: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val seriesNow by rememberUpdatedState(series)
    var position by rememberInt(POS_ACTIONS)
    val primaryFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val seasonsFocus = remember { FocusRequester() }
    val peopleFocus = remember { FocusRequester() }
    val extrasFocus = remember { FocusRequester() }
    val similarFocus = remember { FocusRequester() }
    val discoverFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val seasons = state.seasons.filterNotNull()

    val firstRowFocus =
        when {
            seasons.isNotEmpty() -> seasonsFocus
            state.people.isNotEmpty() -> peopleFocus
            state.extras.isNotEmpty() -> extrasFocus
            state.similar.isNotEmpty() -> similarFocus
            state.discovered.isNotEmpty() -> discoverFocus
            else -> null
        }
    val restore =
        when (position) {
            POS_SEASONS -> if (seasons.isNotEmpty()) seasonsFocus else primaryFocus
            POS_PEOPLE -> if (state.people.isNotEmpty()) peopleFocus else primaryFocus
            POS_EXTRAS -> if (state.extras.isNotEmpty()) extrasFocus else primaryFocus
            POS_SIMILAR -> if (state.similar.isNotEmpty()) similarFocus else primaryFocus
            POS_DISCOVER -> if (state.discovered.isNotEmpty()) discoverFocus else primaryFocus
            else -> primaryFocus
        }
    val arrival = arrivalFocus(restore, "jtv-series")

    val onActionFocused: () -> Unit = {
        position = POS_ACTIONS
        scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
        Unit
    }

    fun openSeriesMenu(fromLongClick: Boolean) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
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

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize().then(arrival)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinScrollBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    val logoUrl = rememberLogoUrl(series)
                    DetailHeader(
                        kicker = stringResource(R.string.tally_series_kicker),
                        title = series.name ?: "",
                        logoUrl =
                            if (preferences.appPreferences.interfacePreferences.showLogos) {
                                logoUrl
                            } else {
                                null
                            },
                        meta = seriesMeta(series, seasons.size),
                        genres = series.data.genres.orEmpty(),
                        tagline = null,
                        overview = series.data.overview,
                        director = createdByLine(series),
                        tech = emptyList(),
                        // The series meta line (years, rating, seasons, episodes, stars) is longer than a film's.
                        textMaxWidth = SeriesTextWidth,
                        onOverviewClick = { dialogs.overview = ItemDetailsDialogInfo(seriesNow) },
                        actions = {
                            SeriesActionRow(
                                series = series,
                                state = state,
                                nextUp = nextUp,
                                primaryFocus = primaryFocus,
                                actionFocus = actionFocus,
                                down = firstRowFocus,
                                onActionFocused = onActionFocused,
                                onPlay = {
                                    position = POS_ACTIONS
                                    viewModel.playNextUp()
                                },
                                onSeasons = {
                                    position = POS_ACTIONS
                                    val target =
                                        nextUp?.data?.seasonId?.let { seasonId ->
                                            SeasonEpisodeIds(
                                                seasonId,
                                                nextUp.data.parentIndexNumber,
                                                nextUp.id,
                                                nextUp.indexNumber,
                                            )
                                        }
                                    viewModel.navigateTo(
                                        Destination.SeriesOverview(series.id, BaseItemKind.SERIES, target),
                                    )
                                },
                                onTrailer = { trailer ->
                                    TrailerService.onClick(context, trailer, viewModel::navigateTo)
                                },
                                onWatch = onWatch,
                                onFavorite = { viewModel.setFavorite(series.id, !series.favorite, null) },
                                onDiscover = {
                                    state.discoverSeries?.let {
                                        viewModel.navigateTo(Destination.DiscoveredItem(it))
                                    }
                                },
                                onMore = { openSeriesMenu(fromLongClick = false) },
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bringHeader),
                    )
                }
                if (seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_series_seasons),
                                items = seasons,
                                key = { _, season -> season.id },
                                modifier = Modifier.focusRequester(seasonsFocus),
                                up = if (firstRowFocus == seasonsFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_SEASONS
                                    reveal()
                                },
                                card = { season, _, cardModifier, onFocused ->
                                    SeasonCard(
                                        season = season,
                                        watched = seasonWatched[season.id],
                                        onClick = {
                                            position = POS_SEASONS
                                            viewModel.navigateTo(season.destination())
                                        },
                                        onLongClick = {
                                            position = POS_SEASONS
                                            openSeasonMenu(season)
                                        },
                                        onFocused = onFocused,
                                        modifier = cardModifier,
                                    )
                                },
                            )
                        }
                    }
                }
                if (state.people.isNotEmpty()) {
                    item(key = "people") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_cast),
                                items = state.people,
                                // One person can hold several credits, so the id alone is not a unique key.
                                key = { index, person -> "$index-${person.id}" },
                                modifier = Modifier.focusRequester(peopleFocus),
                                up = if (firstRowFocus == peopleFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_PEOPLE
                                    reveal()
                                },
                                card = { person, _, cardModifier, onFocused ->
                                    PersonCard(
                                        name = person.name ?: "",
                                        role = person.role,
                                        imageUrl = person.imageUrl,
                                        onClick = {
                                            position = POS_PEOPLE
                                            viewModel.navigateTo(
                                                Destination.MediaItem(person.id, BaseItemKind.PERSON),
                                            )
                                        },
                                        onLongClick = {
                                            position = POS_PEOPLE
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
                                        onFocused = onFocused,
                                        modifier = cardModifier,
                                    )
                                },
                            )
                        }
                    }
                }
                if (state.extras.isNotEmpty()) {
                    item(key = "extras") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_extras),
                                items = state.extras,
                                key = { index, extra -> "${extra.type}-$index-${extra.title}" },
                                modifier = Modifier.focusRequester(extrasFocus),
                                up = if (firstRowFocus == extrasFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_EXTRAS
                                    reveal()
                                },
                                card = { extra, _, cardModifier, onFocused ->
                                    ExtraCard(
                                        extra = extra,
                                        modifier = cardModifier,
                                        onFocused = onFocused,
                                        onClick = {
                                            position = POS_EXTRAS
                                            viewModel.navigateTo(extra.destination)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                if (state.similar.isNotEmpty()) {
                    item(key = "similar") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_more_like_this),
                                items = state.similar,
                                key = { _, item -> item.id },
                                modifier = Modifier.focusRequester(similarFocus),
                                up = if (firstRowFocus == similarFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_SIMILAR
                                    reveal()
                                },
                                card = { item, _, cardModifier, onFocused ->
                                    PosterCard(
                                        item = item,
                                        onClick = {
                                            position = POS_SIMILAR
                                            viewModel.navigateTo(item.destination())
                                        },
                                        onLongClick = {
                                            position = POS_SIMILAR
                                            openItemMenu(item)
                                        },
                                        onPlay = {
                                            if (item.type.playable) {
                                                position = POS_SIMILAR
                                                viewModel.navigateTo(Destination.Playback(item))
                                            }
                                        },
                                        onFocused = onFocused,
                                        modifier = cardModifier,
                                    )
                                },
                            )
                        }
                    }
                }
                if (state.discovered.isNotEmpty()) {
                    item(key = "discover") {
                        DiscoverRow(
                            row =
                                DiscoverRowData(
                                    ResStringProvider(R.string.discover),
                                    DataLoadingState.Success(state.discovered),
                                    type = DiscoverRequestType.UNKNOWN,
                                ),
                            onClickItem = { _: Int, item: DiscoverItem ->
                                position = POS_DISCOVER
                                viewModel.navigateTo(item.destination)
                            },
                            onLongClickItem = { _, _ -> },
                            onCardFocus = { position = POS_DISCOVER },
                            focusRequester = discoverFocus,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(TallyColors.ground)
                                    .padding(bottom = 24.dp)
                                    .focusProperties {
                                        if (firstRowFocus == discoverFocus) {
                                            up = actionFocus
                                        }
                                    },
                        )
                    }
                }
            }
        }
        TopScrim(listState)
    }
}

@Composable
internal fun TopScrim(listState: androidx.compose.foundation.lazy.LazyListState) {
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    if (scrolled) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TopScrimHeight)
                    .drawBehind {
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0f to TallyColors.ground,
                                    1f to Color.Transparent,
                                ),
                        )
                    },
        )
    }
}

/**
 * Scroll only enough to reveal the focused child (as the film page does). The TV default parks
 * it a third of the way down, which pushes the header off the top when a button takes focus.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object MinScrollBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            size <= containerSize && trailing > containerSize -> trailing - containerSize
            size <= containerSize && offset < 0f -> offset
            else -> offset
        }
    }
}

/** Scrolled rows fade out under this band at the top (it sits below the upstream clock). */
internal val TopScrimHeight = 64.dp

/**
 * A row section on opaque ground, as on the film page. [content] gets `reveal`, to call when one
 * of its cards takes focus: it scrolls the whole section into view with [TopScrimHeight] of room.
 */
@Composable
internal fun RowGround(content: @Composable (reveal: () -> Unit) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val headroom = with(LocalDensity.current) { TopScrimHeight.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val reveal: () -> Unit = {
        scope.launch(ExceptionHandler()) {
            requester.bringIntoView(
                Rect(0f, -headroom, size.width.toFloat(), size.height.toFloat()),
            )
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(TallyColors.ground)
                .onSizeChanged { size = it }
                .bringIntoViewRequester(requester)
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(bottom = 24.dp),
    ) {
        content(reveal)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesActionRow(
    series: BaseItem,
    state: SeriesState,
    nextUp: BaseItem?,
    primaryFocus: FocusRequester,
    actionFocus: FocusRequester,
    down: FocusRequester?,
    onActionFocused: () -> Unit,
    onPlay: () -> Unit,
    onSeasons: () -> Unit,
    onTrailer: (com.github.damontecres.wholphin.data.model.Trailer) -> Unit,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onDiscover: () -> Unit,
    onMore: () -> Unit,
) {
    var showTrailers by remember { mutableStateOf(false) }
    val special = stringResource(R.string.tally_series_special)
    val primaryLabel =
        when (val label = nextUpLabel(nextUp?.data, special)) {
            NextUpLabel.Play -> stringResource(R.string.tally_media_play)
            is NextUpLabel.NextUp -> stringResource(R.string.tally_series_next_up_code, label.code)
            is NextUpLabel.Resume -> stringResource(R.string.tally_series_resume_code, label.code, label.percent)
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = FocusEdge, end = 24.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bleedHorizontal()
                    .focusRequester(actionFocus)
                    .focusGroup()
                    .focusRestorer(primaryFocus)
                    .focusProperties {
                        if (down != null) this.down = down
                    },
        ) {
            item(key = "play") {
                TallyButton(
                    label = primaryLabel,
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    onClick = onPlay,
                    onFocused = onActionFocused,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            item(key = "seasons") {
                TallyButton(
                    label = stringResource(R.string.tally_series_seasons),
                    glyph = stringResource(R.string.fa_list_ul),
                    onClick = onSeasons,
                    onFocused = onActionFocused,
                )
            }
            if (state.trailers.isNotEmpty()) {
                item(key = "trailer") {
                    TallyButton(
                        label = stringResource(R.string.tally_media_trailer),
                        glyph = stringResource(R.string.fa_film),
                        onClick = {
                            if (state.trailers.size == 1) {
                                onTrailer(state.trailers.first())
                            } else {
                                showTrailers = true
                            }
                        },
                        onFocused = onActionFocused,
                    )
                }
            }
            item(key = "watched") {
                TallyButton(
                    label =
                        stringResource(
                            if (series.played) R.string.tally_media_watched else R.string.tally_media_unwatched,
                        ),
                    glyph = stringResource(if (series.played) R.string.fa_eye else R.string.fa_eye_slash),
                    onClick = onWatch,
                    onFocused = onActionFocused,
                )
            }
            item(key = "favorite") {
                TallyButton(
                    label =
                        stringResource(
                            if (series.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite,
                        ),
                    glyph = stringResource(R.string.fa_heart),
                    onClick = onFavorite,
                    onFocused = onActionFocused,
                    trailing =
                        if (series.favorite) {
                            { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                        } else {
                            null
                        },
                )
            }
            if (state.discoverSeries != null) {
                item(key = "discover") {
                    TallyButton(
                        label = stringResource(R.string.discover),
                        glyph = stringResource(R.string.fa_magnifying_glass_plus),
                        onClick = onDiscover,
                        onFocused = onActionFocused,
                    )
                }
            }
            item(key = "more") {
                TallyButton(
                    label = stringResource(R.string.tally_media_more),
                    glyph = stringResource(R.string.fa_ellipsis),
                    onClick = onMore,
                    onFocused = onActionFocused,
                )
            }
        }
    }
    if (showTrailers) {
        TrailerDialog(
            onDismissRequest = { showTrailers = false },
            trailers = state.trailers,
            onClick = onTrailer,
        )
    }
}

@Composable
private fun seriesMeta(
    series: BaseItem,
    loadedSeasons: Int,
): List<DetailMetaPart> {
    val seasons = series.data.childCount ?: loadedSeasons
    val episodes = series.data.recursiveItemCount
    return buildList {
        seriesYears(series.data.productionYear, series.data.endDate, series.data.status)
            ?.let { add(DetailMetaPart.Plain(it)) }
        series.data.officialRating
            ?.takeIf { it.isNotBlank() }
            ?.let { add(DetailMetaPart.Boxed(it)) }
        if (seasons > 0) {
            add(DetailMetaPart.Plain(pluralStringResource(R.plurals.tally_media_seasons, seasons, seasons)))
        }
        if (episodes != null && episodes > 0) {
            add(DetailMetaPart.Plain(pluralStringResource(R.plurals.tally_series_episodes, episodes, episodes)))
        }
        series.data.communityRating?.let { rating ->
            add(
                DetailMetaPart.Plain(
                    stringResource(
                        R.string.tally_media_community,
                        String.format(Locale.US, "%.1f", rating),
                    ),
                ),
            )
        }
    }
}

/** `Created by …` from the Creator credits, else the Writer credits; null when there are none. */
@Composable
private fun createdByLine(series: BaseItem): String? {
    val people = series.data.people.orEmpty()
    val creators =
        people
            .filter { it.type == PersonKind.CREATOR && it.name.isNotNullOrBlank() }
            .ifEmpty { people.filter { it.type == PersonKind.WRITER && it.name.isNotNullOrBlank() } }
            .mapNotNull { it.name }
            .distinct()
    if (creators.isEmpty()) return null
    return stringResource(R.string.tally_series_created_by, creators.joinToString(", "))
}

/**
 * A season's poster: `4 LEFT` in accent while episodes are unplayed, a `WATCHED` tick once all
 * are, and the share watched as the progress bar.
 */
@Composable
private fun SeasonCard(
    season: BaseItem,
    watched: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageUrl = LocalImageUrlService.current.rememberImageUrl(season, ImageType.PRIMARY)
    val unplayed = season.data.userData?.unplayedItemCount ?: 0
    val played = season.played
    val title = season.name ?: ""
    CardFrame(
        imageUrl = imageUrl,
        width = SeasonPosterWidth,
        height = SeasonPosterWidth * 3 / 2,
        contentDescription = title,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        onFocused = onFocused,
        progress = watched?.takeIf { !played && it > 0f && it < 1f },
        tag =
            when {
                played -> stringResource(R.string.tally_series_watched_tag)
                unplayed > 0 -> stringResource(R.string.tally_series_left, unplayed)
                else -> null
            },
        tagAccent = !played && unplayed > 0,
        tagGlyph = if (played) stringResource(R.string.fa_check) else null,
        favorite = season.favorite,
        label = {
            CardTitleText(title)
            season.data.productionYear?.let { CardDetailText(it.toString()) }
        },
    )
}

private val SeasonPosterWidth = 132.dp
private val SeriesTextWidth = 560.dp

@Composable
internal fun ExtraCard(
    extra: ExtrasItem,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val single = extra as? ExtrasItem.Single
    val image =
        if (single != null) {
            rememberWideImageUrl(single.item)
        } else {
            extra.imageUrl
        }
    val progress =
        if (single != null && !single.item.played) {
            val percent =
                resumePercent(
                    single.item.data.userData
                        ?.playbackPositionTicks ?: 0L,
                    single.item.data.runTimeTicks ?: 0L,
                )
            if (percent in 1..99) percent / 100f else null
        } else {
            null
        }
    LandscapeCard(
        title = extra.title,
        kicker = stringResource(extra.type.stringRes),
        imageUrl = image,
        progress = progress,
        onClick = onClick,
        // Upstream's extras rows have no long-press menu.
        onLongClick = {},
        onPlay = onClick,
        onFocused = onFocused,
        modifier = modifier,
    )
}
