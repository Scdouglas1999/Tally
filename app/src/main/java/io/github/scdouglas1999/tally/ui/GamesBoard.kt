package io.github.scdouglas1999.tally.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.data.BoardOrganizer
import io.github.scdouglas1999.tally.data.BoardRow
import io.github.scdouglas1999.tally.data.isFollowed
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.FocusedGamePanel
import io.github.scdouglas1999.tally.ui.components.GameActionsDialog
import io.github.scdouglas1999.tally.ui.components.GameCard
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.gameActions
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch

/**
 * The games board: the large [FocusedGamePanel] mirroring the focused card on top,
 * then vertically scrolling rows of [GameCard]s (one per league + state).
 *
 * Focus behavior (mirrors upstream rows of cards):
 *  - on open (and on return from the player), focus lands on the card at the
 *    remembered position, defaulting to the first card of the first row;
 *  - each row restores its own last-focused card when re-entered from above/below;
 *  - Up from the first row reaches the top bar (the panel is not focusable).
 */
@Composable
fun GamesBoard(
    rows: List<BoardRow>,
    favorites: Set<String>,
    hideScores: Boolean,
    loading: Boolean,
    hasBoard: Boolean,
    boardError: String?,
    feedErrors: Map<String, String>,
    hasGames: Boolean,
    onWatch: (TallyGame) -> Unit,
    onAddToMultiview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedPosition by rememberPosition()
    var menuGameId by rememberSaveable { mutableStateOf<String?>(null) }
    val boardFocusRequester = remember { FocusRequester() }
    val menuReturnFocus = remember { FocusRequester() }
    // The page that hosts this board cannot grow the parameter list, so follow/hide read the same
    // view model the page already owns (one store, one instance).
    val viewModel: TallyViewModel = hiltViewModel()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val teams = ui.favoriteTeams

    val focusedGame =
        remember(rows, focusedGameId) {
            rows.asSequence().flatMap { it.games }.firstOrNull { it.id == focusedGameId }
                ?: rows.firstOrNull()?.games?.firstOrNull()
        }

    // When the board appears or reappears (page open, back from the player), put
    // focus on the card at the remembered position — the one that was played.
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty()) {
            boardFocusRequester.tryRequestFocus("jellytv-games")
        }
    }

    val menuGame = rows.asSequence().flatMap { it.games }.firstOrNull { it.id == menuGameId }
    BackHandler(enabled = menuGame != null) {
        menuReturnFocus.tryRequestFocus("jtv-actions-return")
        menuGameId = null
    }
    Column(modifier = modifier.fillMaxSize()) {
        FocusedGamePanel(
            game = focusedGame,
            hideScores = hideScores,
            modifier =
                Modifier
                    .padding(horizontal = TallyDimens.marginHorizontal)
                    .padding(top = 16.dp),
        )
        if (feedErrors.isNotEmpty()) {
            FeedErrorNotice(
                feedErrors = feedErrors,
                modifier =
                    Modifier
                        .padding(horizontal = TallyDimens.marginHorizontal)
                        .padding(top = 12.dp),
            )
        }
        val emptyModifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = 16.dp, bottom = TallyDimens.marginVertical)
        when {
            loading -> {
                EmptyState(
                    title = stringResource(R.string.tally_loading_games),
                    subtitle = "",
                    modifier = emptyModifier,
                )
            }

            boardError != null && !hasBoard -> {
                EmptyState(
                    title = stringResource(R.string.tally_board_failed_title),
                    subtitle = boardError,
                    modifier = emptyModifier,
                )
            }

            rows.isEmpty() && hasGames -> {
                EmptyState(
                    title = stringResource(R.string.tally_empty_filtered_title),
                    subtitle = stringResource(R.string.tally_empty_filtered_sub),
                    modifier = emptyModifier,
                )
            }

            rows.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.tally_empty_games_title),
                    subtitle = stringResource(R.string.tally_empty_games_sub),
                    modifier = emptyModifier,
                )
            }

            else -> {
                val targetRow = if (focusedPosition.row in rows.indices) focusedPosition.row else 0
                val targetColumn =
                    if (focusedPosition.column in (rows.getOrNull(targetRow)?.games?.indices ?: IntRange.EMPTY)) {
                        focusedPosition.column
                    } else {
                        0
                    }
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 16.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { rowIndex, row ->
                        GameRow(
                            isFirstRow = rowIndex == 0,
                            row = row,
                            favorites = favorites,
                            hideScores = hideScores,
                            boardFocusIndex = if (rowIndex == targetRow) targetColumn else -1,
                            boardFocusRequester = boardFocusRequester,
                            onCardFocused = { index, game ->
                                focusedGameId = game.id
                                focusedPosition = RowColumn(rowIndex, index)
                                // Keep the focused row's header at the top of the list, not its card at the bottom edge.
                                scope.launch { listState.animateScrollToItem(rowIndex) }
                            },
                            onWatch = onWatch,
                            onAddToMultiview = onAddToMultiview,
                            favoriteTeams = teams,
                            menuGameId = menuGameId,
                            menuReturnFocus = menuReturnFocus,
                            onLongClick = { menuGameId = it.id },
                        )
                    }
                }
            }
        }
    }
    if (menuGame != null) {
        GameActionsDialog(
            game = menuGame,
            actions =
                gameActions(
                    game = menuGame,
                    favoriteTeams = teams,
                    hideScores = hideScores,
                    onWatch = onWatch,
                    onAddToMultiview = { game -> game.watch?.channelId?.let(onAddToMultiview) },
                    onWatchInCorner = null,
                    onToggleFollow = viewModel::toggleFollow,
                    onToggleHideScores = { viewModel.setHideScores(!hideScores) },
                ),
            onDismiss = {
                menuReturnFocus.tryRequestFocus("jtv-actions-return")
                menuGameId = null
            },
        )
    }
}

