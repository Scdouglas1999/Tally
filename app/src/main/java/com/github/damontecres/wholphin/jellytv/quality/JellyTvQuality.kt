package com.github.damontecres.wholphin.jellytv.quality

import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-player quality choice ("Original" or a lower bitrate), which Wholphin lacks: it only has one global maximum
 * bitrate in Settings. Process-wide, bound to the open upstream player by seams in `PlaybackViewModel`:
 *  - `changeStreams` asks [maxBitrateOverride] before the global preference (every stream request goes through it),
 *    and passes a transcoding URL through [transcodingUrl];
 *  - `init` publishes the player's `currentPlayback` here ([nowPlaying]) and binds [requests] to a restart of the
 *    stream at the current position.
 * The choice lasts for the life of that player (every item it plays), then resets to Original.
 */
object JellyTvQuality {
    /** Bits per second, or null for Original (the global preference applies). */
    private val _choice = MutableStateFlow<Int?>(null)
    val choice: StateFlow<Int?> = _choice.asStateFlow()

    private val _nowPlaying = MutableStateFlow<CurrentPlayback?>(null)
    val nowPlaying: StateFlow<CurrentPlayback?> = _nowPlaying.asStateFlow()

    private val requests = MutableSharedFlow<Int?>(extraBufferCapacity = 4)

    /** Read by `changeStreams`: the bitrate cap for this request, or null to use the preference. */
    fun maxBitrateOverride(): Int? = _choice.value

    /**
     * Read by `changeStreams` for a transcoded stream. With a quality chosen, the server must re-encode the video:
     * otherwise it copies a source whose reported bitrate is below the cap, and live channels report only their
     * audio's bitrate (a 720p channel shows as 0.19 Mbps), so a lower choice changed nothing. Jellyfin 10.10 leaves
     * `AllowVideoStreamCopy` out of the transcoding URL even when the request disallowed it, so it is added here.
     */
    fun transcodingUrl(url: String): String =
        if (_choice.value == null || url.contains("AllowVideoStreamCopy=", ignoreCase = true)) {
            url
        } else {
            "$url&AllowVideoStreamCopy=false"
        }

    /** The dialog chose a quality: remember it and restart the stream at the current position. */
    fun choose(bitsPerSecond: Int?) {
        _choice.value = bitsPerSecond
        requests.tryEmit(bitsPerSecond)
    }

    fun publish(playback: CurrentPlayback?) {
        _nowPlaying.value = playback
    }

    /**
     * Called from the player's `init`. [restart] re-requests the stream at the current position; its argument is
     * true for Original (direct play allowed again). The binding and the choice end with [scope].
     */
    fun bindPlayer(
        scope: CoroutineScope,
        restart: suspend (original: Boolean) -> Unit,
    ) {
        scope.launch {
            try {
                requests.collect { restart(it == null) }
            } finally {
                _choice.value = null
                _nowPlaying.value = null
            }
        }
    }
}
