package io.github.scdouglas1999.tally.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.data.TallyMultiviewState
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.GameActions
import io.github.scdouglas1999.tally.ui.components.GameActionsDialog
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.multiview.MultiviewBenchEntry
import io.github.scdouglas1999.tally.ui.multiview.MultiviewLayout
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTile
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTilePlayback
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTileView
import io.github.scdouglas1999.tally.ui.multiview.MultiviewViewModel
import io.github.scdouglas1999.tally.ui.multiview.defaultMultiviewLayout
import io.github.scdouglas1999.tally.ui.multiview.rememberMultiviewPlayers
import io.github.scdouglas1999.tally.ui.phone.PhoneMultiviewPage
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * Multiview: up to four live streams, a swap-in rail, and audio that follows the focused tile.
 * OK promotes a tile into the large slot, or returns the large tile to an equal grid.
 * Long-OK opens the actions menu for the tile: full screen, follow its teams, hide scores, remove.
 */
@Composable
fun TallyMultiviewPage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: MultiviewViewModel = hiltViewModel(),
) {
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhoneMultiviewPage(viewModel = viewModel, modifier = modifier)
        return
    }
    val tiles by viewModel.tiles.collectAsState()
    val bench by viewModel.bench.collectAsState()
    val hideScores by viewModel.hideScores.collectAsState()
    val audioIndex by viewModel.audioIndex.collectAsState()
    val layoutChoice by viewModel.layoutChoice.collectAsState()
    val bigIndex by viewModel.bigIndex.collectAsState()
    val favoriteTeams by viewModel.favoriteTeams.collectAsState()
    val players = rememberMultiviewPlayers()
    val layout = layoutChoice ?: defaultMultiviewLayout(tiles.size)
    val big = if (tiles.isEmpty()) 0 else bigIndex.coerceIn(0, tiles.lastIndex)

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

    val tileFocus = remember { List(TallyMultiviewState.MAX) { FocusRequester() } }
    val firstRailFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    var previousCount by remember { mutableIntStateOf(-1) }
    // Null while the rail is focused, so the hint only says "Equal tiles" on the large tile.
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    var lastTileIndex by remember { mutableIntStateOf(0) }
    // The tile whose actions menu is open, by channel so a board refresh cannot retarget it.
    var actionsChannelId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tiles.size, bench.isEmpty()) {
        val size = tiles.size
        if (!initialFocusDone) {
            when {
                size > 0 -> {
                    val index =
                        if (layout == MultiviewLayout.FOCUS) {
                            big.coerceIn(0, size - 1)
                        } else {
                            0
                        }
                    tileFocus[index].tryRequestFocus("multiview-tile")
                    initialFocusDone = true
                }

                bench.isNotEmpty() -> {
                    firstRailFocus.tryRequestFocus("multiview-rail")
                    initialFocusDone = true
                }
            }
        } else if (previousCount > size && size > 0) {
            tileFocus[audioIndex.coerceIn(0, size - 1)].tryRequestFocus("multiview-tile")
        } else if (previousCount > size && size == 0 && bench.isNotEmpty()) {
            firstRailFocus.tryRequestFocus("multiview-rail")
        }
        previousCount = size
    }

    val bigFocused = layout == MultiviewLayout.FOCUS && focusedIndex != null && focusedIndex == big
    val hint =
        stringResource(
            if (bigFocused) R.string.tally_mv_hint_equal else R.string.tally_mv_hint_focus,
        )

    TallySurface(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = TallyDimens.marginHorizontal,
                        vertical = TallyDimens.marginVertical,
                    ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    if (tiles.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.tally_mv_empty_title),
                            subtitle = stringResource(R.string.tally_mv_empty_subtitle),
                            modifier = Modifier.fillMaxSize(),
                            takeFocus = false, // the swap-in rail takes focus on this page
                        )
                    } else {
                        MultiviewStage(
                            tiles = tiles,
                            layout = layout,
                            bigIndex = big,
                            audioIndex = audioIndex,
                            hideScores = hideScores,
                            focusedIndex = focusedIndex,
                            playback = { channelId ->
                                players.playback[channelId] ?: MultiviewTilePlayback()
                            },
                            tileFocus = tileFocus,
                            railFocus = firstRailFocus,
                            hasRail = bench.isNotEmpty(),
                            onFocused = { index ->
                                viewModel.focusTile(index)
                                focusedIndex = index
                                lastTileIndex = index
                            },
                            onClick = viewModel::onTileOk,
                            onLongClick = { index -> actionsChannelId = tiles.getOrNull(index)?.channelId },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                MultiviewRail(
                    bench = bench,
                    hideScores = hideScores,
                    onSwapIn = viewModel::swapIn,
                    onRailFocused = { focusedIndex = null },
                    railFocus = { index ->
                        Modifier.focusProperties {
                            if (tiles.isNotEmpty()) {
                                left = tileFocus[lastTileIndex.coerceIn(0, tiles.lastIndex)]
                            }
                            right = FocusRequester.Cancel
                            if (index == 0) up = FocusRequester.Cancel
                        }
                    },
                    firstRailFocus = firstRailFocus,
                )
            }
            if (tiles.isNotEmpty()) {
                Text(
                    text = hint,
                    style = TallyType.clock,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .padding(top = 12.dp)
                            .width(RailWidth)
                            .padding(start = 24.dp, end = 4.dp)
                            .align(Alignment.End),
                )
            }
        }
    }

    val actionsIndex = tiles.indexOfFirst { it.channelId == actionsChannelId }
    val actionsTile = tiles.getOrNull(actionsIndex)
    if (actionsTile != null) {
        val game = actionsTile.game
        GameActionsDialog(
            game = game,
            channelName = actionsTile.name.ifBlank { stringResource(R.string.tally_mv_unknown_channel) },
            actions =
                GameActions(
                    watch =
                        if (actionsTile.liveTvItemId.isNullOrBlank()) {
                            null
                        } else {
                            { viewModel.watchFullScreen(actionsIndex) }
                        },
                    watchLabel = R.string.tally_actions_full_screen,
                    followAway = game?.let { { viewModel.toggleFollow(it.teamKey(it.away)) } },
                    followHome = game?.let { { viewModel.toggleFollow(it.teamKey(it.home)) } },
                    followedAway = game != null && game.teamKey(game.away) in favoriteTeams,
                    followedHome = game != null && game.teamKey(game.home) in favoriteTeams,
                    hideScores = hideScores,
                    toggleHideScores = viewModel::toggleHideScores,
                    removeFromMultiview = { viewModel.removeTile(actionsIndex) },
                ),
            onDismiss = { actionsChannelId = null },
        )
    }
}