/**
 * One board row: a [RowHeader] ("NFL / LIVE") over a [LazyRow] of [GameCard]s that
 * scrolls edge to edge but rests inside the safe margins.
 */
@Composable
private fun GameRow(
    isFirstRow: Boolean,
    row: BoardRow,
    favorites: Set<String>,
    hideScores: Boolean,
    boardFocusIndex: Int,
    boardFocusRequester: FocusRequester,
    onCardFocused: (Int, TallyGame) -> Unit,
    onWatch: (TallyGame) -> Unit,
    onAddToMultiview: (String) -> Unit,
    favoriteTeams: Set<String>,
    menuGameId: String?,
    menuReturnFocus: FocusRequester,
    onLongClick: (TallyGame) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var position by rememberInt()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier.focusProperties {
                onEnter = {
                    rowFocus.tryRequestFocus()
                }
            },
    ) {
        RowHeader(
            title = "${row.league} / ${rowStateLabel(row.state)}",
            count = row.games.size,
            modifier = Modifier.padding(horizontal = TallyDimens.marginHorizontal),
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(TallyDimens.cardGap),
            contentPadding = PaddingValues(horizontal = TallyDimens.marginHorizontal),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(firstFocus)
                    .focusRequester(rowFocus)
                    .then(if (isFirstRow) Modifier.upToTab() else Modifier),
        ) {
            itemsIndexed(row.games, key = { _, game -> game.id }) { index, game ->
                GameCard(
                    game = game,
                    hideScores = hideScores,
                    isFavorite = game.watch?.channelId in favorites || game.isFollowed(favoriteTeams),
                    followed = game.isFollowed(favoriteTeams),
                    onClick = { onWatch(game) },
                    onLongClick = { onLongClick(game) },
                    onFocused = {
                        position = index
                        onCardFocused(index, game)
                    },
                    modifier =
                        Modifier
                            .ifElse(index == position, Modifier.focusRequester(firstFocus))
                            .ifElse(index == boardFocusIndex, Modifier.focusRequester(boardFocusRequester))
                            .ifElse(game.id == menuGameId, Modifier.focusRequester(menuReturnFocus)),
                )
            }
        }
    }
}

@Composable
private fun rowStateLabel(state: String): String =
    when (state) {
        "in" -> stringResource(R.string.tally_state_live)
        "pre" -> stringResource(R.string.tally_state_upcoming)
        "post" -> stringResource(R.string.tally_final)
        BoardOrganizer.POSTPONED -> stringResource(R.string.tally_polish2_state_postponed)
        else -> state
    }

/**
 * One-line, red-bordered notice naming the leagues whose feeds failed. Shown above
 * the rows; the rest of the board still renders.
 */
@Composable
private fun FeedErrorNotice(
    feedErrors: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .border(TallyDimens.hairline, TallyColors.live)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_feeds_failed, feedErrors.keys.joinToString(", ")),
            style = TallyType.label,
            color = TallyColors.liveText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
