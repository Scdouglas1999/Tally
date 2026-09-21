package com.github.damontecres.wholphin.services

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.launchIO
// JELLYTV: begin
import com.github.damontecres.wholphin.jellytv.JellyTvPlayRouter
import com.github.damontecres.wholphin.ui.nav.Destination
// JELLYTV: end
import com.github.damontecres.wholphin.ui.showToast
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
// JELLYTV: begin
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
// JELLYTV: end
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens for basic messages from the server such as messages
 */
@ActivityScoped
class ServerEventListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        // JELLYTV: begin
        private val navigationManager: NavigationManager,
        private val jellyTvPlayRouter: JellyTvPlayRouter,
        // JELLYTV: end
    ) : DefaultLifecycleObserver {
        private val activity = (context as AppCompatActivity)

        private var listenJob: Job? = null

        // JELLYTV: begin
        private var playJob: Job? = null
        // JELLYTV: end

        init {
            activity.lifecycle.addObserver(this)
            serverRepository.current.collectLatestIn(activity.lifecycleScope) {
                Timber.d("New user/server: %s", it)
                listenJob?.cancel()
                // JELLYTV: begin
                playJob?.cancel()
                // JELLYTV: end
                if (it != null) {
                    init(it.server, it.user)
                }
            }
        }

        fun init(
            server: JellyfinServer?,
            user: JellyfinUser?,
        ) {
            if (server != null && user != null && api.baseUrl != null && api.accessToken != null) {
                (context as AppCompatActivity).lifecycleScope.launchIO {
                    api.sessionApi.postCapabilities(
                        playableMediaTypes = listOf(MediaType.VIDEO),
                        supportedCommands =
                            listOf(
                                GeneralCommandType.DISPLAY_MESSAGE,
                                GeneralCommandType.SEND_STRING,
                            ),
                        supportsMediaControl = true,
                    )
                    setupListeners()
                }
            }
        }

        fun setupListeners() {
            serverRepository.currentUser
            Timber.v("Subscribing to WebSocket")
            listenJob?.cancel()
            listenJob =
                api.webSocket
                    .subscribe<GeneralCommandMessage>()
                    .onEach { message ->
                        if (message.data?.name in
                            setOf(
                                GeneralCommandType.DISPLAY_MESSAGE,
                                GeneralCommandType.SEND_STRING,
                            )
                        ) {
                            val header = message.data?.arguments["Header"]
                            val text =
                                message.data?.arguments["Text"] ?: message.data?.arguments["String"]
                            val toast =
                                listOfNotNull(header, text)
                                    .joinToString("\n")
                            showToast(context, toast, Toast.LENGTH_LONG)
                        }
                    }.catch { ex ->
                        Timber.e(ex, "Error in websocket subscription")
                    }.launchIn(activity.lifecycleScope)
            // JELLYTV: begin
            playJob?.cancel()
            playJob =
                api.webSocket
                    .subscribe<PlayMessage>()
                    .onEach { message ->
                        val data = message.data
                        if (data != null && data.playCommand == PlayCommand.PLAY_NOW) {
                            val itemIds = data.itemIds
                            if (!itemIds.isNullOrEmpty()) {
                                val index = data.startIndex?.takeIf { it in itemIds.indices } ?: 0
                                val itemId = itemIds[index]
                                Timber.d("Server requested playback of item %s", itemId)
                                // Switching games from the phone: swap the running player out instead of
                                // stacking a second one on top of it (the old one would keep playing underneath).
                                val top = navigationManager.backStack.lastOrNull()
                                if (top is Destination.Playback || top is Destination.JellyTvPlayback) {
                                    navigationManager.backStack.removeLastOrNull()
                                }
                                navigationManager.navigateTo(
                                    jellyTvPlayRouter.destinationFor(itemId, (data.startPositionTicks ?: 0) / 10_000),
                                )
                            }
                        }
                    }.catch { ex ->
                        Timber.e(ex, "Error in websocket play subscription")
                    }.launchIn(activity.lifecycleScope)
            // JELLYTV: end
        }

        override fun onResume(owner: LifecycleOwner) {
            serverRepository.current.value?.let { init(it.server, it.user) }
        }

        override fun onPause(owner: LifecycleOwner) {
            Timber.v("Cancelling WebSocket")
            listenJob?.cancel()
            // JELLYTV: begin
            playJob?.cancel()
            // JELLYTV: end
        }

        override fun onStop(owner: LifecycleOwner) {
            Timber.v("Cancelling WebSocket")
            listenJob?.cancel()
            // JELLYTV: begin
            playJob?.cancel()
            // JELLYTV: end
        }
    }
