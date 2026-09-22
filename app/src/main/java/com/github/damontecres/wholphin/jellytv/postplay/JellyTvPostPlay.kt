package com.github.damontecres.wholphin.jellytv.postplay

import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Where to go when a player reaches the natural end of something with nothing queued after it
 * (seam in `PlaybackViewModel.onPlaybackStateChanged`). Null keeps upstream's behaviour (go back).
 * Only a finished movie gets the post-play page; episodes, live TV and intros never do.
 */
object JellyTvPostPlay {
    fun destinationFor(item: PlaylistItem): Destination? =
        if (item is PlaylistItem.Media && item.item.type == BaseItemKind.MOVIE) {
            Destination.JellyTvPostPlay(item.id)
        } else {
            null
        }
}
