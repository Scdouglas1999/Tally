package io.github.scdouglas1999.tally.lan

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.scdouglas1999.tally.quality.TallyQuality
import timber.log.Timber

/**
 * Playback that fails because the server's address changed under it (`PlaybackViewModel.onPlayerError`, through
 * `TallyLivePlayback.recover`, seam W13).
 *
 * When the address in use stops answering, the websocket to the server drops with it, and the server ends the
 * device's session: it stops the transcode or live stream that was playing. The player plays out its buffer, then
 * its next request finds nothing (404). Instead of upstream's "Error during playback", the stream is requested
 * again at the player's position (a live channel at its live edge), through the address now in use (the in-player
 * Quality restart, which keeps the chosen quality; `LiveStreamStop` ends the old playback first). Once per switch,
 * and only for a network or HTTP error within a few minutes of it: anything else is upstream's to handle.
 */
object RouteRecovery {
    const val WINDOW_MS = 5 * 60_000L

    @Volatile
    private var usedFor = 0L

    fun recover(
        player: Player,
        error: PlaybackException,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val switchedAt = TallyServerRoute.router.lastFailoverAt
        if (!shouldRestart(error.errorCode, switchedAt, usedFor, now)) return false
        usedFor = switchedAt
        // a live stream the server closed is stopped before it is opened again (LiveStreamStop, in changeStreams)
        Timber.i("Playback failed after the server address changed; requesting the stream again at %d ms", player.currentPosition)
        // the restart sets a new media item on the stopped player: prepare it then
        player.addListener(
            object : Player.Listener {
                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                    player.removeListener(this)
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                        player.play()
                    }
                }
            },
        )
        TallyQuality.choose(TallyQuality.choice.value)
        return true
    }

    /** A network or HTTP error ([errorCode] 2000-2999) within [WINDOW_MS] of a failover not yet recovered from. */
    internal fun shouldRestart(
        errorCode: Int,
        switchedAt: Long,
        usedFor: Long,
        now: Long,
    ): Boolean =
        errorCode in IO_ERRORS &&
            switchedAt > 0L &&
            switchedAt != usedFor &&
            now - switchedAt in 0..WINDOW_MS

    private val IO_ERRORS = 2000..2999
}
