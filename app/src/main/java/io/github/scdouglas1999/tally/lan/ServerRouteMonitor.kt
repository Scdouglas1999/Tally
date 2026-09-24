package io.github.scdouglas1999.tally.lan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.DefaultSocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ServerRouteEntryPoint {
    fun serverRouteMonitor(): ServerRouteMonitor
}

/**
 * Keeps the signed-in server's route (see [ServerRouter]) up to date, on a TV and on a phone:
 * - learns the server's other addresses: its LocalAddress (every check asks `/System/Info/Public`, which reports it)
 *   and Jellyfin's UDP discovery on the network (upstream's `discoverLocalServers`, only with the local network
 *   permission where Android asks for it), when the session starts and whenever the network changes;
 * - checks every address when the network changes, every 30 seconds while a preferred (home) address is not the
 *   one in use, every 15 seconds while nothing answers and every 5 minutes otherwise, and switches back to the
 *   preferred address when it answers again;
 * - reopens the websocket through the new address when the address it was opened through stopped answering (remote
 *   control, watch parties); a socket on an address that still works moves at its next reconnect.
 */
@Singleton
class ServerRouteMonitor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val jellyfin: Jellyfin,
        private val apiClient: ApiClient,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private val router get() = TallyServerRoute.router
        private val started = AtomicBoolean(false)
        private val networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun start() {
            if (!started.compareAndSet(false, true)) return
            // the servers already known: pick their best address before the session is restored
            scope.launch(Dispatchers.IO) {
                router.status.value.keys
                    .forEach { router.evaluate(it) }
            }
            registerNetworkCallback()
            scope.launch {
                serverRepository.current
                    .map { it?.server }
                    .distinctUntilChanged { a, b -> a?.id == b?.id && a?.url == b?.url }
                    .collectLatest { server -> if (server != null) follow(server) }
            }
            scope.launch {
                router.switches.collect { switch -> reopenSocket(switch) }
            }
        }

        /** Checks every address of the signed-in server now. True when one answers. */
        suspend fun checkNow(): Boolean {
            val server = serverRepository.current.value?.server ?: return false
            router.register(server.id.toString(), server.url)
            return withContext(Dispatchers.IO) { router.evaluate(server.id.toString()) }
        }

        /** The session's server: learn its addresses, then keep checking them until the server changes. */
        private suspend fun follow(server: JellyfinServer) {
            val id = server.id.toString()
            router.register(id, server.url)
            coroutineScope {
                launch {
                    networkChanged.collectLatest {
                        // let the new network settle (DHCP, DNS) before asking it anything
                        delay(NETWORK_SETTLE_MS)
                        discover(server)
                        withContext(Dispatchers.IO) { router.evaluate(id) }
                    }
                }
                discover(server)
                while (true) {
                    withContext(Dispatchers.IO) { router.evaluate(id) }
                    val status = router.status.value[normalizeServerId(server.id)]
                    val wait =
                        when {
                            status?.reachable == false -> OFFLINE_CHECK_MS
                            !router.onPreferred(id) -> SWITCH_BACK_CHECK_MS
                            else -> SLOW_CHECK_MS
                        }
                    // a switch between checks (a request found its address dead) changes the pace: start over
                    withTimeoutOrNull(wait) { router.switches.first { it.serverId == normalizeServerId(server.id) } }
                    if (router.status.value[normalizeServerId(server.id)]?.active != status?.active) {
                        // just switched: the next check after the short pace, not at once
                        delay(SWITCH_BACK_CHECK_MS)
                    }
                }
            }
        }

        /** Jellyfin's UDP discovery: addresses of this server found on the network are home addresses. */
        private suspend fun discover(server: JellyfinServer) {
            if (!hasLocalNetworkPermission()) return
            val wanted = normalizeServerId(server.id)
            try {
                withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                    jellyfin.discovery.discoverLocalServers().collect { found ->
                        if (normalizeServerId(found.id) == wanted) {
                            router.learn(server.id.toString(), found.address, home = true)
                        } else {
                            Timber.v("Discovery: %s at %s is another server", found.id, found.address)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Server discovery failed")
            }
        }

        private fun hasLocalNetworkPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        private fun registerNetworkCallback() {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
            try {
                connectivity.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        // capabilities change often (signal strength): only a new network or a change of its
                        // validation (internet reachable or not) is worth a check
                        private var last: Pair<Network?, Boolean>? = null

                        private fun changed(state: Pair<Network?, Boolean>) {
                            if (state == last) return
                            last = state
                            networkChanged.tryEmit(Unit)
                        }

                        override fun onAvailable(network: Network) = changed(network to false)

                        override fun onLost(network: Network) = changed(null to false)

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) = changed(network to networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    },
                )
            } catch (e: RuntimeException) {
                Timber.w(e, "No network callback")
            }
        }

        /** After a switch, the websocket is opened again so it runs over the address now in use. */
        private fun reopenSocket(switch: RouteSwitch) {
            val server = serverRepository.current.value?.server ?: return
            if (normalizeServerId(server.id) != switch.serverId) return
            val socket = apiClient.webSocket as? DefaultSocketApi ?: return
            // a socket that is (re)connecting goes through the route already: its handshake reaches the new address
            if (socket.state.value !is SocketApiState.Connected) return
            // Only away from an address that stopped answering: a socket there is dead or hanging. When the path
            // merely improved (home is back), the working socket stays: closing it would end the server session,
            // and with it a transcode or live stream that is playing.
            if (!switch.failure || router.socketAddress(switch.serverId) != switch.from) return
            Timber.i("Server route: reopening the websocket through %s", switch.to)
            socket.notifyApiClientUpdate()
        }

        companion object {
            const val NETWORK_SETTLE_MS = 1_500L
            const val DISCOVERY_TIMEOUT_MS = 5_000L
            const val OFFLINE_CHECK_MS = 15_000L
            const val SWITCH_BACK_CHECK_MS = 30_000L
            const val SLOW_CHECK_MS = 5 * 60_000L

            /** Starts the monitor (app start, `TallyOfflineStart.onAppStart`); nothing where there is no Hilt graph. */
            fun start(context: Context) {
                try {
                    EntryPointAccessors
                        .fromApplication(context.applicationContext, ServerRouteEntryPoint::class.java)
                        .serverRouteMonitor()
                } catch (e: RuntimeException) {
                    // not a Hilt app (a unit test's plain or mocked context)
                    null
                }?.start()
            }
        }
    }
