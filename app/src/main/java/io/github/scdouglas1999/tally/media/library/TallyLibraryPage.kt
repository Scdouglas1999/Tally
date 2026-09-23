package io.github.scdouglas1999.tally.media.library

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.DefaultTvFilterOptions
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.CollectionFolderList
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewModel
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.TabViewModel
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsPoster
import com.github.damontecres.wholphin.ui.components.ViewOptionsType
import com.github.damontecres.wholphin.ui.components.ViewOptionsWide
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.SeriesSortOptions
import com.github.damontecres.wholphin.ui.data.VideoSortOptions
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.successValue
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.media.home.HomeHeader
import io.github.scdouglas1999.tally.media.kit.CapsLift
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.rememberMediaGridState
import io.github.scdouglas1999.tally.media.series.wholePx
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import javax.inject.Inject

/** The library's name for the page kicker, from the libraries the drawer already has (no extra request). */
@HiltViewModel
class LibraryPageViewModel
    @Inject
    constructor(
        private val navDrawerService: NavDrawerService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        fun libraryName(itemId: UUID): Flow<String?> =
            navDrawerService.state.map { state ->
                state.allLibraries.firstOrNull { it.itemId == itemId }?.name
                    ?: (state.items + state.moreItems)
                        .filterIsInstance<ServerNavDrawerItem>()
                        .firstOrNull { it.itemId == itemId }
                        ?.name
            }
    }

/**
 * A library (a `CollectionFolder`) in the Tally look: Movies, TV Shows, Collections (box sets), home videos and
 * libraries of unknown type. Data and behavior are upstream's `CollectionFolderMovie` / `CollectionFolderTv` /
 * `CollectionFolderGeneric` / `CollectionFolderPhotoAlbum`: the same tabs in the same order, remembered the same
 * way ([TabViewModel]), the same `CollectionFolderViewModel` per grid (same key and arguments: paging, sort,
 * filters, view options saved per library, position), the same genre, studio and recommended view models.
 *
 * Layout: a fixed header (the library's name as an accent kicker, the tab strip with the count and the controls at
 * its right end), then the tab's content.
 */
