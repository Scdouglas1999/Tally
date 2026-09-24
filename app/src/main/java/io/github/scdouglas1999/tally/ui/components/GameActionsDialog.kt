package io.github.scdouglas1999.tally.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.ui.DvrKeepLastTvDialog
import io.github.scdouglas1999.tally.dvr.ui.DvrMenuLine
import io.github.scdouglas1999.tally.dvr.ui.DvrTvMenuRows
import io.github.scdouglas1999.tally.dvr.ui.dvrGameMenuLines
import io.github.scdouglas1999.tally.dvr.ui.dvrTeamMenuLine
import io.github.scdouglas1999.tally.dvr.ui.rememberGameDvr
import io.github.scdouglas1999.tally.ui.components.phone.PhoneGameSheet
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay

/**
 * What can be done with a game from wherever it is shown. A null action is not offered.
 * [followedAway]/[followedHome] say whether each team is currently followed (the action toggles it).
 * [watchLabel] replaces "Watch" where the game is already on screen (multiview: "Watch full screen").
 */
data class GameActions(
    val watch: (() -> Unit)? = null,
    val addToMultiview: (() -> Unit)? = null,
    val watchInCorner: (() -> Unit)? = null,
    val followAway: (() -> Unit)? = null,
    val followHome: (() -> Unit)? = null,
    val followedAway: Boolean = false,
    val followedHome: Boolean = false,
    val hideScores: Boolean,
    val toggleHideScores: () -> Unit,
    val removeFromMultiview: (() -> Unit)? = null,
    @param:StringRes val watchLabel: Int = R.string.tally_actions_watch,
)

/**
 * Builds [GameActions] from a game. Watch needs a resolved Live TV item; multiview needs a channel.
 * [onWatchInCorner] is null everywhere except the player's switcher, which is the only place that row appears.
 */
fun gameActions(
    game: TallyGame,
    favoriteTeams: Set<String>,
    hideScores: Boolean,
    onWatch: (TallyGame) -> Unit,
    onAddToMultiview: (TallyGame) -> Unit,
    onWatchInCorner: ((TallyGame) -> Unit)?,
    onToggleFollow: (String) -> Unit,
    onToggleHideScores: () -> Unit,
): GameActions {
    val teams = favoriteTeams.map { it.uppercase() }.toSet()
    val channelId = game.watch?.channelId?.takeIf { it.isNotBlank() }
    return GameActions(
        watch = if (!game.watch?.liveTvItemId.isNullOrBlank()) ({ onWatch(game) }) else null,
        addToMultiview = if (channelId != null) ({ onAddToMultiview(game) }) else null,
        watchInCorner = onWatchInCorner?.let { callback -> { callback(game) } },
        followAway = { onToggleFollow(game.teamKey(game.away)) },
        followHome = { onToggleFollow(game.teamKey(game.home)) },
        followedAway = game.teamKey(game.away) in teams,
        followedHome = game.teamKey(game.home) in teams,
        hideScores = hideScores,
        toggleHideScores = onToggleHideScores,
    )
}

private data class ActionLine(
    val label: String,
    val dismissOnClick: Boolean,
    val onClick: () -> Unit,
    val dvr: DvrMenuLine? = null,
) {
    val menuLine: DvrMenuLine get() = dvr ?: DvrMenuLine(label = label, dismiss = dismissOnClick, onClick = onClick)
}

private fun DvrMenuLine.toActionLine(): ActionLine =
    ActionLine(label = label.orEmpty(), dismissOnClick = dismiss, onClick = onClick ?: {}, dvr = this)

/**
 * The long-press menu for a game: a centered Tally panel over a 60% black scrim.
 * Watch, multiview, corner and remove close the dialog after they run. Follow and hide-scores toggle in place.
 * Focus starts on the first row, stays inside the list, and BACK dismisses.
 *
 * [game] is null for a channel with no game resolved to it (a multiview tile between games); the
 * header then shows [channelName] and the follow rows are left out.
 */
