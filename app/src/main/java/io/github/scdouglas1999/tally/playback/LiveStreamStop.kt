package io.github.scdouglas1999.tally.playback

import androidx.media3.common.Player
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.onMain
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.github.damontecres.wholphin.util.TrackActivityPlaybackListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Changing streams in the player (Quality, an audio track, subtitles burned in, the restart after the server's address
 * changed) opens a live channel's stream again (seam W69 in `PlaybackViewModel.changeStreams`).
 *
 * Upstream reports the old playback stopped only after the new stream is open, and the stop report carries the old
 * live stream's id. Jellyfin gives a channel's live stream the same id every time it is opened and counts who uses
 * it. While the old stream is still open, the new playback shares it and the late report only lowers the count. But
 * when the server has already closed the old stream (it does when the device's websocket drops, as it does when the
 * home-network route changes, or when a transcode goes a minute without being asked for anything), opening the
 * channel again makes a new stream under the same id, and the late report closes it: a few seconds in, the new
 * stream answers 404 and the player shows "Error during playback".
 *
 * So the old playback is ended first: the player lets go of the old stream (left playing, it would ask the server for
 * the old transcode again once that is killed, and the server would start it again), the old playback's stop report
 * is sent and answered, and only then is the stream requested again. That report closes the old stream or finds it
 * already gone, and no report sent later carries the id. One mechanism for every such change.
 *
 * A channel then starts at its live edge ([opened]): the new stream's window starts afresh, so the old stream's
 * position means nothing in it, and asking for it left the player waiting up to a minute for the new window to grow
 * to that position.
 */
object LiveStreamStop {
    /** How long the old playback's stop report may take before the stream is opened again anyway. */
    const val STOP_TIMEOUT_MS = 10_000L

    /** The player [stop] stopped, to be prepared again once the new stream is set on it ([opened]). Main thread. */
    private var stoppedPlayer: Player? = null

    /** True when replacing [playing] opens its live stream again, so it has to be stopped first. */
    fun stopsFirst(playing: CurrentPlayback?): Boolean = playing?.liveStreamId != null

    /**
     * Ends the old playback before its live stream is opened again: [player] lets go of the stream, [old] (the old
     * playback's reporter, already detached from the view model) sends its stop report, and this returns once that
     * report is answered or failed, or after [STOP_TIMEOUT_MS] (the report is then canceled: sent any later, it could
     * close the new stream).
     */
    suspend fun stop(
        player: Player,
        old: TrackActivityPlaybackListener?,
    ) {
        val reports =
            onMain {
                old?.let { player.removeListener(it) }
                stoppedPlayer = player
                player.stop()
                // the stop report, with the position the player stopped at
                old?.release()
                old?.tallyReportsInFlight.orEmpty()
            }
        if (!awaitReports(reports, STOP_TIMEOUT_MS)) {
            Timber.w("The old playback's stop report did not finish in %d ms; opening the live stream again anyway", STOP_TIMEOUT_MS)
        }
    }

    /**
     * The new stream was just set on [player] for [item] (main thread): a channel starts at its live edge, and a
     * player [stop] stopped is prepared again (upstream's in-player changes expect the player still prepared).
     */
    fun opened(
        player: Player,
        item: BaseItem,
    ) {
        if (item.type == BaseItemKind.TV_CHANNEL) player.seekToDefaultPosition()
        if (stoppedPlayer === player) {
            stoppedPlayer = null
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
    }

    /** Waits for [reports] to finish; after [timeoutMs] cancels those still running and returns false. */
    internal suspend fun awaitReports(
        reports: List<Job>,
        timeoutMs: Long,
    ): Boolean {
        val finished = withTimeoutOrNull(timeoutMs) { reports.forEach { it.join() } } != null
        if (!finished) reports.forEach { it.cancel() }
        return finished
    }
}
