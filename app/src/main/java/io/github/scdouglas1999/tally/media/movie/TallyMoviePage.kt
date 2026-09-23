package io.github.scdouglas1999.tally.media.movie

import android.text.format.DateFormat
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.data.stringRes
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.RequestOrRestoreFocus
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.TrailerDialog
import com.github.damontecres.wholphin.ui.components.chooseVersionParams
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.movie.MovieState
import com.github.damontecres.wholphin.ui.detail.movie.MovieViewModel
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.ExceptionHandler
import io.github.scdouglas1999.tally.media.kit.DetailHeader
import io.github.scdouglas1999.tally.media.kit.DetailMetaPart
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.LandscapeWidth
import io.github.scdouglas1999.tally.media.kit.MediaRow
import io.github.scdouglas1999.tally.media.kit.PersonCard
import io.github.scdouglas1999.tally.media.kit.PosterCard
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.bleedHorizontal
import io.github.scdouglas1999.tally.media.kit.formatEndsAt
import io.github.scdouglas1999.tally.media.kit.formatPosition
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.kit.techBoxes
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

private const val POS_ACTIONS = 0
private const val POS_PEOPLE = 1
private const val POS_CHAPTERS = 2
private const val POS_EXTRAS = 3
private const val POS_SIMILAR = 4
private const val POS_DISCOVER = 5

