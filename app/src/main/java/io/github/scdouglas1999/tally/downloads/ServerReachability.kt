package io.github.scdouglas1999.tally.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the signed-in server answers. It is checked when the network changes, when asked ([checkNow]), every
 * minute while someone watches [offline], and while offline on a backoff (5 s doubling to a minute) until it answers
 * again. The check is Jellyfin's unauthenticated `/System/Ping` with a short timeout.
 */
@Singleton
class ServerReachability
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
        @param:StandardOkHttpClient client: OkHttpClient,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private val pingClient =
            client
                .newBuilder()
                .connectTimeout(PING_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(PING_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(PING_TIMEOUT_S * 2, TimeUnit.SECONDS)
                .build()

        private val _offline = MutableStateFlow(false)
        val offline: StateFlow<Boolean> = _offline.asStateFlow()

        /** Called with true when the server answers again after being unreachable. */
        var onBackOnline: suspend () -> Unit = {}

        private val started = AtomicBoolean(false)
        private var retryJob: Job? = null

        fun start() {
            if (!started.compareAndSet(false, true)) return
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            try {
                connectivity?.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = checkNow()

                        override fun onLost(network: Network) = checkNow()

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) checkNow()
                        }
                    },
                )
            } catch (e: RuntimeException) {
                Timber.w(e, "No network callback")
            }
            scope.launch {
                // a slow heartbeat only while the UI watches offline mode
                while (true) {
                    delay(HEARTBEAT_MS)
                    if (_offline.subscriptionCount.value > 0 && !_offline.value) check()
                }
            }
        }

        /** Marks the server unreachable right away (the app could not reach it) and starts retrying. */
        fun markOffline() {
            setOffline(true)
        }

        fun checkNow() {
            scope.launch { check() }
        }

        /** Pings the server; returns true when it answered. */
        suspend fun check(): Boolean {
            val base =
                serverRepository.current.value
                    ?.server
                    ?.url
            if (base == null) {
                setOffline(false)
                return false
            }
            val reachable = hasNetwork() && ping(base)
            setOffline(!reachable)
            return reachable
        }

        private fun setOffline(offline: Boolean) {
            val was = _offline.value
            _offline.value = offline
            if (offline && retryJob?.isActive != true) {
                retryJob =
                    scope.launch {
                        var wait = FIRST_RETRY_MS
                        while (_offline.value) {
                            delay(wait)
                            wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                            check()
                        }
                    }
            }
            if (was && !offline) {
                Timber.i("Server reachable again")
                scope.launch { onBackOnline() }
            } else if (!was && offline) {
                Timber.i("Server unreachable: offline mode")
            }
        }

        private fun hasNetwork(): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private suspend fun ping(base: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    pingClient
                        .newCall(Request.Builder().url(base.trimEnd('/') + "/System/Ping").build())
                        .execute()
                        .use { it.isSuccessful }
                } catch (e: IOException) {
                    Timber.d("Server ping failed: %s", e.message)
                    false
                } catch (e: IllegalArgumentException) {
                    Timber.w(e, "Bad server URL")
                    false
                }
            }

        private companion object {
            const val PING_TIMEOUT_S = 5L
            const val FIRST_RETRY_MS = 5_000L
            const val MAX_RETRY_MS = 60_000L
            const val HEARTBEAT_MS = 60_000L
        }
    }
