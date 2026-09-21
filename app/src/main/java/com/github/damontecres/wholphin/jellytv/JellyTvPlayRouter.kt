package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.ui.nav.Destination
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Decides which player a server-pushed "play this item" opens. A JellyTV channel opens in the JellyTV player
 * (score bug, game switcher); everything else opens in the normal player, exactly as upstream would.
 */
@Singleton
class JellyTvPlayRouter
    @Inject
    constructor(
        private val repository: JellyTvRepository,
    ) {
        suspend fun destinationFor(
            itemId: UUID,
            positionMs: Long,
        ): Destination {
            val fallback = Destination.Playback(itemId = itemId, positionMs = positionMs)
            if (repository.availability.value !is JellyTvRepository.Availability.Available) return fallback
            return try {
                if (repository.board.value == null) repository.refreshNow()
                val wanted = itemId.toString().replace("-", "")
                val channel = repository.board.value?.channels?.firstOrNull { it.liveTvItemId.equals(wanted, ignoreCase = true) }
                if (channel != null) Destination.JellyTvPlayback(itemId = itemId, channelId = channel.id) else fallback
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not match pushed item %s to a JellyTV channel", itemId)
                fallback
            }
        }
    }
