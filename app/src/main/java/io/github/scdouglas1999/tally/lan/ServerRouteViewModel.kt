package io.github.scdouglas1999.tally.lan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The signed-in server and the path to it, for the server row in Settings. */
data class ServerPath(
    val serverName: String,
    val status: RouteStatus?,
)

@HiltViewModel
class ServerRouteViewModel
    @Inject
    constructor(
        serverRepository: ServerRepository,
        private val monitor: ServerRouteMonitor,
    ) : ViewModel() {
        val path: StateFlow<ServerPath?> =
            combine(serverRepository.current, TallyServerRoute.router.status) { current, routes ->
                current?.server?.let { server ->
                    ServerPath(
                        serverName = server.name ?: server.url,
                        status = routes[normalizeServerId(server.id)],
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Checks the server's addresses again (the row was pressed). */
        fun check() {
            viewModelScope.launch { monitor.checkNow() }
        }
    }
