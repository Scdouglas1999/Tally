package io.github.scdouglas1999.tally.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.data.TallyMultiviewState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.ui.components.GameActions
import io.github.scdouglas1999.tally.ui.components.GameActionsDialog
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.multiview.MultiviewBenchEntry
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTile
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTilePlayback
import io.github.scdouglas1999.tally.ui.multiview.MultiviewViewModel
import io.github.scdouglas1999.tally.ui.multiview.phone.PhoneMultiviewTile
import io.github.scdouglas1999.tally.ui.multiview.phone.PhoneTileLabelHeight
import io.github.scdouglas1999.tally.ui.multiview.rememberMultiviewPlayers
import io.github.scdouglas1999.tally.ui.player.controls.phone.PhonePlayerWindow
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

private val TileGap = 8.dp
private val BarHeight = PhoneDimens.touchTarget

/**
 * Multiview on a phone ([io.github.scdouglas1999.tally.ui.TallyMultiviewPage]'s phone layout, the same
 * [MultiviewViewModel] and players): landscape, the games side by side (two) or in a 2x2 grid (three or four) under a
 * slim bar (back, MULTIVIEW and the count, what a tap does, GAMES). A tap on a tile moves the sound to it (the accent
 * frame marks it); a double tap puts that game full screen, and back (or another double tap) returns to the grid; a
 * long-press opens the tile's game sheet (the TV's long-OK menu: full screen, follow, hide scores, remove). GAMES opens
 * a sheet to add and remove: the games on screen, the live games and the other channels (the TV's swap-in rail).
 */
@Composable
fun PhoneMultiviewPage(
    viewModel: MultiviewViewModel,
    modifier: Modifier = Modifier,
) {
    // Landscape and full screen, as the player (held by a count, so opening a tile full screen keeps them).
    PhonePlayerWindow()
    val tiles by viewModel.tiles.collectAsState()
    val bench by viewModel.bench.collectAsState()
    val hideScores by viewModel.hideScores.collectAsState()
    val audioIndex by viewModel.audioIndex.collectAsState()
    val favoriteTeams by viewModel.favoriteTeams.collectAsState()
    val players = rememberMultiviewPlayers()

    LaunchedEffect(tiles) { players.sync(tiles) }
    LaunchedEffect(tiles, audioIndex) { players.setAudio(tiles.getOrNull(audioIndex)?.channelId) }
    // Keep the screen on while a tile is playing, the way upstream's playback does.
    val anyPlaying = players.playback.values.any { it.playing }
    LaunchedEffect(anyPlaying) { viewModel.setKeepScreenOn(anyPlaying) }
    DisposableEffect(players) { onDispose { viewModel.setKeepScreenOn(false) } }

    // The game shown full screen, by channel so a change in the queue cannot retarget it.
    var fullChannelId by remember { mutableStateOf<String?>(null) }
    val fullIndex = tiles.indexOfFirst { it.channelId == fullChannelId }
    BackHandler(enabled = fullIndex >= 0) { fullChannelId = null }
    var editing by remember { mutableStateOf(false) }
    var actionsChannelId by remember { mutableStateOf<String?>(null) }

    val playback = { channelId: String -> players.playback[channelId] ?: MultiviewTilePlayback() }
    val onDoubleTap = { index: Int ->
        val channelId = tiles.getOrNull(index)?.channelId
        viewModel.focusTile(index)
        fullChannelId = if (fullChannelId == channelId) null else channelId
    }

    Box(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        if (fullIndex >= 0) {
            // Full screen: the picture as large as the screen allows, on black; the tile keeps its sound tag.
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(TallyColors.screen)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val tile = tiles[fullIndex]
                key(tile.channelId) {
                    PhoneMultiviewTile(
                        tile = tile,
                        playback = playback(tile.channelId),
                        hasAudio = fullIndex == audioIndex,
                        hideScores = hideScores,
                        onTap = { viewModel.focusTile(fullIndex) },
                        onDoubleTap = { onDoubleTap(fullIndex) },
                        onLongPress = { actionsChannelId = tile.channelId },
                        labelBar = false,
                        modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                    )
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = PhoneDimens.margin),
            ) {
                MultiviewBar(
                    count = tiles.size,
                    showHint = tiles.isNotEmpty(),
                    onBack = viewModel::close,
                    onEdit = { editing = true },
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 8.dp),
                ) {
                    if (tiles.isEmpty()) {
                        PhoneEmptyState(
                            title = stringResource(R.string.tally_mv_empty_title),
                            subtitle = stringResource(R.string.tally_phone_sports_mv_empty_sub),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        MultiviewGrid(
                            tiles = tiles,
                            audioIndex = audioIndex,
                            hideScores = hideScores,
                            playback = playback,
                            onTap = viewModel::focusTile,
                            onDoubleTap = onDoubleTap,
                            onLongPress = { index -> actionsChannelId = tiles.getOrNull(index)?.channelId },
                        )
                    }
                }
            }
        }
    }

    if (editing) {
        MultiviewEditSheet(
            tiles = tiles,
            bench = bench,
            hideScores = hideScores,
            onRemove = viewModel::removeTile,
            onAdd = viewModel::swapIn,
            onDismiss = { editing = false },
        )
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
                    removeFromMultiview = {
                        if (fullChannelId == actionsTile.channelId) fullChannelId = null
                        viewModel.removeTile(actionsIndex)
                    },
                ),
            onDismiss = { actionsChannelId = null },
        )
    }
}

