package io.github.scdouglas1999.tally.media.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.data.model.createStudioDestination
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.components.GenreViewModel
import com.github.damontecres.wholphin.ui.components.StudioViewModel
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleStyle
import io.github.scdouglas1999.tally.media.kit.MediaGrid
import io.github.scdouglas1999.tally.media.kit.rememberMediaGridState
import io.github.scdouglas1999.tally.media.library.phone.PhoneNameGrid
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Upstream's genre and studio grids: four 16:9 cards across, 16dp apart. */
private const val NAME_GRID_COLUMNS = 4
private val NameGridGap = 16.dp

/** Upstream's [GenreViewModel], created as upstream's `GenreCardGrid` creates it. */
@Composable
internal fun genreViewModel(
    itemId: UUID,
    includeItemTypes: List<BaseItemKind>?,
): GenreViewModel =
    hiltViewModel<GenreViewModel, GenreViewModel.Factory>(
        creationCallback = { it.create(itemId, includeItemTypes) },
    )

/** Upstream's [StudioViewModel], created as upstream's `StudioCardGrid` creates it. */
@Composable
internal fun studioViewModel(
    itemId: UUID,
    includeItemTypes: List<BaseItemKind>?,
): StudioViewModel =
    hiltViewModel<StudioViewModel, StudioViewModel.Factory>(
        creationCallback = { it.create(itemId, includeItemTypes) },
    )

/**
 * The card width upstream asks the server's genre/studio images for: four across the screen less upstream's
 * padding. Kept as upstream computes it so the cached image URLs are the same.
 */
@Composable
private fun upstreamCardWidthPx(): Int {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(density) {
        with(density) {
            ((configuration.screenWidthDp.dp - (16.dp * 2 + NameGridGap * 3)) / NAME_GRID_COLUMNS).roundToPx()
        }
    }
}

/** `12 GENRES` in the header strip, once the genres are in. */
@Composable
internal fun GenreCount(viewModel: GenreViewModel) {
    val state by viewModel.state.collectAsState()
    if (state.item is DataLoadingState.Success) {
        HeaderCount(
            pluralStringResource(R.plurals.tally_library_genres, state.genres.size, state.genres.size),
        )
    }
}

/** `6 STUDIOS` in the header strip, once the studios are in. */
@Composable
internal fun StudioCount(viewModel: StudioViewModel) {
    val state by viewModel.state.collectAsState()
    if (state.item is DataLoadingState.Success) {
        HeaderCount(
            pluralStringResource(R.plurals.tally_library_studios, state.studios.size, state.studios.size),
        )
    }
}

/**
 * The Genres tab: a [MediaGrid] of 16:9 cards, each a picture from the genre (as upstream's grid loads it) with
 * the genre's name in the label bar. OK opens the genre's films or shows.
 */
@Composable
internal fun GenreGrid(
    itemId: UUID,
    includeItemTypes: List<BaseItemKind>?,
    collectionType: CollectionType,
    modifier: Modifier = Modifier,
    viewModel: GenreViewModel = genreViewModel(itemId, includeItemTypes),
) {
    val cardWidthPx = upstreamCardWidthPx()
    OneTimeLaunchedEffect { viewModel.init(cardWidthPx) }
    val state by viewModel.state.collectAsState()
    when (val st = state.item) {
        DataLoadingState.Pending, DataLoadingState.Loading -> {
            LoadingMark(modifier.fillMaxSize())
        }

        is DataLoadingState.Error -> {
            LibraryError(st.localizedMessage, modifier)
        }

        is DataLoadingState.Success -> {
            NameGrid(
                names = state.genres.map { NameCell(it.id, it.name, it.imageUrl) },
                onClick = { cell ->
                    viewModel.navigationManager.navigateTo(
                        createGenreDestination(
                            genreId = cell.id,
                            genreName = cell.name,
                            parentId = itemId,
                            parentName = st.data.title,
                            includeItemTypes = includeItemTypes,
                            collectionType = collectionType,
                        ),
                    )
                },
                modifier = modifier,
            )
        }
    }
}

/** The Studios tab (TV libraries): as [GenreGrid], with the studios' own pictures. */
@Composable
internal fun StudioGrid(
    itemId: UUID,
    includeItemTypes: List<BaseItemKind>?,
    modifier: Modifier = Modifier,
    viewModel: StudioViewModel = studioViewModel(itemId, includeItemTypes),
) {
    val cardWidthPx = upstreamCardWidthPx()
    OneTimeLaunchedEffect { viewModel.init(cardWidthPx) }
    val state by viewModel.state.collectAsState()
    when (val st = state.item) {
        DataLoadingState.Pending, DataLoadingState.Loading -> {
            LoadingMark(modifier.fillMaxSize())
        }

        is DataLoadingState.Error -> {
            LibraryError(st.localizedMessage, modifier)
        }

        is DataLoadingState.Success -> {
            NameGrid(
                names = state.studios.map { NameCell(it.id, it.name, it.imageUrl) },
                onClick = { cell ->
                    viewModel.navigationManager.navigateTo(
                        createStudioDestination(
                            studioId = cell.id,
                            name = cell.name,
                            parentId = itemId,
                            parentName = st.data.title,
                            includeItemTypes = includeItemTypes,
                        ),
                    )
                },
                modifier = modifier,
            )
        }
    }
}

internal data class NameCell(
    val id: UUID,
    val name: String,
    val imageUrl: String?,
)

@Composable
private fun NameGrid(
    names: List<NameCell>,
    onClick: (NameCell) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhoneNameGrid(names = names, onClick = onClick, modifier = modifier)
        return
    }
    if (names.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.tally_library_empty_title),
            subtitle = stringResource(R.string.tally_library_empty_body),
            // Where an empty library grid puts it: under the header strip, clear of the jump bar's column.
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(start = TallyDimens.marginHorizontal, end = GridEndPadding)
                    .padding(top = BodyTopGap, bottom = TallyDimens.marginVertical),
        )
        return
    }
    val gridState = rememberMediaGridState()
    LaunchedEffect(Unit) { gridState.cardRequester.tryRequestFocus("tally-name-grid") }
    MediaGrid(
        items = names,
        columns = NAME_GRID_COLUMNS,
        state = gridState,
        gap = NameGridGap,
        topPadding = BodyTopGap,
        bottomPadding = TallyDimens.marginVertical,
        key = { index, cell -> "$index-${cell.id}" },
        modifier =
            modifier
                .fillMaxSize()
                .padding(start = TallyDimens.marginHorizontal, end = GridEndPadding),
        card = { cell, _, cardModifier, width ->
            CardFrame(
                imageUrl = cell.imageUrl,
                width = width,
                height = width * 9 / 16,
                contentDescription = cell.name,
                onClick = { onClick(cell) },
                onLongClick = {},
                modifier = cardModifier,
                label = {
                    Text(
                        text = cell.name,
                        style = CardTitleStyle,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        },
    )
}
