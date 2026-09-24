package io.github.scdouglas1999.tally.downloads

import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID

/** A downloaded subtitle file: the server's stream [index] and what the player needs to show it. */
@Serializable
data class Sidecar(
    val index: Int,
    /** "webvtt" or "ass". */
    val codec: String,
    val language: String?,
    val title: String?,
    val displayTitle: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val path: String,
)

/** The local artwork of a download. */
internal data class Artwork(
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val thumb: String?,
)

/**
 * Fetches what a download needs besides its media: artwork (poster, backdrop, logo, thumb) and subtitle files. Every
 * request goes through the authenticated client against the server's current [baseUrl].
 */
internal class DownloadAssets(
    private val client: OkHttpClient,
) {
    /** Text subtitle streams a download carries as files: all of them when converted, the external ones for Original. */
    fun sidecarStreams(
        source: MediaSourceInfo,
        converted: Boolean,
    ): List<MediaStream> =
        source.mediaStreams
            .orEmpty()
            .filter { it.type == MediaStreamType.SUBTITLE && it.isTextSubtitleStream }
            .filter { converted || it.isExternal }

    /** Downloads the subtitle streams to [dir]; throws on a network failure. */
    fun fetchSidecars(
        baseUrl: String,
        itemId: UUID,
        source: MediaSourceInfo,
        converted: Boolean,
        dir: File,
    ): List<Sidecar> {
        val sourceId = source.id ?: return emptyList()
        dir.mkdirs()
        return sidecarStreams(source, converted).mapNotNull { stream ->
            val format = sidecarFormat(stream.codec)
            val url = "${baseUrl.trimEnd('/')}/Videos/${itemId.hex()}/$sourceId/Subtitles/${stream.index}/0/Stream.$format"
            val file = File(dir, "sub-${stream.index}.$format")
            if (!fetch(url, file)) return@mapNotNull null
            Sidecar(
                index = stream.index,
                codec = if (format == "ass") "ass" else "webvtt",
                language = stream.language,
                title = stream.title,
                displayTitle = stream.displayTitle,
                isDefault = stream.isDefault,
                isForced = stream.isForced,
                path = file.absolutePath,
            )
        }
    }

    /** Poster, backdrop, logo and thumb of [item] (a show's for an episode, the album's for a track). */
    fun fetchArtwork(
        baseUrl: String,
        item: BaseItemDto,
        dir: File,
    ): Artwork {
        dir.mkdirs()
        val base = baseUrl.trimEnd('/')

        fun image(
            id: UUID?,
            type: ImageType,
            tag: String?,
            name: String,
            maxWidth: Int,
        ): String? {
            if (id == null || tag == null) return null
            val url = "$base/Items/${id.hex()}/Images/${type.serialName}?tag=$tag&maxWidth=$maxWidth&quality=90"
            val file = File(dir, name)
            return if (fetch(url, file)) file.absolutePath else null
        }
        val episode = item.type == BaseItemKind.EPISODE
        val audio = item.type == BaseItemKind.AUDIO
        val poster =
            when {
                episode && item.seriesPrimaryImageTag != null -> {
                    image(item.seriesId, ImageType.PRIMARY, item.seriesPrimaryImageTag, "poster", POSTER_WIDTH)
                }

                audio && item.albumPrimaryImageTag != null -> {
                    image(item.albumId, ImageType.PRIMARY, item.albumPrimaryImageTag, "poster", POSTER_WIDTH)
                }

                else -> {
                    image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), "poster", POSTER_WIDTH)
                }
            }
        val backdrop =
            item.backdropImageTags?.firstOrNull()?.let {
                image(item.id, ImageType.BACKDROP, it, "backdrop", BACKDROP_WIDTH)
            } ?: item.parentBackdropImageTags?.firstOrNull()?.let {
                image(item.parentBackdropItemId, ImageType.BACKDROP, it, "backdrop", BACKDROP_WIDTH)
            }
        val logo =
            item.imageTags?.get(ImageType.LOGO)?.let { image(item.id, ImageType.LOGO, it, "logo", LOGO_WIDTH) }
                ?: image(item.parentLogoItemId, ImageType.LOGO, item.parentLogoImageTag, "logo", LOGO_WIDTH)
        val thumb =
            when {
                // an episode's own primary image is its 16:9 still
                episode -> {
                    image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), "thumb", THUMB_WIDTH)
                }

                else -> {
                    item.imageTags?.get(ImageType.THUMB)?.let { image(item.id, ImageType.THUMB, it, "thumb", THUMB_WIDTH) }
                        ?: image(item.parentThumbItemId, ImageType.THUMB, item.parentThumbImageTag, "thumb", THUMB_WIDTH)
                }
            }
        return Artwork(poster, backdrop, logo, thumb)
    }

    /** True when saved; false when the server has no such file (404); throws on network trouble. */
    private fun fetch(
        url: String,
        file: File,
    ): Boolean {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${url.substringBefore('?')}")
            val body = response.body
            val partial = File(file.parentFile, file.name + ".part")
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!partial.renameTo(file)) {
                partial.delete()
                throw IOException("Cannot write $file")
            }
            Timber.v("Saved %s (%s bytes)", file.name, file.length())
            return true
        }
    }

    companion object {
        /** ASS/SSA keep their styling; every other text format becomes WebVTT. */
        fun sidecarFormat(codec: String?): String =
            when (codec?.lowercase()) {
                "ass", "ssa" -> "ass"
                else -> "vtt"
            }

        private const val POSTER_WIDTH = 600
        private const val BACKDROP_WIDTH = 1920
        private const val LOGO_WIDTH = 800
        private const val THUMB_WIDTH = 800
    }
}
