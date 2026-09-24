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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.github.damontecres.wholphin.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.scdouglas1999.tally.downloads.db.DownloadRecord
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

    override fun getDownloadManager(): DownloadManager {
        engine.start()
        return engine.manager()
    }

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val message =
            when (active.size) {
                0 -> null
                1 -> engine.titleOf(active.first().request.id)
                else -> resources.getQuantityString(R.plurals.tally_dl_notification_many, active.size, active.size)
            }
        return DownloadNotifications
            .helper(this)
            .buildProgressNotification(
                this,
                R.drawable.tally_ic_notification,
                DownloadNotifications.openApp(this),
                message,
                downloads,
                notMetRequirements,
            )
    }

    companion object {
        private const val JOB_ID = 0x7a11d

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

    @Volatile private var helper: DownloadNotificationHelper? = null

    fun helper(context: Context): DownloadNotificationHelper =
        helper ?: DownloadNotificationHelper(context.applicationContext, CHANNEL_ID).also { helper = it }

    fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun completed(
        context: Context,
        record: DownloadRecord,
    ) {
        val notification =
            helper(context).buildDownloadCompletedNotification(
                context,
                R.drawable.tally_ic_notification,
                openApp(context),
                record.displayTitle(),
            )
        post(context, record, notification)
    }

    fun failed(
        context: Context,
        record: DownloadRecord,
        reason: String,
    ) {
        val notification =
            helper(context).buildDownloadFailedNotification(
                context,
                R.drawable.tally_ic_notification,
                openApp(context),
                context.getString(R.string.tally_dl_notification_failed, record.displayTitle(), reason),
            )
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

/** "Show · S1E2 · Title" for episodes, the title otherwise. */
internal fun DownloadRecord.displayTitle(): String =
    if (seriesName != null && episodeNumber != null) {
        val code = seasonNumber?.let { "S${it}E$episodeNumber" } ?: "E$episodeNumber"
        "$seriesName · $code · $title"
    } else {
        title
    }