@Composable
fun TallyLibraryPage(
    destination: Destination.MediaItem,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
) {
    val itemId = destination.itemId
    val drawerName by remember(itemId) { pageViewModel.libraryName(itemId) }.collectAsState(null)
    val nav = pageViewModel.navigationManager
    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .drawBehind {
                                // The home page's scrim over the app backdrop (shown when a view option or the
                                // Recommended tab sets one): the header and the grid stay readable on it.
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
                    when (destination.collectionType) {
                        CollectionType.MOVIES -> {
                            TabbedLibrary(
                                itemId = itemId,
                                preferences = preferences,
                                drawerName = drawerName,
                                tabs = MovieTabs,
                                collectionType = CollectionType.MOVIES,
                                nav = nav,
                            )
                        }

                        CollectionType.TVSHOWS -> {
                            TabbedLibrary(
                                itemId = itemId,
                                preferences = preferences,
                                drawerName = drawerName,
                                tabs = TvTabs,
                                collectionType = CollectionType.TVSHOWS,
                                nav = nav,
                            )
                        }

                        else -> {
                            SingleLibrary(
                                spec = singleSpec(itemId, destination.collectionType),
                                preferences = preferences,
                                drawerName = drawerName,
                                nav = nav,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The tabs of a library, in upstream's order. */
private enum class LibraryTab(
    @param:StringRes val title: Int,
) {
    RECOMMENDED(R.string.recommended),
    LIBRARY(R.string.library),
    COLLECTIONS(R.string.collections),
    GENRES(R.string.genres),
    STUDIOS(R.string.studios),
}

private val MovieTabs = listOf(LibraryTab.RECOMMENDED, LibraryTab.LIBRARY, LibraryTab.COLLECTIONS, LibraryTab.GENRES)
private val TvTabs = listOf(LibraryTab.RECOMMENDED, LibraryTab.LIBRARY, LibraryTab.GENRES, LibraryTab.STUDIOS)

/** How a grid's click opens an item, as each upstream page does it. */
internal enum class ClickKind {
    /** `item.destination()` (Movies, TV Shows). */
    DESTINATION,

    /** `item.destination(index)` (Collections library, other libraries). */
    INDEXED,

    /** Photos open the slideshow at their place, anything else `item.destination(index)` (home videos). */
    PHOTOS,
}

/** Everything upstream passes to `CollectionFolderView` for one grid, plus the Tally extras (bar, noun). */
internal data class FolderSpec(
    val itemId: String,
    val viewModelKey: String,
    val initialFilter: CollectionFolderFilter,
    val recursive: Boolean,
    val sortOptions: List<ItemSortBy>,
    val filterOptions: List<ItemFilterBy<*>>,
    val playEnabled: Boolean,
    val defaultViewOptions: ViewOptions,
    val useSeriesForPrimary: Boolean,
    val click: ClickKind,
    val jumpBar: Boolean,
    val collectionType: CollectionType?,
)

/** The grid tabs of Movies and TV Shows, as `CollectionFolderMovie` / `CollectionFolderTv` set them up. */
private fun tabSpec(
    itemId: UUID,
    tab: LibraryTab,
    collectionType: CollectionType,
): FolderSpec? =
    when {
        collectionType == CollectionType.MOVIES && tab == LibraryTab.LIBRARY -> {
            FolderSpec(
                itemId = itemId.toServerString(),
                viewModelKey = "${itemId}_library",
                initialFilter = CollectionFolderFilter(filter = GetItemsFilter(includeItemTypes = listOf(BaseItemKind.MOVIE))),
                recursive = true,
                sortOptions = MovieSortOptions,
                filterOptions = DefaultFilterOptions,
                playEnabled = true,
                defaultViewOptions = ViewOptionsPoster,
                useSeriesForPrimary = true,
                click = ClickKind.DESTINATION,
                jumpBar = true,
                collectionType = collectionType,
            )
        }

        collectionType == CollectionType.MOVIES && tab == LibraryTab.COLLECTIONS -> {
            FolderSpec(
                itemId = itemId.toServerString(),
                viewModelKey = "${itemId}_collection",
                initialFilter = CollectionFolderFilter(filter = GetItemsFilter(includeItemTypes = listOf(BaseItemKind.BOX_SET))),
                recursive = true,
                sortOptions = VideoSortOptions,
                filterOptions = DefaultFilterOptions,
                playEnabled = false,
                defaultViewOptions = ViewOptionsPoster,
                useSeriesForPrimary = true,
                click = ClickKind.DESTINATION,
                jumpBar = false,
                collectionType = collectionType,
            )
        }

        collectionType == CollectionType.TVSHOWS && tab == LibraryTab.LIBRARY -> {
            FolderSpec(
                itemId = itemId.toServerString(),
                // Upstream's TV library grid uses CollectionFolderView's default key.
                viewModelKey = itemId.toServerString(),
                initialFilter = CollectionFolderFilter(filter = GetItemsFilter(includeItemTypes = listOf(BaseItemKind.SERIES))),
                recursive = true,
                sortOptions = SeriesSortOptions,
                filterOptions = DefaultTvFilterOptions,
                playEnabled = false,
                defaultViewOptions = ViewOptionsPoster,
                useSeriesForPrimary = true,
                click = ClickKind.DESTINATION,
                jumpBar = true,
                collectionType = collectionType,
            )
        }

        else -> {
            null
        }
    }

/** A library without tabs, as `DestinationContent.CollectionFolder` opens it for a COLLECTION_FOLDER. */
private fun singleSpec(
    itemId: UUID,
    collectionType: CollectionType?,
): FolderSpec =
    when (collectionType) {
        // CollectionFolderGeneric(usePosters = true, recursive = false, playEnabled = false, MovieSortOptions)
        CollectionType.BOXSETS -> {
            FolderSpec(
                itemId = itemId.toServerString(),
                viewModelKey = itemId.toServerString(),
                initialFilter = CollectionFolderFilter(),
                recursive = false,
                sortOptions = MovieSortOptions,
                filterOptions = DefaultFilterOptions,
                playEnabled = false,
                defaultViewOptions = ViewOptionsPoster,
                useSeriesForPrimary = true,
                click = ClickKind.INDEXED,
                jumpBar = true,
                collectionType = collectionType,
            )
        }

        // CollectionFolderPhotoAlbum(recursive = false)
        CollectionType.HOMEVIDEOS -> {
            FolderSpec(
                itemId = itemId.toServerString(),
                viewModelKey = itemId.toServerString(),
                initialFilter = CollectionFolderFilter(),
                recursive = false,
                sortOptions = VideoSortOptions,
                filterOptions = DefaultFilterOptions,
                playEnabled = true,
                defaultViewOptions = ViewOptionsWide,
                useSeriesForPrimary = false,
                click = ClickKind.PHOTOS,
                jumpBar = true,
                collectionType = collectionType,
            )
        }

        // CollectionFolderGeneric(usePosters = false, recursive = false, playEnabled = false)
        else -> {
            FolderSpec(
                itemId = itemId.toServerString(),
                viewModelKey = itemId.toServerString(),
                initialFilter = CollectionFolderFilter(),
                recursive = false,
                sortOptions = VideoSortOptions,
                filterOptions = DefaultFilterOptions,
                playEnabled = false,
                defaultViewOptions = ViewOptionsWide,
                useSeriesForPrimary = true,
                click = ClickKind.INDEXED,
                jumpBar = true,
                collectionType = collectionType,
            )
        }
    }

/** Upstream's `CollectionFolderViewModel` for [spec], created exactly as `CollectionFolderView` creates it. */
@Composable
private fun folderViewModel(spec: FolderSpec): CollectionFolderViewModel =
    hiltViewModel<CollectionFolderViewModel, CollectionFolderViewModel.Factory>(key = spec.viewModelKey) {
        it.create(
            itemId = spec.itemId,
            initialSortAndDirection = null,
            recursive = spec.recursive,
            collectionFilter = spec.initialFilter,
            useSeriesForPrimary = spec.useSeriesForPrimary,
            defaultViewOptions = spec.defaultViewOptions,
        )
    }

/**
 * What the header controls and the grid of one folder share: which dialog is open, where focus goes back to
 * (upstream returns focus to the filter button while its menu is open and to the random button after a random
 * pick), and the item dialogs.
 */
@Stable
internal class FolderUi(
    clickedRandom: MutableState<Boolean>,
) {
    var clickedRandom by clickedRandom
    var sortOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)

    /** A filter was changed from the dialog: once the list reloads, focus goes back to the filter button. */
    var filterChanged by mutableStateOf(false)
    var viewOpen by mutableStateOf(false)
    val firstControl = FocusRequester()
    val filterButton = FocusRequester()
    val randomButton = FocusRequester()
    val listRequester = FocusRequester()
    val dialogs = ItemDialogsState()
}

@Composable
private fun rememberFolderUi(key: String): FolderUi {
    val clickedRandom = rememberSaveable(key) { mutableStateOf(false) }
    return remember(key) { FolderUi(clickedRandom) }
}

@Composable
private fun TabbedLibrary(
    itemId: UUID,
    preferences: UserPreferences,
    drawerName: String?,
    tabs: List<LibraryTab>,
    collectionType: CollectionType,
    nav: NavigationManager,
) {
    val tabViewModel =
        hiltViewModel<TabViewModel, TabViewModel.Factory>(
            key = "$itemId-${tabs.size}",
            creationCallback = { it.create(itemId.toString(), tabs.size) },
        )
    val selected by tabViewModel.state.collectAsState()
    val tab = tabs.getOrNull(selected)
    val tabRequesters = remember(tabs) { List(tabs.size) { FocusRequester() } }
    val spec = tab?.let { tabSpec(itemId, it, collectionType) }
    val ui = rememberFolderUi(spec?.viewModelKey ?: "tab-$selected")
    val recommendedDialogs = remember { ItemDialogsState() }
    val includeTypes =
        if (collectionType == CollectionType.MOVIES) listOf(BaseItemKind.MOVIE) else listOf(BaseItemKind.SERIES)
    val folderName =
        spec?.let {
            folderViewModel(it)
                .state
                .collectAsState()
                .value.item.successValue
                ?.name
        }

    LibraryScaffold(
        kicker = drawerName ?: folderName ?: collectionType.name,
        tabs = tabs.map { stringResource(it.title) },
        selectedTab = selected,
        onSelectTab = tabViewModel::updateSelectedTabIndex,
        tabRequesters = tabRequesters,
        count = {
            when {
                spec != null -> {
                    FolderCount(spec = spec, viewModel = folderViewModel(spec))
                }

                tab == LibraryTab.GENRES -> {
                    GenreCount(genreViewModel(itemId, includeTypes))
                }

                tab == LibraryTab.STUDIOS -> {
                    StudioCount(studioViewModel(itemId, includeTypes))
                }
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
                        nav = nav,
                        focusOnEmpty = tabRequesters.getOrNull(selected),
                    )
                }

                tab == LibraryTab.RECOMMENDED -> {
                    LibraryRecommended(
                        preferences = preferences,
                        viewModel =
                            if (collectionType == CollectionType.MOVIES) {
                                movieRecommendedViewModel(itemId)
                            } else {
                                tvRecommendedViewModel(itemId, preferences)
                            },
                        watchingRows = if (collectionType == CollectionType.MOVIES) setOf(0) else setOf(0, 1),
                        dialogs = recommendedDialogs,
                    )
                }

                tab == LibraryTab.GENRES -> {
                    GenreGrid(
                        itemId = itemId,
                        includeItemTypes = includeTypes,
                        collectionType = collectionType,
                    )
                }

                tab == LibraryTab.STUDIOS -> {
                    StudioGrid(itemId = itemId, includeItemTypes = includeTypes)
                }
            }
        },
    )
    if (spec != null) {
        FolderDialogs(spec = spec, preferences = preferences, viewModel = folderViewModel(spec), ui = ui)
    } else if (tab == LibraryTab.RECOMMENDED) {
        ItemDialogsHost(
            state = recommendedDialogs,
            getMediaSource = { _, _ -> null },
            preferredSubtitleLanguage = null,
            showFilePath = false,
            onConfirmDelete = {},
        )
    }
}

@Composable
private fun SingleLibrary(
    spec: FolderSpec,
    preferences: UserPreferences,
    drawerName: String?,
    nav: NavigationManager,
) {
    val viewModel = folderViewModel(spec)
    val ui = rememberFolderUi(spec.viewModelKey)
    val state by viewModel.state.collectAsState()
    val itemName = state.item.successValue?.name
    val kicker =
        spec.initialFilter.nameOverride
            ?: drawerName
            ?: itemName
            ?: state.item.successValue
                ?.data
                ?.collectionType
                ?.name
            ?: stringResource(R.string.collection)
    LibraryScaffold(
        kicker = kicker,
        tabs = emptyList(),
        selectedTab = -1,
        onSelectTab = {},
        tabRequesters = emptyList(),
        count = { FolderCount(spec = spec, viewModel = viewModel) },
        controls = { FolderControls(spec = spec, viewModel = viewModel, ui = ui) },
        body = {
            FolderBody(
                spec = spec,
                preferences = preferences,
                viewModel = viewModel,
                ui = ui,
                kicker = kicker,
                nav = nav,
                focusOnEmpty = null,
            )
        },
    )
    FolderDialogs(spec = spec, preferences = preferences, viewModel = viewModel, ui = ui)
}

/** Room between the header strip and the first row of cards; a focused icon control's caption hangs in it. */
internal val BodyTopGap = 24.dp

/** The body's right edge: the jump bar sits here, at the right edge of the page. */
private val BodyEndPadding = 12.dp

/** A grid without the jump bar ends where a grid with it does. */
internal val GridEndPadding = BodyEndPadding + JumpBarWidth + 16.dp - FocusEdge

/**
 * The fixed header over the tab content: the kicker, then the strip: [tabs] (if any) on the left, [count] and
 * [controls] at the right end, a hairline under it all. [body] fills the rest.
 */
@Composable
private fun LibraryScaffold(
    kicker: String,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    tabRequesters: List<FocusRequester>,
    count: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .padding(start = TallyDimens.marginHorizontal, end = BodyEndPadding + FocusEdge),
        ) {
            Text(
                text = kicker.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HeaderStrip(
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(ControlHeight + TallyDimens.hairline)
                        .drawBehind {
                            val stroke = TallyDimens.hairline.toPx()
                            drawLine(
                                color = TallyColors.rule,
                                start = Offset(0f, size.height - stroke / 2f),
                                end = Offset(size.width, size.height - stroke / 2f),
                                strokeWidth = stroke,
                            )
                        },
                tabs = {
                    if (tabs.isNotEmpty()) {
                        Row(
                            modifier =
                                Modifier
                                    .focusRestorer(tabRequesters.getOrNull(selectedTab) ?: FocusRequester.Default)
                                    .focusGroup(),
                        ) {
                            tabs.forEachIndexed { index, label ->
                                LibraryTabView(
                                    label = label,
                                    current = index == selectedTab,
                                    onClick = { if (index != selectedTab) onSelectTab(index) },
                                    modifier = Modifier.focusRequester(tabRequesters[index]),
                                )
                            }
                        }
                    }
                },
                count = count,
                controls = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ControlGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        controls()
                    }
                },
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            body()
        }
    }
}

