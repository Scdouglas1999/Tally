package io.github.scdouglas1999.tally.media.music

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilterOverride
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.components.RecommendedRow
import com.github.damontecres.wholphin.ui.components.RecommendedViewModel
import com.github.damontecres.wholphin.ui.components.TabViewModel
import com.github.damontecres.wholphin.ui.components.ViewOptionsSquare
import com.github.damontecres.wholphin.ui.data.AlbumSortOptions
import com.github.damontecres.wholphin.ui.data.ArtistSortOptions
import com.github.damontecres.wholphin.ui.data.SongSortOptions
import com.github.damontecres.wholphin.ui.detail.CollectionFolderMusicViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.successValue
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.library.ClickKind
import io.github.scdouglas1999.tally.media.library.FolderBody
import io.github.scdouglas1999.tally.media.library.FolderControls
import io.github.scdouglas1999.tally.media.library.FolderCount
import io.github.scdouglas1999.tally.media.library.FolderDialogs
import io.github.scdouglas1999.tally.media.library.FolderSpec
import io.github.scdouglas1999.tally.media.library.GenreCount
import io.github.scdouglas1999.tally.media.library.GenreGrid
import io.github.scdouglas1999.tally.media.library.LibraryPageViewModel
import io.github.scdouglas1999.tally.media.library.LibraryRecommended
import io.github.scdouglas1999.tally.media.library.LibraryScaffold
import io.github.scdouglas1999.tally.media.library.LoadingMark
import io.github.scdouglas1999.tally.media.library.folderViewModel
import io.github.scdouglas1999.tally.media.library.genreViewModel
import io.github.scdouglas1999.tally.media.library.rememberFolderUi
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.time.LocalDateTime
import java.util.UUID

/** The tabs of a music library, in upstream's order (`CollectionFolderMusic`). */
private enum class MusicTab(
    @param:StringRes val title: Int,
) {
    RECOMMENDED(R.string.tally_music_tab_recommended),
    ALBUMS(R.string.tally_music_tab_albums),
    ARTISTS(R.string.tally_music_tab_artists),
    GENRES(R.string.tally_music_tab_genres),
    SONGS(R.string.tally_music_tab_songs),
}

/**
 * A music library in the Tally look: the library page's header (kicker, tab strip, count and controls) and grid
 * (`media/library`), with upstream's `CollectionFolderMusic` tabs and data: Recommended (upstream's music rows),
 * Albums, Artists and Songs (the same `CollectionFolderViewModel` per tab, same keys, square cards, the A–Z bar), and
 * Genres. Play all / shuffle and the play key on a song use upstream's music queue
 * ([CollectionFolderMusicViewModel]), not the video player.
 */
