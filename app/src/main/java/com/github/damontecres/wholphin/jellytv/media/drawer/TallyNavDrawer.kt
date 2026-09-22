package com.github.damontecres.wholphin.jellytv.media.drawer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.DrawerState
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawer
import com.github.damontecres.wholphin.ui.theme.LocalTheme

/** True when the Tally drawer replaces upstream's (the JELLYTV theme is selected). */
@Composable
fun tallyDrawerActive(): Boolean = LocalTheme.current == AppThemeColors.JELLYTV

/**
 * The Tally navigation drawer (seam W34 in `ApplicationContent`): the same `NavDrawerViewModel`, items, actions and
 * drawer mechanics as upstream's `NavDrawer`, drawn in the Tally language (see `jellytv/UI.md`), with the Live TV
 * library hidden while the Sports section is available.
 *
 * STUB: task `drawer` implements it; until then it draws upstream's drawer.
 */
@Composable
fun TallyNavDrawer(
    destination: Destination,
    preferences: UserPreferences,
    user: JellyfinUser,
    server: JellyfinServer,
    drawerState: DrawerState,
    navDrawerListState: LazyListState,
    onClearBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDrawer(
        destination = destination,
        preferences = preferences,
        user = user,
        server = server,
        drawerState = drawerState,
        navDrawerListState = navDrawerListState,
        onClearBackdrop = onClearBackdrop,
        modifier = modifier,
    )
}
