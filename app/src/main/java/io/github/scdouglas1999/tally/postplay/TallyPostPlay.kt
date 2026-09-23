package io.github.scdouglas1999.tally.postplay

import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.nav.Destination
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Where to go when a player reaches the natural end of something with nothing queued after it
 * (seam in `PlaybackViewModel.onPlaybackStateChanged`). Null keeps upstream's behavior (go back).
 * Only a finished movie gets the post-play page, and only in the Tally theme (a Wholphin theme gets Wholphin's
 * behavior back whole); episodes, live TV and intros never do.
 */
object TallyPostPlay {
    fun destinationFor(
        item: PlaylistItem,
        preferences: AppPreferences,
    ): Destination? =
        // The theme in effect (as `tallyThemeInEffect`): on a phone the Tally look is always on, whatever is saved.
        if ((
                preferences.interfacePreferences.appThemeColors == AppThemeColors.TALLY ||
                    TallyFormFactor.current == TallyFormFactor.PHONE
            ) &&
            item is PlaylistItem.Media &&
            item.item.type == BaseItemKind.MOVIE
        ) {
            Destination.TallyPostPlay(item.id)
        } else {
            null
        }
}
