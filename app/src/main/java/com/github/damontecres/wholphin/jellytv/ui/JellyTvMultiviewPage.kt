package com.github.damontecres.wholphin.jellytv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvTeam
import com.github.damontecres.wholphin.jellytv.ui.components.EmptyState
import com.github.damontecres.wholphin.jellytv.ui.components.RowHeader
import com.github.damontecres.wholphin.jellytv.ui.components.gameStatusLabel
import com.github.damontecres.wholphin.jellytv.ui.multiview.MultiviewTilePlayback
import com.github.damontecres.wholphin.jellytv.ui.multiview.MultiviewTileView
import com.github.damontecres.wholphin.jellytv.ui.multiview.MultiviewViewModel
import com.github.damontecres.wholphin.jellytv.ui.multiview.rememberMultiviewPlayers
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * Multiview: up to four live streams side by side, a "swap in" rail of live games,
 * and audio that follows the focused tile. OK opens a tile's game full screen,
 * long-OK removes the tile.
 */
@Composable
fun JellyTvMultiviewPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: MultiviewViewModel = hiltViewModel(),
) {
    val tiles by viewModel.tiles.collectAsState()
    val bench by viewModel.bench.collectAsState()
    val hideScores by viewModel.hideScores.collectAsState()
    val audioIndex by viewModel.audioIndex.collectAsState()
    val players = rememberMultiviewPlayers()

    LaunchedEffect(tiles) { players.sync(tiles) }
    LaunchedEffect(tiles, audioIndex) {
        players.setAudio(tiles.getOrNull(audioIndex)?.channelId)
    }

    // Keep the screen on while a tile is playing, the way upstream's playback does.
    val anyPlaying = players.playback.values.any { it.playing }
    LaunchedEffect(anyPlaying) { viewModel.setKeepScreenOn(anyPlaying) }
    DisposableEffect(players) {
        onDispose { viewModel.setKeepScreenOn(false) }
    }

    val firstTileFocus = remember { FocusRequester() }
    val firstRailFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(tiles.isEmpty(), bench.isEmpty()) {
        if (!initialFocusDone) {
            when {
                tiles.isNotEmpty() -> {
                    firstTileFocus.tryRequestFocus()
                    initialFocusDone = true
                }

                bench.isNotEmpty() -> {
                    firstRailFocus.tryRequestFocus()
                    initialFocusDone = true
                }
            }
        } else if (tiles.isEmpty() && bench.isNotEmpty()) {
            // The last tile was removed; focus would be lost, so hand it to the rail.
            firstRailFocus.tryRequestFocus()
        }
    }

    @Composable
    fun TileSlot(
        index: Int,
        modifier: Modifier = Modifier,
    ) {
        val tile = tiles.getOrNull(index) ?: return
        MultiviewTileView(
            tile = tile,
            playback = players.playback[tile.channelId] ?: MultiviewTilePlayback(),
            hasAudio = index == audioIndex,
            hideScores = hideScores,
            onFocused = { viewModel.focusTile(index) },
            onClick = {
                val itemId =
                    tile.game
                        ?.watch
                        ?.liveTvItemId
                        ?.toUUIDOrNull()
                if (itemId != null) {
                    viewModel.navigationManager.navigateTo(
                        Destination.JellyTvPlayback(itemId = itemId, channelId = tile.channelId),
                    )
                }
            },
            onLongClick = { viewModel.removeTile(index) },
            modifier =
                if (index == 0) {
                    modifier.focusRequester(firstTileFocus)
                } else {
                    modifier
                },
        )
    }

    JtvSurface(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = JtvDimens.marginHorizontal,
                        vertical = JtvDimens.marginVertical,
                    ),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                when (tiles.size) {
                    0 -> {
                        EmptyState(
                            title = stringResource(R.string.jtv_mv_empty_title),
                            subtitle = stringResource(R.string.jtv_mv_empty_subtitle),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    1 -> {
                        TileSlot(0, Modifier.fillMaxWidth().align(Alignment.Center))
                    }

                    2 -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            TileSlot(0, Modifier.weight(1f))
                            TileSlot(1, Modifier.weight(1f))
                        }
                    }

                    else -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TileSlot(0, Modifier.weight(1f))
                                TileSlot(1, Modifier.weight(1f))
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TileSlot(2, Modifier.weight(1f))
                                if (tiles.size > 3) {
                                    TileSlot(3, Modifier.weight(1f))
                                } else {
                                    // A lone third tile stays half width on the second row.
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(264.dp)
                        .drawBehind {
                            val strokeWidth = JtvDimens.hairline.toPx()
                            drawLine(
                                color = JtvColors.rule,
                                start = Offset(strokeWidth / 2f, 0f),
                                end = Offset(strokeWidth / 2f, size.height),
                                strokeWidth = strokeWidth,
                            )
                        }.padding(start = 24.dp),
            ) {
                RowHeader(
                    title = stringResource(R.string.jtv_mv_swap_in),
                    count = bench.size,
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(bench, key = { _, game -> game.id }) { index, game ->
                        SwapInRow(
                            game = game,
                            hideScores = hideScores,
                            onClick = { viewModel.swapIn(game) },
                            modifier =
                                if (index == 0) {
                                    Modifier.focusRequester(firstRailFocus)
                                } else {
                                    Modifier
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A compact focusable game row for the swap-in rail: league + clock on one line,
 * then two team lines with mono scores.
 */
@Composable
private fun SwapInRow(
    game: JtvGame,
    hideScores: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = JtvColors.ground,
                contentColor = JtvColors.text,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = JtvColors.text,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = JtvColors.text,
                disabledContainerColor = JtvColors.ground,
                disabledContentColor = JtvColors.textSecondary,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = game.league.uppercase(),
                    style = JtvType.label,
                    color = JtvColors.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = gameStatusLabel(game),
                    style = JtvType.label,
                    color = if (game.isLive) JtvColors.accent else JtvColors.textSecondary,
                    maxLines = 1,
                )
            }
            SwapInTeamLine(team = game.away, hideScores = hideScores)
            SwapInTeamLine(team = game.home, hideScores = hideScores)
        }
    }
}

@Composable
private fun SwapInTeamLine(
    team: JtvTeam,
    hideScores: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = team.shortName.ifBlank { team.abbr },
            style = JtvType.body,
            color = JtvColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text =
                when {
                    hideScores -> "–"
                    else -> team.score?.toString() ?: "–"
                },
            style = JtvType.clock,
            color = JtvColors.text,
            maxLines = 1,
        )
    }
}
