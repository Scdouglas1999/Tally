package io.github.scdouglas1999.tally.media.episode

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.PeopleFavorites
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.chooseVersionParams
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.episode.EpisodeViewModel
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.media.kit.DetailHeader
import io.github.scdouglas1999.tally.media.kit.DetailMetaPart
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.LandscapeWidth
import io.github.scdouglas1999.tally.media.kit.MediaRow
import io.github.scdouglas1999.tally.media.kit.PersonCard
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.arrivalFocus
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.formatEndsAt
import io.github.scdouglas1999.tally.media.kit.formatPosition
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.kit.techBoxes
import io.github.scdouglas1999.tally.media.series.LoadingMark
import io.github.scdouglas1999.tally.media.series.MinScrollBringIntoViewSpec
import io.github.scdouglas1999.tally.media.series.RowGround
import io.github.scdouglas1999.tally.media.series.TopScrim
import io.github.scdouglas1999.tally.media.series.airDate
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.media.series.episodeNumber
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.time.Duration

/** What the Tally episode page shows that upstream's [EpisodeViewModel] does not load. */
data class EpisodeExtras(
    val itemId: UUID? = null,
    val people: List<Person> = emptyList(),
    val seasonEpisodes: List<BaseItem> = emptyList(),
)

/**
 * The episode's people (with favorites, as the film page gets them) and its season's episodes,
 * with the same `getEpisodes` request upstream's `SeriesViewModel.loadEpisodesInternal` makes.
 */
