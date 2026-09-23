package io.github.scdouglas1999.tally.api

import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failures talking to the Tally plugin endpoints.
 */
sealed class TallyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The server has no Tally plugin (404); the Tally section must not appear. */
    data object NotInstalled : TallyException("The Tally server plugin is not installed")

    /** No server/user, or the token was rejected (401/403). */
    data object SignedOut : TallyException("Not signed in")

    class Http(
        val code: Int,
    ) : TallyException("HTTP $code")

    class Network(
        cause: IOException,
    ) : TallyException("Network error", cause)

    class BadResponse(
        cause: Throwable,
    ) : TallyException("Malformed response", cause)
}

/**
 * Thin client for the `Jellyfin.Plugin.JellyTV` client API (`/JellyTV/Client/v1`).
 *
 * Uses the app's authenticated [OkHttpClient] so every request carries the user's
 * Authorization header, and [ApiClient.baseUrl] as the server root.
 */
@Singleton
class TallyApi
    @Inject
    constructor(
        private val api: ApiClient,
        @param:AuthOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private fun baseUrl(): String {
            val base = api.baseUrl
            if (base.isNullOrBlank()) throw TallyException.SignedOut
            return base.trimEnd('/')
        }

        /**
         * Resolves a root-relative server path (e.g. `hlsPath`, `cardPath`) to an
         * absolute URL, or null when there is no server (signed out).
         */
        fun absoluteUrl(rootRelativePath: String): String? =
            api.baseUrl
                ?.takeIf { it.isNotBlank() }
                ?.trimEnd('/')
                ?.plus(rootRelativePath)

        private suspend fun execute(request: Request): String =
            withContext(WholphinDispatchers.IO) {
                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        when {
                            response.isSuccessful -> body
                            response.code == 404 -> throw TallyException.NotInstalled
                            response.code == 401 || response.code == 403 -> throw TallyException.SignedOut
                            else -> throw TallyException.Http(response.code)
                        }
                    }
                } catch (e: IOException) {
                    throw TallyException.Network(e)
                }
            }

        private suspend fun get(path: String): String =
            execute(
                Request
                    .Builder()
                    .url(baseUrl() + path)
                    .get()
                    .build(),
            )

        private suspend inline fun <reified T> getDecoded(path: String): T = decode(get(path))

        private inline fun <reified T> decode(body: String): T =
            try {
                TallyJson.decodeFromString<T>(body)
            } catch (e: Exception) {
                throw TallyException.BadResponse(e)
            }

        suspend fun info(): TallyInfo = getDecoded("/JellyTV/Client/v1/info")

        suspend fun board(since: Long?): TallyBoard = getDecoded("/JellyTV/Client/v1/board" + (since?.let { "?since=$it" } ?: ""))

        suspend fun channel(id: String): TallyChannel = getDecoded("/JellyTV/Client/v1/channels/$id")

        /**
         * The raw settings document. It is shared with the web UI, so it is kept as a
         * [JsonObject] to round-trip keys this app does not know about.
         */
        suspend fun settingsRaw(): JsonObject =
            try {
                TallyJson.decodeFromString<JsonObject>(get("/JellyTV/Client/v1/settings"))
            } catch (e: TallyException) {
                throw e
            } catch (e: Exception) {
                throw TallyException.BadResponse(e)
            }

        suspend fun putSettingsRaw(settings: JsonObject) {
            val body =
                TallyJson
                    .encodeToString(JsonObject.serializer(), settings)
                    .toRequestBody("application/json".toMediaType())
            execute(
                Request
                    .Builder()
                    .url(baseUrl() + "/JellyTV/Client/v1/settings")
                    .put(body)
                    .build(),
            )
        }
    }