/**
 * A tab, as the season rundown's tabs: mono label; the current tab in `text` over a 3dp accent bar; focus is the
 * `groundRaised` fill and a square 3dp accent border. OK opens the tab (upstream's tabs open on click).
 */
@Composable
private fun LibraryTabView(
    label: String,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val color = if (current || focused) TallyColors.text else TallyColors.muted
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = color,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = color,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = color,
            ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .height(ControlHeight)
                .drawWithContent {
                    drawContent()
                    if (current) {
                        val bar = wholePx(TallyDimens.focusBorder.toPx())
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(0f, size.height - bar),
                            size = Size(size.width, bar),
                        )
                    }
                    if (focused) {
                        val stroke = wholePx(TallyDimens.focusBorder.toPx())
                        val inset = stroke / 2f
                        drawRect(
                            color = TallyColors.accent,
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
                    .padding(horizontal = TabPadding),
        ) {
            Text(
                text = label.tallyUppercase(),
                style = TallyType.label,
                color = color,
                maxLines = 1,
                modifier = Modifier.offset(y = CapsLift),
            )
        }
    }
}

/** The mono `muted` count at the right end of the strip (`22 FILMS`). */
@Composable
internal fun HeaderCount(text: String) {
    Text(
        text = text.tallyUppercase(),
        style = TallyType.label,
        color = TallyColors.muted,
        maxLines = 1,
        modifier = Modifier.offset(y = CapsLift),
    )
}

