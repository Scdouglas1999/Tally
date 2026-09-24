@file:OptIn(UnstableApi::class)

package io.github.scdouglas1999.tally.downloads

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.github.damontecres.wholphin.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.scdouglas1999.tally.downloads.db.DownloadRecord
import io.github.scdouglas1999.tally.ui.formfactor.isTallyPhone
import kotlinx.coroutines.launch
import timber.log.Timber

/** Hilt access for code that is not injected (services, seams in upstream classes, static helpers). */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DownloadsEntryPoint {
    fun downloads(): TallyDownloads

    fun engine(): DownloadEngine
}

internal fun downloadsEntryPoint(context: Context): DownloadsEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, DownloadsEntryPoint::class.java)

/**
 * Media3's foreground download service: keeps the process alive while downloads run, with one progress
 * notification (Tally's monochrome mark). The manager itself belongs to [DownloadEngine].
 */
class TallyDownloadService :
    DownloadService(
        DownloadNotifications.PROGRESS_ID,
        DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
        DownloadNotifications.CHANNEL_ID,
        R.string.tally_dl_channel_name,
        R.string.tally_dl_channel_description,
    ) {
    private val engine by lazy { downloadsEntryPoint(this).engine() }

    /** Downloads are a phone feature: on a TV the manager is never started, so nothing downloads or notifies. */
    private val phone by lazy { isTallyPhone(this) }

    /** The downloads of the current run (since the queue was last empty), for "Downloading 2 of 5". */
    private val batch = LinkedHashSet<String>()

    override fun getDownloadManager(): DownloadManager {
        if (phone) engine.start()
        return engine.manager()
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // the notification's Pause and Cancel
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.scope.launch { active().forEach { engine.setPaused(it, true) } }
            ACTION_CANCEL_ALL -> engine.scope.launch { active().forEach { engine.remove(it) } }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** The records of every download that is not finished and not paused. */
    private fun active(): List<DownloadRecord> {
        val running =
            engine.downloads.value.values
                .filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
                .map { it.request.id }
                .toSet()
        return engine.records.value.filter { it.id in running }
    }

    /**
     * "Downloading 2 of 5" over "Severance S1E3 · 42%", with the progress bar and Pause / Cancel; tapping it opens the
     * Downloads page.
     */
    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        if (active.isEmpty()) batch.clear()
        active.forEach { batch.add(it.request.id) }
        val activeIds = active.map { it.request.id }.toSet()
        val current = active.firstOrNull { it.state == Download.STATE_DOWNLOADING } ?: active.firstOrNull()
        val position = batch.count { it !in activeIds } + 1
        val percent = current?.percentDownloaded?.takeIf { it >= 0f }?.toInt()
        val title =
            if (active.isEmpty()) {
                getString(R.string.tally_dlui_notification_waiting)
            } else {
                getString(R.string.tally_dlui_notification_title, position.coerceAtMost(batch.size), batch.size)
            }
        val name =
            current?.let {
                engine.records.value
                    .firstOrNull { r -> r.id == it.request.id }
                    ?.shortTitle()
            }
        val text = listOfNotNull(name, percent?.let { getString(R.string.tally_dlui_percent, it) }).joinToString(" · ")
        return NotificationCompat
            .Builder(this, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.tally_ic_notification)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { null })
            .setContentIntent(DownloadNotifications.openDownloads(this))
            .setProgress(100, percent ?: 0, current == null || percent == null)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.tally_dlui_pause), serviceIntent(ACTION_PAUSE_ALL))
            .addAction(0, getString(R.string.tally_dlui_cancel), serviceIntent(ACTION_CANCEL_ALL))
            .build()
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TallyDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val JOB_ID = 0x7a11d
        private const val ACTION_PAUSE_ALL = "io.github.scdouglas1999.tally.downloads.PAUSE_ALL"
        private const val ACTION_CANCEL_ALL = "io.github.scdouglas1999.tally.downloads.CANCEL_ALL"

        /** Starts the service so it takes the foreground while downloads run (no-op if the system refuses). */
        fun start(context: Context) {
            try {
                DownloadService.start(context, TallyDownloadService::class.java)
            } catch (e: IllegalStateException) {
                // started from the background on Android 12+: the scheduler starts it when it may
                Timber.w(e, "Download service not started")
            }
        }
    }
}

/** The download notifications: progress (from the service), and one when a download completes or fails for good. */
internal object DownloadNotifications {
    const val CHANNEL_ID = "tally_downloads"
    const val PROGRESS_ID = 0x7a11

    /** Opens the Downloads page (`wholphin://downloads`, handled at the seam in upstream's `IntentService`). */
    fun openDownloads(context: Context): PendingIntent {
        val intent =
            Intent(Intent.ACTION_VIEW, DOWNLOADS_URI.toUri())
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The link the notifications open: the Downloads page. */
    const val DOWNLOADS_URI = "wholphin://downloads"

    /** "Downloaded · Severance S1E3", opening the Downloads page. */
    fun completed(
        context: Context,
        record: DownloadRecord,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.tally_ic_notification)
                .setContentTitle(context.getString(R.string.tally_dlui_notification_done, record.shortTitle()))
                .setContentIntent(openDownloads(context))
                .setAutoCancel(true)
                .build()
        post(context, record, notification)
    }

    fun failed(
        context: Context,
        record: DownloadRecord,
        reason: String,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.tally_ic_notification)
                .setContentTitle(context.getString(R.string.tally_dl_notification_failed, record.shortTitle(), reason))
                .setContentIntent(openDownloads(context))
                .setAutoCancel(true)
                .build()
        post(context, record, notification)
    }

    private fun post(
        context: Context,
        record: DownloadRecord,
        notification: Notification,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(record.id.hashCode(), notification)
        } catch (e: SecurityException) {
            Timber.w(e, "Notification refused")
        }
    }
}

/** "Severance S1E3" for episodes, the title otherwise: what the notifications name. */
internal fun DownloadRecord.shortTitle(): String =
    if (seriesName != null && episodeNumber != null) {
        val code = seasonNumber?.let { "S${it}E$episodeNumber" } ?: "E$episodeNumber"
        "$seriesName $code"
    } else {
        title
    }

/** "Show · S1E2 · Title" for episodes, the title otherwise. */
internal fun DownloadRecord.displayTitle(): String =
    if (seriesName != null && episodeNumber != null) {
        val code = seasonNumber?.let { "S${it}E$episodeNumber" } ?: "E$episodeNumber"
        "$seriesName · $code · $title"
    } else {
        title
    }
