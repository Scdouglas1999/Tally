package com.github.damontecres.wholphin.jellytv.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvTeam
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.tryRequestFocus
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
    @param:StringRes val watchLabel: Int = R.string.jtv_actions_watch,
)

/**
 * Builds [GameActions] from a game. Watch needs a resolved Live TV item; multiview needs a channel.
 * [onWatchInCorner] is null everywhere except the player's switcher, which is the only place that row appears.
 */
fun gameActions(
    game: JtvGame,
    favoriteTeams: Set<String>,
    hideScores: Boolean,
    onWatch: (JtvGame) -> Unit,
    onAddToMultiview: (JtvGame) -> Unit,
    onWatchInCorner: ((JtvGame) -> Unit)?,
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
)

/**
 * The long-press menu for a game: a centred JellyTV panel over a 60% black scrim.
 * Watch, multiview, corner and remove close the dialog after they run. Follow and hide-scores toggle in place.
 * Focus starts on the first row, stays inside the list, and BACK dismisses.
 *
 * [game] is null for a channel with no game resolved to it (a multiview tile between games); the
 * header then shows [channelName] and the follow rows are left out.
 */
@Composable
fun GameActionsDialog(
    game: JtvGame?,
    actions: GameActions,
    onDismiss: () -> Unit,
    channelName: String = "",
) {
    val away = game?.away?.menuName().orEmpty()
    val home = game?.home?.menuName().orEmpty()
    val lines =
        buildList {
            actions.watch?.let { watch ->
                add(ActionLine(stringResource(actions.watchLabel), dismissOnClick = true, onClick = watch))
            }
            actions.addToMultiview?.let { addToMultiview ->
                add(
                    ActionLine(
                        stringResource(R.string.jtv_actions_multiview),
                        dismissOnClick = true,
                        onClick = addToMultiview,
                    ),
                )
            }
            actions.watchInCorner?.let { watchInCorner ->
                add(
                    ActionLine(
                        stringResource(R.string.jtv_actions_corner),
                        dismissOnClick = true,
                        onClick = watchInCorner,
                    ),
                )
            }
            if (game != null) {
                actions.followAway?.let { followAway ->
                    add(
                        ActionLine(
                            label =
                                stringResource(
                                    if (actions.followedAway) R.string.jtv_actions_following else R.string.jtv_actions_follow,
                                    away,
                                ),
                            dismissOnClick = false,
                            onClick = followAway,
                        ),
                    )
                }
                actions.followHome?.let { followHome ->
                    add(
                        ActionLine(
                            label =
                                stringResource(
                                    if (actions.followedHome) R.string.jtv_actions_following else R.string.jtv_actions_follow,
                                    home,
                                ),
                            dismissOnClick = false,
                            onClick = followHome,
                        ),
                    )
                }
            }
            add(
                ActionLine(
                    label =
                        stringResource(
                            if (actions.hideScores) R.string.jtv_actions_show_scores else R.string.jtv_actions_hide_scores,
                        ),
                    dismissOnClick = false,
                    onClick = actions.toggleHideScores,
                ),
            )
            actions.removeFromMultiview?.let { remove ->
                add(
                    ActionLine(
                        stringResource(R.string.jtv_actions_remove_multiview),
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
                        .background(JtvColors.ground)
                        .border(JtvDimens.hairline, JtvColors.rule)
                        .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (game != null) {
                        Text(
                            text = "${game.league.uppercase()}   ${gameStatusLabel(game)}",
                            style = JtvType.label,
                            color = if (game.isLive) JtvColors.accent else JtvColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = if (game != null) stringResource(R.string.jtv_actions_at, away, home) else channelName,
                        style = dialogTitle,
                        color = JtvColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    lines.forEachIndexed { index, line ->
                        JtvRow(
                            label = line.label,
                            onClick = {
                                if (!acceptClicks) return@JtvRow
                                line.onClick()
                                if (line.dismissOnClick) onDismiss()
                            },
                            trailing = {
                                KeyHint(
                                    key = stringResource(R.string.jtv_key_ok),
                                    label = "",
                                )
                            },
                            modifier =
                                Modifier
                                    .then(if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                                    .focusProperties {
                                        if (index == 0) up = FocusRequester.Cancel
                                        if (index == lines.lastIndex) down = FocusRequester.Cancel
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                    },
                        )
                    }
                }
            }
        }
    }
}

private fun JtvTeam.menuName(): String = shortName.ifBlank { abbr.ifBlank { name } }

private const val CLICK_ARM_MS = 400L

private val dialogTitle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

@PreviewTvSpec
@Composable
private fun GameActionsDialogPreview() {
    JtvSurface {
        GameActionsDialog(
            game = JtvSamples.liveFootball,
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
