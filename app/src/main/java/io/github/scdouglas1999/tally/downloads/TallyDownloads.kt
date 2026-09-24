package io.github.scdouglas1999.tally.downloads

import android.content.Context
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.scdouglas1999.tally.downloads.db.DownloadRecord
import io.github.scdouglas1999.tally.downloads.db.OfflineProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.media3.exoplayer.offline.Download as Media3Download
import androidx.media3.exoplayer.scheduler.Requirements as Media3Requirements

/**
 * Downloads for offline playback: films, episodes (one, a season, a show, the next N unwatched), music tracks (a
 * track, an album, an artist's albums, a playlist) and video playlists. Never live TV or sports.
 *
 * The UI talks only to this class. Everything here is for the signed-in user of the current server; downloads of
 * other users wait until they sign in again.
 *
 * - [options] lists the qualities for an item or a group with estimated sizes and whether each is allowed.
 * - [enqueue] downloads; [all] / [entry] show progress and state; [pause], [resume], [cancel], [delete],
 *   [deleteAll] control them; [storage] shows the space used and free.
 * - Playback prefers a completed download automatically (films and episodes in the player, tracks in the music
 *   service), online too; nothing to call.
 * - [offlineMode] is true while the server cannot be reached. At startup with the server unreachable and completed
 *   downloads present the app opens [destination] (the downloads page) instead of the server list, and returns to
 *   normal by itself when the server answers ([offlineMode] turns false; the UI decides where to go).
 * - [settings] and its setters are the download settings.
 *
 * Call [start] once when the app starts (done by the app's startup seam).
 */
