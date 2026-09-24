package io.github.scdouglas1999.tally.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.github.damontecres.wholphin.data.model.AudioItem
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import timber.log.Timber

/**
 * Music in the background on a phone (seam in upstream's `MusicService.start` / `stop`). Upstream's `MusicService` is a
 * singleton holding a Media3 [MediaSession] but not an Android service, so once the app leaves the screen Android
 * freezes it and the music stops. On a phone the session is handed to [TallyMusicPlaybackService], a Media3
 * [MediaSessionService]: it runs in the foreground while music plays, with the system media notification and the lock
 * screen controls. A TV keeps upstream's behavior exactly (nothing here runs there). Video is not part of this.
 * The queue's items get their album art as artwork ([SessionArtwork]), so the notification shows the cover the
 * now-playing screen shows.
 */
object TallyMusicPlayback {
    @Volatile
    internal var session: MediaSession? = null

    @Volatile
    internal var service: TallyMusicPlaybackService? = null

    /** Upstream's music session exists and playback starts: on a phone, run it in the playback service. */
    fun onSessionStarted(
        context: Context,
        mediaSession: MediaSession,
    ) {
        if (TallyFormFactor.of(context) != TallyFormFactor.PHONE) return
        session = mediaSession
        SessionArtwork.attach(mediaSession)
        val running = service
        if (running != null) {
            running.attach(mediaSession)
            return
        }
        try {
            // Started while the app is on screen; Media3 moves it to the foreground once the music plays.
            context.startService(Intent(context, TallyMusicPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed to start from the background: the music plays while the app is on screen, as before.
            Timber.w(e, "Could not start the music playback service")
        }
    }

    /** Upstream is about to release the session (music stopped): take it out of the service and stop the service. */
    fun onSessionStopping(mediaSession: MediaSession?) {
        if (session !== mediaSession && mediaSession != null) return
        if (session != null) SessionArtwork.detach()
        session = null
        service?.detach()
    }
}

/**
 * The phone's music playback service: serves upstream's music session to the system (notification, lock screen,
 * Bluetooth and headset controls) and keeps the app in the foreground while it plays. Tapping the notification opens
 * the app. It never creates a player of its own.
 */
@OptIn(UnstableApi::class)
class TallyMusicPlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        TallyMusicPlayback.service = this
        val session = TallyMusicPlayback.session
        if (session == null) {
            stopSelf()
        } else {
            attach(session)
        }
    }

    internal fun attach(session: MediaSession) {
        launchIntent()?.let { session.setSessionActivity(it) }
        if (!isSessionAdded(session)) addSession(session)
    }

    internal fun detach() {
        sessions.forEach { removeSession(it) }
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = TallyMusicPlayback.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away while nothing plays: nothing left to keep running.
        val player = TallyMusicPlayback.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            detach()
        }
    }

    override fun onDestroy() {
        sessions.forEach { removeSession(it) }
        if (TallyMusicPlayback.service === this) TallyMusicPlayback.service = null
        super.onDestroy()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        // as a tap on the launcher icon: the app's task comes back as it was
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Gives every item of the music queue its album art as [androidx.media3.common.MediaMetadata.artworkUri]: upstream's
 * items carry the cover only in their [AudioItem] tag (what the now-playing screen shows), so the media session, and
 * with it the notification and the lock screen, had no picture. Items are completed as they are queued; replacing an
 * item with the same stream and a richer metadata does not interrupt it.
 */
@OptIn(UnstableApi::class)
internal object SessionArtwork : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var filling = false

    /** Called from any thread; the session's player is used on the main thread, the player's own (upstream's). */
    fun attach(session: MediaSession) {
        main.post {
            val player =
                try {
                    session.player
                } catch (e: IllegalStateException) {
                    Timber.w(e, "Music session gone before its artwork was set up")
                    return@post
                }
            if (this.player === player) return@post
            this.player?.removeListener(this)
            this.player = player
            player.addListener(this)
            fill(player)
        }
    }

    fun detach() {
        main.post {
            val player = this.player ?: return@post
            player.removeListener(this)
            this.player = null
        }
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) player?.let(::fill)
    }

    private fun fill(player: Player) {
        if (filling) return
        val missing =
            (0 until player.mediaItemCount).mapNotNull { index ->
                val item = player.getMediaItemAt(index)
                withArtwork(item)?.let { index to it }
            }
        if (missing.isEmpty()) return
        filling = true
        try {
            missing.forEach { (index, item) -> player.replaceMediaItem(index, item) }
        } catch (e: IllegalStateException) {
            Timber.w(e, "Could not add the music artwork")
        } finally {
            filling = false
        }
    }

    /** [item] with its album art as artwork, or null when it already has one or has no album art. */
    private fun withArtwork(item: MediaItem): MediaItem? {
        if (item.mediaMetadata.artworkUri != null || item.mediaMetadata.artworkData != null) return null
        val imageUrl = (item.localConfiguration?.tag as? AudioItem)?.imageUrl ?: return null
        return item
            .buildUpon()
            .setMediaMetadata(
                item.mediaMetadata
                    .buildUpon()
                    .setArtworkUri(imageUrl.toUri())
                    .build(),
            ).build()
    }
}
