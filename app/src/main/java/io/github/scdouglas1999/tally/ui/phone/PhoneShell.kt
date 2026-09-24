package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.DestinationContent
import com.github.damontecres.wholphin.ui.nav.NavDrawerViewModel
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import io.github.scdouglas1999.tally.media.music.phone.PhoneMiniPlayer
import io.github.scdouglas1999.tally.media.music.phone.phoneMiniPlayerSpace
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens

/** True on a phone: [PhoneShell] replaces the drawer (seam W43 in `ApplicationContent`). */
@Composable
fun phoneShellActive(): Boolean = LocalTallyFormFactor.current == TallyFormFactor.PHONE

/**
 * The room a phone page leaves at its bottom so nothing ends under the bottom bar and the gesture bar (or, while the
 * keyboard is open and the bar is hidden, under the keyboard). Scrolling pages use it as the bottom of their content
 * padding. Zero outside [PhoneShell] (full-screen destinations handle the system bars themselves).
 */
val LocalPhoneContentPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * True while the page on screen was opened from the More sheet rather than from one of the bar's tabs: a page that is
 * a tab's root on the bar (a library with tabs) shows a back arrow then, as every other page the More sheet opens.
 */
val LocalPhoneMorePage = compositionLocalOf { false }

/**
 * The status bar's height as top padding, for a page that does not run a picture under the status bar. The phone
 * shell leaves the top to each page: nothing may sit under the status bar except a backdrop or a top bar's ground.
 */
fun Modifier.phoneStatusBarPadding(): Modifier = statusBarsPadding()

/**
 * The phone's frame around a page, in place of the TV drawer: the page fills the screen and the [PhoneBottomBar]
 * sits over its bottom; [LocalPhoneContentPadding] tells the page how much room the bar and the gesture bar take.
 * The bar is hidden while the keyboard is open (full-screen destinations never get the shell). Uses the drawer's
 * [NavDrawerViewModel] (same instance and key), so the current tab and every navigation are the drawer's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneShell(
    destination: Destination,
    preferences: UserPreferences,
    user: JellyfinUser,
    server: JellyfinServer,
    onClearBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: NavDrawerViewModel =
        hiltViewModel(
            LocalView.current.findViewTreeViewModelStoreOwner()!!,
            key = "${server.id}_${user.id}",
        )
    LaunchedEffect(Unit) { viewModel.updateSelectedIndex() }
    val serviceState by viewModel.serviceState.collectAsState()
    val state by viewModel.state.collectAsState()
    val nav = remember(serviceState) { PhoneNavModel.from(serviceState) }
    val onSettings = destination is Destination.Settings
    val current = if (onSettings) PhoneTab.MORE else nav.currentTab(state.selectedIndex)
    var moreOpen by remember { mutableStateOf(false) }

    val showBar = !WindowInsets.isImeVisible
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // While music plays, the mini player sits on the bar: pages end above it too.
    val miniPlayer = phoneMiniPlayerSpace()
    val contentPadding =
        PaddingValues(
            bottom =
                if (showBar) {
                    PhoneDimens.bottomBarHeight + navigationBar + miniPlayer
                } else {
                    max(keyboard, navigationBar)
                },
        )

    Box(modifier = modifier) {
        CompositionLocalProvider(
            LocalPhoneContentPadding provides contentPadding,
            LocalPhoneMorePage provides (current == PhoneTab.MORE),
        ) {
            DestinationContent(
                destination = destination,
                preferences = preferences,
                onClearBackdrop = onClearBackdrop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showBar) {
            PhoneMiniPlayer(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = PhoneDimens.bottomBarHeight + navigationBar),
            )
            PhoneBottomBar(
                nav = nav,
                current = current,
                onTab = { tab ->
                    when (tab) {
                        PhoneTab.HOME -> {
                            viewModel.setIndex(HOME_INDEX)
                            if (destination is Destination.Home) {
                                viewModel.navigationManager.reloadHome()
                                onClearBackdrop.invoke()
                            } else {
                                viewModel.navigationManager.goToHome()
                            }
                        }

                        PhoneTab.MOVIES -> {
                            nav.movies?.let { viewModel.onClickDrawerItem(it.index, it.value) }
                        }

                        PhoneTab.SHOWS -> {
                            nav.shows?.let { viewModel.onClickDrawerItem(it.index, it.value) }
                        }

                        PhoneTab.SPORTS -> {
                            nav.sports?.let { viewModel.onClickDrawerItem(it.index, it.value) }
                        }

                        PhoneTab.MORE -> {
                            moreOpen = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (moreOpen) {
        val userImageUrl = remember(user) { viewModel.getUserImage(user) }
        PhoneMoreSheet(
            nav = nav,
            selectedIndex = state.selectedIndex,
            onSettings = onSettings,
            userName = user.name ?: user.id.toString(),
            userId = user.id.toString(),
            serverName = server.name ?: server.url,
            userImageUrl = userImageUrl,
            nowPlayingTitle = if (serviceState.nowPlayingEnabled) serviceState.nowPlayingTitle.orEmpty() else null,
            onDismiss = { moreOpen = false },
            onProfile = {
                moreOpen = false
                viewModel.navigateToSetup(SetupDestination.UserList(server))
            },
            onNowPlaying = {
                moreOpen = false
                viewModel.setIndex(NOW_PLAYING_INDEX)
                viewModel.navigationManager.navigateTo(Destination.NowPlaying)
            },
            onSearch = {
                moreOpen = false
                viewModel.setIndex(SEARCH_INDEX)
                viewModel.navigationManager.navigateToFromDrawer(Destination.Search())
            },
            onItem = { index, item ->
                moreOpen = false
                viewModel.onClickDrawerItem(index, item)
            },
            onSettingsClick = {
                moreOpen = false
                viewModel.navigationManager.navigateTo(Destination.Settings(PreferenceScreenOption.BASIC))
            },
        )
    }
}
