package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.detail.CollectionFolderLiveTv
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.data.TallyRepository
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.ui.TallyUiState
import io.github.scdouglas1999.tally.ui.TallyViewModel
import io.github.scdouglas1999.tally.ui.components.phone.PhoneChannelCard
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * The Channels tab on a phone: a 2-column grid of [PhoneChannelCard]s, favorites first then by name (the TV grid's
 * order). A tap plays the channel, a long-press adds it to multiview. The TV's loading, failed and empty states.
 */
@Composable
internal fun PhoneChannelsGrid(
    state: TallyUiState,
    viewModel: TallyViewModel,
    modifier: Modifier = Modifier,
) {
    val sorted =
        remember(state.channels, state.favorites) {
            state.channels.sortedWith(
                compareByDescending<TallyChannel> { it.id in state.favorites }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }
    when {
        state.loading -> {
            PhoneLoading(modifier)
        }

        state.boardError != null && !state.hasBoard -> {
            PhoneEmptyState(
                title = stringResource(R.string.tally_board_failed_title),
                subtitle = state.boardError,
                modifier = modifier,
            )
        }

        sorted.isEmpty() -> {
            PhoneEmptyState(
                title = stringResource(R.string.tally_no_channels_title),
                subtitle = stringResource(R.string.tally_no_channels_sub),
                modifier = modifier,
            )
        }

        else -> {
            val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = rememberLazyGridState(),
                horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
                verticalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
                contentPadding =
                    PaddingValues(
                        start = PhoneDimens.margin,
                        end = PhoneDimens.margin,
                        top = 16.dp,
                        bottom = bottom + PhoneDimens.rowGap,
                    ),
                modifier = modifier,
            ) {
                items(sorted, key = { it.id }) { channel ->
                    val liveGame =
                        remember(state.games, channel.gameId) {
                            state.games.firstOrNull { it.isLive && it.id == channel.gameId }
                        }
                    PhoneChannelCard(
                        channel = channel,
                        imageUrl = remember(channel.cardPath) { viewModel.absoluteUrl(channel.cardPath) },
                        game = liveGame,
                        hideScores = state.hideScores,
                        onClick = { viewModel.watchChannel(channel) },
                        onLongClick = { viewModel.addToMultiview(channel.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The server's Live TV library on a phone (Wholphin's guide grid is a TV layout): a top bar with the library's name
 * and the Channels grid, from the Tally plugin's board. On a server without the plugin, Wholphin's own Live TV page.
 */
@Composable
fun PhoneLiveTvPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: TallyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.availability is TallyRepository.Availability.NotInstalled) {
        CollectionFolderLiveTv(preferences = preferences, destination = destination, modifier = modifier)
        return
    }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { resId -> showToast(context, context.getString(resId)) }
    }
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = stringResource(R.string.tally_phone_sports_live_tv),
            onBack = { viewModel.navigationManager.goBack() },
        )
        PhoneChannelsGrid(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