/** Space between the controls, and the least space kept around the count. */
private val ControlGap = 6.dp
private val CountGap = 16.dp

/** Widest the sort button's label runs (`SORT · DATE RELEASED` fits). */
private val SortLabelMaxWidth = 200.dp

/** A little tighter than the rundown's 16dp: this strip also holds the count and the controls. */
private val TabPadding = 12.dp

/**
 * The header strip: [tabs] at the left, [controls] at the right end, [count] just left of the controls. The tabs
 * and the controls always get their room; the count is left out when it would not fit between them with
 * [CountGap] on both sides (a long count in a narrow strip), rather than squeezing anything.
 */
@Composable
private fun HeaderStrip(
    tabs: @Composable () -> Unit,
    count: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(tabs, count, controls), modifier = modifier) { (tabsM, countM, controlsM), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val controlsP = controlsM.map { it.measure(loose) }
        val controlsW = controlsP.maxOfOrNull { it.width } ?: 0
        val tabsP = tabsM.map { it.measure(loose.copy(maxWidth = (width - controlsW).coerceAtLeast(0))) }
        val tabsW = tabsP.maxOfOrNull { it.width } ?: 0
        val countP = countM.map { it.measure(loose) }
        val countW = countP.maxOfOrNull { it.width } ?: 0
        val gap = CountGap.roundToPx()
        val controlsGap = if (controlsW > 0) ControlGap.roundToPx() * 2 else 0
        val showCount = countW > 0 && tabsW + gap + countW + controlsGap + controlsW <= width
        val rowHeight = ControlHeight.roundToPx()
        layout(width, constraints.maxHeight) {
            tabsP.forEach { it.place(0, 0) }
            controlsP.forEach { it.place(width - it.width, (rowHeight - it.height) / 2) }
            if (showCount) {
                countP.forEach {
                    it.place(width - controlsW - controlsGap - it.width, (rowHeight - it.height) / 2)
                }
            }
        }
    }
}