@Composable
fun GameActionsDialog(
    game: TallyGame?,
    actions: GameActions,
    onDismiss: () -> Unit,
    channelName: String = "",
) {
    // The server DVR's part of the menu (null when the server does not record).
    val dvr = rememberGameDvr(game)
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        // The game sheet: the game's panel, WATCH, then these actions (no corner view on a phone).
        PhoneGameSheet(
            game = game,
            actions = actions.copy(watchInCorner = null),
            onDismiss = onDismiss,
            channelName = channelName,
            dvr = dvr,
        )
        return
    }
    val away = game?.away?.menuName().orEmpty()
    val home = game?.home?.menuName().orEmpty()
    // No spoilers: a finished game with a recording shows its score only when asked.
    var scoreShown by remember(game?.id) { mutableStateOf(false) }
    var keepTeam by remember { mutableStateOf<TallyTeam?>(null) }
    val dvrLines = dvr?.let { dvrGameMenuLines(it) }.orEmpty()
    val awayRuleLine = dvr?.let { dvrTeamMenuLine(it, it.game.away) { team -> keepTeam = team } }
    val homeRuleLine = dvr?.let { dvrTeamMenuLine(it, it.game.home) { team -> keepTeam = team } }
    val lines =
        buildList {
            dvr?.watchableItemId?.takeIf { dvr.guarded }?.let {
                add(
                    ActionLine(
                        stringResource(R.string.tally_dvr_watch_recording),
                        dismissOnClick = true,
                        onClick = dvr::watchRecording,
                    ),
                )
            }
            actions.watch?.let { watch ->
                add(ActionLine(stringResource(actions.watchLabel), dismissOnClick = true, onClick = watch))
            }
            actions.addToMultiview?.let { addToMultiview ->
                add(
                    ActionLine(
                        stringResource(R.string.tally_actions_multiview),
                        dismissOnClick = true,
                        onClick = addToMultiview,
                    ),
                )
            }
            actions.watchInCorner?.let { watchInCorner ->
                add(
                    ActionLine(
                        stringResource(R.string.tally_actions_corner),
                        dismissOnClick = true,
                        onClick = watchInCorner,
                    ),
                )
            }
            dvrLines.forEach { add(it.toActionLine()) }
            if (game != null) {
                actions.followAway?.let { followAway ->
                    add(
                        ActionLine(
                            label =
                                stringResource(
                                    if (actions.followedAway) R.string.tally_actions_following else R.string.tally_actions_follow,
                                    away,
                                ),
                            dismissOnClick = false,
                            onClick = followAway,
                        ),
                    )
                }
                awayRuleLine?.let { add(it.toActionLine()) }
                actions.followHome?.let { followHome ->
                    add(
                        ActionLine(
                            label =
                                stringResource(
                                    if (actions.followedHome) R.string.tally_actions_following else R.string.tally_actions_follow,
                                    home,
                                ),
                            dismissOnClick = false,
                            onClick = followHome,
                        ),
                    )
                }
                homeRuleLine?.let { add(it.toActionLine()) }
            }
            add(
                ActionLine(
                    label =
                        stringResource(
                            if (actions.hideScores) R.string.tally_actions_show_scores else R.string.tally_actions_hide_scores,
                        ),
                    dismissOnClick = false,
                    onClick = actions.toggleHideScores,
                ),
            )
            if (dvr?.guarded == true) {
                add(
                    ActionLine(
                        label =
                            stringResource(
                                if (scoreShown) R.string.tally_dvr_hide_the_score else R.string.tally_dvr_show_the_score,
                            ),
                        dismissOnClick = false,
                        onClick = { scoreShown = !scoreShown },
                    ),
                )
            }
            actions.removeFromMultiview?.let { remove ->
                add(
                    ActionLine(
                        stringResource(R.string.tally_actions_remove_multiview),
                        dismissOnClick = true,
                        onClick = remove,
                    ),
                )
            }
        }
    val firstRow = remember { FocusRequester() }
    // The long-press that opened the menu ends with a key-up. If the first row is already
    // focused, that key-up clicks it. Ignore clicks until the hold is over, then focus.
    var acceptClicks by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(CLICK_ARM_MS)
        acceptClicks = true
        var attempts = 0
        while (!firstRow.tryRequestFocus("jtv-game-actions") && attempts < 8) {
            attempts++
            delay(50)
        }
    }
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.decorView.elevation = 0f
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier =
                    Modifier
                        .width(560.dp)
                        .heightIn(max = MENU_MAX_HEIGHT)
                        .background(TallyColors.ground)
                        .border(TallyDimens.hairline, TallyColors.rule)
                        .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (game != null) {
                        Text(
                            text = "${game.league.uppercase()}   ${gameStatusLabel(game)}",
                            style = TallyType.label,
                            color = if (game.isLive) TallyColors.accent else TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = if (game != null) stringResource(R.string.tally_actions_at, away, home) else channelName,
                        style = dialogTitle,
                        color = TallyColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (game != null && scoreShown) {
                        Text(
                            text = "${game.away.menuName()} ${game.away.score ?: 0}  ·  ${game.home.menuName()} ${game.home.score ?: 0}",
                            style = TallyType.label,
                            color = TallyColors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                // DVR lines carry an info line above their row (a job's state, the estimate) and can be disabled or
                // info only; the rows scroll when the menu is taller than the screen.
                DvrTvMenuRows(
                    lines = lines.map { it.menuLine },
                    firstRowFocus = firstRow,
                    acceptClicks = { acceptClicks },
                    onDismiss = onDismiss,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
    val keepFor = keepTeam
    if (dvr != null && keepFor != null) {
        DvrKeepLastTvDialog(
            team = keepFor,
            rule = dvr.ruleFor(keepFor),
            onChoose = { keepLast -> dvr.recordTeam(keepFor, keepLast) },
            onDelete = { dvr.ruleFor(keepFor)?.let(dvr::deleteRule) },
            onDismiss = { keepTeam = null },
        )
    }
}

private fun TallyTeam.menuName(): String = shortName.ifBlank { abbr.ifBlank { name } }

private val MENU_MAX_HEIGHT = 500.dp

private const val CLICK_ARM_MS = 400L

private val dialogTitle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

@PreviewTvSpec
@Composable
private fun GameActionsDialogPreview() {
    TallySurface {
        GameActionsDialog(
            game = TallySamples.liveFootball,
            actions =
                GameActions(
                    watch = {},
                    addToMultiview = {},
                    followAway = {},
                    followHome = {},
                    followedAway = false,
                    followedHome = true,
                    hideScores = false,
                    toggleHideScores = {},
                ),
            onDismiss = {},
        )
    }
}