@Composable
private fun MultiviewBar(
    count: Int,
    showHint: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(BarHeight + 8.dp),
    ) {
        PhoneTopBarAction(
            glyph = R.string.tally_phone_fa_arrow_left,
            label = stringResource(R.string.tally_phone_back),
            onClick = onBack,
            modifier = Modifier.offset(x = (-12).dp),
        )
        Text(
            text = stringResource(R.string.tally_phone_sports_multiview).tallyUppercase(),
            style = PhoneType.labelLarge,
            color = TallyColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count / ${TallyMultiviewState.MAX}",
            style = PhoneType.labelLarge,
            color = TallyColors.muted,
            maxLines = 1,
        )
        Spacer(Modifier.width(20.dp))
        if (showHint) {
            Text(
                text = stringResource(R.string.tally_phone_sports_mv_hint).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        PhoneButton(
            label = stringResource(R.string.tally_phone_sports_mv_edit),
            glyph = stringResource(R.string.tally_phone_sports_fa_plus),
            onClick = onEdit,
        )
    }
}

/** The tiles: one alone, two side by side, three or four in a 2x2 grid, as large as the area allows, centered. */
@Composable
private fun MultiviewGrid(
    tiles: List<MultiviewTile>,
    audioIndex: Int,
    hideScores: Boolean,
    playback: (String) -> MultiviewTilePlayback,
    onTap: (Int) -> Unit,
    onDoubleTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val count = tiles.size
        val columns = if (count == 1) 1 else 2
        val rows = if (count <= 2) 1 else 2
        val byWidth = (maxWidth - TileGap * (columns - 1)) / columns
        val byHeight = ((maxHeight - TileGap * (rows - 1)) / rows - PhoneTileLabelHeight) * (16f / 9f)
        val tileWidth: Dp = minOf(byWidth, byHeight)
        val tileHeight = tileWidth * (9f / 16f) + PhoneTileLabelHeight
        val gridWidth = tileWidth * columns + TileGap * (columns - 1)
        val gridHeight = tileHeight * rows + TileGap * (rows - 1)
        val left = (maxWidth - gridWidth) / 2
        val top = (maxHeight - gridHeight) / 2
        tiles.forEachIndexed { index, tile ->
            val column = if (count == 1) 0 else index % 2
            val row = if (count <= 2) 0 else index / 2
            key(tile.channelId) {
                PhoneMultiviewTile(
                    tile = tile,
                    playback = playback(tile.channelId),
                    hasAudio = index == audioIndex,
                    hideScores = hideScores,
                    onTap = { onTap(index) },
                    onDoubleTap = { onDoubleTap(index) },
                    onLongPress = { onLongPress(index) },
                    modifier =
                        Modifier
                            .offset(x = left + (tileWidth + TileGap) * column, y = top + (tileHeight + TileGap) * row)
                            .width(tileWidth),
                )
            }
        }
    }
}

