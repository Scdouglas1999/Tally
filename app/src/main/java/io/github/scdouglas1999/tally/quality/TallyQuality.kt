package io.github.scdouglas1999.tally.quality

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
object TallyQuality {
    /** Bits per second, or null for Original (the global preference applies). */
    private val _choice = MutableStateFlow<Int?>(null)
    val choice: StateFlow<Int?> = _choice.asStateFlow()

    private val _nowPlaying = MutableStateFlow<CurrentPlayback?>(null)
    val nowPlaying: StateFlow<CurrentPlayback?> = _nowPlaying.asStateFlow()

    private val requests = MutableSharedFlow<Int?>(extraBufferCapacity = 4)

    @Volatile private var bindings = 0

    /** Read by `changeStreams`: the bitrate cap for this request, or null to use the preference. */
    fun maxBitrateOverride(): Int? = _choice.value

    /**
     * Read by `changeStreams` for a transcoded stream. With a quality chosen, the server must re-encode the video, at
     * no more than the chosen rung's height:
     *  - `AllowVideoStreamCopy=false`: otherwise it copies a source whose reported bitrate is below the cap, and live
     *    channels report only their audio's bitrate (a 720p channel shows as 0.19 Mbps). Jellyfin 10.10 leaves the
     *    flag out of the transcoding URL even when the request disallowed it.
     *  - `MaxHeight`: Jellyfin also caps the output bitrate at ten times the source's reported video bitrate, which
     *    for a live channel is 18 bits/s, so a bitrate cap alone left live video at full resolution (measured:
     *    1080p at ~4.9 Mbps for "480P · 2"). The height is what reliably lowers it, and it makes every rung's label
     *    true (a 1 Mbps cap alone gave 480p where the label says 360p).
     */
    fun transcodingUrl(url: String): String {
        val choice = _choice.value ?: return url
        var result = url
        if (!url.contains("AllowVideoStreamCopy=", ignoreCase = true)) result += "&AllowVideoStreamCopy=false"
        val height = QualityLadder.heightFor(choice)
        if (height != null && !url.contains("MaxHeight=", ignoreCase = true)) result += "&MaxHeight=$height"
        return result
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
        val binding = ++bindings
        scope.launch {
            try {
                requests.collect { restart(it == null) }
            } finally {
                // When one player replaces another (a deep link, Send to), the old player's scope ends after the new
                // one has bound: only the player that bound last may clear the shared state.
                if (bindings == binding) {
                    _choice.value = null
                    _nowPlaying.value = null
                }
            }
        }
    }
}
