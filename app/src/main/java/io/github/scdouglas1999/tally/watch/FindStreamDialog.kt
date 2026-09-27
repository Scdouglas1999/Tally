package io.github.scdouglas1999.tally.watch

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.settings.FOCUS_ROOM
import io.github.scdouglas1999.tally.ui.settings.OPEN_GRACE_MS
import io.github.scdouglas1999.tally.ui.settings.TallyPanelFrame
import io.github.scdouglas1999.tally.ui.settings.TallyPanelWindow
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay

/** Draws [TallyWatchLauncher]'s find dialog while it is open (from the global overlays, above every screen). */
@Composable
fun FindStreamHost(launcher: TallyWatchLauncher) {
    val state by launcher.state.collectAsStateWithLifecycle()
    val current = state ?: return
    FindStreamDialog(state = current, onCancel = launcher::cancel, onKeepLooking = launcher::keepLooking)
}

/**
 * "Looking for a stream…": WATCH on a game no channel carries yet. A small Tally panel on a TV (focus on Cancel;
 * BACK cancels), a bottom sheet on a phone. It shows the game and a sputtering lamp while the server looks; when the
 * round ends without a stream it says so and offers OK (focused) and Keep looking (another round), as tv-web does. A
 * stream that appears plays at once and the panel closes (the launcher closes it). Presses in the first 400 ms are
 * ignored, so the OK that opened the panel does not also press a button.
 */
@Composable
internal fun FindStreamDialog(
    state: TallyWatchLauncher.State,
    onCancel: () -> Unit,
    onKeepLooking: () -> Unit,
) {
    val searching = state is TallyWatchLauncher.State.Searching
    val kicker =
        stringResource(if (searching) R.string.tally_221_find_searching else R.string.tally_221_find_none_kicker)
    // The key-up of the OK that opened the panel (or ended the round) must not press the button that takes focus.
    val shownAt = remember(searching) { SystemClock.elapsedRealtime() }
    val guarded = { action: () -> Unit -> { if (SystemClock.elapsedRealtime() - shownAt >= OPEN_GRACE_MS) action() } }
    TallyPanelWindow(onDismissRequest = onCancel) {
        TallyPanelFrame(kicker = kicker, onBack = onCancel, width = 520.dp, trapHorizontal = false) {
            if (isPhone()) {
                PhoneFindContent(state.game, searching, onCancel = guarded(onCancel), onKeepLooking = guarded(onKeepLooking))
            } else {
                TvFindContent(state.game, searching, onCancel = guarded(onCancel), onKeepLooking = guarded(onKeepLooking))
            }
        }
    }
}

@Composable
private fun TvFindContent(
    game: TallyGame,
    searching: Boolean,
    onCancel: () -> Unit,
    onKeepLooking: () -> Unit,
) {
    // One first button in both states (CANCEL while looking, OK after), so focus stays on it when the state
    // changes; Keep looking hands focus to it before the round starts (a focused button that leaves the panel
    // takes focus out of the dialog with it).
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        repeat(20) {
            if (focused) return@LaunchedEffect
            focus.tryRequestFocus("tally-find-stream")
            delay(50)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp).padding(top = FOCUS_ROOM),
    ) {
        GameLine(game = game, searching = searching, titleStyle = tvTitle, labelStyle = TallyType.label, mark = 12.dp)
        if (!searching) {
            Text(text = stringResource(R.string.tally_221_find_none), style = tvMessage, color = TallyColors.textSecondary)
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp),
    ) {
        TallyButton(
            label = stringResource(if (searching) R.string.cancel else R.string.tally_221_ok),
            onClick = onCancel,
            primary = !searching,
            modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused },
        )
        if (!searching) {
            TallyButton(
                label = stringResource(R.string.tally_221_keep_looking),
                onClick = {
                    focus.tryRequestFocus("tally-find-stream-keep")
                    onKeepLooking()
                },
            )
        }
    }
}

@Composable
private fun PhoneFindContent(
    game: TallyGame,
    searching: Boolean,
    onCancel: () -> Unit,
    onKeepLooking: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 16.dp),
    ) {
        GameLine(game = game, searching = searching, titleStyle = PhoneType.headline, labelStyle = PhoneType.label, mark = 10.dp)
        if (!searching) {
            Text(text = stringResource(R.string.tally_221_find_none), style = PhoneType.body, color = TallyColors.textSecondary)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 20.dp, bottom = 8.dp),
    ) {
        if (searching) {
            PhoneButton(label = stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
        } else {
            PhoneButton(
                label = stringResource(R.string.tally_221_ok),
                onClick = onCancel,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PhoneButton(
                label = stringResource(R.string.tally_221_keep_looking),
                onClick = onKeepLooking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The game: a sputtering lamp while the server looks (an unlit square after), the matchup, league and status. */
@Composable
private fun GameLine(
    game: TallyGame,
    searching: Boolean,
    titleStyle: TextStyle,
    labelStyle: TextStyle,
    mark: androidx.compose.ui.unit.Dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (searching) {
            TallyLamp(state = LampState.Sputtering, size = mark, glow = false)
        } else {
            IndicatorSquare(color = TallyColors.ruleStrong, size = mark)
        }
        Text(
            text = matchup(game),
            style = titleStyle,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(2.dp))
    Text(
        text = listOf(game.league, gameStatusLabel(game)).filter { it.isNotBlank() }.joinToString(" · ").tallyUppercase(),
        style = labelStyle,
        color = if (game.isLive) TallyColors.accent else TallyColors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun matchup(game: TallyGame): String {
    val away = game.away.menuName()
    val home = game.home.menuName()
    return if (away.isBlank() || home.isBlank()) game.name else stringResource(R.string.tally_actions_at, away, home)
}

private fun TallyTeam.menuName(): String = shortName.ifBlank { abbr.ifBlank { name } }

private val tvTitle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    )

private val tvMessage =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )
