package io.github.scdouglas1999.tally.media.movie.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.data.stringRes
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.TrailerDialog
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.detail.movie.MovieState
import com.github.damontecres.wholphin.ui.detail.movie.MovieViewModel
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import io.github.scdouglas1999.tally.downloads.ui.DownloadSubject
import io.github.scdouglas1999.tally.downloads.ui.phoneAction
import io.github.scdouglas1999.tally.downloads.ui.rememberDownloadUi
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.PersonCard
import io.github.scdouglas1999.tally.media.kit.PosterCard
import io.github.scdouglas1999.tally.media.kit.formatPosition
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.kit.techBoxes
import io.github.scdouglas1999.tally.media.movie.chapterImage
import io.github.scdouglas1999.tally.media.movie.directorLine
import io.github.scdouglas1999.tally.media.movie.movieEnds
import io.github.scdouglas1999.tally.media.movie.movieMeta
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import kotlin.time.Duration

/**
 * The film page on a phone: the backdrop, the heading, PLAY / RESUME and the action buttons, the overview, director
 * and format chips, then the TV page's rows. Same view model, state, dialogs and menus as the TV page
 * (`TallyMoviePage`), which hands its loaded state here on a phone.
 */
@Composable
fun PhoneMovieLoaded(
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
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var showTrailers by remember { mutableStateOf(false) }
    val downloads = rememberDownloadUi()
    val downloadSubject = remember(movie.id, movie.name) { DownloadSubject.of(movie) }

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

    val onTrailer: (Trailer) -> Unit = { trailer -> TrailerService.onClick(context, trailer, viewModel::navigateTo) }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val images = LocalImageUrlService.current
    val backdropUrl = remember(movie.id) { images.getItemImageUrl(movie, ImageType.BACKDROP) }

    // Opaque: the app-wide backdrop behind the page does not show through (the page draws its own picture).
    Box(modifier = Modifier.fillMaxSize().background(TallyColors.ground)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottom + 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "backdrop") {
                PhoneDetailBackdrop(imageUrl = backdropUrl, onBack = { backDispatcher?.onBackPressed() })
            }
            item(key = "heading") {
                val logoUrl = rememberLogoUrl(movie)
                PhoneDetailHeading(
                    kicker =
                        if (movie.type == BaseItemKind.VIDEO) {
                            stringResource(R.string.tally_media_video)
                        } else {
                            stringResource(R.string.tally_media_film)
                        },
                    title = movie.name ?: "",
                    logoUrl = if (preferences.appPreferences.interfacePreferences.showLogos) logoUrl else null,
                    meta = movieMeta(movie),
                    ends = movieEnds(movie),
                    genres = movie.data.genres.orEmpty(),
                    tagline = movie.data.taglines?.firstOrNull(),
                )
            }
            item(key = "actions") {
                PhoneItemActions(
                    item = movie,
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
                    trailing = listOfNotNull(downloadSubject?.let { downloads.phoneAction(it) }),
                    onPlay = { position ->
                        viewModel.navigateTo(Destination.Playback(movie.id, position.inWholeMilliseconds))
                    },
                    onWatch = { viewModel.setWatched(movie.id, !movie.played) },
                    onFavorite = { viewModel.setFavorite(movie.id, !movie.favorite) },
                    onMore = { openMovieMenu(fromLongClick = false) },
                )
            }
            item(key = "about") {
                PhoneAbout(
                    overview = movie.data.overview,
                    caption = directorLine(movie),
                    tech =
                        techBoxes(
                            state.chosenStreams?.source ?: movie.data.mediaSources?.firstOrNull(),
                            state.chosenStreams?.videoStream,
                            state.chosenStreams?.audioStream,
                        ),
                )
            }
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
                                    navigateTo = viewModel::navigateTo,
                                    onClickFavorite = viewModel::setFavorite,
                                ),
                        )
                },
            )
            if (state.chapters.isNotEmpty()) {
                item(key = "chapters") {
                    PhoneMediaRow(
                        title = stringResource(R.string.tally_media_chapters),
                        items = state.chapters,
                        key = { _, chapter -> chapter.index },
                    ) { chapter, _ ->
                        val play = {
                            viewModel.navigateTo(Destination.Playback(movie.id, chapter.position.inWholeMilliseconds))
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
                            onClick = play,
                            onLongClick = { openMovieMenu(fromLongClick = true) },
                            onPlay = play,
                            width = PhoneLandscapeWidth,
                        )
                    }
                }
            }
            phoneExtrasRow(
                extras = state.extras,
                onOpen = { viewModel.navigateTo(it.destination) },
                onMenu = { extra ->
                    val item =
                        when (extra) {
                            is ExtrasItem.Single -> extra.item
                            is ExtrasItem.Group -> extra.items.firstOrNull()
                        }
                    if (item != null) openItemMenu(item) else openMovieMenu(true)
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
}

/**
 * PLAY (or RESUME 42% with its progress line) across the width, then the icon-over-label buttons: [extra] first
 * (TRAILER), WATCHED, FAVORITE, [trailing] (EPISODES) and MORE. [onPlay] gets the position to start from.
 * [primaryLabel] and [primaryProgress] replace the item's own (a show's NEXT UP · S1 E6).
 */
@Composable
fun PhoneItemActions(
    item: BaseItem,
    extra: List<PhoneAction>,
    trailing: List<PhoneAction>,
    onPlay: (Duration) -> Unit,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    primaryProgress: Float? = null,
) {
    val resume = item.playbackPosition
    val resumable = resume > Duration.ZERO
    val percent =
        resumePercent(
            item.data.userData?.playbackPositionTicks ?: 0L,
            item.data.runTimeTicks ?: 0L,
        ).let { if (resumable && it == 0) 1 else it }
    Column(
        modifier =
            modifier
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 20.dp)
                .widthIn(max = PhoneDimens.buttonMaxWidth)
                .fillMaxWidth(),
    ) {
        PhonePrimaryButton(
            label =
                primaryLabel
                    ?: if (resumable) {
                        stringResource(R.string.tally_media_resume, percent)
                    } else {
                        stringResource(R.string.tally_media_play)
                    },
            glyph = stringResource(R.string.fa_play),
            progress =
                if (primaryLabel != null) {
                    primaryProgress
                } else if (resumable) {
                    percent / 100f
                } else {
                    null
                },
            onClick = { onPlay(if (resumable) resume else Duration.ZERO) },
        )
        Spacer(Modifier.height(10.dp))
        PhoneActionRow(
            actions =
                buildList {
                    addAll(extra)
                    add(
                        PhoneAction(
                            key = "watched",
                            glyph = stringResource(if (item.played) R.string.fa_eye else R.string.fa_eye_slash),
                            label =
                                stringResource(
                                    if (item.played) R.string.tally_media_watched else R.string.tally_media_unwatched,
                                ),
                            onClick = onWatch,
                        ),
                    )
                    add(
                        PhoneAction(
                            key = "favorite",
                            glyph = stringResource(R.string.fa_heart),
                            label =
                                stringResource(
                                    if (item.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite,
                                ),
                            onClick = onFavorite,
                            active = item.favorite,
                        ),
                    )
                    addAll(trailing)
                    add(
                        PhoneAction(
                            key = "more",
                            glyph = stringResource(R.string.fa_ellipsis),
                            label = stringResource(R.string.tally_media_more),
                            onClick = onMore,
                        ),
                    )
                },
        )
    }
}

/** The overview (4 lines, tap to expand), the credit caption and the format chips; [bottomGap] under them. */
@Composable
fun PhoneAbout(
    overview: String?,
    caption: String?,
    tech: List<String>,
    modifier: Modifier = Modifier,
    bottomGap: Dp = PhoneDimens.rowGap,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 20.dp, bottom = bottomGap),
    ) {
        if (!overview.isNullOrBlank()) PhoneOverview(overview)
        if (!caption.isNullOrBlank()) {
            PhoneCaption(caption, modifier = Modifier.padding(top = 10.dp))
        }
        if (tech.isNotEmpty()) PhoneTechChips(tech, modifier = Modifier.padding(top = 12.dp))
    }
}

