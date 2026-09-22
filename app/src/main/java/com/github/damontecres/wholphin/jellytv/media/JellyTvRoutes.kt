package com.github.damontecres.wholphin.jellytv.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.LocalTheme

/**
 * Which destinations JellyTV draws itself (see `jellytv/UI.md`). Called first by `DestinationContent` (seam W31):
 * returns true when it rendered [destination], false to let upstream render it. Only while the JELLYTV theme is
 * selected: a Wholphin theme gets Wholphin's screens back, whole.
 *
 * Screens are added here one at a time as their JellyTV versions land; each reuses the upstream ViewModel.
 */
object JellyTvRoutes {
    @Composable
    fun Content(
        destination: Destination,
        preferences: UserPreferences,
        onClearBackdrop: () -> Unit,
        modifier: Modifier,
    ): Boolean {
        if (LocalTheme.current != AppThemeColors.JELLYTV) return false
        return when (destination) {
            else -> false
        }
    }
}