@Composable
private fun countText(
    spec: FolderSpec,
    filter: GetItemsFilter,
    count: Int,
): String {
    val res =
        when (libraryNoun(filter.includeItemTypes ?: spec.initialFilter.filter.includeItemTypes, spec.collectionType)) {
            LibraryNoun.FILMS -> R.plurals.tally_library_films
            LibraryNoun.SHOWS -> R.plurals.tally_library_shows
            LibraryNoun.COLLECTIONS -> R.plurals.tally_library_collections
            LibraryNoun.EPISODES -> R.plurals.tally_library_episodes
            LibraryNoun.VIDEOS -> R.plurals.tally_library_videos
            LibraryNoun.ITEMS -> R.plurals.tally_library_items
        }
    return pluralStringResource(res, count, count)
}

/** A folder's count (`22 FILMS`), once its items are in. */
@Composable
private fun FolderCount(
    spec: FolderSpec,
    viewModel: CollectionFolderViewModel,
) {
    val state by viewModel.state.collectAsState()
    val pager = state.items.successValue ?: return
    HeaderCount(countText(spec, state.filter, pager.size))
}

/**
 * The controls of a folder: SORT (long press reverses the order, as upstream), FILTER (with the number of filters
 * on), VIEW, then random, and play / shuffle where upstream has them.
 */