/** CAST & CREW as 88dp person cards. */
fun LazyListScope.phonePeopleRow(
    people: List<Person>,
    onOpen: (Person) -> Unit,
    onMenu: (Person) -> Unit,
    title: Int = R.string.tally_media_cast,
) {
    if (people.isEmpty()) return
    item(key = "people-$title") {
        PhoneMediaRow(
            title = stringResource(title),
            items = people,
            // One person can hold several credits, so the id alone is not a unique key.
            key = { index, person -> "$index-${person.id}" },
        ) { person, _ ->
            PersonCard(
                name = person.name ?: "",
                role = person.role,
                imageUrl = person.imageUrl,
                onClick = { onOpen(person) },
                onLongClick = { onMenu(person) },
                width = PhonePersonWidth,
            )
        }
    }
}

/** EXTRAS as landscape cards. */
fun LazyListScope.phoneExtrasRow(
    extras: List<ExtrasItem>,
    onOpen: (ExtrasItem) -> Unit,
    onMenu: ((ExtrasItem) -> Unit)?,
) {
    if (extras.isEmpty()) return
    item(key = "extras") {
        PhoneMediaRow(
            title = stringResource(R.string.tally_media_extras),
            items = extras,
            key = { index, extra -> "${extra.type}-$index-${extra.title}" },
        ) { extra, _ ->
            val single = extra as? ExtrasItem.Single
            val image = if (single != null) rememberWideImageUrl(single.item) else extra.imageUrl
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
                favorite = single?.item?.favorite == true,
                onClick = { onOpen(extra) },
                onLongClick = { onMenu?.invoke(extra) },
                onPlay = { onOpen(extra) },
                width = PhoneLandscapeWidth,
            )
        }
    }
}

/** MORE LIKE THIS as posters. */
fun LazyListScope.phoneSimilarRow(
    items: List<BaseItem>,
    onOpen: (BaseItem) -> Unit,
    onMenu: (BaseItem) -> Unit,
    onPlay: (BaseItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "similar") {
        PhoneMediaRow(
            title = stringResource(R.string.tally_media_more_like_this),
            items = items,
            key = { _, item -> item.id },
        ) { item, _ ->
            PosterCard(
                item = item,
                onClick = { onOpen(item) },
                onLongClick = { onMenu(item) },
                onPlay = { onPlay(item) },
                width = PhoneDimens.posterWidth,
            )
        }
    }
}

/** Upstream's Discover row (Seerr), as the TV page shows it. */
fun LazyListScope.phoneDiscoverRow(
    discovered: List<DiscoverItem>,
    onOpen: (DiscoverItem) -> Unit,
) {
    if (discovered.isEmpty()) return
    item(key = "discover") {
        DiscoverRow(
            row =
                DiscoverRowData(
                    ResStringProvider(R.string.discover),
                    DataLoadingState.Success(discovered),
                    type = DiscoverRequestType.UNKNOWN,
                ),
            onClickItem = { _: Int, item: DiscoverItem -> onOpen(item) },
            onLongClickItem = { _, _ -> },
            onCardFocus = { },
            focusRequester = remember { FocusRequester() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneDimens.margin)
                    .padding(bottom = PhoneDimens.rowGap),
        )
    }
}
