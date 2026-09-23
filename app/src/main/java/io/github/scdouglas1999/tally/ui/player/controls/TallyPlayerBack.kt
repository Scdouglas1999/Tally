package io.github.scdouglas1999.tally.ui.player.controls

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor

/**
 * BACK in the player (seam W37 at the top of `PlaybackPageContent`), in the Tally look.
 *
 * The app targets API 36+, where BACK is predictive: while any back callback is enabled (the navigation's own always
 * is, below the player) the system hands BACK to the callbacks and the key never reaches the views. Upstream hides the
 * controls from its key handler (`PlaybackKeyHandler`, "TODO change this to a BackHandler"), so on a TV that branch
 * never ran: with the controls up, BACK left the player at once, and how many presses anything took depended on which
 * callbacks happened to be enabled. This is that branch as a back callback:
 *  - controls up (TV): BACK hides them (upstream's behavior), and nothing else;
 *  - the player is the only page (a play command opened it on an empty stack): BACK goes to Home instead of letting the
 *    activity finish, so BACK in the player never leaves the app.
 * With the controls hidden it stays out of the way: the page's own callbacks (skip prompt, next-up) and then the
 * navigation's pop leave the player with one press. The settings panel and the quality / sleep panels are dialogs with
 * their own BACK.
 */
@Composable
fun TallyPlayerBack(
    controllerViewState: ControllerViewState,
    navigationManager: NavigationManager,
) {
    val phone = LocalTallyFormFactor.current == TallyFormFactor.PHONE
    val tally = LocalTheme.current == AppThemeColors.TALLY || phone
    if (!tally) return
    val onlyPage = navigationManager.backStack.size <= 1
    // On a phone the controls come and go with a tap, and BACK (or the controls' back arrow) leaves the player.
    val hideFirst = controllerViewState.controlsVisible && !phone
    BackHandler(enabled = hideFirst || onlyPage) {
        if (hideFirst) {
            controllerViewState.hideControls()
        } else {
            navigationManager.goToHome()
        }
    }
}