@Composable
private fun FolderControls(
    spec: FolderSpec,
    viewModel: CollectionFolderViewModel,
    ui: FolderUi,
) {
    val state by viewModel.state.collectAsState()
    val pager = state.items.successValue
    val notEmpty = pager?.isNotEmpty() == true
    if (spec.sortOptions.isNotEmpty()) {
        LibraryControlButton(
            label = sortLabel(state.sortAndDirection),
            suffix = directionArrow(state.sortAndDirection.direction),
            // Long sort names (Community Rating) are cut so the strip keeps room for the tabs.
            maxLabelWidth = SortLabelMaxWidth,
            onClick = { ui.sortOpen = true },
            onLongClick = { viewModel.onSortChange(state.sortAndDirection.flip(), spec.recursive, state.filter) },
            modifier = Modifier.focusRequester(ui.firstControl),
        )
    }
    if (spec.filterOptions.isNotEmpty()) {
        val count = state.filter.countFilters(spec.filterOptions)
        LibraryControlButton(
            label =
                if (count > 0) {
                    stringResource(R.string.tally_library_filter_count, count)
                } else {
                    stringResource(R.string.tally_library_filter)
                },
            onClick = { ui.filterOpen = true },
            modifier = Modifier.focusRequester(ui.filterButton),
        )
    }
    LibraryControlButton(
        label = stringResource(R.string.tally_library_view),
        onClick = { ui.viewOpen = true },
    )
    LibraryIconButton(
        glyph = stringResource(R.string.fa_dice),
        label = stringResource(R.string.tally_library_random),
        onClick = {
            ui.clickedRandom = true
            viewModel.onClickRandom()
        },
        enabled = notEmpty,
        captionAtEnd = !spec.playEnabled,
        modifier = Modifier.focusRequester(ui.randomButton),
    )
    if (spec.playEnabled) {
        LibraryIconButton(
            glyph = stringResource(R.string.fa_play),
            label = stringResource(R.string.tally_library_play),
            onClick = { playAll(spec, state, viewModel, shuffle = false) },
            enabled = notEmpty,
        )
        LibraryIconButton(
            glyph = stringResource(R.string.fa_shuffle),
            label = stringResource(R.string.tally_library_shuffle),
            onClick = { playAll(spec, state, viewModel, shuffle = true) },
            enabled = notEmpty,
            captionAtEnd = true,
        )
    }
}

/** Upstream's play-all: a photo album starts its slideshow, anything else plays the list as sorted and filtered. */
private fun playAll(
    spec: FolderSpec,
    state: com.github.damontecres.wholphin.ui.components.CollectionFolderState,
    viewModel: CollectionFolderViewModel,
    shuffle: Boolean,
) {
    val id = spec.itemId.toUUIDOrNull() ?: return
    val destination =
        if (state.item.successValue?.type == BaseItemKind.PHOTO_ALBUM) {
            Destination.Slideshow(
                parentId = id,
                index = 0,
                filter = CollectionFolderFilter(filter = state.filter),
                sortAndDirection = state.sortAndDirection,
                recursive = true,
                startSlideshow = true,
            )
        } else {
            Destination.PlaybackList(
                itemId = id,
                startIndex = 0,
                shuffle = shuffle,
                recursive = spec.recursive,
                sortAndDirection = state.sortAndDirection,
                filter = state.filter,
            )
        }
    viewModel.navigateTo(destination)
}

/** The sort, filter and view dialogs, and the item dialogs (context menu and what it opens). */
@Composable
private fun FolderDialogs(
    spec: FolderSpec,
    preferences: UserPreferences,
    viewModel: CollectionFolderViewModel,
    ui: FolderUi,
) {
    val state by viewModel.state.collectAsState()
    if (ui.sortOpen) {
        SortDialog(
            sortOptions = spec.sortOptions,
            current = state.sortAndDirection,
            onSortChange = { viewModel.onSortChange(it, spec.recursive, state.filter) },
            onDismiss = { ui.sortOpen = false },
        )
    }
    if (ui.filterOpen) {
        FilterDialog(
            filterOptions = spec.filterOptions,
            current = state.filter,
            onFilterChange = {
                ui.filterChanged = true
                viewModel.onFilterChange(it, spec.recursive)
            },
            getPossibleValues = { viewModel.getFilterOptionValues(it) },
            onDismiss = {
                ui.filterOpen = false
                ui.filterButton.tryRequestFocus("tally-library-filter")
            },
        )
    }
    if (ui.viewOpen) {
        ViewDialog(
            viewOptions = state.viewOptions,
            defaultViewOptions = spec.defaultViewOptions,
            onViewOptionsChange = viewModel::saveViewOptions,
            onDismiss = {
                ui.viewOpen = false
                viewModel.saveViewOptions(viewModel.state.value.viewOptions)
            },
        )
    }
    ItemDialogsHost(
        state = ui.dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = viewModel.isAdministrator(),
        onConfirmDelete = {},
    )
}

