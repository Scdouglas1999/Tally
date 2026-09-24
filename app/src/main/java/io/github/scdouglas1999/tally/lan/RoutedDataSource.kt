package io.github.scdouglas1999.tally.lan

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * The video player's network source through the server route: upstream's player reads streams with Media3's own
 * HTTP stack (not the app's OkHttp client), so the route's interceptor never sees them. This opens each stream,
 * playlist and segment of a known server at its active address, and when that address does not answer, moves the
 * route to the next one that does (same server id) and opens there. A read that breaks later is retried by the
 * player, which opens again here: playback carries on from where it was.
 */
@OptIn(UnstableApi::class)
class RoutedDataSource(
    private val upstream: DataSource,
    private val router: () -> ServerRouter,
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
        private val router: () -> ServerRouter = { TallyServerRoute.router },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutedDataSource(upstream.createDataSource(), router)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        if (scheme != "http" && scheme != "https") return upstream.open(dataSpec)
        val url = dataSpec.uri.toString().toHttpUrlOrNull() ?: return upstream.open(dataSpec)
        val routes = router()
        val target = routes.target(url) ?: return upstream.open(dataSpec)
        try {
            return upstream.open(dataSpec.withUri(Uri.parse(target.url.toString())))
        } catch (e: IOException) {
            if (!unreachable(e)) throw e
            try {
                upstream.close()
            } catch (closing: IOException) {
                e.addSuppressed(closing)
            }
            val next = routes.failover(target.serverId, target.active)
            if (next == null || next == target.active) throw e
            val again = routes.target(url) ?: throw e
            return upstream.open(dataSpec.withUri(Uri.parse(again.url.toString())))
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        /**
         * The address did not answer (connection refused, timeout, no route, a gateway saying the server is not
         * there), as opposed to the server answering with an error, or the player canceling the load.
         */
        internal fun unreachable(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    return cause.responseCode == 502 || cause.responseCode == 504
                }
                if (cause is InterruptedIOException && cause !is SocketTimeoutException) return false
                cause = cause.cause
            }
            return true
        }
    }
}
