package io.github.scdouglas1999.tally.dvr

import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.github.scdouglas1999.tally.api.TallyJson
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A DVR call the server refused or could not answer. [code] is the HTTP status (0 = no answer), [serverMessage] the
 * server's own `{"error": "…"}` text when it sent one (403 without the permission, 409 "Someone is watching this
 * recording", …).
 */
class DvrException(
    val code: Int,
    val serverMessage: String?,
    cause: Throwable? = null,
) : Exception(serverMessage ?: "HTTP $code", cause)

/**
 * The DVR's client API on the Tally plugin (`/JellyTV/Client/v1/recordings…`), through the app's authenticated
 * [OkHttpClient]. Unlike [io.github.scdouglas1999.tally.api.TallyApi] it keeps every status and the server's error
 * text: the DVR answers 403 and 409 with a reason worth showing.
 */
@Singleton
class TallyDvrApi
    @Inject
    constructor(
        private val api: ApiClient,
        @param:AuthOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private fun url(path: String): String {
            val base = api.baseUrl?.takeIf { it.isNotBlank() } ?: throw DvrException(401, null)
            return base.trimEnd('/') + path
        }

        private suspend fun call(request: Request): String =
            withContext(WholphinDispatchers.IO) {
                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        if (response.isSuccessful) {
                            body
                        } else {
                            throw DvrException(response.code, errorText(body))
                        }
                    }
                } catch (e: IOException) {
                    throw DvrException(0, null, e)
                }
            }

        private fun errorText(body: String): String? =
            try {
                (TallyJson.decodeFromString<JsonObject>(body)["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }

        private suspend inline fun <reified T> get(path: String): T {
            val body =
                call(
                    Request
                        .Builder()
                        .url(url(path))
                        .get()
                        .build(),
                )
            return try {
                TallyJson.decodeFromString<T>(body)
            } catch (e: Exception) {
                throw DvrException(0, null, e)
            }
        }

        private suspend fun post(
            path: String,
            json: JsonObject,
        ) {
            val body = TallyJson.encodeToString(JsonObject.serializer(), json).toRequestBody("application/json".toMediaType())
            call(
                Request
                    .Builder()
                    .url(url(path))
                    .post(body)
                    .build(),
            )
        }

        private suspend fun delete(path: String) {
            call(
                Request
                    .Builder()
                    .url(url(path))
                    .delete()
                    .build(),
            )
        }

        suspend fun list(): DvrList = get(BASE)

        suspend fun storage(gameId: String? = null): DvrStorage =
            get("$BASE/storage" + (gameId?.let { "?gameId=" + URLEncoder.encode(it, "UTF-8") } ?: ""))

        /** Records one game. */
        suspend fun recordGame(gameId: String) = post(BASE, buildJsonObject { put("gameId", gameId) })

        /** Records every game of a team in [league] (as the board names it, "MLB"); keeps the last [keepLast], 0 = all. */
        suspend fun recordTeam(
            teamId: String,
            league: String,
            keepLast: Int,
        ) = post(
            BASE,
            buildJsonObject {
                put("teamId", teamId)
                put("league", league)
                put("keepLast", keepLast)
            },
        )

        /** Cancels a job that has not started, or stops a recording (what was recorded is kept). */
        suspend fun cancelJob(jobId: String) = delete("$BASE/jobs/$jobId")

        /** Deletes a finished recording (its file and library item), or dismisses a failed job. */
        suspend fun deleteRecording(jobId: String) = delete("$BASE/jobs/$jobId/recording")

        suspend fun deleteRule(ruleId: String) = delete("$BASE/rules/$ruleId")

        fun absoluteUrl(rootRelativePath: String): String? =
            api.baseUrl
                ?.takeIf { it.isNotBlank() }
                ?.trimEnd('/')
                ?.plus(rootRelativePath)

        private companion object {
            const val BASE = "/JellyTV/Client/v1/recordings"
        }
    }
