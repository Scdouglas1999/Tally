@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.jellytv.ui.multiview

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.github.damontecres.wholphin.R
import timber.log.Timber

/**
 * What the UI sees for one tile: the player (null while no HLS URL is known yet),
 * whether it is buffering or playing, or a fatal error message.
 */
data class MultiviewTilePlayback(
    val player: Player? = null,
    val buffering: Boolean = true,
    val playing: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the ExoPlayers behind the multiview tiles, keyed by channelId so that
 * recomposition or re-ordering never recreates a running player.
 *
 * The HLS URLs are anonymous and signed, so the default HTTP data source is enough;
 * no MediaSession, no refresh-rate switching. All calls happen on the main thread.
 */
class MultiviewPlayers internal constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val decoderLimitMessage = appContext.getString(R.string.jtv_mv_no_decoder)
    private val unavailableMessage = appContext.getString(R.string.jtv_mv_unavailable)

    /** channelId -> observable per-tile state; the UI reads this map directly. */
    val playback = mutableStateMapOf<String, MultiviewTilePlayback>()

    private val entries = LinkedHashMap<String, Entry>()
    private var audioChannelId: String? = null
    private var released = false

    /**
     * Reconcile the players with [tiles]: create players for new channelIds, release
     * players whose channel left, leave the rest untouched.
     */
    fun sync(tiles: List<MultiviewTile>) {
        if (released) return
        val wanted = tiles.map { it.channelId }.toSet()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next.key !in wanted) {
                next.value.destroy()
                iterator.remove()
            }
        }
        playback.keys.filter { it !in wanted }.forEach { playback.remove(it) }
        for (tile in tiles) {
            if (tile.channelId in entries) continue
            val url = tile.hlsUrl
            if (url.isNullOrBlank()) {
                // Channel not on the board yet (or signed out): show buffering until it resolves.
                playback[tile.channelId] = MultiviewTilePlayback()
            } else {
                entries[tile.channelId] = Entry(tile.channelId, url).also { it.publish() }
            }
        }
    }

    /** Unmute [channelId], mute all the rest. */
    fun setAudio(channelId: String?) {
        audioChannelId = channelId
        entries.forEach { (id, entry) -> entry.player.volume = if (id == channelId) 1f else 0f }
    }

    fun pauseAll() = entries.values.forEach { it.player.pause() }

    fun resumeAll() = entries.values.forEach { it.player.play() }

    fun release() {
        released = true
        entries.values.forEach { it.destroy() }
        entries.clear()
        playback.clear()
    }

    private fun isDecoderInitFailure(error: PlaybackException): Boolean {
        if (error !is ExoPlaybackException || error.type != ExoPlaybackException.TYPE_RENDERER) {
            return false
        }
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is MediaCodecRenderer.DecoderInitializationException) return true
            cause = cause.cause
        }
        return false
    }

    private inner class Entry(
        val channelId: String,
        url: String,
    ) {
        val player: ExoPlayer = ExoPlayer.Builder(appContext).build()

        var retries = 0
        var errorMessage: String? = null
        var pendingRetry: Runnable? = null

        // prepare() reports state to the listener synchronously, and the listener reads every field above:
        // start the player only once they all exist.
        init {
            player.setMediaItem(
                MediaItem
                    .Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
            player.volume = if (channelId == audioChannelId) 1f else 0f
            player.addListener(TileListener())
            player.prepare()
            player.playWhenReady = true
        }

        fun publish() {
            playback[channelId] =
                when {
                    errorMessage != null -> {
                        MultiviewTilePlayback(player = player, buffering = false, error = errorMessage)
                    }

                    player.playbackState == Player.STATE_READY -> {
                        MultiviewTilePlayback(player = player, buffering = false, playing = player.isPlaying)
                    }

                    else -> {
                        MultiviewTilePlayback(player = player, buffering = true)
                    }
                }
        }

        fun destroy() {
            pendingRetry?.let { handler.removeCallbacks(it) }
            player.release()
        }

        private fun scheduleRetry() {
            retries++
            pendingRetry =
                Runnable {
                    pendingRetry = null
                    errorMessage = null
                    player.prepare()
                    player.playWhenReady = true
                    publish()
                }.also { handler.postDelayed(it, RETRY_DELAY_MS) }
        }

        private inner class TileListener : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retries = 0
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "Multiview stream failed on %s (attempt %d)", channelId, retries)
                when {
                    // The device has run out of decoders; retrying cannot help.
                    isDecoderInitFailure(error) -> errorMessage = decoderLimitMessage

                    retries >= MAX_RETRIES -> errorMessage = unavailableMessage

                    else -> scheduleRetry()
                }
                publish()
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_RETRIES = 5
    }
}

/**
 * Remembers the [MultiviewPlayers] for the lifetime of the page: players are released
 * on dispose, paused when the app stops, and resumed when it starts again.
 */
@Composable
fun rememberMultiviewPlayers(): MultiviewPlayers {
    val context = LocalContext.current
    val players = remember { MultiviewPlayers(context) }
    DisposableEffect(players) {
        onDispose { players.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { players.pauseAll() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { players.resumeAll() }
    return players
}
