package io.github.scdouglas1999.tally.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import timber.log.Timber

/**
 * Music in the background on a phone (seam in upstream's `MusicService.start` / `stop`). Upstream's `MusicService` is a
 * singleton holding a Media3 [MediaSession] but not an Android service, so once the app leaves the screen Android
 * freezes it and the music stops. On a phone the session is handed to [TallyMusicPlaybackService], a Media3
 * [MediaSessionService]: it runs in the foreground while music plays, with the system media notification and the lock
 * screen controls. A TV keeps upstream's behavior exactly (nothing here runs there). Video is not part of this.
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
