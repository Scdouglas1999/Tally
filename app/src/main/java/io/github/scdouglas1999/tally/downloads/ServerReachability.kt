package io.github.scdouglas1999.tally.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.scdouglas1999.tally.lan.TallyServerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the signed-in server answers. It is checked when the network changes, when asked ([checkNow]), every
 * minute while someone watches [offline], and while offline on a backoff (5 s doubling to a minute) until it answers
 * again. The check is the server route's ([TallyServerRoute]): the server counts as reachable when any of its
 * addresses (the saved one, its home addresses) answers `/System/Info/Public` with its id.
 */
@Singleton
class ServerReachability
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
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

        /**
         * Asks every address of the server (the saved one and its home addresses, [TallyServerRoute]); returns true
         * when one answered. Offline means neither answers.
         */
        suspend fun check(): Boolean {
            val server = serverRepository.current.value?.server
            if (server == null) {
                setOffline(false)
                return false
            }
            val reachable =
                hasNetwork() &&
                    withContext(Dispatchers.IO) {
                        val router = TallyServerRoute.router
                        router.register(server.id.toString(), server.url)
                        router.evaluate(server.id.toString())
                    }
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

        private companion object {
            const val FIRST_RETRY_MS = 5_000L
            const val MAX_RETRY_MS = 60_000L
            const val HEARTBEAT_MS = 60_000L
        }
    }
