package io.github.scdouglas1999.tally.ui.player.phone

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBarAction

/** A button the live player puts in the phone controls' top bar: GAMES (the switcher) or BOX SCORE. */
data class LiveTopBarAction(
    @param:StringRes val glyph: Int,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The live player's buttons for the phone controls' top bar, provided by the live player on a phone around upstream's
 * player ([LocalLiveTopBarActions]). The shared phone controls draw them with [LiveTopBarButtons].
 */
class LiveTopBarActions {
    var actions by mutableStateOf<List<LiveTopBarAction>>(emptyList())
}

/** Null everywhere except inside the live player on a phone. */
val LocalLiveTopBarActions = staticCompositionLocalOf<LiveTopBarActions?> { null }

/**
 * For the phone player controls' top bar, before its subtitles and settings buttons: the live player's buttons as
 * 48dp [PhoneTopBarAction]s. Draws nothing outside the live player.
 */
@Composable
fun LiveTopBarButtons() {
    val holder = LocalLiveTopBarActions.current ?: return
    holder.actions.forEach { action ->
        PhoneTopBarAction(glyph = action.glyph, label = action.label, onClick = action.onClick)
    }
}
