package com.github.damontecres.wholphin.jellytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvChannel
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.jellytv.ui.components.EmptyState
import com.github.damontecres.wholphin.jellytv.ui.components.JtvRow
import com.github.damontecres.wholphin.jellytv.ui.components.JtvTab
import com.github.damontecres.wholphin.jellytv.ui.components.JtvTopBar
import com.github.damontecres.wholphin.jellytv.ui.components.KeyHint
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The JellyTV section root: the top bar (wordmark, tabs, clock) above the
 * selected tab's content.
 */
@Composable
fun JellyTvPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: JellyTvViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clock by viewModel.clock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { resId ->
            showToast(context, context.getString(resId))
        }
    }
    JtvSurface(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            JtvTopBar(
                tabs = JtvTab.entries,
                selected = state.selectedTab,
                onSelect = viewModel::selectTab,
                // Upstream draws its own clock in this corner when the user has it enabled; never show two.
                clock = if (preferences.appPreferences.interfacePreferences.showClock) "" else clock,
            )
            when (state.selectedTab) {
                JtvTab.GAMES ->
                    GamesBoard(
                        rows = state.rows,
                        favorites = state.favorites,
                        hideScores = state.hideScores,
                        loading = state.loading,
                        hasBoard = state.hasBoard,
                        boardError = state.boardError,
                        feedErrors = state.feedErrors,
                        hasGames = state.games.isNotEmpty(),
                        onWatch = viewModel::watch,
                        onAddToMultiview = viewModel::addToMultiview,
                        modifier = Modifier.fillMaxSize(),
                    )

                JtvTab.CHANNELS ->
                    ChannelsGrid(
                        channels = state.channels,
                        games = state.games,
                        favorites = state.favorites,
                        hideScores = state.hideScores,
                        loading = state.loading,
                        hasBoard = state.hasBoard,
                        boardError = state.boardError,
                        cardUrl = { viewModel.absoluteUrl(it.cardPath) },
                        onWatch = viewModel::watchChannel,
                        onAddToMultiview = viewModel::addToMultiview,
                        modifier = Modifier.fillMaxSize(),
                    )

                JtvTab.MULTIVIEW ->
                    MultiviewQueue(
                        channelIds = state.multiview,
                        channels = state.channels,
                        onRemove = viewModel::removeFromMultiview,
                        onOpen = viewModel::openMultiview,
                        modifier = Modifier.fillMaxSize(),
                    )

                JtvTab.SETTINGS ->
                    JtvSettingsContent(
                        onlyWatchable = state.onlyWatchable,
                        hideScores = state.hideScores,
                        info = (state.availability as? JellyTvRepository.Availability.Available)?.info,
                        onToggleOnlyWatchable = viewModel::toggleOnlyWatchable,
                        onHideScoresChange = viewModel::setHideScores,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

/**
 * The multiview queue: the queued channel names as focusable rows (OK removes one)
 * and the primary "Open multiview" action. Empty queues get an [EmptyState]
 * explaining how to add channels.
 */
@Composable
private fun MultiviewQueue(
    channelIds: List<String>,
    channels: List<JtvChannel>,
    onRemove: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channelIds.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.jtv_multiview_empty_title),
            subtitle = stringResource(R.string.jtv_multiview_empty_sub),
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = JtvDimens.marginHorizontal)
                    .padding(top = 24.dp),
        )
    } else {
        val firstRowFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            firstRowFocus.tryRequestFocus("jellytv-multiview")
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                modifier
                    .padding(horizontal = JtvDimens.marginHorizontal)
                    .padding(top = 24.dp),
        ) {
            // The thing people come here to do goes first and takes focus; the queue is housekeeping.
            JtvRow(
                label = stringResource(R.string.jtv_open_multiview),
                onClick = onOpen,
                primary = true,
                modifier = Modifier.focusRequester(firstRowFocus),
            )
            channelIds.forEach { channelId ->
                JtvRow(
                    label = channels.firstOrNull { it.id == channelId }?.name ?: channelId,
                    onClick = { onRemove(channelId) },
                    trailing = {
                        KeyHint(
                            key = stringResource(R.string.jtv_key_ok),
                            label = stringResource(R.string.jtv_remove_from_multiview),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Standalone JellyTV settings destination; renders the same content as the
 * Settings tab of [JellyTvPage].
 */
@Composable
fun JellyTvSettingsPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: JellyTvViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    JtvSurface(modifier = modifier) {
        JtvSettingsContent(
            onlyWatchable = state.onlyWatchable,
            hideScores = state.hideScores,
            info = (state.availability as? JellyTvRepository.Availability.Available)?.info,
            onToggleOnlyWatchable = viewModel::toggleOnlyWatchable,
            onHideScoresChange = viewModel::setHideScores,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
