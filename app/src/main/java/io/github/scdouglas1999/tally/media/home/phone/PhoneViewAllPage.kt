package io.github.scdouglas1999.tally.media.home.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ItemGridViewModel
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.components.rememberContextMenu
import com.github.damontecres.wholphin.ui.detail.HomeRowGridViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import io.github.scdouglas1999.tally.media.home.homeCardKicker
import io.github.scdouglas1999.tally.media.kit.CardDetailStyle
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleStyle
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.media.kit.phone.PhoneMediaGrid
import io.github.scdouglas1999.tally.media.kit.phone.phoneGridColumns
import io.github.scdouglas1999.tally.media.kit.posterDetail
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.library.LibraryPageViewModel
import io.github.scdouglas1999.tally.media.library.phone.PhoneGenreGrid
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.CollectionType

/**
 * "View all" of a home row on a phone (`Destination.MoreHomeRow`, upstream's `HomeRowGrid` on the TV): the same
 * [HomeRowGridViewModel] (paged, loads as the grid scrolls) and item menu; a top bar with the row's title and a
 * back arrow, then a grid of the row's cards from the top: three posters across, two landscape cards. Genre and studio rows show
 * the library page's genre tiles.
 */
@Composable
fun PhoneViewAllPage(
    preferences: UserPreferences,
    destination: Destination.MoreHomeRow,
    modifier: Modifier = Modifier,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
    viewModel: HomeRowGridViewModel =
        hiltViewModel<HomeRowGridViewModel, HomeRowGridViewModel.Factory>(
            creationCallback = { it.create(destination.title, destination.config) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val viewOptions = destination.config.viewOptions
    // The TV opens the grid at the card that was focused in the row; a phone starts at the top (coming back to the
    // grid, the list keeps where it was: the state is saved).
    val gridState = rememberLazyGridState()
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = destination.title.getString(),
            onBack = { pageViewModel.navigationManager.goBack() },
            scrolled = gridState.phoneScrolled,
        )
        when (val st = state.loading) {
            is HomeRowLoadingState.Error -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_media_error_title),
                    subtitle = st.localizedMessage,
                )
            }

            is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                PhoneLoading(Modifier.fillMaxSize())
            }

            is HomeRowLoadingState.Success -> {
                when (val config = destination.config) {
                    is HomeRowConfig.Genres -> {
                        PhoneGenreGrid(
                            itemId = config.parentId,
                            includeItemTypes = null,
                            collectionType = CollectionType.UNKNOWN,
                            studios = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is HomeRowConfig.Studios -> {
                        PhoneGenreGrid(
                            itemId = config.parentId,
                            includeItemTypes = null,
                            collectionType = CollectionType.UNKNOWN,
                            studios = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> {
                        val items = st.items
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            PhoneMediaGrid(
                                items = items,
                                columns = phoneGridColumns(viewOptions.aspectRatio.ratio, maxWidth),
                                state = gridState,
                                topPadding = 8.dp,
                                bottomPadding = bottom + PhoneDimens.rowGap,
                                key = { index, item -> "$index-${item?.id}" },
                                modifier = Modifier.fillMaxSize(),
                            ) { item, index, width ->
                                ViewAllCard(
                                    item = item,
                                    imageType = viewOptions.imageType,
                                    aspectRatio = viewOptions.aspectRatio.ratio,
                                    width = width,
                                    onClick = { item?.let { viewModel.navigateTo(it.destination(index)) } },
                                    onLongClick = { item?.let { contextMenu.showContextMenu(index, it) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    contextMenu.Compose()
}

/**
 * A grid of arbitrary items on a phone (`Destination.ItemGrid`, upstream's `ItemGrid` on the TV: a library's
 * Recommended rows' ALL, a film's extras): the same [ItemGridViewModel] (paged, loads as the grid scrolls) and item
 * menu, laid out as [PhoneViewAllPage]: a top bar with the title and a back arrow over the grid of titled cards.
 */
@Composable
fun PhoneItemGridPage(
    preferences: UserPreferences,
    destination: Destination.ItemGrid<*>,
    modifier: Modifier = Modifier,
    pageViewModel: LibraryPageViewModel = hiltViewModel(),
    viewModel: ItemGridViewModel =
        hiltViewModel<ItemGridViewModel, ItemGridViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val viewOptions = destination.viewOptions
    // The TV opens the grid at the card that was focused in the row; a phone starts at the top (coming back to the
    // grid, the list keeps where it was: the state is saved).
    val gridState = rememberLazyGridState()
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = destination.title.getString(),
            onBack = { pageViewModel.navigationManager.goBack() },
            scrolled = gridState.phoneScrolled,
        )
        when (val st = state.items) {
            is DataLoadingState.Error -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_media_error_title),
                    subtitle = st.localizedMessage,
                )
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                PhoneLoading(Modifier.fillMaxSize())
            }

            is DataLoadingState.Success -> {
                val items = st.data
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    PhoneMediaGrid(
                        items = items,
                        columns = phoneGridColumns(viewOptions.aspectRatio.ratio, maxWidth),
                        state = gridState,
                        topPadding = 8.dp,
                        bottomPadding = bottom + PhoneDimens.rowGap,
                        key = { index, item -> "$index-${item?.id}" },
                        modifier = Modifier.fillMaxSize(),
                    ) { item, index, width ->
                        ViewAllCard(
                            item = item,
                            imageType = viewOptions.imageType,
                            aspectRatio = viewOptions.aspectRatio.ratio,
                            width = width,
                            onClick = { item?.let { viewModel.navigateTo(it.destination(index)) } },
                            onLongClick = { item?.let { contextMenu.showContextMenu(index, it) } },
                        )
                    }
                }
            }
        }
    }
    contextMenu.Compose()
}

/** A card of the grid: the row's picture type and ratio, with the title (and the year or seasons) under it, as upstream's grid always titles its cards. */
@Composable
private fun ViewAllCard(
    item: BaseItem?,
    imageType: ViewOptionImageType,
    aspectRatio: Float,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val imageService = LocalImageUrlService.current
    val fillWidth = with(LocalDensity.current) { width.roundToPx() }
    val url =
        remember(item, imageType, fillWidth) {
            imageService.getItemImageUrl(item, imageType.imageType, fillWidth = fillWidth)
        }
    val percent = resumePercent(item?.data?.userData?.playbackPositionTicks ?: 0L, item?.data?.runTimeTicks ?: 0L)
    val title = (item?.title ?: item?.name).orEmpty()
    // An episode shows its code (S2 E3) under the show's name, as the home cards do; the rest the year or seasons.
    val detail = item?.let { homeCardKicker(it, watchingRow = false) ?: posterDetail(it) }
    CardFrame(
        imageUrl = url,
        width = width,
        height = width / aspectRatio,
        contentDescription = title,
        onClick = onClick,
        onLongClick = onLongClick,
        progress = if (item?.played != true && percent in 1..99) percent / 100f else null,
        tag = if (item?.played == true) stringResource(R.string.tally_media_seen) else null,
        favorite = item?.favorite == true,
        label = {
            Text(
                text = title,
                style = CardTitleStyle,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (detail != null) {
                Text(
                    text = detail.tallyUppercase(),
                    style = CardDetailStyle,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = Modifier.padding(0.dp),
    )
}
