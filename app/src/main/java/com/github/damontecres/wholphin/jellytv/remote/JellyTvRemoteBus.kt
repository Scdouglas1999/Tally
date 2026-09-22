package com.github.damontecres.wholphin.jellytv.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Hands track commands from a Jellyfin remote (a phone, the web client) to whichever upstream player is open.
 * The player binds in its `init` (seam in `PlaybackViewModel`) and its view model scope ends the binding.
 * With no player open, a command is dropped: there is nothing to switch.
 *
 * Indexes are Jellyfin media-stream indexes, exactly as the server sends them; -1 turns subtitles off.
 */
object JellyTvRemoteBus {
    sealed interface TrackCommand {
        val index: Int

        data class Audio(
            override val index: Int,
        ) : TrackCommand

        data class Subtitle(
            override val index: Int,
        ) : TrackCommand
    }

    private val commands = MutableSharedFlow<TrackCommand>(extraBufferCapacity = 8)

    /** False when no player could take it (nothing is playing, or the buffer is full). */
    fun send(command: TrackCommand): Boolean = commands.subscriptionCount.value > 0 && commands.tryEmit(command)

    fun bindPlayer(
        scope: CoroutineScope,
        onAudio: (Int) -> Unit,
        onSubtitle: (Int) -> Unit,
    ) {
        scope.launch {
            commands.collect { command ->
                when (command) {
                    is TrackCommand.Audio -> onAudio(command.index)
                    is TrackCommand.Subtitle -> onSubtitle(command.index)
                }
            }
        }
    }
}
