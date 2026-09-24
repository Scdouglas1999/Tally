@file:OptIn(UnstableApi::class)

package io.github.scdouglas1999.tally.downloads

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.damontecres.wholphin.preferences.PlayerBackend
import io.github.scdouglas1999.tally.downloads.db.DownloadRecord
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.util.UUID

/**
 * The hooks Wholphin's player and music service call (seams W57-W59, see TALLY.md) so a completed download plays
 * from the device, online too. A download is used only when direct play is allowed (a forced transcode, a chosen
 * in-player quality or a fallback after a playback error goes to the server as before).
 */
object TallyDownloadPlayback {
    private fun engine(context: Context) = downloadsEntryPoint(context).engine()

    /** `PlaybackViewModel.init`: true while the server cannot be reached (no cinema-mode intros then). */
    fun isOffline(context: Context): Boolean {
        val entryPoint = downloadsEntryPoint(context)
        return entryPoint.downloads().offlineMode.value || !entryPoint.engine().hasNetwork()
    }

    /**
     * `PlaybackViewModel.init`: the stored item while the server cannot be reached (offline mode or no network),
     * with this device's progress; null to ask the server as usual.
     */
    suspend fun offlineItem(
        context: Context,
        itemId: UUID,
    ): BaseItemDto? {
        if (!isOffline(context)) return null
        val engine = engine(context)
        val record = engine.completedRecord(itemId) ?: return null
        Timber.i("Offline: playing %s from its download", itemId)
        return storedItem(engine, record)
    }

    /** `PlaybackViewModel.play`: the download's media source (its streams as the download has them), or null. */
    suspend fun localSource(
        context: Context,
        itemId: UUID,
        forceTranscoding: Boolean,
    ): MediaSourceInfo? {
        if (forceTranscoding) return null
        val engine = engine(context)
        val record = engine.completedRecord(itemId) ?: return null
        return localSource(engine, record)
    }

    /** `PlaybackViewModel.changeStreams`: the playback answer for the download instead of the server's, or null. */
    suspend fun playbackInfo(
        context: Context,
        itemId: UUID,
        enableDirectPlay: Boolean,
    ): Response<PlaybackInfoResponse>? {
        if (!enableDirectPlay) return null
        val engine = engine(context)
        val record = engine.completedRecord(itemId) ?: return null
        val source = localSource(engine, record) ?: return null
        Timber.i("Playing %s from its download (%s)", itemId, record.quality)
        return Response(
            content = PlaybackInfoResponse(mediaSources = listOf(source), playSessionId = null, errorCode = null),
            status = 200,
            headers = emptyMap(),
        )
    }

    /** `PlaybackViewModel.createPlayer`: downloads play in ExoPlayer (it reads Tally's download cache); null = as chosen. */
    suspend fun backendFor(
        context: Context,
        itemId: UUID,
    ): PlayerBackend? = engine(context).completedRecord(itemId)?.let { PlayerBackend.EXO_PLAYER }

    /** `PlayerFactory`: the player's [factory] reads downloads first, then [upstream] (what it used before). */
    fun readLocalCopies(
        context: Context,
        factory: DefaultMediaSourceFactory,
        upstream: DataSource.Factory,
    ) {
        factory.setDataSourceFactory(LocalFirstDataSource.Factory(upstream, engine(context).lookup))
    }

    /** `MusicService.convert`: the download of a track to play instead of the server stream, or null. */
    fun localAudioUri(
        context: Context,
        itemId: UUID,
    ): String? = engine(context).completed(itemId)?.mediaUri

    internal fun storedItem(
        engine: DownloadEngine,
        record: DownloadRecord,
    ): BaseItemDto? =
        try {
            val item = engine.json.decodeFromString(BaseItemDto.serializer(), record.itemJson)
            val progress =
                engine.progressRows.value.firstOrNull {
                    it.serverId == record.serverId && it.userId == record.userId &&
                        it.itemId == record.itemId
                }
            if (progress == null) {
                item
            } else {
                val data =
                    item.userData
                        ?: UserItemDataDto(
                            key = record.itemId,
                            itemId = item.id,
                            playbackPositionTicks = 0,
                            playCount = 0,
                            isFavorite = false,
                            played = false,
                        )
                item.copy(userData = data.copy(playbackPositionTicks = progress.positionTicks, played = progress.played))
            }
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Stored item of %s unreadable", record.id)
            null
        }

    /**
     * The media source of a download as the player must see it: played directly from its `tallydl://` URI, the
     * streams it really has (a converted download: H.264, its one audio stream, subtitles as files), subtitle files
     * as external streams at their server URL (the player's data source serves them from the device).
     */
    internal fun localSource(
        engine: DownloadEngine,
        record: DownloadRecord,
    ): MediaSourceInfo? {
        val item = storedItem(engine, record) ?: return null
        val stored = item.mediaSources?.firstOrNull { it.id == record.sourceId } ?: item.mediaSources?.firstOrNull() ?: return null
        val converted = record.quality != "ORIGINAL"
        val sidecars = engine.sidecars(record).associateBy { it.index }
        val itemHex = item.id.hex()
        val streams =
            stored.mediaStreams.orEmpty().mapNotNull { stream ->
                when (stream.type) {
                    MediaStreamType.VIDEO -> {
                        if (converted) stream.copy(codec = "h264", isInterlaced = false) else stream
                    }

                    // A converted download carries one audio stream, muxed in the HLS segments. It is not listed: the
                    // player would select it by the stream ids of an HLS track, which HLS does not have, and fall back
                    // to the server. With a single track there is nothing to choose.
                    MediaStreamType.AUDIO -> {
                        if (converted) null else stream
                    }

                    MediaStreamType.SUBTITLE -> {
                        val sidecar = sidecars[stream.index]
                        when {
                            sidecar != null -> {
                                stream.copy(
                                    codec = sidecar.codec,
                                    isExternal = true,
                                    isTextSubtitleStream = true,
                                    deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
                                    deliveryUrl =
                                        "/Videos/$itemHex/${stored.id}/Subtitles/${stream.index}/0/Stream." +
                                            DownloadAssets.sidecarFormat(sidecar.codec),
                                )
                            }

                            // not in the file and not downloaded
                            converted || stream.isExternal -> {
                                null
                            }

                            else -> {
                                stream.copy(deliveryMethod = SubtitleDeliveryMethod.EMBED, deliveryUrl = null)
                            }
                        }
                    }

                    else -> {
                        stream
                    }
                }
            }
        val rung = DownloadRung.entries.firstOrNull { it.name == record.quality }
        return stored.copy(
            path = record.mediaUri,
            isRemote = true,
            supportsDirectPlay = true,
            supportsDirectStream = false,
            supportsTranscoding = false,
            transcodingUrl = null,
            container = if (converted) "hls" else stored.container,
            mediaStreams = streams,
            defaultAudioStreamIndex = if (converted) null else stored.defaultAudioStreamIndex,
            bitrate = rung?.let { it.videoBitsPerSecond + DownloadRung.AUDIO_BITS_PER_SECOND } ?: stored.bitrate,
            size = record.completedBytes ?: stored.size,
        )
    }
}
