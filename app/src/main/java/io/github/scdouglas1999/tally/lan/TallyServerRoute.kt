package io.github.scdouglas1999.tally.lan

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.github.damontecres.wholphin.WholphinApplication
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import timber.log.Timber

/**
 * The app-wide [ServerRouter] (seam in `AppModule.okHttpClient`: its [interceptor] is on the app's base OkHttp client,
 * so the Jellyfin SDK's API calls and websocket, images, playback, downloads and the Tally API all go through it).
 *
 * An object rather than a Hilt binding because the seam only adds lines to upstream's provider, and the router has
 * to be there before the first request (the session is restored with API calls before anything else starts). The
 * routes are kept in SharedPreferences: the interceptor reads them synchronously on its first request.
 */
object TallyServerRoute {
    @Volatile
    private var instance: ServerRouter? = null

    /** The router, created on first use with the app's storage (memory only outside the app, e.g. unit tests). */
    val router: ServerRouter
        get() =
            instance ?: synchronized(this) {
                instance ?: ServerRouter(store()).also { instance = it }
            }

    val interceptor: Interceptor = Interceptor { chain -> router.interceptor.intercept(chain) }

    /** Media3's default media sources, with streams opened through the route ([RoutedDataSource]). */
    @OptIn(UnstableApi::class)
    fun mediaSources(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(RoutedDataSource.Factory(DefaultDataSource.Factory(context)))

    private fun store(): RouteStore {
        val context =
            try {
                WholphinApplication.instance.applicationContext
            } catch (e: Exception) {
                null
            } ?: return MemoryRouteStore()
        return PreferencesRouteStore(context)
    }
}

/** [RouteStore] in the app's SharedPreferences, as one JSON value. */
private class PreferencesRouteStore(
    context: Context,
) : RouteStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): Map<String, StoredRoute> =
        prefs.getString(KEY, null)?.let {
            try {
                json.decodeFromString<Map<String, StoredRoute>>(it)
            } catch (e: Exception) {
                Timber.w(e, "Stored server routes unreadable, starting over")
                null
            }
        } ?: emptyMap()

    override fun save(routes: Map<String, StoredRoute>) {
        prefs.edit { putString(KEY, json.encodeToString(routes)) }
    }

    private companion object {
        const val PREFS = "tally_server_routes"
        const val KEY = "routes"
    }
}
