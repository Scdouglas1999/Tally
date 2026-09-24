package io.github.scdouglas1999.tally.downloads

import android.content.Context
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.scdouglas1999.tally.ui.formfactor.isTallyPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** As saved; every field optional so defaults can differ per device and new fields can be added. */
@Serializable
internal data class StoredDownloadSettings(
    /** "ASK", "ORIGINAL" or a [DownloadRung] name. */
    val defaultQuality: String? = null,
    val wifiOnly: Boolean? = null,
    val concurrentDownloads: Int? = null,
    /** 0 = off. */
    val autoDeleteWatchedAfterHours: Int? = null,
    val location: String? = null,
)

/** Download settings in Tally's own preferences ([KeyValueService]), with device-dependent defaults. */
@Singleton
class DownloadSettingsStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val keyValueService: KeyValueService,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private val phone by lazy { isTallyPhone(context) }

        val settings: StateFlow<DownloadSettings> by lazy {
            keyValueService
                .get(KEY, StoredDownloadSettings())
                .map { it.resolve() }
                .stateIn(scope, SharingStarted.Eagerly, StoredDownloadSettings().resolve())
        }

        suspend fun current(): DownloadSettings = keyValueService.get(KEY, StoredDownloadSettings()).first().resolve()

        internal suspend fun update(block: (StoredDownloadSettings) -> StoredDownloadSettings) {
            val stored = keyValueService.get(KEY, StoredDownloadSettings()).first()
            keyValueService.save(KEY, block(stored))
        }

        private fun StoredDownloadSettings.resolve(): DownloadSettings =
            DownloadSettings(
                defaultQuality = decodeQuality(defaultQuality),
                wifiOnly = wifiOnly ?: phone,
                concurrentDownloads = (concurrentDownloads ?: DEFAULT_CONCURRENT).coerceIn(1, MAX_CONCURRENT),
                autoDeleteWatchedAfterHours = autoDeleteWatchedAfterHours?.takeIf { it > 0 },
                location =
                    location?.let { name -> StorageLocation.entries.firstOrNull { it.name == name } }
                        ?: StorageLocation.INTERNAL,
            )

        internal companion object {
            const val KEY = "tally.downloads.settings"
            const val DEFAULT_CONCURRENT = 2
            const val MAX_CONCURRENT = 3

            fun encodeQuality(quality: DownloadQuality?): String =
                when (quality) {
                    null -> "ASK"
                    DownloadQuality.Original -> "ORIGINAL"
                    is DownloadQuality.Converted -> quality.rung.name
                }

            fun decodeQuality(value: String?): DownloadQuality? =
                when (value) {
                    null, "ASK" -> null
                    "ORIGINAL" -> DownloadQuality.Original
                    else -> DownloadRung.entries.firstOrNull { it.name == value }?.let { DownloadQuality.Converted(it) }
                }
        }
    }
