package com.github.damontecres.wholphin.jellytv

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timber.log.Timber

/**
 * How live streams are buffered. Jellyfin serves live TV as 3-second HLS segments with no low-latency hints, so
 * ExoPlayer's defaults sit about 9 s behind the edge and hold roughly 6 s of media: one slow upstream segment
 * shows as a stall. Sitting a few seconds further back costs nothing a viewer notices (cable is 5-10 s behind
 * the stadium anyway) and turns most stalls into nothing.
 */
object JellyTvLivePlayback {
    /** Distance behind the live edge to hold, in ms: five 3-second segments. */
    const val LIVE_TARGET_OFFSET_MS = 15_000L

    /** Applies the live offset to every media item that does not set its own. Only live windows are affected. */
    fun tune(factory: DefaultMediaSourceFactory): DefaultMediaSourceFactory =
        factory
            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
            // do not race to the edge after a stall: catching up at 1.03x steals from the cushion just rebuilt
            .setLiveMaxSpeed(1.0f)

    /** Live media that must be buffered before the first frame, and again after a stall. */
    const val LIVE_START_BUFFER_MS = 15_000L
    const val LIVE_RESTART_BUFFER_MS = 6_000L

    /**
     * Upstream's defaults, except that a live stream does not start until [LIVE_START_BUFFER_MS] of it is
     * buffered. Jellyfin's playlists carry no wall-clock tags, so ExoPlayer cannot drift back to a target offset
     * after it has started: whatever cushion exists at the first frame is the cushion for the whole game. Holding
     * the first frame about six seconds longer buys 12-15 s of protection for the next three hours. VOD is untouched.
     */
    fun loadControl(): LoadControl = LiveCushionLoadControl()

    /**
     * Subclassed rather than delegated: LoadControl's Java default methods forward to one another, and Kotlin
     * interface delegation does not cover defaults, so a delegate recursed until the stack ran out.
     */
    private class LiveCushionLoadControl : DefaultLoadControl() {
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
            // Not parameters.targetLiveOffsetUs: ExoPlayer leaves that unset for playlists without
            // PROGRAM-DATE-TIME, which is every Jellyfin live playlist. The window itself still says live.
            val window = parameters.timeline.takeIf { !it.isEmpty }?.getWindow(0, Timeline.Window())
            val live = window != null && window.isLive() && window.isDynamic
            if (!live) return super.shouldStartPlayback(parameters)
            val neededUs = (if (parameters.rebuffering) LIVE_RESTART_BUFFER_MS else LIVE_START_BUFFER_MS) * 1000
            return parameters.bufferedDurationUs >= neededUs && super.shouldStartPlayback(parameters)
        }
    }

    /**
     * A live playlist is a sliding window; wait out one long stall and the position the player wants is gone.
     * ExoPlayer reports that as a fatal error, and upstream shows "Error during playback" for transcoded
     * streams (which live TV always is). Re-sync to the live edge instead. Returns true when handled.
     */
    fun recover(
        player: Player,
        error: PlaybackException,
    ): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
        Timber.w("Fell behind the live window; re-syncing to the live edge")
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
        return true
    }
}
