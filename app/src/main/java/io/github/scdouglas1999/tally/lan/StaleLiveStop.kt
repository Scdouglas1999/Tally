package io.github.scdouglas1999.tally.lan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import timber.log.Timber

/**
 * The stop report of a live stream the server has already closed ([RouteRecovery]).
 *
 * Jellyfin closes a live channel's stream when the device's websocket drops, and the stream id of a channel is the
 * same every time it is opened. When playback is requested again, upstream reports the old playback stopped after
 * the new stream opened; with the old stream's id, that report closes the new one (the new stream then answers
 * 404 a few seconds in). So the one stop report of the recovered session is sent without its live stream id: the
 * server still records the old playback as stopped, and the stream it already closed is not closed again.
 */
object StaleLiveStop {
    @Volatile
    private var session: String? = null

    /** The next stop report for [playSessionId] leaves out its live stream id. */
    fun expect(playSessionId: String?) {
        session = playSessionId
    }

    fun filter(request: Request): Request {
        val expected = session ?: return request
        if (request.method != "POST" || !request.url.encodedPath.endsWith("/Sessions/Playing/Stopped", ignoreCase = true)) {
            return request
        }
        val body = request.body ?: return request
        return try {
            val text = Buffer().also { body.writeTo(it) }.readUtf8()
            val json = Json.parseToJsonElement(text).jsonObject
            if (json["PlaySessionId"]?.jsonPrimitive?.content != expected) return request
            session = null
            val stripped = JsonObject(json.filterKeys { it != "LiveStreamId" })
            Timber.i("Stop report of session %s sent without its live stream (already closed by the server)", expected)
            request
                .newBuilder()
                .post(stripped.toString().toRequestBody(body.contentType()))
                .build()
        } catch (e: Exception) {
            Timber.w(e, "Stop report left as it was")
            request
        }
    }
}
