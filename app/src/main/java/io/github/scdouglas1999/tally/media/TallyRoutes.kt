package io.github.scdouglas1999.tally.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import io.github.scdouglas1999.tally.media.episode.TallyEpisodePage
import io.github.scdouglas1999.tally.media.home.TallyHomePage
import io.github.scdouglas1999.tally.media.library.TallyLibraryPage
import io.github.scdouglas1999.tally.media.movie.TallyMoviePage
import io.github.scdouglas1999.tally.media.series.TallySeasonRundown
import io.github.scdouglas1999.tally.media.series.TallySeriesPage
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

/**
 * Which destinations Tally draws itself (see `tally/UI.md`). Called first by `DestinationContent` (seam W31):
 * returns true when it rendered [destination], false to let upstream render it. Only while the TALLY theme is
 * selected: a Wholphin theme gets Wholphin's screens back, whole.
 *
 * Screens are added here one at a time as their Tally versions land; each reuses the upstream ViewModel.
 */
object TallyRoutes {
    @Composable
    fun Content(
        destination: Destination,
        preferences: UserPreferences,
        onClearBackdrop: () -> Unit,
        modifier: Modifier,
    ): Boolean {
        if (LocalTheme.current != AppThemeColors.TALLY) return false
        return when (destination) {
            is Destination.Home -> {
                TallyHomePage(preferences, modifier)
                true
            }

            is Destination.MediaItem -> {
                when (destination.type) {
                    BaseItemKind.MOVIE, BaseItemKind.VIDEO -> {
                        TallyMoviePage(destination, preferences, modifier)
                        true
                    }

                    BaseItemKind.SERIES -> {
                        TallySeriesPage(destination, preferences, modifier)
                        true
                    }

                    BaseItemKind.EPISODE -> {
                        TallyEpisodePage(destination, preferences, modifier)
                        true
                    }

                    BaseItemKind.COLLECTION_FOLDER -> {
                        when (destination.collectionType) {
                            CollectionType.MOVIES,
                            CollectionType.TVSHOWS,
                            CollectionType.BOXSETS,
                            CollectionType.HOMEVIDEOS,
                            CollectionType.UNKNOWN,
                            null,
                            -> {
                                LaunchedEffect(Unit) { onClearBackdrop.invoke() }
                                TallyLibraryPage(destination, preferences, modifier)
                                true
                            }

                            // Music, live TV, photos, playlists, books... stay upstream's for now.
                            else -> {
                                false
                            }
                        }
                    }

                    else -> {
                        false
                    }
                }
            }

            is Destination.SeriesOverview -> {
                TallySeasonRundown(
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
