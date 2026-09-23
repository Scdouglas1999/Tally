package io.github.scdouglas1999.tally.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.data.TallyRepository
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.components.TallyTab
import io.github.scdouglas1999.tally.ui.components.TallyTopBar
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface

/**
 * The Tally section root: the top bar (wordmark, tabs, clock) above the
 * selected tab's content.
 */
@Composable
fun TallyPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: TallyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clock by viewModel.clock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { resId ->
            showToast(context, context.getString(resId))
        }
    }
    // Leaving the content upward always lands on the current tab, not on whichever tab happens to be
    // nearest (a wide empty panel is nearest to MULTIVIEW; Compose's geometric search would pick that).
    val selectedTabFocus = remember { FocusRequester() }
    TallySurface(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            TallyTopBar(
                tabs = TallyTab.entries,
                selected = state.selectedTab,
                onSelect = viewModel::selectTab,
                selectedTabFocus = selectedTabFocus,
                // Upstream draws its own clock in this corner when the user has it enabled; never show two.
                clock = if (preferences.appPreferences.interfacePreferences.showClock) "" else clock,
            )
            CompositionLocalProvider(LocalTallyUpTarget provides selectedTabFocus) {
                Box(Modifier.focusProperties { up = selectedTabFocus }) {
                    when (state.selectedTab) {
                        TallyTab.GAMES -> {
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
                        }

                        TallyTab.CHANNELS -> {
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
                        }

                        TallyTab.MULTIVIEW -> {
                            MultiviewQueue(
                                channelIds = state.multiview,
                                channels = state.channels,
                                onRemove = viewModel::removeFromMultiview,
                                onOpen = viewModel::openMultiview,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        TallyTab.SETTINGS -> {
                            TallySettingsContent(
                                onlyWatchable = state.onlyWatchable,
                                hideScores = state.hideScores,
                                info = (state.availability as? TallyRepository.Availability.Available)?.info,
                                onToggleOnlyWatchable = viewModel::toggleOnlyWatchable,
                                onHideScoresChange = viewModel::setHideScores,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
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
    channels: List<TallyChannel>,
    onRemove: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channelIds.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.tally_multiview_empty_title),
            subtitle = stringResource(R.string.tally_multiview_empty_sub),
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = TallyDimens.marginHorizontal)
                    .padding(top = 24.dp, bottom = TallyDimens.marginVertical),
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
                    .padding(horizontal = TallyDimens.marginHorizontal)
                    .padding(top = 24.dp),
        ) {
            // The thing people come here to do goes first and takes focus; the queue is housekeeping.
            TallyRow(
                label = stringResource(R.string.tally_open_multiview),
                onClick = onOpen,
                primary = true,
                modifier = Modifier.focusRequester(firstRowFocus).upToTab(),
            )
            channelIds.forEach { channelId ->
                TallyRow(
                    label = channels.firstOrNull { it.id == channelId }?.name ?: channelId,
                    onClick = { onRemove(channelId) },
                    trailing = {
                        KeyHint(
                            key = stringResource(R.string.tally_key_ok),
                            label = stringResource(R.string.tally_remove_from_multiview),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Standalone Tally settings destination; renders the same content as the
 * Settings tab of [TallyPage].
 */
@Composable
fun TallySettingsPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: TallyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TallySurface(modifier = modifier) {
        TallySettingsContent(
            onlyWatchable = state.onlyWatchable,
            hideScores = state.hideScores,
            info = (state.availability as? TallyRepository.Availability.Available)?.info,
            onToggleOnlyWatchable = viewModel::toggleOnlyWatchable,
            onHideScoresChange = viewModel::setHideScores,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
