package io.github.scdouglas1999.tally.downloads.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Tally's own database for downloads, separate from Wholphin's `AppDatabase` (which is versioned upstream and must not
 * get Tally tables). Version 1; future changes add migrations here.
 */
@Database(
    entities = [DownloadRecord::class, OfflineProgress::class],
    version = 1,
    exportSchema = true,
)
abstract class TallyDownloadsDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        const val NAME = "tally_downloads.db"
    }
}

/**
 * One download: who it belongs to, what the item is (enough to list and play it without the server: [itemJson] is
 * the item as the server returned it, media sources and streams included), where its media is and what state the
 * Tally side of it is in. The byte-level state lives in Media3's download index under the same [id].
 */
@Entity(
    tableName = "downloads",
    indices = [Index("serverId", "userId"), Index("itemId")],
)
data class DownloadRecord(
    /** `<server>_<user>_<item>` (hex ids); also the Media3 download id. */
    @PrimaryKey val id: String,
    val serverId: String,
    val userId: String,
    val itemId: String,
    /** BaseItemKind serial name. */
    val type: String,
    val title: String,
    val seriesId: String?,
    val seriesName: String?,
    val seasonId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val albumId: String?,
    val album: String?,
    val albumArtist: String?,
    /** Joined with [ARTIST_SEPARATOR]. */
    val artists: String?,
    val playlistId: String?,
    val playlistName: String?,
    val playlistIndex: Int?,
    val runtimeTicks: Long?,
    val overview: String?,
    val productionYear: Int?,
    /** "ORIGINAL" or a [io.github.scdouglas1999.tally.downloads.DownloadRung] name. */
    val quality: String,
    val estimatedBytes: Long?,
    /** A [io.github.scdouglas1999.tally.downloads.StorageLocation] name. */
    val location: String,
    /** The `tallydl://` URI of the media (a file or an HLS multivariant playlist). */
    val mediaUri: String,
    val mimeType: String?,
    val sourceId: String?,
    /** Converted downloads: the one audio stream they carry (server index). */
    val audioStreamIndex: Int?,
    val itemJson: String,
    /** JSON list of [io.github.scdouglas1999.tally.downloads.Sidecar]. */
    val sidecarsJson: String,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val thumbPath: String?,
    /** Artwork and subtitle files fetched and the media handed to Media3. */
    val assetsReady: Boolean,
    /** A [io.github.scdouglas1999.tally.downloads.DownloadFailure] name, null when not failed. */
    val failure: String?,
    val failureCount: Int,
    val nextRetryAt: Long?,
    /** Bytes on the device once complete. */
    val completedBytes: Long?,
    val createdAt: Long,
    val completedAt: Long?,
    /** When it was watched to the end on this device (auto-delete). */
    val watchedAt: Long?,
) {
    companion object {
        const val ARTIST_SEPARATOR = "\u001f"
    }
}

/** Playback progress recorded on this device; [pendingSync] until it has been reported to the server. */
@Entity(
    tableName = "offline_progress",
    primaryKeys = ["serverId", "userId", "itemId"],
)
data class OfflineProgress(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val positionTicks: Long,
    val played: Boolean,
    val updatedAt: Long,
    val pendingSync: Boolean,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads ORDER BY createdAt")
    suspend fun all(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE downloads SET failure = :failure, failureCount = :count, nextRetryAt = :nextRetryAt WHERE id = :id",
    )
    suspend fun setFailure(
        id: String,
        failure: String?,
        count: Int,
        nextRetryAt: Long?,
    )

    @Query("UPDATE downloads SET completedAt = :at, completedBytes = :bytes, failure = NULL WHERE id = :id")
    suspend fun setCompleted(
        id: String,
        at: Long,
        bytes: Long,
    )

    @Query("UPDATE downloads SET watchedAt = :at WHERE id = :id")
    suspend fun setWatched(
        id: String,
        at: Long?,
    )

    @Query("SELECT * FROM offline_progress")
    fun observeProgress(): Flow<List<OfflineProgress>>

    @Query("SELECT * FROM offline_progress WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    suspend fun progress(
        serverId: String,
        userId: String,
        itemId: String,
    ): OfflineProgress?

    @Query("SELECT * FROM offline_progress WHERE pendingSync = 1")
    suspend fun pendingProgress(): List<OfflineProgress>

    @Upsert
    suspend fun putProgress(progress: OfflineProgress)

    @Query("DELETE FROM offline_progress WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    suspend fun deleteProgress(
        serverId: String,
        userId: String,
        itemId: String,
    )
}
