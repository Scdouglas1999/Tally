package io.github.scdouglas1999.tally.downloads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.services.PlayerFactory
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.scdouglas1999.tally.downloads.db.DownloadRecord
import io.github.scdouglas1999.tally.downloads.db.OfflineProgress
import io.github.scdouglas1999.tally.quality.TallyQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records playback progress of downloaded items on this device: once a second it notes where the player is, every
 * ten seconds and when playback pauses, ends or leaves the item it saves it. Saved while offline it is marked for
 * the server ([ProgressSyncWorker]). An episode watched to the end is stamped for auto-delete.
 *
 * It follows the upstream player through [TallyQuality.nowPlaying] (published by the player's seam) and
 * [PlayerFactory.currentPlayer]; it never changes playback.
 */
@Singleton
class OfflineProgressRecorder
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val engine: DownloadEngine,
        private val playerFactory: PlayerFactory,
        private val reachability: ServerReachability,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private var started = false

        fun start() {
            if (started) return
            started = true
            scope.launch(Dispatchers.Main) {
                TallyQuality.nowPlaying
                    .distinctUntilChangedBy { it?.item?.id }
                    .collectLatest { playback ->
                        val itemId = playback?.item?.id ?: return@collectLatest
                        val record = engine.completedRecord(itemId) ?: return@collectLatest
                        follow(record)
                    }
            }
        }

        private suspend fun follow(record: DownloadRecord) {
            val player = playerFactory.currentPlayer ?: return
            val mediaId = record.itemId.toUUIDOrNull()?.toString() ?: return
            var position = -1L
            var duration: Long? = null
            var ended = false

            fun sample() {
                if (player.currentMediaItem?.mediaId != mediaId) return
                position = player.currentPosition
                duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            }
            val listener =
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        sample()
                        if (!isPlaying && position >= 0) scope.launch { save(record, position, duration, false) }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && player.currentMediaItem?.mediaId == mediaId) {
                            ended = true
                            scope.launch { save(record, duration ?: 0L, duration, true) }
                        }
                    }
                }
            player.addListener(listener)
            try {
                var tick = 0
                while (true) {
                    delay(1_000)
                    sample()
                    if (++tick % 10 == 0 && position >= 0 && !ended) save(record, position, duration, false)
                }
            } finally {
                player.removeListener(listener)
                if (position >= 0 && !ended) {
                    withContext(NonCancellable) { save(record, position, duration, false) }
                }
            }
        }

        private suspend fun save(
            record: DownloadRecord,
            positionMs: Long,
            durationMs: Long?,
            ended: Boolean,
        ) {
            val dao = engine.dao
            val previous = dao.progress(record.serverId, record.userId, record.itemId)
            val runtime = durationMs ?: record.runtimeTicks?.div(TICKS_PER_MS)
            val progress =
                DownloadPlanner.progressAt(
                    positionMs = positionMs,
                    durationMs = runtime,
                    ended = ended,
                    wasPlayed = previous?.played == true,
                    nowMs = System.currentTimeMillis(),
                )
            val offline = reachability.offline.value || !engine.hasNetwork()
            dao.putProgress(
                OfflineProgress(
                    serverId = record.serverId,
                    userId = record.userId,
                    itemId = record.itemId,
                    positionTicks = progress.positionMs * TICKS_PER_MS,
                    played = progress.played,
                    updatedAt = progress.updatedAtMs,
                    // online, the player reports to the server itself
                    pendingSync = offline || previous?.pendingSync == true,
                ),
            )
            if (offline) ProgressSyncWorker.enqueue(context)
            if (progress.played && ended && record.type == BaseItemKind.EPISODE.serialName) {
                dao.setWatched(record.id, progress.updatedAtMs)
                AutoDeleteWorker.schedule(context, engine)
            }
        }
    }

/**
 * Reports progress recorded offline once the server can be reached (network constraint, exponential backoff), and
 * refreshes the stored progress of downloaded items from the server. Offline progress is written only when it is
 * newer than the server's LastPlayedDate ([DownloadPlanner.shouldPush]); progress made on another device wins.
 */
