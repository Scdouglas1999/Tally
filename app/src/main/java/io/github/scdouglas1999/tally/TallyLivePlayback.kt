package io.github.scdouglas1999.tally

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.scdouglas1999.tally.lan.RouteRecovery
import timber.log.Timber

/**
 * How live streams are buffered. Jellyfin serves live TV as 3-second HLS segments with no low-latency hints, so
 * ExoPlayer's defaults sit about 9 s behind the edge and hold roughly 6 s of media: one slow upstream segment
 * shows as a stall. Sitting a few seconds further back costs nothing a viewer notices (cable is 5-10 s behind
 * the stadium anyway) and turns most stalls into nothing.
 */
object TallyLivePlayback {
    /** Distance behind the live edge to hold, in ms: five 3-second segments. */
    const val LIVE_TARGET_OFFSET_MS = 15_000L

    /** Applies the live offset to every media item that does not set its own. Only live windows are affected. */
    fun tune(factory: DefaultMediaSourceFactory): DefaultMediaSourceFactory =
        factory
            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
            // do not race to the edge after a stall: catching up at 1.03x steals from the cushion just rebuilt
            .setLiveMaxSpeed(1.0f)
            // and do not slow down to rebuild the offset either: at 0.97x a 30 fps stream on a 60 Hz screen holds some
            // frames for three refreshes instead of two, which shows as judder in fast pans. The offset may drift
            // after a stall; falling out of the window is handled by recover().
            .setLiveMinSpeed(1.0f)

    /**
     * Upstream's buffering, plus a little history so a switch back does not refetch. An earlier build held the
     * first frame until 15 s of live media was buffered; on a real server that turned tune-in into a long
     * black screen with a stuck first frame and playback that sometimes never started, so it is gone.
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder().setBackBuffer(20_000, true).build()

    /**
     * A live playlist is a sliding window; wait out one long stall and the position the player wants is gone.
     * ExoPlayer reports that as a fatal error, and upstream shows "Error during playback" for transcoded
     * streams (which live TV always is). Re-sync to the live edge instead. Returns true when handled. A stream
     * lost because the server's address changed is requested again first ([RouteRecovery]).
     */
    fun recover(
        player: Player,
        error: PlaybackException,
    ): Boolean {
        // the server's address changed under the stream (home network <-> internet): request it again
        if (RouteRecovery.recover(player, error)) return true
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
        Timber.w("Fell behind the live window; re-syncing to the live edge")
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
        return true
    }
}