@Composable
fun TallyMusicLibrary(
    destination: Destination.MediaItem,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
    musicViewModel: CollectionFolderMusicViewModel =
        hiltViewModel<CollectionFolderMusicViewModel, CollectionFolderMusicViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
) {
    val itemId = destination.itemId
    val drawerName by remember(itemId) { pageViewModel.libraryName(itemId) }.collectAsState(null)
    val tabs = MusicTab.entries
    val tabViewModel =
        hiltViewModel<TabViewModel, TabViewModel.Factory>(
            key = "$itemId-${tabs.size}",
            creationCallback = { it.create(itemId.toString(), tabs.size) },
        )
    val selected by tabViewModel.state.collectAsState()
    val tab = tabs.getOrNull(selected)
    val tabRequesters = remember { List(tabs.size) { FocusRequester() } }
    val spec = tab?.let { remember(itemId, it) { musicSpec(itemId, it, musicViewModel) } }
    val ui = rememberFolderUi(spec?.viewModelKey ?: "music-tab-$selected")
    val recommendedDialogs = remember { ItemDialogsState() }
    val includeGenreTypes = listOf(BaseItemKind.MUSIC_ALBUM)
    val folderName =
        spec?.let {
            folderViewModel(it)
                .state
                .collectAsState()
                .value.item.successValue
                ?.name
        }
    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .drawBehind {
                                // The library page's scrim over the app backdrop.
                                drawRect(
                                    brush =
                                        Brush.linearGradient(
                                            colorStops =
                                                arrayOf(
                                                    0f to TallyColors.ground.copy(alpha = 0.88f),
                                                    0.45f to TallyColors.ground.copy(alpha = 0.5f),
                                                    0.85f to Color.Transparent,
                                                ),
                                            start = Offset.Zero,
                                            end = Offset(size.width * 0.95f, size.height * 0.15f),
                                        ),
                                )
                            },
                ) {
                    LibraryScaffold(
                        kicker = drawerName ?: folderName ?: stringResource(R.string.tally_music_library),
                        tabs = tabs.map { stringResource(it.title) },
                        selectedTab = selected,
                        onSelectTab = tabViewModel::updateSelectedTabIndex,
                        tabRequesters = tabRequesters,
                        count = {
                            when {
                                spec != null -> FolderCount(spec = spec, viewModel = folderViewModel(spec))
                                tab == MusicTab.GENRES -> GenreCount(genreViewModel(itemId, includeGenreTypes))
                            }
                        },
                        controls = {
                            if (spec != null) FolderControls(spec = spec, viewModel = folderViewModel(spec), ui = ui)
                        },
                        body = {
                            when {
                                tab == null -> {
                                    LoadingMark(Modifier.fillMaxSize())
                                }

                                spec != null -> {
                                    FolderBody(
                                        spec = spec,
                                        preferences = preferences,
                                        viewModel = folderViewModel(spec),
                                        ui = ui,
                                        kicker = drawerName ?: folderName.orEmpty(),
                                        nav = pageViewModel.navigationManager,
                                        focusOnEmpty = tabRequesters.getOrNull(selected),
                                    )
                                }

                                tab == MusicTab.RECOMMENDED -> {
                                    LibraryRecommended(
                                        preferences = preferences,
                                        viewModel = musicRecommendedViewModel(itemId),
                                        watchingRows = emptySet(),
                                        dialogs = recommendedDialogs,
                                    )
                                }

                                tab == MusicTab.GENRES -> {
                                    GenreGrid(
                                        itemId = itemId,
                                        includeItemTypes = includeGenreTypes,
                                        collectionType = CollectionType.MUSIC,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    if (spec != null) {
        FolderDialogs(spec = spec, preferences = preferences, viewModel = folderViewModel(spec), ui = ui)
    } else if (tab == MusicTab.RECOMMENDED) {
        ItemDialogsHost(
            state = recommendedDialogs,
            getMediaSource = { _, _ -> null },
            preferredSubtitleLanguage = null,
            showFilePath = false,
            onConfirmDelete = {},
        )
    }
}

/**
 * The grid tabs as `CollectionFolderMusic` sets up its `CollectionFolderView`s: same view model keys, filters, sort
 * options, square view options and play rules; play all / shuffle and a song's play key go to the music queue.
 */
private fun musicSpec(
    itemId: UUID,
    tab: MusicTab,
    musicViewModel: CollectionFolderMusicViewModel,
): FolderSpec? {
    val playSong: (Int, BaseItem) -> Unit = { index, item -> musicViewModel.onClickPlayRemoteButton(index, item) }
    val playAll: (Boolean) -> Unit = { shuffle -> musicViewModel.onClickPlayAll(shuffle) }

    fun spec(
        key: String,
        filter: GetItemsFilter,
        sortOptions: List<ItemSortBy>,
        playEnabled: Boolean,
        countNoun: Int,
    ) = FolderSpec(
        itemId = itemId.toServerString(),
        viewModelKey = "${itemId}_$key",
        initialFilter = CollectionFolderFilter(filter = filter),
        recursive = true,
        sortOptions = sortOptions,
        filterOptions = DefaultFilterOptions,
        playEnabled = playEnabled,
        defaultViewOptions = ViewOptionsSquare,
        useSeriesForPrimary = true,
        click = ClickKind.DESTINATION,
        jumpBar = true,
        collectionType = CollectionType.MUSIC,
        onPlayAll = playAll,
        onPlayItem = playSong,
        countNoun = countNoun,
    )
    return when (tab) {
        MusicTab.ALBUMS -> {
            spec(
                key = "albums",
                filter = GetItemsFilter(includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM)),
                sortOptions = AlbumSortOptions,
                playEnabled = true,
                countNoun = R.plurals.tally_music_album_count,
            )
        }

        MusicTab.ARTISTS -> {
            spec(
                key = "artists",
                filter = GetItemsFilter(override = GetItemsFilterOverride.ARTIST),
                sortOptions = ArtistSortOptions,
                playEnabled = false,
                countNoun = R.plurals.tally_music_artist_count,
            )
        }

        MusicTab.SONGS -> {
            spec(
                key = "songs",
                filter = GetItemsFilter(includeItemTypes = listOf(BaseItemKind.AUDIO)),
                sortOptions = SongSortOptions,
                playEnabled = true,
                countNoun = R.plurals.tally_music_song_count,
            )
        }

        MusicTab.RECOMMENDED, MusicTab.GENRES -> {
            null
        }
    }
}

/** Upstream's [RecommendedViewModel] for a music library, created as `RecommendedMusic` creates it. */
@Composable
private fun musicRecommendedViewModel(parentId: UUID): RecommendedViewModel =
    hiltViewModel<RecommendedViewModel, RecommendedViewModel.Factory>(
        creationCallback = {
            it.create(
                parentId = parentId,
                suggestionsType = BaseItemKind.MUSIC_ALBUM,
                recommendedRows = musicRows(parentId),
                viewOptions =
                    HomeRowViewOptions(
                        aspectRatio = AspectRatio.SQUARE,
                        heightDp = Cards.HEIGHT_EPISODE,
                        showTitles = true,
                    ),
            )
        },
    )

/** Upstream's recommended music rows (`RecommendedMusic.getRecommendedRows`), unchanged. */
private fun musicRows(parentId: UUID): List<RecommendedRow<*>> =
    listOf(
        RecommendedRow(
            title = R.string.recently_released,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
                    enableTotalRecordCount = false,
                    maxPremiereDate = LocalDateTime.now(),
                ),
        ),
        RecommendedRow(
            title = R.string.recently_added,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.top_unwatched,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    enableUserData = true,
                    isPlayed = false,
                    sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
    )