@HiltWorker
class ProgressSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val engine: DownloadEngine,
        private val api: ApiClient,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val (serverId, userId) = engine.owner() ?: return Result.retry()
            val dao = engine.dao
            return try {
                dao
                    .pendingProgress()
                    .filter { it.serverId == serverId.hex() && it.userId == userId.hex() }
                    .forEach { push(it) }
                pull(serverId.hex(), userId.hex())
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Progress sync failed; retrying later")
                Result.retry()
            }
        }

        private suspend fun push(row: OfflineProgress) {
            val itemId = row.itemId.toUUIDOrNull() ?: return
            val userId = row.userId.toUUIDOrNull() ?: return
            val server = api.itemsApi.getItemUserData(itemId = itemId, userId = userId).content
            val local = DownloadPlanner.LocalProgress(row.positionTicks / TICKS_PER_MS, row.played, row.updatedAt)
            val serverProgress =
                DownloadPlanner.ServerProgress(
                    positionMs = server.playbackPositionTicks / TICKS_PER_MS,
                    played = server.played,
                    lastPlayedAtMs = server.lastPlayedDate?.toEpochMs(),
                )
            if (DownloadPlanner.shouldPush(local, serverProgress)) {
                val at = row.updatedAt.toSdkDateTime()
                if (row.played && !server.played) {
                    api.playStateApi.markPlayedItem(itemId = itemId, userId = userId, datePlayed = at)
                }
                api.itemsApi.updateItemUserData(
                    itemId = itemId,
                    userId = userId,
                    data =
                        UpdateUserItemDataDto(
                            playbackPositionTicks = row.positionTicks,
                            lastPlayedDate = at,
                            played = if (row.played) true else null,
                        ),
                )
                Timber.i("Reported offline progress of %s (%s ms, played=%s)", itemId, local.positionMs, row.played)
                engine.dao.putProgress(row.copy(pendingSync = false))
            } else {
                Timber.i("Server has newer progress for %s; offline progress dropped", itemId)
                engine.dao.putProgress(
                    row.copy(
                        positionTicks = server.playbackPositionTicks,
                        played = server.played,
                        updatedAt = serverProgress.lastPlayedAtMs ?: row.updatedAt,
                        pendingSync = false,
                    ),
                )
            }
        }

        /** Stores the server's progress of every downloaded item where it is newer than what this device has. */
        private suspend fun pull(
            serverHex: String,
            userHex: String,
        ) {
            val dao = engine.dao
            dao
                .all()
                .filter { it.serverId == serverHex && it.userId == userHex && it.completedAt != null }
                .forEach { record ->
                    val itemId = record.itemId.toUUIDOrNull() ?: return@forEach
                    val userId = record.userId.toUUIDOrNull() ?: return@forEach
                    val server =
                        try {
                            api.itemsApi.getItemUserData(itemId = itemId, userId = userId).content
                        } catch (e: IOException) {
                            throw e
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // e.g. the item is gone from the server
                            Timber.w(e, "No user data for %s", itemId)
                            return@forEach
                        }
                    val serverAt = server.lastPlayedDate?.toEpochMs() ?: return@forEach
                    val local = dao.progress(record.serverId, record.userId, record.itemId)
                    if (local?.pendingSync == true) return@forEach
                    if (local == null || serverAt > local.updatedAt) {
                        dao.putProgress(
                            OfflineProgress(
                                serverId = record.serverId,
                                userId = record.userId,
                                itemId = record.itemId,
                                positionTicks = server.playbackPositionTicks,
                                played = server.played,
                                updatedAt = serverAt,
                                pendingSync = false,
                            ),
                        )
                    }
                }
        }

        companion object {
            private const val NAME = "tally-downloads-progress-sync"

            fun enqueue(context: Context) {
                val request =
                    OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
    }

/**
 * Deletes downloaded episodes whose watched-to-the-end stamp is older than the auto-delete setting, then schedules
 * itself for the next one due. Off when the setting is off.
 */
@HiltWorker
class AutoDeleteWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val downloads: TallyDownloads,
        private val engine: DownloadEngine,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val hours = engine.settingsStore.current().autoDeleteWatchedAfterHours ?: return Result.success()
            val now = System.currentTimeMillis()
            engine.dao
                .all()
                .filter { it.watchedAt != null && it.type == BaseItemKind.EPISODE.serialName }
                .filter { it.watchedAt!! + TimeUnit.HOURS.toMillis(hours.toLong()) <= now }
                .forEach {
                    Timber.i("Auto-deleting watched episode %s", it.id)
                    downloads.deleteRecord(it)
                }
            schedule(applicationContext, engine)
            return Result.success()
        }

        companion object {
            private const val NAME = "tally-downloads-auto-delete"

            /** Schedules the next run for the earliest watched episode still on the device (nothing when off). */
            suspend fun schedule(
                context: Context,
                engine: DownloadEngine,
            ) {
                val hours = engine.settingsStore.current().autoDeleteWatchedAfterHours
                val workManager = WorkManager.getInstance(context)
                if (hours == null) {
                    workManager.cancelUniqueWork(NAME)
                    return
                }
                val next =
                    engine.dao
                        .all()
                        .mapNotNull { it.watchedAt }
                        .minOrNull() ?: return
                val delayMs = (next + TimeUnit.HOURS.toMillis(hours.toLong()) - System.currentTimeMillis()).coerceAtLeast(0)
                val request =
                    OneTimeWorkRequestBuilder<AutoDeleteWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .build()
                workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

/** The SDK's dates are local date-times in the device's zone (its serializer converts to and from it). */
internal fun LocalDateTime.toEpochMs(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun Long.toSdkDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
