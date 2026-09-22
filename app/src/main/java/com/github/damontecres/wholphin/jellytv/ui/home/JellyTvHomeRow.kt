package com.github.damontecres.wholphin.jellytv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.data.isFollowed
import com.github.damontecres.wholphin.jellytv.media.home.HomeRowTitle
import com.github.damontecres.wholphin.jellytv.ui.components.GameActionsDialog
import com.github.damontecres.wholphin.jellytv.ui.components.GameCard
import com.github.damontecres.wholphin.jellytv.ui.components.gameActions
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The JellyTV row on Wholphin's home screen: live and upcoming games as cards, above the library rows.
 *
 * Renders nothing (no header, zero height) when the server has no JellyTV plugin or there is nothing
 * worth watching, so a plain Jellyfin server sees upstream's home screen unchanged. The title is drawn
 * like [com.github.damontecres.wholphin.ui.cards.ItemRow]'s title; only the cards sit inside [JtvScale]
 * so they stay the size they are in the JellyTV section.
 *
 * Focus: the row never asks for focus — upstream's home screen decides what is focused first. It is
 * reached by pressing UP from the first library row, and LEFT/RIGHT move between its cards.
 */
@Composable
fun JellyTvHomeRow(modifier: Modifier = Modifier) {
    val viewModel: JellyTvHomeRowViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuGameId by rememberSaveable { mutableStateOf<String?>(null) }
    val menuReturnFocus = remember { FocusRequester() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { resId -> showToast(context, context.getString(resId)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { text -> showToast(context, text) }
    }
    LaunchedEffect(state.hideScores) { JellyTvHomeHeaderState.hideScores.value = state.hideScores }
    val menuGame = state.games.firstOrNull { it.id == menuGameId }
    BackHandler(enabled = menuGame != null) {
        menuReturnFocus.tryRequestFocus("jtv-home-actions-return")
        menuGameId = null
    }
    if (state.games.isEmpty()) return

    // When the cards first appear right after the page opened, they take the initial focus (see JellyTvHomeFocus).
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(firstCardFocus) { JellyTvHomeFocus.claim(firstCardFocus) }

    // Same vertical rhythm as a home [ItemRow]: 8.dp between title and the card row, 8.dp of card-row
    // padding, and the 8.dp the home page adds under every library row. Card insets are inside [JtvScale]
    // (1.dp draws as 0.8.dp), so 16.dp and 8.dp outside become 20.dp and 10.dp here.
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
    ) {
        JellyTvHomeRowTitle(anyLive = state.anyLive, count = state.games.size)
        JtvScale {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(JtvDimens.cardGap),
                contentPadding =
                    PaddingValues(
                        horizontal = 20.dp,
                        vertical = 10.dp,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusGroup()
                        .focusRestorer()
                        .onFocusChanged { if (!it.hasFocus) JellyTvHomeHeaderState.focusedGame.value = null },
            ) {
                itemsIndexed(state.games, key = { _, game -> game.id }) { index, game ->
                    GameCard(
                        game = game,
                        hideScores = state.hideScores,
                        isFavorite = game.watch?.channelId in state.favorites || game.isFollowed(state.favoriteTeams),
                        followed = game.isFollowed(state.favoriteTeams),
                        onClick = { viewModel.watch(game) },
                        onLongClick = { menuGameId = game.id },
                        modifier =
                            when {
                                index == 0 && game.id == menuGameId -> {
                                    Modifier.focusRequester(firstCardFocus).focusRequester(menuReturnFocus)
                                }

                                index == 0 -> {
                                    Modifier.focusRequester(firstCardFocus)
                                }

                                game.id == menuGameId -> {
                                    Modifier.focusRequester(menuReturnFocus)
                                }

                                else -> {
                                    Modifier
                                }
                            },
                        onFocused = { viewModel.onCardFocused(game) },
                    )
                }
            }
            if (menuGame != null) {
                GameActionsDialog(
                    game = menuGame,
                    actions =
                        gameActions(
                            game = menuGame,
                            favoriteTeams = state.favoriteTeams,
                            hideScores = state.hideScores,
                            onWatch = viewModel::watch,
                            onAddToMultiview = { game -> game.watch?.channelId?.let(viewModel::addToMultiview) },
                            onWatchInCorner = null,
                            onToggleFollow = viewModel::toggleFollow,
                            onToggleHideScores = viewModel::toggleHideScores,
                        ),
                    onDismiss = {
                        menuReturnFocus.tryRequestFocus("jtv-home-actions-return")
                        menuGameId = null
                    },
                )
            }
        }
    }
}

/**
 * The row title: Tally's mono row header in the JellyTV look, else Wholphin's home-row title ([ItemRowTitle]:
 * `titleLarge`, `onBackground`, 8.dp start) plus a muted count.
 */
@Composable
private fun JellyTvHomeRowTitle(
    anyLive: Boolean,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(if (anyLive) R.string.jtv_home_row_live else R.string.jtv_home_row_today)
    HomeRowTitle(title = title, count = count, start = 20.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            ItemRowTitle(title = title)
            Text(
                text = stringResource(R.string.jtv_home_row_count, count),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