/** A folder's content: loading, error, empty, or the grid (or upstream's list for the list layouts). */
@Composable
private fun FolderBody(
    spec: FolderSpec,
    preferences: UserPreferences,
    viewModel: CollectionFolderViewModel,
    ui: FolderUi,
    kicker: String,
    nav: NavigationManager,
    focusOnEmpty: FocusRequester?,
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(spec.itemId) {
        viewModel.onResumePage()
        onPauseOrDispose { viewModel.release() }
    }
    when (val st = state.item) {
        DataLoadingState.Loading, DataLoadingState.Pending -> {
            LoadingMark(Modifier.fillMaxSize())
        }

        is DataLoadingState.Error -> {
            LibraryError(st.localizedMessage)
        }

        is DataLoadingState.Success -> {
            when (val items = state.items) {
                is DataLoadingState.Error -> {
                    LaunchedEffect(Unit) { (focusOnEmpty ?: ui.firstControl).tryRequestFocus("tally-library-error") }
                    LibraryError(items.localizedMessage, takeFocus = false)
                }

                DataLoadingState.Loading, DataLoadingState.Pending -> {
                    LoadingMark(Modifier.fillMaxSize())
                }

                is DataLoadingState.Success -> {
                    FolderItems(
                        spec = spec,
                        preferences = preferences,
                        viewModel = viewModel,
                        ui = ui,
                        kicker = kicker,
                        nav = nav,
                        items = items.data,
                        collectionType = st.data?.data?.collectionType,
                        focusOnEmpty = focusOnEmpty,
                        playlistViewModel = playlistViewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderItems(
    spec: FolderSpec,
    preferences: UserPreferences,
    viewModel: CollectionFolderViewModel,
    ui: FolderUi,
    kicker: String,
    nav: NavigationManager,
    items: List<BaseItem?>,
    collectionType: CollectionType?,
    focusOnEmpty: FocusRequester?,
    playlistViewModel: AddPlaylistViewModel,
) {
    val state by viewModel.state.collectAsState()
    val currentState by rememberUpdatedState(state)
    val savedPosition = remember { viewModel.position }
    val gridState = rememberMediaGridState(savedPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val viewOptions = state.viewOptions
    val isGrid = viewOptions.type == ViewOptionsType.GRID
    var listPosition by remember { mutableStateOf(savedPosition) }
    val position = if (isGrid) gridState.focusedIndex else listPosition

    // Where focus goes once the items are in: upstream's order.
    LaunchedEffect(Unit) {
        val target =
            when {
                ui.dialogs.contextMenu != null -> null
                ui.filterOpen || ui.filterChanged -> ui.filterButton
                ui.clickedRandom -> ui.randomButton
                items.isNotEmpty() -> if (isGrid) gridState.cardRequester else ui.listRequester
                else -> focusOnEmpty ?: ui.firstControl
            }
        if (target != null) {
            repeat(FOCUS_ATTEMPTS) {
                if (target.tryRequestFocus("tally-library-items")) return@repeat
                withFrameNanos { }
            }
        }
        ui.clickedRandom = false
        ui.filterChanged = false
    }

    val focusedItem = items.getOrNull(position)
    if (viewOptions.showBackdrop) {
        LaunchedEffect(focusedItem) { focusedItem?.let(viewModel::updateBackdrop) }
    }

    val onClickItem: (Int, BaseItem) -> Unit =
        remember(spec) {
            { index, item ->
                viewModel.position = index
                when (spec.click) {
                    ClickKind.DESTINATION -> {
                        nav.navigateTo(item.destination())
                    }

                    ClickKind.INDEXED -> {
                        nav.navigateTo(item.destination(index))
                    }

                    ClickKind.PHOTOS -> {
                        val destination =
                            if (item.type == BaseItemKind.PHOTO) {
                                Destination.Slideshow(
                                    parentId = spec.itemId.toUUIDOrNull() ?: item.id,
                                    index = index,
                                    filter = CollectionFolderFilter(filter = currentState.filter),
                                    sortAndDirection = currentState.sortAndDirection,
                                    recursive = spec.recursive,
                                    startSlideshow = false,
                                )
                            } else {
                                item.destination(index)
                            }
                        viewModel.navigateTo(destination)
                    }
                }
            }
        }
    val onLongClickItem: (Int, BaseItem) -> Unit =
        remember(spec) {
            { index, item ->
                viewModel.position = index
                ui.dialogs.contextMenu =
                    ContextMenu.ForBaseItem(
                        fromLongClick = true,
                        item = item,
                        chosenStreams = null,
                        showGoTo = true,
                        showStreamChoices = false,
                        canDelete = viewModel.canDelete(item, preferences.appPreferences),
                        canRemoveContinueWatching = false,
                        canRemoveNextUp = false,
                        actions =
                            ContextMenuActions(
                                navigateTo = viewModel::navigateTo,
                                onClickWatch = { itemId, watched -> viewModel.setWatched(index, itemId, watched) },
                                onClickFavorite = { itemId, favorite -> viewModel.setFavorite(index, itemId, favorite) },
                                onClickAddPlaylist = { itemId -> ui.dialogs.playlistItemId = itemId },
                                onSendMediaInfo = viewModel::sendReportFor,
                                onDeleteItem = { viewModel.deleteItem(index, it) },
                                onShowOverview = { ui.dialogs.overview = ItemDetailsDialogInfo(it) },
                                onChooseVersion = { _, _ ->
                                    // Not supported on this page
                                },
                                onChooseTracks = {
                                    // Not supported on this page
                                },
                                onClearChosenStreams = {
                                    // Not supported on this page
                                },
                                onClickAddToQueue = playlistViewModel::addToQueue,
                            ),
                    )
            }
        }
    val onClickPlay: (Int, BaseItem) -> Unit =
        remember(spec) {
            { index, item ->
                val destination =
                    if (item.type == BaseItemKind.PHOTO_ALBUM) {
                        Destination.Slideshow(
                            parentId = item.id,
                            index = index,
                            filter = CollectionFolderFilter(filter = currentState.filter),
                            sortAndDirection = currentState.sortAndDirection,
                            recursive = true,
                            startSlideshow = true,
                        )
                    } else {
                        Destination.Playback(item)
                    }
                viewModel.navigateTo(destination)
            }
        }

    if (items.isEmpty()) {
        val filtered = state.filter.countFilters(spec.filterOptions) > 0
        EmptyState(
            title = stringResource(R.string.tally_library_empty_title),
            subtitle =
                stringResource(
                    if (filtered) R.string.tally_library_empty_filtered else R.string.tally_library_empty_body,
                ),
            takeFocus = false,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = TallyDimens.marginHorizontal, end = GridEndPadding)
                    .padding(top = BodyTopGap, bottom = TallyDimens.marginVertical),
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (viewOptions.showDetails) {
            HomeHeader(
                item = focusedItem,
                rowTitle = kicker,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
            )
        }
        if (isGrid) {
            LibraryGrid(
                items = items,
                viewOptions = viewOptions,
                sortAndDirection = state.sortAndDirection,
                jumpBarAllowed = spec.jumpBar,
                gridState = gridState,
                onClickItem = onClickItem,
                onLongClickItem = onLongClickItem,
                onClickPlay = onClickPlay,
                letterPosition = { viewModel.positionOfLetter(it) ?: -1 },
                onFocusIndex = { viewModel.position = it },
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = TallyDimens.marginHorizontal, end = BodyEndPadding)
                        .padding(top = BodyTopGap),
            )
        } else {
            // The list layouts are upstream's list, as they are, until they get a Tally look of their own.
            CollectionFolderList(
                preferences = preferences,
                collectionType = collectionType,
                initialPosition = savedPosition,
                items = items,
                sortAndDirection = state.sortAndDirection,
                gridFocusRequester = ui.listRequester,
                onClickItem = onClickItem,
                onLongClickItem = onLongClickItem,
                positionCallback = { _, index ->
                    listPosition = index
                    viewModel.position = index
                },
                letterPosition = { viewModel.positionOfLetter(it) ?: -1 },
                viewOptions = viewOptions,
                onClickPlay = onClickPlay,
                focusedItem = focusedItem,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = TallyDimens.marginHorizontal, end = BodyEndPadding)
                        .padding(top = BodyTopGap),
            )
        }
    }
}

private const val FOCUS_ATTEMPTS = 8

/** Mono `LOADING…` in `muted`, centered. */
@Composable
internal fun LoadingMark(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.tally_library_loading).tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
        )
    }
}

/** An error in the content area: [EmptyState] with the message. */
@Composable
internal fun LibraryError(
    message: String,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = true,
) {
    EmptyState(
        title = stringResource(R.string.tally_media_error_title),
        subtitle = message.ifBlank { stringResource(R.string.tally_media_error_body) },
        takeFocus = takeFocus,
        modifier =
            modifier
                .fillMaxSize()
                .padding(start = TallyDimens.marginHorizontal, end = GridEndPadding)
                .padding(top = BodyTopGap, bottom = TallyDimens.marginVertical),
    )
}
