package com.github.damontecres.wholphin.jellytv.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.jellytv.media.episode.JtvEpisodePage
import com.github.damontecres.wholphin.jellytv.media.home.JtvHomePage
import com.github.damontecres.wholphin.jellytv.media.movie.JtvMoviePage
import com.github.damontecres.wholphin.jellytv.media.series.JtvSeasonRundown
import com.github.damontecres.wholphin.jellytv.media.series.JtvSeriesPage
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import org.jellyfin.sdk.model.api.BaseItemKind

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
            is Destination.Home -> {
                JtvHomePage(preferences, modifier)
                true
            }

            is Destination.MediaItem -> {
                when (destination.type) {
                    BaseItemKind.MOVIE, BaseItemKind.VIDEO -> {
                        JtvMoviePage(destination, preferences, modifier)
                        true
                    }

                    BaseItemKind.SERIES -> {
                        JtvSeriesPage(destination, preferences, modifier)
                        true
                    }

                    BaseItemKind.EPISODE -> {
                        JtvEpisodePage(destination, preferences, modifier)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }

            is Destination.SeriesOverview -> {
                JtvSeasonRundown(
                    destination = destination,
                    preferences = preferences,
                    initialSeasonEpisode = destination.seasonEpisode,
                    modifier = modifier,
                )
                true
            }

            else -> {
                false
            }
        }
    }
}