@HiltViewModel
class TallyEpisodeExtrasViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val peopleFavorites: PeopleFavorites,
    ) : ViewModel() {
        private val _extras = MutableStateFlow(EpisodeExtras())
        val extras: StateFlow<EpisodeExtras> = _extras

        fun load(episode: BaseItem) {
            val seriesId = episode.data.seriesId
            val seasonId = episode.data.seasonId
            viewModelScope.launchIO {
                try {
                    val people = peopleFavorites.getPeopleFor(episode)
                    _extras.update { it.copy(itemId = episode.id, people = people) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "People for episode %s", episode.id)
                }
            }
            if (seriesId != null && seasonId != null) {
                viewModelScope.launchIO {
                    try {
                        val result by
                            api.tvShowsApi.getEpisodes(
                                GetEpisodesRequest(
                                    seriesId = seriesId,
                                    seasonId = seasonId,
                                    sortBy = ItemSortBy.INDEX_NUMBER,
                                    fields =
                                        listOf(
                                            ItemFields.MEDIA_SOURCES,
                                            ItemFields.MEDIA_SOURCE_COUNT,
                                            ItemFields.OVERVIEW,
                                            ItemFields.CUSTOM_RATING,
                                            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                                            ItemFields.CAN_DELETE,
                                            ItemFields.PARENT_ID,
                                        ),
                                ),
                            )
                        _extras.update { current ->
                            current.copy(seasonEpisodes = result.items.map { BaseItem(it) })
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Season episodes for %s", episode.id)
                    }
                }
            }
        }
    }

private const val POS_ACTIONS = 0
private const val POS_PEOPLE = 1
private const val POS_CHAPTERS = 2
private const val POS_SEASON = 3

@Composable
fun TallyEpisodePage(
    destination: Destination.MediaItem,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: EpisodeViewModel =
        hiltViewModel<EpisodeViewModel, EpisodeViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    extrasViewModel: TallyEpisodeExtrasViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.init()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val extras by extrasViewModel.extras.collectAsState()
    val canDelete by viewModel.canDelete.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)
    val contextActions =
        remember(dialogs) {
            ContextMenuActions(
                navigateTo = viewModel::navigateTo,
                onClickWatch = viewModel::setWatched,
                onClickFavorite = viewModel::setFavorite,
                onClickAddPlaylist = { dialogs.playlistItemId = it },
                onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                onDeleteItem = viewModel::deleteItem,
                onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
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
                onClearChosenStreams = { viewModel.clearChosenStreams(it) },
            )
        }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(modifier = modifier.fillMaxSize()) {
                    when (val loading = state.episode) {
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
                            LoadingMark(Modifier.fillMaxSize(), "jtv-episode-loading")
                        }

                        is DataLoadingState.Success -> {
                            val ep = loading.data
                            LifecycleResumeEffect(ep) {
                                ep.data.seriesId?.let { seriesId ->
                                    viewModel.maybePlayThemeSong(seriesId)
                                }
                                onPauseOrDispose {
                                    viewModel.release()
                                }
                            }
                            LaunchedEffect(ep.id, ep.data.userData) { extrasViewModel.load(ep) }
                            EpisodeLoaded(
                                episode = ep,
                                extras = extras,
                                chosenStreams = state.chosenStreams,
                                canDelete = canDelete,
                                dialogs = dialogs,
                                contextActions = contextActions,
                                viewModel = viewModel,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeLoaded(
    episode: BaseItem,
    extras: EpisodeExtras,
    chosenStreams: com.github.damontecres.wholphin.data.ChosenStreams?,
    canDelete: Boolean,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
    viewModel: EpisodeViewModel,
) {
    val episodeNow by rememberUpdatedState(episode)
    val streamsNow by rememberUpdatedState(chosenStreams)
    var position by rememberInt(POS_ACTIONS)
    val primaryFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val peopleFocus = remember { FocusRequester() }
    val chaptersFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val people = if (extras.itemId == episode.id) extras.people else emptyList()
    val chapters = remember(episode.id, episode.data.chapters) { Chapter.fromDto(episode.data) }
    val currentIndex = extras.seasonEpisodes.indexOfFirst { it.id == episode.id }
    val moreFromSeason =
        if (currentIndex >= 0) extras.seasonEpisodes.drop(currentIndex + 1) else emptyList()

    val firstRowFocus =
        when {
            people.isNotEmpty() -> peopleFocus
            chapters.isNotEmpty() -> chaptersFocus
            moreFromSeason.isNotEmpty() -> seasonFocus
            else -> null
        }
    val restore =
        when (position) {
            POS_PEOPLE -> if (people.isNotEmpty()) peopleFocus else primaryFocus
            POS_CHAPTERS -> if (chapters.isNotEmpty()) chaptersFocus else primaryFocus
            POS_SEASON -> if (moreFromSeason.isNotEmpty()) seasonFocus else primaryFocus
            else -> primaryFocus
        }
    val arrival = arrivalFocus(restore, "jtv-episode")

    val onActionFocused: () -> Unit = {
        position = POS_ACTIONS
        scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
        Unit
    }

    fun openEpisodeMenu(fromLongClick: Boolean) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
                item = episodeNow,
                chosenStreams = streamsNow,
                showGoTo = false,
                showStreamChoices = true,
                canDelete = canDelete,
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

    val special = stringResource(R.string.tally_series_special)
    val code = episodeCode(episode.data.parentIndexNumber, episode.indexNumber, episode.data.indexNumberEnd, special)
    val seasonLabel =
        when (val season = episode.data.parentIndexNumber) {
            0 -> stringResource(R.string.tally_series_specials)
            null -> episode.data.seasonName ?: ""
            else -> stringResource(R.string.tally_series_season_n, season)
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
                    DetailHeader(
                        kicker = listOfNotNull(episode.data.seriesName, code).joinToString(" · "),
                        kickerColor = TallyColors.accent,
                        title = episode.name ?: "",
                        logoUrl = null,
                        meta = episodeMeta(episode),
                        ends = episodeEnds(episode),
                        genres = emptyList(),
                        tagline = null,
                        overview = episode.data.overview,
                        director = directorLine(episode),
                        tech =
                            techBoxes(
                                chosenStreams?.source ?: episode.data.mediaSources?.firstOrNull(),
                                chosenStreams?.videoStream,
                                chosenStreams?.audioStream,
                            ),
                        onOverviewClick = { dialogs.overview = ItemDetailsDialogInfo(episodeNow) },
                        actions = {
                            EpisodeActionRow(
                                episode = episode,
                                chosenSourceId =
                                    chosenStreams
                                        ?.source
                                        ?.id
                                        ?.toUUIDOrNull(),
                                primaryFocus = primaryFocus,
                                actionFocus = actionFocus,
                                down = firstRowFocus,
                                onActionFocused = onActionFocused,
                                onPlay = { duration ->
                                    position = POS_ACTIONS
                                    viewModel.navigateTo(
                                        Destination.Playback(episode.id, duration.inWholeMilliseconds),
                                    )
                                },
                                onWatch = { viewModel.setWatched(episode.id, !episode.played) },
                                onFavorite = { viewModel.setFavorite(episode.id, !episode.favorite) },
                                onSeason = {
                                    val seriesId = episode.data.seriesId
                                    val seasonId = episode.data.seasonId
                                    if (seriesId != null && seasonId != null) {
                                        viewModel.navigateTo(
                                            Destination.SeriesOverview(
                                                seriesId,
                                                BaseItemKind.SERIES,
                                                SeasonEpisodeIds(
                                                    seasonId,
                                                    episode.data.parentIndexNumber,
                                                    episode.id,
                                                    episode.indexNumber,
                                                ),
                                            ),
                                        )
                                    }
                                },
                                onMore = { openEpisodeMenu(fromLongClick = false) },
                                onChooseVersion = { source ->
                                    contextActions.onChooseVersion(episode, source)
                                },
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bringHeader),
                    )
                }
                if (people.isNotEmpty()) {
                    item(key = "people") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_cast),
                                items = people,
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
                                                            navigateTo = viewModel::navigateTo,
                                                            onClickFavorite = viewModel::setFavorite,
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
                if (chapters.isNotEmpty()) {
                    item(key = "chapters") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_chapters),
                                items = chapters,
                                key = { _, chapter -> chapter.index },
                                modifier = Modifier.focusRequester(chaptersFocus),
                                up = if (firstRowFocus == chaptersFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_CHAPTERS
                                    reveal()
                                },
                                card = { chapter, _, cardModifier, onFocused ->
                                    val playChapter = {
                                        position = POS_CHAPTERS
                                        viewModel.navigateTo(
                                            Destination.Playback(episode.id, chapter.position.inWholeMilliseconds),
                                        )
                                    }
                                    LandscapeCard(
                                        title = chapter.name ?: "",
                                        kicker =
                                            stringResource(
                                                R.string.tally_media_chapter,
                                                chapter.index + 1,
                                                formatPosition(chapter.position.inWholeMilliseconds * 10_000L),
                                            ),
                                        imageUrl = chapterImage(chapter),
                                        onClick = playChapter,
                                        onLongClick = {
                                            position = POS_CHAPTERS
                                            openEpisodeMenu(fromLongClick = true)
                                        },
                                        onPlay = playChapter,
                                        onFocused = onFocused,
                                        modifier = cardModifier,
                                    )
                                },
                            )
                        }
                    }
                }
                if (moreFromSeason.isNotEmpty()) {
                    item(key = "season") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_series_more_from, seasonLabel),
                                items = moreFromSeason,
                                key = { _, item -> item.id },
                                modifier = Modifier.focusRequester(seasonFocus),
                                up = if (firstRowFocus == seasonFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_SEASON
                                    reveal()
                                },
                                card = { item, _, cardModifier, onFocused ->
                                    val percent =
                                        resumePercent(
                                            item.data.userData?.playbackPositionTicks ?: 0L,
                                            item.data.runTimeTicks ?: 0L,
                                        )
                                    LandscapeCard(
                                        title = item.name ?: "",
                                        kicker = seasonCardKicker(item),
                                        imageUrl = rememberWideImageUrl(item),
                                        progress = if (!item.played && percent in 1..99) percent / 100f else null,
                                        favorite = item.favorite,
                                        onClick = {
                                            position = POS_SEASON
                                            viewModel.navigateTo(Destination.MediaItem(item))
                                        },
                                        onLongClick = {
                                            position = POS_SEASON
                                            openItemMenu(item)
                                        },
                                        onPlay = {
                                            position = POS_SEASON
                                            viewModel.navigateTo(Destination.Playback(item))
                                        },
                                        onFocused = onFocused,
                                        modifier = cardModifier,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        TopScrim(listState)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeActionRow(
    episode: BaseItem,
    chosenSourceId: UUID?,
    primaryFocus: FocusRequester,
    actionFocus: FocusRequester,
    down: FocusRequester?,
    onActionFocused: () -> Unit,
    onPlay: (Duration) -> Unit,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onSeason: () -> Unit,
    onMore: () -> Unit,
    onChooseVersion: (MediaSourceInfo) -> Unit,
) {
    val resources = LocalResources.current
    val resume = episode.playbackPosition
    val resumable = resume > Duration.ZERO
    val percent =
        resumePercent(
            episode.data.userData?.playbackPositionTicks ?: 0L,
            episode.data.runTimeTicks ?: 0L,
        ).let { if (resumable && it == 0) 1 else it }
    var restartWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(resumable) {
        if (!resumable && restartWasFocused) {
            restartWasFocused = false
            primaryFocus.tryRequestFocus("jtv-episode-play")
        }
    }
    val clearRestart: () -> Unit = {
        restartWasFocused = false
        onActionFocused()
    }
    var versionDialog by remember { mutableStateOf<DialogParams?>(null) }
    val sources =
        episode.data.mediaSources
            .orEmpty()
            .filter { it.id.isNotNullOrBlank() }

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
                    label =
                        if (resumable) {
                            stringResource(R.string.tally_media_resume, percent)
                        } else {
                            stringResource(R.string.tally_media_play)
                        },
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    onClick = { onPlay(if (resumable) resume else Duration.ZERO) },
                    onFocused = clearRestart,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            if (resumable) {
                item(key = "restart") {
                    TallyButton(
                        label = stringResource(R.string.tally_media_from_start),
                        glyph = stringResource(R.string.fa_rotate_left),
                        onClick = { onPlay(Duration.ZERO) },
                        onFocused = {
                            restartWasFocused = true
                            onActionFocused()
                        },
                    )
                }
            }
            item(key = "watched") {
                TallyButton(
                    label =
                        stringResource(
                            if (episode.played) R.string.tally_media_watched else R.string.tally_media_unwatched,
                        ),
                    glyph = stringResource(if (episode.played) R.string.fa_eye else R.string.fa_eye_slash),
                    onClick = onWatch,
                    onFocused = clearRestart,
                )
            }
            item(key = "favorite") {
                TallyButton(
                    label =
                        stringResource(
                            if (episode.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite,
                        ),
                    glyph = stringResource(R.string.fa_heart),
                    onClick = onFavorite,
                    onFocused = clearRestart,
                    trailing =
                        if (episode.favorite) {
                            { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                        } else {
                            null
                        },
                )
            }
            if (episode.data.seriesId != null && episode.data.seasonId != null) {
                item(key = "episodes") {
                    TallyButton(
                        label = stringResource(R.string.tally_signin_episodes),
                        glyph = stringResource(R.string.fa_list_ul),
                        onClick = onSeason,
                        onFocused = clearRestart,
                    )
                }
            }
            if (sources.size > 1) {
                item(key = "version") {
                    TallyButton(
                        label = stringResource(R.string.tally_media_version),
                        glyph = stringResource(R.string.fa_file_video),
                        onClick = {
                            versionDialog =
                                chooseVersionParams(resources, sources, chosenSourceId) { index ->
                                    onChooseVersion(sources[index])
                                }
                        },
                        onFocused = clearRestart,
                    )
                }
            }
            item(key = "more") {
                TallyButton(
                    label = stringResource(R.string.tally_media_more),
                    glyph = stringResource(R.string.fa_ellipsis),
                    onClick = onMore,
                    onFocused = clearRestart,
                )
            }
        }
    }
    versionDialog?.let { params ->
        DialogPopup(
            showDialog = true,
            title = params.title,
            dialogItems = params.items,
            onDismissRequest = { versionDialog = null },
            dismissOnClick = true,
            waitToLoad = params.fromLongClick,
        )
    }
}

/** `JAN 20, 2008` · rating box · `47M` · `★ 8.2`. */
@Composable
private fun episodeMeta(episode: BaseItem): List<DetailMetaPart> {
    val runtimeTicks = episode.data.runTimeTicks ?: 0L
    return buildList {
        airDate(episode.data.premiereDate)?.let { add(DetailMetaPart.Plain(it)) }
        episode.data.officialRating
            ?.takeIf { it.isNotBlank() }
            ?.let { add(DetailMetaPart.Boxed(it)) }
        if (runtimeTicks > 0L) add(DetailMetaPart.Plain(formatRuntime(runtimeTicks)))
        episode.data.communityRating?.let { rating ->
            add(
                DetailMetaPart.Plain(
                    stringResource(R.string.tally_media_community, String.format(Locale.US, "%.1f", rating)),
                ),
            )
        }
        episode.data.criticRating?.let { rating ->
            add(DetailMetaPart.Plain(stringResource(R.string.tally_media_critic, rating.roundToInt())))
        }
    }
}

@Composable
private fun episodeEnds(episode: BaseItem): String? {
    val context = LocalContext.current
    val runtimeTicks = episode.data.runTimeTicks ?: 0L
    val positionTicks = episode.data.userData?.playbackPositionTicks ?: 0L
    val remainingMs = ((runtimeTicks - positionTicks).coerceAtLeast(0L)) / 10_000L
    val is24h = DateFormat.is24HourFormat(context)
    return remember(remainingMs, is24h, episode.played) {
        if (episode.played || remainingMs <= 0L) {
            null
        } else {
            formatEndsAt(Instant.now(), remainingMs, ZoneId.systemDefault(), is24h)
        }
    }
}

@Composable
private fun directorLine(episode: BaseItem): String? {
    val names =
        episode.data.people
            ?.filter { it.type == PersonKind.DIRECTOR && it.name.isNotNullOrBlank() }
            ?.mapNotNull { it.name }
            ?.distinct()
            .orEmpty()
    if (names.isEmpty()) return null
    return stringResource(R.string.tally_media_directed_by, names.joinToString(", "))
}

/** `E04 · 47M` */
@Composable
private fun seasonCardKicker(item: BaseItem): String? {
    val number = episodeNumber(item.indexNumber)
    val runtime = (item.data.runTimeTicks ?: 0L).takeIf { it > 0L }?.let { formatRuntime(it) }
    return when {
        number != null && runtime != null -> stringResource(R.string.tally_series_card_kicker, number, runtime)
        else -> number ?: runtime
    }
}

@Composable
private fun chapterImage(chapter: Chapter): String? {
    val density = LocalDensity.current
    val service = LocalImageUrlService.current
    return remember(chapter.itemId, chapter.index, chapter.tag, density) {
        service.getItemImageUrl(
            itemId = chapter.itemId,
            imageType = ImageType.CHAPTER,
            tag = chapter.tag,
            imageIndex = chapter.index,
            fillWidth = with(density) { LandscapeWidth.roundToPx() },
        )
    }
}
