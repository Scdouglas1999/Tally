package com.github.damontecres.wholphin.jellytv.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.ui.components.GameCard
import com.github.damontecres.wholphin.jellytv.ui.components.RowHeader
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.ui.showToast

/**
 * The JellyTV row on Wholphin's home screen: live and upcoming games as cards, above the library rows.
 *
 * Renders NOTHING (no header, zero height) when the server has no JellyTV plugin or there is nothing
 * worth watching, so a plain Jellyfin server sees upstream's home screen unchanged. Everything drawn
 * sits inside [JtvScale] so the cards are exactly the size they are in the JellyTV section.
 *
 * Focus: the row never asks for focus — upstream's home screen decides what is focused first. It is
 * reached by pressing UP from the first library row, and LEFT/RIGHT move between its cards.
 */
@Composable
fun JellyTvHomeRow(modifier: Modifier = Modifier) {
    val viewModel: JellyTvHomeRowViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { resId -> showToast(context, context.getString(resId)) }
    }
    LaunchedEffect(state.hideScores) { JellyTvHomeHeaderState.hideScores.value = state.hideScores }
    if (state.games.isEmpty()) return

    // When the cards first appear right after the page opened, they take the initial focus (see JellyTvHomeFocus).
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(firstCardFocus) { JellyTvHomeFocus.claim(firstCardFocus) }

    JtvScale {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 24.dp),
        ) {
            RowHeader(
                title =
                    stringResource(
                        if (state.anyLive) R.string.jtv_home_row_live else R.string.jtv_home_row_today,
                    ),
                count = state.games.size,
                modifier = Modifier.padding(horizontal = JtvDimens.marginHorizontal),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(JtvDimens.cardGap),
                contentPadding = PaddingValues(horizontal = JtvDimens.marginHorizontal),
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
                        isFavorite = game.watch?.channelId in state.favorites,
                        onClick = { viewModel.watch(game) },
                        onLongClick = { game.watch?.channelId?.let(viewModel::addToMultiview) },
                        modifier = if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                        onFocused = { viewModel.onCardFocused(game) },
                    )
                }
            }
        }
    }
}