@Composable
private fun MultiviewStage(
    tiles: List<MultiviewTile>,
    layout: MultiviewLayout,
    bigIndex: Int,
    audioIndex: Int,
    hideScores: Boolean,
    focusedIndex: Int?,
    playback: (String) -> MultiviewTilePlayback,
    tileFocus: List<FocusRequester>,
    railFocus: FocusRequester,
    hasRail: Boolean,
    onFocused: (Int) -> Unit,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.padding(TallyDimens.focusBorder),
    ) {
        val slots =
            multiviewTileSlots(
                count = tiles.size,
                layout = layout,
                bigIndex = bigIndex,
                areaWidth = maxWidth,
                areaHeight = maxHeight,
            )
        if (slots.size == tiles.size) {
            tiles.forEachIndexed { index, tile ->
                val slot = slots[index]
                key(index) {
                    val x by animateDpAsState(slot.x, tween(TILE_MOVE_MILLIS), label = "mvX$index")
                    val y by animateDpAsState(slot.y, tween(TILE_MOVE_MILLIS), label = "mvY$index")
                    val tileWidth by animateDpAsState(slot.width, tween(TILE_MOVE_MILLIS), label = "mvW$index")
                    val focus = tileFocusMap(index, tiles.size, layout, bigIndex, hasRail)
                    MultiviewTileView(
                        tile = tile,
                        playback = playback(tile.channelId),
                        hasAudio = index == audioIndex,
                        hideScores = hideScores,
                        onFocused = { onFocused(index) },
                        onClick = { onClick(index) },
                        onLongClick = { onLongClick(index) },
                        modifier =
                            Modifier
                                .zIndex(if (index == focusedIndex) 1f else 0f)
                                .offset(x, y)
                                .width(tileWidth)
                                .focusRequester(tileFocus[index])
                                .tileFocusProperties(focus, tileFocus, railFocus),
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiviewRail(
    bench: List<MultiviewBenchEntry>,
    hideScores: Boolean,
    onSwapIn: (MultiviewBenchEntry) -> Unit,
    onRailFocused: () -> Unit,
    railFocus: (Int) -> Modifier,
    firstRailFocus: FocusRequester,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(RailWidth)
                .drawBehind {
                    val strokeWidth = TallyDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(strokeWidth / 2f, 0f),
                        end = Offset(strokeWidth / 2f, size.height),
                        strokeWidth = strokeWidth,
                    )
                }.padding(start = 24.dp),
    ) {
        RowHeader(
            title = stringResource(R.string.tally_mv_swap_in),
            count = bench.size,
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(bench, key = { _, entry -> entry.channelId }) { index, entry ->
                SwapInRow(
                    entry = entry,
                    hideScores = hideScores,
                    onClick = { onSwapIn(entry) },
                    onFocused = onRailFocused,
                    modifier =
                        railFocus(index).then(
                            if (index == 0) Modifier.focusRequester(firstRailFocus) else Modifier,
                        ),
                )
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
    entry: MultiviewBenchEntry,
    hideScores: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.ground,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
                disabledContainerColor = TallyColors.ground,
                disabledContentColor = TallyColors.textSecondary,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
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
            val game = entry.game
            if (game == null) {
                // no live game on this channel right now: the channel name is all there is to say
                Text(
                    text = stringResource(R.string.tally_mv_channel).uppercase(),
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
                Text(
                    text = entry.name,
                    style = TallyType.body,
                    color = TallyColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = game.league.uppercase(),
                        style = TallyType.label,
                        color = TallyColors.muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = gameStatusLabel(game),
                        style = TallyType.label,
                        color = TallyColors.accent,
                        maxLines = 1,
                    )
                }
                SwapInTeamLine(team = game.away, hideScores = hideScores)
                SwapInTeamLine(team = game.home, hideScores = hideScores)
            }
        }
    }
}

@Composable
private fun SwapInTeamLine(
    team: TallyTeam,
    hideScores: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = team.shortName.ifBlank { team.abbr },
            style = TallyType.body,
            color = TallyColors.text,
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
            style = TallyType.clock,
            color = TallyColors.text,
            maxLines = 1,
        )
    }
}

private data class MultiviewTileSlot(
    val x: Dp,
    val y: Dp,
    val width: Dp,
)

/**
 * Equal tiles share a centered grid. Focus puts [bigIndex] on the left at 68% of the
 * stage and stacks the rest on the right, top-aligned with the large tile, 16:9.
 */
private fun multiviewTileSlots(
    count: Int,
    layout: MultiviewLayout,
    bigIndex: Int,
    areaWidth: Dp,
    areaHeight: Dp,
): List<MultiviewTileSlot> {
    if (count <= 0 || areaWidth <= 0.dp || areaHeight <= 0.dp) return emptyList()
    return if (count == 1 || layout == MultiviewLayout.EQUAL) {
        equalSlots(count, areaWidth, areaHeight)
    } else {
        focusSlots(count, bigIndex, areaWidth, areaHeight)
    }
}

private fun equalSlots(
    count: Int,
    areaWidth: Dp,
    areaHeight: Dp,
): List<MultiviewTileSlot> {
    val columns = if (count == 1) 1 else 2
    val rows = if (count <= 2) 1 else 2
    val maxW = if (columns == 1) areaWidth else (areaWidth - TileGap) / 2
    var tileW = maxW
    var tileH = tileBlockHeight(tileW)
    val rowGap = if (rows > 1) TileGap else 0.dp
    val naturalH = tileH * rows + rowGap
    if (naturalH > areaHeight) {
        val budget = areaHeight - TileLabelHeight * rows - rowGap
        tileW = (budget / rows * (16f / 9f)).coerceAtMost(maxW)
        tileH = tileBlockHeight(tileW)
    }
    val gridW = tileW * columns + if (columns > 1) TileGap else 0.dp
    val gridH = tileH * rows + rowGap
    val left = ((areaWidth - gridW) / 2).coerceAtLeast(0.dp)
    val top = ((areaHeight - gridH) / 2).coerceAtLeast(0.dp)
    return List(count) { index ->
        val col = if (count == 1) 0 else index % 2
        val row = if (count <= 2) 0 else index / 2
        MultiviewTileSlot(
            x = left + (tileW + TileGap) * col,
            y = top + (tileH + TileGap) * row,
            width = tileW,
        )
    }
}

private fun focusSlots(
    count: Int,
    bigIndex: Int,
    areaWidth: Dp,
    areaHeight: Dp,
): List<MultiviewTileSlot> {
    val big = bigIndex.coerceIn(0, count - 1)
    var bigW = areaWidth * FOCUS_WIDTH_FRACTION
    var smallW = areaWidth - TileGap - bigW
    val nSmall = count - 1
    if (nSmall > 0 && smallW > 0.dp) {
        val stack = tileBlockHeight(smallW) * nSmall + TileGap * (nSmall - 1)
        if (stack > areaHeight) {
            val budget = areaHeight - TileLabelHeight * nSmall - TileGap * (nSmall - 1)
            smallW = budget / nSmall * (16f / 9f)
            bigW = (areaWidth - TileGap - smallW).coerceAtLeast(smallW)
        }
    }
    if (tileBlockHeight(bigW) > areaHeight) {
        bigW = (areaHeight - TileLabelHeight) * (16f / 9f)
    }
    val smallH = tileBlockHeight(smallW)
    val bigH = tileBlockHeight(bigW)
    val stackH = if (nSmall == 0) bigH else smallH * nSmall + TileGap * (nSmall - 1)
    // Three and four tiles are top-aligned. Two tiles share that top edge and sit in the
    // vertical middle, the same way the equal pair does.
    val top =
        if (count >= 3) {
            0.dp
        } else {
            ((areaHeight - maxOf(bigH, stackH)) / 2).coerceAtLeast(0.dp)
        }
    val slots = MutableList(count) { MultiviewTileSlot(0.dp, top, bigW) }
    slots[big] = MultiviewTileSlot(x = 0.dp, y = top, width = bigW)
    var stackIndex = 0
    for (index in 0 until count) {
        if (index == big) continue
        slots[index] =
            MultiviewTileSlot(
                x = bigW + TileGap,
                y = top + (smallH + TileGap) * stackIndex,
                width = smallW,
            )
        stackIndex++
    }
    return slots
}

/** 16:9 picture plus the channel [io.github.scdouglas1999.tally.ui.components.LabelBar]. */
private fun tileBlockHeight(width: Dp): Dp = width * (9f / 16f) + TileLabelHeight

private sealed interface FocusTarget {
    data object Unset : FocusTarget

    data object Block : FocusTarget

    data class Tile(
        val index: Int,
    ) : FocusTarget

    data object Rail : FocusTarget
}

private data class TileFocus(
    val left: FocusTarget,
    val right: FocusTarget,
    val up: FocusTarget,
    val down: FocusTarget,
)

private fun tileFocusMap(
    index: Int,
    count: Int,
    layout: MultiviewLayout,
    bigIndex: Int,
    hasRail: Boolean,
): TileFocus {
    if (count <= 1 || layout == MultiviewLayout.EQUAL) {
        return equalFocus(index, count, hasRail)
    }
    val big = bigIndex.coerceIn(0, count - 1)
    val smalls = (0 until count).filter { it != big }
    val rail = if (hasRail) FocusTarget.Rail else FocusTarget.Block
    if (index == big) {
        return TileFocus(
            left = FocusTarget.Unset,
            right = smalls.firstOrNull()?.let { FocusTarget.Tile(it) } ?: rail,
            up = FocusTarget.Block,
            down = FocusTarget.Block,
        )
    }
    val pos = smalls.indexOf(index)
    return TileFocus(
        left = FocusTarget.Tile(big),
        right = rail,
        up = if (pos > 0) FocusTarget.Tile(smalls[pos - 1]) else FocusTarget.Block,
        down = if (pos in 0 until smalls.lastIndex) FocusTarget.Tile(smalls[pos + 1]) else FocusTarget.Block,
    )
}

private fun equalFocus(
    index: Int,
    count: Int,
    hasRail: Boolean,
): TileFocus {
    val rail = if (hasRail) FocusTarget.Rail else FocusTarget.Block
    if (count <= 1) {
        return TileFocus(
            left = FocusTarget.Unset,
            right = rail,
            up = FocusTarget.Block,
            down = FocusTarget.Block,
        )
    }
    val col = index % 2
    val row = if (count <= 2) 0 else index / 2
    val right =
        when {
            col == 0 && index + 1 < count -> FocusTarget.Tile(index + 1)
            else -> rail
        }
    val up =
        when {
            row == 0 -> FocusTarget.Block
            count == 3 && index == 2 -> FocusTarget.Tile(0)
            else -> FocusTarget.Tile(index - 2)
        }
    val down =
        when {
            count <= 2 -> FocusTarget.Block
            index + 2 < count -> FocusTarget.Tile(index + 2)
            count == 3 && index == 1 -> FocusTarget.Tile(2)
            else -> FocusTarget.Block
        }
    return TileFocus(
        left = if (col == 0) FocusTarget.Unset else FocusTarget.Tile(index - 1),
        right = right,
        up = up,
        down = down,
    )
}

private fun Modifier.tileFocusProperties(
    focus: TileFocus,
    tiles: List<FocusRequester>,
    rail: FocusRequester,
): Modifier {
    fun resolve(target: FocusTarget): FocusRequester? =
        when (target) {
            FocusTarget.Unset -> null
            FocusTarget.Block -> FocusRequester.Cancel
            is FocusTarget.Tile -> tiles[target.index]
            FocusTarget.Rail -> rail
        }
    return focusProperties {
        resolve(focus.left)?.let { left = it }
        resolve(focus.right)?.let { right = it }
        resolve(focus.up)?.let { up = it }
        resolve(focus.down)?.let { down = it }
    }
}

/** Channel label bar. Kept in step with LabelBar's default height so the 12dp gaps stay even. */
private val TileLabelHeight = 30.dp

private val TileGap = 12.dp

/** Large tile's share of the stage width in focus layout. */
private const val FOCUS_WIDTH_FRACTION = 0.68f

/** Wide enough for the mono key hint on one line beside the tiles. */
private val RailWidth = 320.dp

private const val TILE_MOVE_MILLIS = 200
