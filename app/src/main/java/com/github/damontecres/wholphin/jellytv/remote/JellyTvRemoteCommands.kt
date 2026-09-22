package com.github.damontecres.wholphin.jellytv.remote

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.services.NavigationManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.GeneralCommandType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes the TV a full Jellyfin remote-control target: D-pad navigation, volume, audio and subtitle tracks, and
 * "open this item on the TV" from the Jellyfin phone app or web client. Upstream only shows DisplayMessage /
 * SendString toasts; it keeps doing that, this class handles everything in [SUPPORTED].
 *
 * STUB: task `remote` implements [listen].
 */
@Singleton
class JellyTvRemoteCommands
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
    ) {
        /** Subscribes to the session's general commands until the returned job is cancelled (the caller owns it). */
        fun listen(
            api: ApiClient,
            activity: AppCompatActivity,
        ): Job = activity.lifecycleScope.launch { }

        companion object {
            /** Advertised to the server in the session capabilities (seam in `ServerEventListener`). */
            val SUPPORTED: List<GeneralCommandType> =
                listOf(
                    GeneralCommandType.MOVE_UP,
                    GeneralCommandType.MOVE_DOWN,
                    GeneralCommandType.MOVE_LEFT,
                    GeneralCommandType.MOVE_RIGHT,
                    GeneralCommandType.SELECT,
                    GeneralCommandType.BACK,
                    GeneralCommandType.GO_HOME,
                    GeneralCommandType.TOGGLE_OSD_MENU,
                    GeneralCommandType.VOLUME_UP,
                    GeneralCommandType.VOLUME_DOWN,
                    GeneralCommandType.SET_VOLUME,
                    GeneralCommandType.MUTE,
                    GeneralCommandType.UNMUTE,
                    GeneralCommandType.TOGGLE_MUTE,
                    GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                    GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                    GeneralCommandType.DISPLAY_CONTENT,
                )
        }
    }