@Composable
fun TallyMoviePage(
    destination: Destination.MediaItem,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: MovieViewModel =
        hiltViewModel<MovieViewModel, MovieViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.init()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)
    val contextActions =
        remember(dialogs) {
            ContextMenuActions(
                navigateTo = viewModel::navigateTo,
                onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
                onClickWatch = viewModel::setWatched,
                onClickFavorite = viewModel::setFavorite,
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
                onClearChosenStreams = { viewModel.clearChosenStreams(it) },
            )
        }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(modifier = modifier.fillMaxSize()) {
                    when (val loading = state.loading) {
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
                            LoadingMark(Modifier.fillMaxSize())
                        }

                        is DataLoadingState.Success -> {
                            LifecycleResumeEffect(destination.itemId) {
                                viewModel.maybePlayThemeSong(destination.itemId)
                                onPauseOrDispose { viewModel.release() }
                            }
                            MovieLoaded(
                                preferences = preferences,
                                movie = loading.data,
                                state = state,
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

@Composable
private fun LoadingMark(modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.tryRequestFocus("jtv-movie-loading") }
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
private fun MovieLoaded(
    preferences: UserPreferences,
    movie: BaseItem,
    state: MovieState,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
    viewModel: MovieViewModel,
) {
    val context = LocalContext.current
    val movieNow by rememberUpdatedState(movie)
    val streamsNow by rememberUpdatedState(state.chosenStreams)
    var position by rememberInt(POS_ACTIONS)
    val primaryFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val peopleFocus = remember { FocusRequester() }
    val chaptersFocus = remember { FocusRequester() }
    val extrasFocus = remember { FocusRequester() }
    val similarFocus = remember { FocusRequester() }
    val discoverFocus = remember { FocusRequester() }
    val bringHeader = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val firstRowFocus =
        when {
            state.people.isNotEmpty() -> peopleFocus
            state.chapters.isNotEmpty() -> chaptersFocus
            state.extras.isNotEmpty() -> extrasFocus
            state.similar.isNotEmpty() -> similarFocus
            state.discovered.isNotEmpty() -> discoverFocus
            else -> null
        }
    val restore =
        when (position) {
            POS_PEOPLE -> if (state.people.isNotEmpty()) peopleFocus else primaryFocus
            POS_CHAPTERS -> if (state.chapters.isNotEmpty()) chaptersFocus else primaryFocus
            POS_EXTRAS -> if (state.extras.isNotEmpty()) extrasFocus else primaryFocus
            POS_SIMILAR -> if (state.similar.isNotEmpty()) similarFocus else primaryFocus
            POS_DISCOVER -> if (state.discovered.isNotEmpty()) discoverFocus else primaryFocus
            else -> primaryFocus
        }
    RequestOrRestoreFocus(restore, "jtv-movie")

    val onActionFocused: () -> Unit = {
        position = POS_ACTIONS
        scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
        Unit
    }

    fun openMovieMenu(fromLongClick: Boolean) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
                item = movieNow,
                chosenStreams = streamsNow,
                showGoTo = false,
                showStreamChoices = true,
                canDelete = state.canDelete,
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

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinScrollBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    val logoUrl = rememberLogoUrl(movie)
                    DetailHeader(
                        kicker =
                            if (movie.type == BaseItemKind.VIDEO) {
                                stringResource(R.string.tally_media_video)
                            } else {
                                stringResource(R.string.tally_media_film)
                            },
                        title = movie.name ?: "",
                        logoUrl =
                            if (preferences.appPreferences.interfacePreferences.showLogos) {
                                logoUrl
                            } else {
                                null
                            },
                        meta = movieMeta(movie),
                        ends = movieEnds(movie),
                        genres = movie.data.genres.orEmpty(),
                        tagline = movie.data.taglines?.firstOrNull(),
                        overview = movie.data.overview,
                        director = directorLine(movie),
                        tech =
                            techBoxes(
                                state.chosenStreams?.source ?: movie.data.mediaSources?.firstOrNull(),
                                state.chosenStreams?.videoStream,
                                state.chosenStreams?.audioStream,
                            ),
                        onOverviewClick = { dialogs.overview = ItemDetailsDialogInfo(movieNow) },
                        actions = {
                            ActionRow(
                                movie = movie,
                                state = state,
                                primaryFocus = primaryFocus,
                                actionFocus = actionFocus,
                                down = firstRowFocus,
                                onActionFocused = onActionFocused,
                                onPlay = { duration ->
                                    position = POS_ACTIONS
                                    viewModel.navigateTo(
                                        Destination.Playback(movie.id, duration.inWholeMilliseconds),
                                    )
                                },
                                onTrailer = { trailer ->
                                    TrailerService.onClick(context, trailer, viewModel::navigateTo)
                                },
                                onWatch = { viewModel.setWatched(movie.id, !movie.played) },
                                onFavorite = { viewModel.setFavorite(movie.id, !movie.favorite) },
                                onMore = { openMovieMenu(fromLongClick = false) },
                                onChooseVersion = { source ->
                                    contextActions.onChooseVersion(movie, source)
                                },
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bringHeader),
                    )
                }
                if (state.people.isNotEmpty()) {
                    item(key = "people") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_cast),
                                items = state.people,
                                // One person can hold several credits (Nolan directs, writes and produces
                                // Inception), so the id alone is not a unique key.
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
                if (state.chapters.isNotEmpty()) {
                    item(key = "chapters") {
                        RowGround { reveal ->
                            MediaRow(
                                title = stringResource(R.string.tally_media_chapters),
                                items = state.chapters,
                                key = { _, chapter -> chapter.index },
                                modifier = Modifier.focusRequester(chaptersFocus),
                                up = if (firstRowFocus == chaptersFocus) actionFocus else null,
                                onRowFocused = {
                                    position = POS_CHAPTERS
                                    reveal()
                                },
                                card = { chapter, _, cardModifier, onFocused ->
                                    val image = chapterImage(chapter)
                                    LandscapeCard(
                                        title = chapter.name ?: "",
                                        kicker =
                                            stringResource(
                                                R.string.tally_media_chapter,
                                                chapter.index + 1,
                                                formatPosition(chapter.position.inWholeMilliseconds * 10_000L),
                                            ),
                                        imageUrl = image,
                                        onClick = {
                                            position = POS_CHAPTERS
                                            viewModel.navigateTo(
                                                Destination.Playback(
                                                    movie.id,
                                                    chapter.position.inWholeMilliseconds,
                                                ),
                                            )
                                        },
                                        onLongClick = {
                                            position = POS_CHAPTERS
                                            openMovieMenu(fromLongClick = true)
                                        },
                                        onPlay = {
                                            position = POS_CHAPTERS
                                            viewModel.navigateTo(
                                                Destination.Playback(
                                                    movie.id,
                                                    chapter.position.inWholeMilliseconds,
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
                                        onLongClick = {
                                            position = POS_EXTRAS
                                            val item =
                                                when (extra) {
                                                    is ExtrasItem.Single -> extra.item
                                                    is ExtrasItem.Group -> extra.items.firstOrNull()
                                                }
                                            if (item != null) openItemMenu(item) else openMovieMenu(true)
                                        },
                                        onPlay = {
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
        val scrolled by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
        }
        if (scrolled) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
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
}

/**
 * Scroll only enough to reveal the focused child. The TV default parks it a third of the way
 * down, which pushes the film header off the top when the play button takes focus.
 */
@OptIn(ExperimentalFoundationApi::class)
private object MinScrollBringIntoViewSpec : BringIntoViewSpec {
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
private val TopScrimHeight = 64.dp

/**
 * A row section on opaque ground. [content] gets `reveal`, to call when one of its cards takes
 * focus: it scrolls the whole section into view with [TopScrimHeight] of room above it, so the
 * row header never lands under the top scrim.
 */
@Composable
private fun RowGround(content: @Composable (reveal: () -> Unit) -> Unit) {
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
private fun ActionRow(
    movie: BaseItem,
    state: MovieState,
    primaryFocus: FocusRequester,
    actionFocus: FocusRequester,
    down: FocusRequester?,
    onActionFocused: () -> Unit,
    onPlay: (Duration) -> Unit,
    onTrailer: (Trailer) -> Unit,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    onChooseVersion: (MediaSourceInfo) -> Unit,
) {
    val resources = LocalResources.current
    val resume = movie.playbackPosition
    val resumable = resume > Duration.ZERO
    val percent =
        resumePercent(
            movie.data.userData?.playbackPositionTicks ?: 0L,
            movie.data.runTimeTicks ?: 0L,
        ).let { if (resumable && it == 0) 1 else it }
    var restartWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(resumable) {
        if (!resumable && restartWasFocused) {
            restartWasFocused = false
            primaryFocus.tryRequestFocus("jtv-movie-play")
        }
    }
    val clearRestart: () -> Unit = {
        restartWasFocused = false
        onActionFocused()
    }
    var versionDialog by remember { mutableStateOf<DialogParams?>(null) }
    var showTrailers by remember { mutableStateOf(false) }
    val sources =
        movie.data.mediaSources
            .orEmpty()
            .filter { it.id.isNotNullOrBlank() }
    val playGlyph = stringResource(R.string.fa_play)
    val restartGlyph = stringResource(R.string.fa_rotate_left)
    val trailerGlyph = stringResource(R.string.fa_film)
    val watchedGlyph = stringResource(if (movie.played) R.string.fa_eye else R.string.fa_eye_slash)
    val heartGlyph = stringResource(R.string.fa_heart)
    val versionGlyph = stringResource(R.string.fa_file_video)
    val moreGlyph = stringResource(R.string.fa_ellipsis)

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
                    glyph = playGlyph,
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
                        glyph = restartGlyph,
                        onClick = { onPlay(Duration.ZERO) },
                        onFocused = {
                            restartWasFocused = true
                            onActionFocused()
                        },
                    )
                }
            }
            if (state.trailers.isNotEmpty()) {
                item(key = "trailer") {
                    TallyButton(
                        label = stringResource(R.string.tally_media_trailer),
                        glyph = trailerGlyph,
                        onClick = {
                            if (state.trailers.size == 1) {
                                onTrailer(state.trailers.first())
                            } else {
                                showTrailers = true
                            }
                        },
                        onFocused = clearRestart,
                    )
                }
            }
            item(key = "watched") {
                TallyButton(
                    label =
                        stringResource(
                            if (movie.played) R.string.tally_media_watched else R.string.tally_media_unwatched,
                        ),
                    glyph = watchedGlyph,
                    onClick = onWatch,
                    onFocused = clearRestart,
                )
            }
            item(key = "favorite") {
                TallyButton(
                    label =
                        stringResource(
                            if (movie.favorite) {
                                R.string.tally_media_favorited
                            } else {
                                R.string.tally_media_favorite
                            },
                        ),
                    glyph = heartGlyph,
                    onClick = onFavorite,
                    onFocused = clearRestart,
                    trailing =
                        if (movie.favorite) {
                            { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                        } else {
                            null
                        },
                )
            }
            if (sources.size > 1) {
                item(key = "version") {
                    TallyButton(
                        label = stringResource(R.string.tally_media_version),
                        glyph = versionGlyph,
                        onClick = {
                            versionDialog =
                                chooseVersionParams(
                                    resources,
                                    sources,
                                    state.chosenStreams
                                        ?.source
                                        ?.id
                                        ?.toUUIDOrNull(),
                                ) { index ->
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
                    glyph = moreGlyph,
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
    if (showTrailers) {
        TrailerDialog(
            onDismissRequest = { showTrailers = false },
            trailers = state.trailers,
            onClick = onTrailer,
        )
    }
}

@Composable
private fun movieMeta(movie: BaseItem): List<DetailMetaPart> {
    val runtimeTicks = movie.data.runTimeTicks ?: 0L
    return buildList {
        movie.data.productionYear?.let { add(DetailMetaPart.Plain(it.toString())) }
        movie.data.officialRating
            ?.takeIf { it.isNotBlank() }
            ?.let { add(DetailMetaPart.Boxed(it)) }
        if (runtimeTicks > 0L) add(DetailMetaPart.Plain(formatRuntime(runtimeTicks)))
        movie.data.communityRating?.let { rating ->
            add(
                DetailMetaPart.Plain(
                    stringResource(
                        R.string.tally_media_community,
                        String.format(Locale.US, "%.1f", rating),
                    ),
                ),
            )
        }
        movie.data.criticRating?.let { rating ->
            add(DetailMetaPart.Plain(stringResource(R.string.tally_media_critic, rating.roundToInt())))
        }
    }
}

@Composable
private fun movieEnds(movie: BaseItem): String? {
    val context = LocalContext.current
    val runtimeTicks = movie.data.runTimeTicks ?: 0L
    val positionTicks = movie.data.userData?.playbackPositionTicks ?: 0L
    val remainingMs = ((runtimeTicks - positionTicks).coerceAtLeast(0L)) / 10_000L
    val is24h = DateFormat.is24HourFormat(context)
    return remember(remainingMs, is24h, movie.played) {
        if (movie.played || remainingMs <= 0L) {
            null
        } else {
            formatEndsAt(Instant.now(), remainingMs, ZoneId.systemDefault(), is24h)
        }
    }
}

@Composable
private fun directorLine(movie: BaseItem): String? {
    val names =
        movie.data.people
            ?.filter { it.type == PersonKind.DIRECTOR && it.name.isNotNullOrBlank() }
            ?.mapNotNull { it.name }
            ?.distinct()
            .orEmpty()
    if (names.isEmpty()) return null
    return stringResource(R.string.tally_media_directed_by, names.joinToString(", "))
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

@Composable
private fun ExtraCard(
    extra: ExtrasItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
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
        onLongClick = onLongClick,
        onPlay = onPlay,
        onFocused = onFocused,
        modifier = modifier,
    )
}
