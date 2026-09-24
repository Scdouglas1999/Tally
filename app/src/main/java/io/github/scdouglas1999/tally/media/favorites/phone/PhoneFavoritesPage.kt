package io.github.scdouglas1999.tally.media.favorites.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.detail.FavoritesLoadingState
import com.github.damontecres.wholphin.ui.detail.FavoritesViewModel
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.EntryPointAccessors
import io.github.scdouglas1999.tally.media.favorites.FavoriteFixProvider
import io.github.scdouglas1999.tally.media.favorites.FavoritesPageEntryPoint
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneItemCard
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowMessage
import io.github.scdouglas1999.tally.media.search.defaultKicker
import io.github.scdouglas1999.tally.media.search.providerContextMenu
import io.github.scdouglas1999.tally.media.search.typeTitle
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.phone.phoneSystemBack
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Favorites on a phone: the same [FavoritesViewModel] (one query per type in upstream's order, the per-type item menus
 * with the page's "Unfavorite" fix) as the TV page; a top bar, then one phone row per type (films, shows, episodes,
 * people…).
 */
@Composable
fun PhoneFavoritesPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: FavoritesViewModel,
    playlistViewModel: AddPlaylistViewModel,
) {
    OneTimeLaunchedEffect { viewModel.init() }
    val state by viewModel.state.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    var menuAt by remember { mutableStateOf<Pair<BaseItemKind, Int>?>(null) }
    val context = LocalContext.current
    val favorites =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, FavoritesPageEntryPoint::class.java)
                .favoriteWatchManager()
        }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()

    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = stringResource(R.string.tally_pages_favorites),
            onBack = phoneSystemBack(),
            scrolled = listState.phoneScrolled,
        )
        when (val loading = state.loadingState) {
            is FavoritesLoadingState.Error -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_media_error_title),
                    subtitle = loading.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                )
            }

            FavoritesLoadingState.Loading, FavoritesLoadingState.Pending -> {
                PhoneLoading(Modifier.fillMaxSize())
            }

            FavoritesLoadingState.NoFavorites -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_pages_no_favorites),
                    subtitle = stringResource(R.string.tally_pages_no_favorites_body),
                )
            }

            FavoritesLoadingState.Success -> {
                val types = state.tabs.keys.toList()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottom + PhoneDimens.rowGap),
                    verticalArrangement = Arrangement.spacedBy(PhoneDimens.rowGap),
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
                                        provider.onSortChange(current.sortAndDirection, true, current.filter)
                                    }
                                }
                            }
                        val title = stringResource(typeTitle(type))
                        when (val items = folder?.items) {
                            is DataLoadingState.Success -> {
                                PhoneCardRow(
                                    title = title,
                                    items = items.data,
                                    key = { index, item -> "$index-${item?.id}" },
                                ) { item, index ->
                                    PhoneItemCard(
                                        item = item,
                                        fallbackType = type,
                                        kicker = item?.let { defaultKicker(it) },
                                        onClick = { item?.let { provider.navigateTo(it.destination()) } },
                                        onLongClick = {
                                            item?.let { it: BaseItem ->
                                                menuAt = type to index
                                                dialogs.contextMenu =
                                                    providerContextMenu(
                                                        provider = menuProvider,
                                                        position = index,
                                                        item = it,
                                                        preferences = preferences,
                                                        dialogs = dialogs,
                                                        onAddToQueue = playlistViewModel::addToQueue,
                                                    )
                                            }
                                        },
                                    )
                                }
                            }

                            is DataLoadingState.Error -> {
                                PhoneRowMessage(title = title, message = items.localizedMessage, failure = true)
                            }

                            else -> {
                                PhoneRowMessage(title = title, message = stringResource(R.string.tally_media_loading))
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
