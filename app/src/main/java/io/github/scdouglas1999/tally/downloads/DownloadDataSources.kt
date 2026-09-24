@file:OptIn(UnstableApi::class)

package io.github.scdouglas1999.tally.downloads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Downloads are addressed as `tallydl://<server>/<location>/<user>/<server path>?<query>`: the same URI is the
 * Media3 cache key and what the player opens, so a download does not depend on the server's address (which can
 * change) and relative HLS URIs resolve inside the scheme. For the network the prefix is swapped for the server's
 * current base URL ([resolver]).
 */
internal object TallyUri {
    const val SCHEME = "tallydl"

    data class Parts(
        val serverHex: String,
        val location: StorageLocation,
        val userHex: String,
        /** The server path and query, starting with `/`. */
        val rest: String,
    )

    fun build(
        serverId: UUID,
        location: StorageLocation,
        userId: UUID,
        pathAndQuery: String,
    ): String = "$SCHEME://${serverId.hex()}/${location.code()}/${userId.hex()}$pathAndQuery"

    fun parse(uri: Uri): Parts? {
        if (uri.scheme != SCHEME) return null
        val server = uri.host ?: return null
        val path = uri.encodedPath ?: return null
        val segments = path.trimStart('/').split('/', limit = 3)
        if (segments.size < 3) return null
        val location =
            when (segments[0]) {
                "i" -> StorageLocation.INTERNAL
                "r" -> StorageLocation.REMOVABLE
                else -> return null
            }
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        return Parts(server, location, segments[1], "/" + segments[2] + query)
    }

    private fun StorageLocation.code() =
        when (this) {
            StorageLocation.INTERNAL -> "i"
            StorageLocation.REMOVABLE -> "r"
        }

    /**
     * For the network: `tallydl://…` becomes `<baseUrl><server path>`. [baseUrlFor] gives the server's current URL by
     * its hex id (null when unknown, which fails the request).
     */
    fun resolve(
        uri: Uri,
        baseUrlFor: (String) -> String?,
    ): Uri? {
        val parts = parse(uri) ?: return null
        val base = baseUrlFor(parts.serverHex) ?: throw IOException("Server of this download is not signed in")
        return Uri.parse(base.trimEnd('/') + parts.rest)
    }
}

/**
 * The downloader's network source: opens `tallydl://` URIs at the server's current address and reports the
 * `tallydl://` URI back ([getUri]), so HLS playlists resolve their relative URIs inside the scheme (their cache keys)
 * and the cache never records the server address as a redirect.
 */
internal class TallyNetworkDataSource(
    private val upstream: DataSource,
    private val baseUrlFor: (String) -> String?,
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
        private val baseUrlFor: (String) -> String?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TallyNetworkDataSource(upstream.createDataSource(), baseUrlFor)
    }

    private var reportedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val resolved = TallyUri.resolve(dataSpec.uri, baseUrlFor)
        reportedUri = dataSpec.uri
        return upstream.open(if (resolved != null) dataSpec.withUri(resolved) else dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = reportedUri?.takeIf { TallyUri.parse(it) != null } ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        reportedUri = null
        upstream.close()
    }
}

internal fun UUID.hex(): String = toString().replace("-", "")

/** What the playback data source asks the download engine. */
internal interface DownloadLookup {
    /** A read-only source on the cache holding [parts], or null when that storage is not available. */
    fun cacheSource(parts: TallyUri.Parts): DataSource?

    /** The local file of a downloaded subtitle when [uri] is the server URL of one, else null. */
    fun sidecarFile(uri: Uri): File?
}

/**
 * The player's data source with downloads in front: `tallydl://` URIs read the download cache, server URLs of
 * downloaded subtitles read the local file, everything else goes to [upstream] untouched.
 */
internal class LocalFirstDataSource(
    private val upstream: DataSource,
    private val lookup: DownloadLookup,
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
        private val lookup: DownloadLookup,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = LocalFirstDataSource(upstream.createDataSource(), lookup)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val parts = TallyUri.parse(uri)
        if (parts != null) {
            val cache = lookup.cacheSource(parts) ?: throw IOException("Download storage not available")
            listeners.forEach(cache::addTransferListener)
            current = cache
            return cache.open(dataSpec)
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            val file = lookup.sidecarFile(uri)
            if (file != null) {
                val source = FileDataSource()
                listeners.forEach(source::addTransferListener)
                current = source
                return source.open(dataSpec.withUri(Uri.fromFile(file)))
            }
        }
        current = upstream
        return upstream.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(current) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = current
        current = null
        source?.close()
    }
}

/** `/Videos/<item>/<source>/Subtitles/<index>/…` → (item hex, index). */
internal fun subtitlePathKey(path: String?): Pair<String, Int>? {
    val match = SUBTITLE_PATH.find(path ?: return null) ?: return null
    val item = match.groupValues[1].replace("-", "").lowercase()
    val index = match.groupValues[2].toIntOrNull() ?: return null
    return item to index
}

private val SUBTITLE_PATH = Regex("/Videos/([0-9a-fA-F-]{32,36})/[^/]+/Subtitles/(\\d+)/", RegexOption.IGNORE_CASE)