/**
 * Adding and removing on a phone: a sheet with the games on screen (REMOVE), the live games on your channels and the
 * other channels (ADD; SWAP IN once four are on screen, which replaces the tile with the sound, as the TV rail does).
 */
@Composable
private fun MultiviewEditSheet(
    tiles: List<MultiviewTile>,
    bench: List<MultiviewBenchEntry>,
    hideScores: Boolean,
    onRemove: (Int) -> Unit,
    onAdd: (MultiviewBenchEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val full = tiles.size >= TallyMultiviewState.MAX
    val live = bench.filter { it.game != null }
    val channels = bench.filter { it.game == null }
    PhoneSheet(onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (tiles.isNotEmpty()) {
                item(key = "on-screen") { SheetHeader(stringResource(R.string.tally_phone_sports_mv_on_screen)) }
                itemsIndexed(tiles, key = { _, tile -> "tile-" + tile.channelId }) { index, tile ->
                    EditRow(
                        name = tile.name.ifBlank { stringResource(R.string.tally_mv_unknown_channel) },
                        game = tile.game,
                        hideScores = hideScores,
                        action = stringResource(R.string.tally_phone_sports_remove),
                        onAction = { onRemove(index) },
                    )
                }
            }
            if (live.isNotEmpty()) {
                item(key = "live") { SheetHeader(stringResource(R.string.tally_phone_sports_mv_live)) }
                itemsIndexed(live, key = { _, entry -> "live-" + entry.channelId }) { _, entry ->
                    EditRow(
                        name = entry.name,
                        game = entry.game,
                        hideScores = hideScores,
                        action = stringResource(if (full) R.string.tally_mv_swap_in else R.string.tally_phone_sports_mv_add),
                        onAction = { onAdd(entry) },
                    )
                }
            }
            if (channels.isNotEmpty()) {
                item(key = "channels") { SheetHeader(stringResource(R.string.tally_phone_sports_mv_channels)) }
                itemsIndexed(channels, key = { _, entry -> "channel-" + entry.channelId }) { _, entry ->
                    EditRow(
                        name = entry.name,
                        game = null,
                        hideScores = hideScores,
                        action = stringResource(if (full) R.string.tally_mv_swap_in else R.string.tally_phone_sports_mv_add),
                        onAction = { onAdd(entry) },
                    )
                }
            }
            item(key = "end") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Text(
        text = title.tallyUppercase(),
        style = PhoneType.label,
        color = TallyColors.muted,
        maxLines = 1,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 12.dp, bottom = 4.dp),
    )
}

/** A sheet row: the game (league · status over the matchup and its score) or the channel's name, and its action. */
@Composable
private fun EditRow(
    name: String,
    game: TallyGame?,
    hideScores: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    PhoneDialogRow(
        onClick = onAction,
        overline =
            game?.let {
                {
                    Text(
                        text =
                            listOf(it.league, gameStatusLabel(it))
                                .filter { part ->
                                    part.isNotBlank()
                                }.joinToString(" · ")
                                .tallyUppercase(),
                        color = if (it.isLive) TallyColors.accent else TallyColors.muted,
                        maxLines = 1,
                    )
                }
            },
        headline = {
            val text =
                if (game != null) {
                    val score = if (!hideScores && game.isLive) "  ${game.away.score ?: "\u2013"}-${game.home.score ?: "\u2013"}" else ""
                    stringResource(
                        R.string.tally_actions_at,
                        game.away.shortName.ifBlank { game.away.abbr },
                        game.home.shortName.ifBlank { game.home.abbr },
                    ) + score
                } else {
                    name
                }
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailing = { Text(text = action.tallyUppercase(), maxLines = 1) },
    )
}
