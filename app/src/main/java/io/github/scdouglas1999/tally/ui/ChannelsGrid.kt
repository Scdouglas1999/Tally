package io.github.scdouglas1999.tally.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.ui.components.ChannelCard
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.theme.TallyDimens

/**
 * The channels tab: a 4-column [LazyVerticalGrid] of [ChannelCard]s, favorites
 * first then alphabetical. Focus lands on the last-focused card (first card on
 * first open), including when returning from the player.
 */
@Composable
fun ChannelsGrid(
    channels: List<TallyChannel>,
    games: List<TallyGame>,
    favorites: Set<String>,
    hideScores: Boolean,
    loading: Boolean,
    hasBoard: Boolean,
    boardError: String?,
    cardUrl: (TallyChannel) -> String?,
    onWatch: (TallyChannel) -> Unit,
    onAddToMultiview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedChannels =
        remember(channels, favorites) {
            channels.sortedWith(
                compareByDescending<TallyChannel> { it.id in favorites }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }

    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }
    var position by rememberInt()

    LaunchedEffect(sortedChannels.isNotEmpty()) {
        if (sortedChannels.isNotEmpty()) {
            gridFocusRequester.tryRequestFocus("jellytv-channels")
        }
    }

    val emptyModifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TallyDimens.marginHorizontal)
            .padding(top = 16.dp, bottom = TallyDimens.marginVertical)

    when {
        loading -> {
            EmptyState(
                title = stringResource(R.string.tally_loading_channels),
                subtitle = "",
                modifier = modifier.then(emptyModifier),
            )
        }

        boardError != null && !hasBoard -> {
            EmptyState(
                title = stringResource(R.string.tally_board_failed_title),
                subtitle = boardError,
                modifier = modifier.then(emptyModifier),
            )
        }

        sortedChannels.isEmpty() -> {
            EmptyState(
                title = stringResource(R.string.tally_no_channels_title),
                subtitle = stringResource(R.string.tally_no_channels_sub),
                modifier = modifier.then(emptyModifier),
            )
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(TallyDimens.cardGap),
                verticalArrangement = Arrangement.spacedBy(TallyDimens.cardGap),
                contentPadding =
                    PaddingValues(
                        horizontal = TallyDimens.marginHorizontal,
                        vertical = TallyDimens.marginVertical,
                    ),
                modifier =
                    modifier
                        .fillMaxSize()
                        .focusGroup()
                        .focusRestorer(firstFocus),
            ) {
                itemsIndexed(sortedChannels, key = { _, channel -> channel.id }) { index, channel ->
                    val liveGame =
                        remember(games, channel.gameId) {
                            games.firstOrNull { it.isLive && it.id == channel.gameId }
                        }
                    ChannelCard(
                        channel = channel,
                        imageUrl = cardUrl(channel),
                        game = liveGame,
                        hideScores = hideScores,
                        onClick = { onWatch(channel) },
                        onLongClick = { onAddToMultiview(channel.id) },
                        onFocused = { position = index },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (index < 4) Modifier.upToTab() else Modifier)
                                .ifElse(
                                    index == position,
                                    Modifier
                                        .focusRequester(firstFocus)
                                        .focusRequester(gridFocusRequester),
                                ),
                    )
                }
            }
        }
    }
}
