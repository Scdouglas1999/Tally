package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import io.github.scdouglas1999.tally.data.TallyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Server-pushed playback. Decides which player a pushed "play this item" opens. A Tally channel opens in the Tally player
 * (score bug, game switcher); everything else opens in the normal player, exactly as upstream would.
 */
@Singleton
class TallyPlayRouter
    @Inject
    constructor(
        private val repository: TallyRepository,
        private val navigationManager: NavigationManager,
    ) {
        /**
         * Plays what the server pushes to this session ("Play on TV" from the Tally web UI, or any Jellyfin
         * remote control). Upstream does not handle [PlayMessage] at all. The caller owns the returned job.
         */
        fun listen(
            api: ApiClient,
            scope: CoroutineScope,
        ): Job =
            api.webSocket
                .subscribe<PlayMessage>()
                .onEach { message ->
                    val data = message.data
                    val itemIds = data?.itemIds
                    if (data != null && data.playCommand == PlayCommand.PLAY_NOW && !itemIds.isNullOrEmpty()) {
                        val itemId = itemIds[data.startIndex?.takeIf { it in itemIds.indices } ?: 0]
                        Timber.d("Server requested playback of item %s", itemId)
                        // Switching games from the phone: swap the running player out instead of stacking a
                        // second one on top of it (the old one would keep playing underneath).
                        val top = navigationManager.backStack.lastOrNull()
                        if (top is Destination.Playback || top is Destination.TallyPlayback) {
                            navigationManager.backStack.removeLastOrNull()
                        }
                        navigationManager.navigateTo(destinationFor(itemId, (data.startPositionTicks ?: 0) / 10_000))
                    }
                }.catch { ex ->
                    Timber.e(ex, "Error in websocket play subscription")
                }.launchIn(scope)

        suspend fun destinationFor(
            itemId: UUID,
            positionMs: Long,
        ): Destination {
            val fallback = Destination.Playback(itemId = itemId, positionMs = positionMs)
            if (repository.availability.value !is TallyRepository.Availability.Available) return fallback
            return try {
                if (repository.board.value == null) repository.refreshNow()
                val wanted = itemId.toString().replace("-", "")
                val channel =
                    repository.board.value
                        ?.channels
                        ?.firstOrNull { it.liveTvItemId.equals(wanted, ignoreCase = true) }
                if (channel != null) Destination.TallyPlayback(itemId = itemId, channelId = channel.id) else fallback
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not match pushed item %s to a Tally channel", itemId)
                fallback
            }
        }
    }