@Singleton
class TallyDownloads
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val engine: DownloadEngine,
        private val reachability: ServerReachability,
        private val recorder: OfflineProgressRecorder,
    ) {
        /** The page the UI draws for downloads (also the offline start page). */
        val destination: Destination = Destination.TallyDownloads

        /** True while the signed-in server cannot be reached. */
        val offlineMode: StateFlow<Boolean> get() = reachability.offline

        /** The download settings; see [DownloadSettings] for defaults. */
        val settings: StateFlow<DownloadSettings> get() = engine.settingsStore.settings

        /** Starts the engine, the reachability checks and the progress recorder (idempotent). */
        fun start() {
            engine.start()
            reachability.onBackOnline = { onBackOnline() }
            reachability.start()
            recorder.start()
            engine.scope.launch(Dispatchers.IO) {
                try {
                    if (engine.dao.pendingProgress().isNotEmpty()) ProgressSyncWorker.enqueue(context)
                    AutoDeleteWorker.schedule(context, engine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Download start-up checks failed")
                }
            }
        }

        private suspend fun onBackOnline() {
            engine.refreshUser()
            ProgressSyncWorker.enqueue(context)
            engine.retryDue(force = true)
        }

        internal fun markOffline() = reachability.markOffline()

        /** Checks the server now (for a "try again" button); [offlineMode] follows. */
        fun checkServer() = reachability.checkNow()

        /** Whether items of this kind can be downloaded at all (films, episodes, videos, tracks). */
        fun isDownloadable(item: BaseItemDto): Boolean = item.type in DownloadEngine.DOWNLOADABLE_KINDS

        /** The qualities for one item: see [options] for a group. Empty when the item cannot be downloaded. */
        suspend fun options(item: BaseItemDto): List<DownloadOption> {
            if (!isDownloadable(item)) return emptyList()
            val full = if (item.mediaSources.isNullOrEmpty()) engine.resolveTarget(DownloadTarget.Items(listOf(item.id))) else null
            return options(full?.map { it.item } ?: listOf(item))
        }

        /**
         * The qualities for a group: Original, then each rung that is below at least one item's source (music:
         * Original only). Sizes are summed over the group. Needs the server.
         */
        suspend fun options(target: DownloadTarget): List<DownloadOption> = options(engine.resolveTarget(target).map { it.item })

        private suspend fun options(items: List<BaseItemDto>): List<DownloadOption> {
            val permissions = engine.permissions()
            return DownloadPlanner.options(items.map(engine::planItem), permissions).map {
                DownloadOption(
                    quality = it.quality,
                    label = it.quality.label,
                    estimatedBytes = it.estimatedBytes,
                    allowed = it.disallowed == null,
                    reason = it.disallowed?.let(::reasonText),
                )
            }
        }

        private fun reasonText(disallowed: Disallowed): String =
            context.getString(
                when (disallowed) {
                    Disallowed.NO_DOWNLOAD_PERMISSION -> R.string.tally_dl_reason_no_download
                    Disallowed.NO_TRANSCODE_PERMISSION -> R.string.tally_dl_reason_no_transcode
                    Disallowed.NOT_DOWNLOADABLE -> R.string.tally_dl_reason_not_downloadable
                },
            )

        /**
         * Downloads [target] at [quality] (a rung not below an item's source downloads that item as Original).
         * Checks permission and free space first; nothing is queued when either fails ([EnqueueResult.error]).
         */
        suspend fun enqueue(
            target: DownloadTarget,
            quality: DownloadQuality,
        ): EnqueueResult {
            if (engine.owner() == null) return EnqueueResult(emptyList(), emptyList(), context.getString(R.string.tally_dl_error_offline))
            val items =
                try {
                    engine.resolveTarget(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not list %s", target)
                    return EnqueueResult(emptyList(), emptyList(), context.getString(R.string.tally_dl_error_offline))
                }
            if (items.isEmpty()) return EnqueueResult(emptyList(), emptyList(), context.getString(R.string.tally_dl_error_nothing))
            val planned = DownloadPlanner.options(items.map { engine.planItem(it.item) }, engine.permissions())
            val option = planned.firstOrNull { it.quality == quality } ?: planned.first()
            option.disallowed?.let { return EnqueueResult(emptyList(), emptyList(), reasonText(it)) }
            val needed = option.estimatedBytes
            val location = settings.value.location
            val free = engine.storage.freeBytes(location)
            if (needed != null && needed > free) {
                val text = context.getString(R.string.tally_dl_error_space, engine.formatBytes(needed), engine.formatBytes(free))
                return EnqueueResult(emptyList(), emptyList(), text)
            }
            val (queued, present) = engine.add(items, quality)
            return EnqueueResult(queued, present, null)
        }

        /** Every download of the signed-in user, oldest first, with live state and progress. */
        val all: Flow<List<DownloadEntry>> by lazy {
            combine(
                engine.records,
                engine.downloads,
                engine.progressRows,
                engine.notMetRequirements,
                offlineMode,
            ) { records, downloads, progress, notMet, offline ->
                val owner = engine.owner()?.let { it.first.hex() to it.second.hex() }
                val progressById = progress.associateBy { "${it.serverId}_${it.userId}_${it.itemId}" }
                records
                    .filter { owner != null && it.serverId == owner.first && it.userId == owner.second }
                    .mapNotNull { record ->
                        val download = downloads[record.id]
                        if (download?.state == Media3Download.STATE_REMOVING) return@mapNotNull null
                        toEntry(record, download, progressById[record.id], notMet, offline)
                    }
            }.conflate().distinctUntilChanged().flowOn(Dispatchers.Default)
        }

        /** One item's download (null when it is not downloaded or downloading). */
        fun entry(itemId: UUID): Flow<DownloadEntry?> = all.map { list -> list.firstOrNull { it.itemId == itemId } }.distinctUntilChanged()

        /** The item as stored with its download, for playing or showing it without the server. */
        suspend fun localItem(itemId: UUID): BaseItemDto? {
            val (serverId, userId) = engine.owner() ?: return null
            val record = engine.dao.get(engine.recordId(serverId, userId, itemId)) ?: return null
            return TallyDownloadPlayback.storedItem(engine, record)
        }

        suspend fun pause(itemId: UUID) {
            record(itemId)?.let { engine.setPaused(it, true) }
        }

        /** Resumes a paused download, or retries a failed one now. */
        suspend fun resume(itemId: UUID) {
            record(itemId)?.let { engine.setPaused(it, false) }
        }

        /** Stops an unfinished download and removes what it had. */
        suspend fun cancel(itemId: UUID) = delete(itemId)

        /** Removes a download and frees its space. */
        suspend fun delete(itemId: UUID) {
            record(itemId)?.let { deleteRecord(it) }
        }

        suspend fun delete(itemIds: Collection<UUID>) = itemIds.forEach { delete(it) }

        /** Removes every download of the signed-in user. */
        suspend fun deleteAll() {
            val (serverId, userId) = engine.owner() ?: return
            engine.dao
                .all()
                .filter { it.serverId == serverId.hex() && it.userId == userId.hex() }
                .forEach { deleteRecord(it) }
        }

        internal suspend fun deleteRecord(record: DownloadRecord) {
            engine.remove(record)
        }

        private suspend fun record(itemId: UUID): DownloadRecord? {
            val (serverId, userId) = engine.owner() ?: return null
            return engine.dao.get(engine.recordId(serverId, userId, itemId))
        }

        /** Space used by downloads and free where new ones go. */
        val storage: Flow<StorageInfo> by lazy {
            combine(engine.records, engine.downloads, settings) { _, _, settings -> settings.location }
                .conflate()
                .map { location ->
                    StorageInfo(
                        usedBytes = engine.storage.usedBytes(),
                        freeBytes = engine.storage.freeBytes(location),
                        location = location,
                        removableAvailable = engine.storage.removableAvailable(),
                    )
                }.distinctUntilChanged()
                .flowOn(Dispatchers.IO)
        }

        suspend fun setDefaultQuality(quality: DownloadQuality?) =
            engine.settingsStore.update { it.copy(defaultQuality = DownloadSettingsStore.encodeQuality(quality)) }

        suspend fun setWifiOnly(wifiOnly: Boolean) = engine.settingsStore.update { it.copy(wifiOnly = wifiOnly) }

        suspend fun setConcurrentDownloads(count: Int) =
            engine.settingsStore.update {
                it.copy(concurrentDownloads = count.coerceIn(1, DownloadSettingsStore.MAX_CONCURRENT))
            }

        /** Null or 0 turns auto-delete off. */
        suspend fun setAutoDeleteWatchedAfterHours(hours: Int?) {
            engine.settingsStore.update { it.copy(autoDeleteWatchedAfterHours = hours?.takeIf { h -> h > 0 } ?: 0) }
            AutoDeleteWorker.schedule(context, engine)
        }

        /** Where new downloads go; existing ones stay where they are. REMOVABLE only when [StorageInfo.removableAvailable]. */
        suspend fun setLocation(location: StorageLocation) = engine.settingsStore.update { it.copy(location = location.name) }

        // ------------------------------------------------------------------------------------------ mapping

        private fun toEntry(
            record: DownloadRecord,
            download: Media3Download?,
            progress: OfflineProgress?,
            notMet: Int,
            offline: Boolean,
        ): DownloadEntry? {
            val itemId = record.itemId.toUUIDOrNull() ?: return null
            // this device's progress, refreshed from the server by the sync worker
            val positionMs = progress?.positionTicks?.div(TICKS_PER_MS) ?: 0L
            val played = progress?.played ?: false
            val quality =
                if (record.quality == "ORIGINAL") {
                    DownloadQuality.Original
                } else {
                    DownloadRung.entries.firstOrNull { it.name == record.quality }?.let { DownloadQuality.Converted(it) }
                        ?: DownloadQuality.Original
                }
            return DownloadEntry(
                itemId = itemId,
                serverId = record.serverId.toUUIDOrNull() ?: return null,
                userId = record.userId.toUUIDOrNull() ?: return null,
                type = BaseItemKind.entries.firstOrNull { it.serialName == record.type } ?: BaseItemKind.VIDEO,
                title = record.title,
                seriesId = record.seriesId?.toUUIDOrNull(),
                seriesName = record.seriesName,
                seasonId = record.seasonId?.toUUIDOrNull(),
                seasonNumber = record.seasonNumber,
                episodeNumber = record.episodeNumber,
                albumId = record.albumId?.toUUIDOrNull(),
                album = record.album,
                albumArtist = record.albumArtist,
                artists =
                    record.artists
                        ?.split(DownloadRecord.ARTIST_SEPARATOR)
                        .orEmpty()
                        .filter { it.isNotEmpty() },
                playlistId = record.playlistId?.toUUIDOrNull(),
                playlistName = record.playlistName,
                playlistIndex = record.playlistIndex,
                runtimeMs = record.runtimeTicks?.div(TICKS_PER_MS),
                overview = record.overview,
                productionYear = record.productionYear,
                quality = quality,
                qualityLabel = quality.label,
                sizeBytes = record.completedBytes ?: record.estimatedBytes,
                state = stateOf(record, download, notMet, offline),
                posterPath = record.posterPath,
                backdropPath = record.backdropPath,
                logoPath = record.logoPath,
                thumbPath = record.thumbPath,
                positionMs = positionMs,
                played = played,
                createdAtMs = record.createdAt,
                completedAtMs = record.completedAt,
            )
        }

        private fun stateOf(
            record: DownloadRecord,
            download: Media3Download?,
            notMet: Int,
            offline: Boolean,
        ): DownloadState {
            record.failure?.let { name ->
                val failure = DownloadFailure.entries.firstOrNull { it.name == name } ?: DownloadFailure.UNKNOWN
                return DownloadState.Failed(engine.failureText(failure), failure.retry)
            }
            if (engine.isDone(record)) return DownloadState.Done
            if (download == null) return DownloadState.Queued(waitingFor(notMet, offline))
            return when (download.state) {
                Media3Download.STATE_COMPLETED -> {
                    DownloadState.Done
                }

                Media3Download.STATE_DOWNLOADING -> {
                    DownloadState.Downloading(fractionOf(record, download), download.bytesDownloaded)
                }

                Media3Download.STATE_STOPPED -> {
                    if (download.stopReason == STOP_PAUSED) {
                        DownloadState.Paused(fractionOf(record, download), download.bytesDownloaded)
                    } else {
                        DownloadState.Queued(context.getString(R.string.tally_dl_waiting_user))
                    }
                }

                Media3Download.STATE_FAILED -> {
                    DownloadState.Failed(engine.failureText(DownloadFailure.UNKNOWN), true)
                }

                else -> {
                    DownloadState.Queued(waitingFor(notMet, offline))
                }
            }
        }

        /** Media3's percentage (segments for HLS), else bytes over the estimate. */
        private fun fractionOf(
            record: DownloadRecord,
            download: Media3Download,
        ): Float {
            val percent = download.percentDownloaded
            val fraction =
                if (percent >= 0) {
                    percent / 100f
                } else {
                    record.estimatedBytes
                        ?.takeIf { it > 0 }
                        ?.let { (download.bytesDownloaded.toFloat() / it).coerceAtMost(0.99f) } ?: 0f
                }
            return fraction.coerceIn(0f, 1f)
        }

        private fun waitingFor(
            notMet: Int,
            offline: Boolean,
        ): String? =
            when {
                notMet and Media3Requirements.NETWORK_UNMETERED != 0 -> context.getString(R.string.tally_dl_waiting_wifi)
                notMet and Media3Requirements.NETWORK != 0 || offline -> context.getString(R.string.tally_dl_waiting_network)
                else -> null
            }
    }
