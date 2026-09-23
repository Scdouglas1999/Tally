package io.github.scdouglas1999.tally.media.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.detail.FavoritesLoadingState
import com.github.damontecres.wholphin.ui.detail.FavoritesViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.search.PageScrollSpec
import io.github.scdouglas1999.tally.media.search.PagesItemRow
import io.github.scdouglas1999.tally.media.search.PagesLoading
import io.github.scdouglas1999.tally.media.search.providerContextMenu
import io.github.scdouglas1999.tally.media.search.typeTitle
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * The Tally favorites page: upstream's [FavoritesViewModel] (one query per type, in upstream's type
 * order, with its per-type context actions), drawn as one [io.github.scdouglas1999.tally.media.kit.MediaRow]
 * per type instead of tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TallyFavoritesPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    OneTimeLaunchedEffect { viewModel.init() }
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    // The row and card the last menu was opened on: the delete confirmation acts there.
    var menuAt by remember { mutableStateOf<Pair<BaseItemKind, Int>?>(null) }
    val context = LocalContext.current
    val favorites =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, FavoritesPageEntryPoint::class.java)
                .favoriteWatchManager()
        }
    val scope = rememberCoroutineScope()
    // After "Unfavorite" the row reloads (and its cards leave composition): focus goes back to that row once the
    // new list is in, or to the first row when the removed item was the row's last. Holds the type and the list
    // the menu was opened on.
    var refocus by remember { mutableStateOf<Pair<BaseItemKind, Any?>?>(null) }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
                    Text(
                        text = stringResource(R.string.tally_pages_favorites).tallyUppercase(),
                        style = TallyType.label,
                        color = TallyColors.accent,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .padding(horizontal = TallyDimens.marginHorizontal)
                                .padding(top = TallyDimens.marginVertical, bottom = 16.dp),
                    )
                    when (val loading = state.loadingState) {
                        is FavoritesLoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.tally_media_error_title),
                                subtitle = loading.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                                modifier = Modifier.fillMaxSize().padding(TallyDimens.marginHorizontal),
                            )
                        }

                        FavoritesLoadingState.Loading,
                        FavoritesLoadingState.Pending,
                        -> {
                            PagesLoading("tally-favorites-loading", Modifier.fillMaxSize())
                        }

                        FavoritesLoadingState.NoFavorites -> {
                            EmptyState(
                                title = stringResource(R.string.tally_pages_no_favorites),
                                subtitle = stringResource(R.string.tally_pages_no_favorites_body),
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = TallyDimens.marginHorizontal)
                                        .padding(bottom = TallyDimens.marginVertical),
                            )
                        }

                        FavoritesLoadingState.Success -> {
                            val types = state.tabs.keys.toList()
                            val firstFocus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { firstFocus.tryRequestFocus("tally-favorites") }
                            val gone = refocus?.takeIf { it.first !in types }
                            if (gone != null) {
                                LaunchedEffect(gone) {
                                    retryFocus(firstFocus)
                                    refocus = null
                                }
                            }
                            CompositionLocalProvider(LocalBringIntoViewSpec provides PageScrollSpec) {
                                LazyColumn(
                                    contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
                                    verticalArrangement = Arrangement.spacedBy(24.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(types, key = { it.serialName }) { type ->
                                        val folder = state.favorites[type]
                                        val provider = remember(type) { viewModel.createTypedProvider(type) }
                                        val menuProvider =
                                            remember(provider, favorites) {
                                                FavoriteFixProvider(provider, favorites, scope) {
                                                    // As upstream's provider does after its (mistaken) call: reload this row.
                                                    viewModel.state.value.favorites[type]?.let { current ->
                                                        refocus = type to (current.items as? DataLoadingState.Success)?.data
                                                        provider.onSortChange(current.sortAndDirection, true, current.filter)
                                                    }
                                                }
                                            }
                                        val margin = Modifier.padding(horizontal = TallyDimens.marginHorizontal)
                                        val title = stringResource(typeTitle(type))
                                        when (val items = folder?.items) {
                                            is DataLoadingState.Success -> {
                                                val rowFocus = remember(type) { FocusRequester() }
                                                val pending = refocus
                                                if (pending != null && pending.first == type && pending.second !== items.data) {
                                                    LaunchedEffect(items.data) {
                                                        retryFocus(rowFocus)
                                                        refocus = null
                                                    }
                                                }
                                                PagesItemRow(
                                                    title = title,
                                                    items = items.data,
                                                    fallbackType = type,
                                                    modifier =
                                                        margin
                                                            .then(
                                                                if (type ==
                                                                    types.first()
                                                                ) {
                                                                    Modifier.focusRequester(firstFocus)
                                                                } else {
                                                                    Modifier
                                                                },
                                                            ).focusRequester(rowFocus),
                                                    onFocusItem = { _, item ->
                                                        if (item != null && folder.viewOptions.showBackdrop) {
                                                            provider.updateBackdrop(item)
                                                        }
                                                    },
                                                    onClick = { _, item -> provider.navigateTo(item.destination()) },
                                                    onLongClick = { index, item ->
                                                        menuAt = type to index
                                                        dialogs.contextMenu =
                                                            providerContextMenu(
                                                                provider = menuProvider,
                                                                position = index,
                                                                item = item,
                                                                preferences = preferences,
                                                                dialogs = dialogs,
                                                                onAddToQueue = playlistViewModel::addToQueue,
                                                            )
                                                    },
                                                    onPlay = { _, item: BaseItem -> provider.navigateTo(Destination.Playback(item)) },
                                                )
                                            }

                                            is DataLoadingState.Error -> {
                                                RowNote(title, items.localizedMessage, failure = true, modifier = margin)
                                            }

                                            else -> {
                                                RowNote(
                                                    title,
                                                    stringResource(R.string.tally_media_loading),
                                                    failure = false,
                                                    modifier = margin,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = viewModel.createTypedProvider(BaseItemKind.MOVIE).isAdministrator(),
        onConfirmDelete = { item ->
            menuAt?.let { (type, index) -> viewModel.createTypedProvider(type).deleteItem(index, item) }
        },
        playlistViewModel = playlistViewModel,
    )
}

/** A row that has just come back needs a frame or two before it can take focus. */
private suspend fun retryFocus(requester: FocusRequester) {
    repeat(FOCUS_ATTEMPTS) {
        if (requester.tryRequestFocus("tally-favorites-refocus")) return
        delay(FOCUS_RETRY_MS)
    }
}

private const val FOCUS_ATTEMPTS = 8
private const val FOCUS_RETRY_MS = 40L

@Composable
private fun RowNote(
    title: String,
    message: String,
    failure: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        RowHeader(title = title)
        Box {
            Text(
                text = message.tallyUppercase(),
                style = TallyType.label,
                color = if (failure) TallyColors.liveText else TallyColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Upstream's per-type provider, except "Unfavorite": upstream's `TypedProvider.setFavorite` calls `setWatched`, so
 * it marks the item unwatched (or watched) and leaves it a favorite. Here it calls the favorite API, as the film
 * page's FAVORITE button does, then [reload]s the row.
 */
private class FavoriteFixProvider(
    private val upstream: ContextMenuProvider,
    private val favorites: FavoriteWatchManager,
    private val scope: CoroutineScope,
    private val reload: () -> Unit,
) : ContextMenuProvider by upstream {
    override fun setFavorite(
        position: Int,
        itemId: UUID,
        favorite: Boolean,
    ) {
        scope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favorites.setFavorite(itemId, favorite)
            reload()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FavoritesPageEntryPoint {
    fun favoriteWatchManager(): FavoriteWatchManager
}
