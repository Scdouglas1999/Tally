package com.github.damontecres.wholphin.jellytv.playback

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The film that follows [item] in its collection (Toy Story → Toy Story 2), so upstream's own Up Next card,
 * autoplay countdown and pass-out protection work for movies the way they already do for episodes.
 * Called from the movie branch of `PlaylistCreator.createFrom` (seam), after playback has started.
 *
 * STUB: task `movienext` implements it. Returns null when there is no next film or on any error.
 */
object CollectionNext {
    suspend fun nextFor(
        api: ApiClient,
        item: BaseItemDto,
    ): BaseItemDto? = null
}
