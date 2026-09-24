@file:OptIn(UnstableApi::class)

package io.github.scdouglas1999.tally.downloads

import android.content.Context
import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where downloads live: one Media3 [SimpleCache] per [StorageLocation] (app-specific storage, never the shared
 * media store), plus small files (artwork, subtitles) under the app's own files directory. Media3's download index
 * and the caches share one [StandaloneDatabaseProvider].
 */
@Singleton
class DownloadStorage
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        val databaseProvider: StandaloneDatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

        private val caches = mutableMapOf<StorageLocation, SimpleCache>()

        /** The root of a location, or null when it is not available (no removable card mounted). */
        fun root(location: StorageLocation): File? =
            when (location) {
                StorageLocation.INTERNAL -> File(context.filesDir, ROOT)
                StorageLocation.REMOVABLE -> removableRoot()
            }

        fun removableAvailable(): Boolean = removableRoot() != null

        private fun removableRoot(): File? =
            try {
                context
                    .getExternalFilesDirs(null)
                    .filterNotNull()
                    .firstOrNull {
                        Environment.isExternalStorageRemovable(it) &&
                            Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                    }?.let { File(it, ROOT) }
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Removable storage state unknown")
                null
            }

        /** The media cache of [location], opened on first use; null when the location is not available. */
        @Synchronized
        fun cache(location: StorageLocation): SimpleCache? {
            caches[location]?.let { return it }
            val root = root(location) ?: return null
            val dir = File(root, "media")
            if (!dir.exists() && !dir.mkdirs()) {
                Timber.w("Cannot create the download folder %s", dir)
                return null
            }
            return SimpleCache(dir, NoOpCacheEvictor(), databaseProvider).also { caches[location] = it }
        }

        /** Artwork and subtitle files of one download (always on internal storage: they are small). */
        fun assetsDir(recordId: String): File = File(File(context.filesDir, ROOT), "assets/$recordId")

        fun deleteAssets(recordId: String) {
            assetsDir(recordId).deleteRecursively()
        }

        /** Free bytes where new downloads go. */
        fun freeBytes(location: StorageLocation): Long {
            val root = root(location) ?: return 0L
            if (!root.exists()) root.mkdirs()
            return root.usableSpace
        }

        /** Bytes used by downloads in every location that has any, plus artwork and subtitles. */
        @Synchronized
        fun usedBytes(): Long {
            // open the caches that exist on disk, so a fresh process counts them too
            StorageLocation.entries.forEach { location ->
                if (root(location)?.let { File(it, "media").isDirectory } == true) cache(location)
            }
            val media =
                caches.values.sumOf {
                    try {
                        it.cacheSpace
                    } catch (e: IllegalStateException) {
                        Timber.w(e, "Download cache released")
                        0L
                    }
                }
            val assets = File(File(context.filesDir, ROOT), "assets").walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            return media + assets
        }

        private companion object {
            const val ROOT = "tally-downloads"
        }
    }
