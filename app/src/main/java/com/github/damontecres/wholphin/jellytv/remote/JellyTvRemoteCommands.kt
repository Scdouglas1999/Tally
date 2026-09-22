package com.github.damontecres.wholphin.jellytv.remote

import android.media.AudioManager
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.ForceKeepAliveMessage
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Makes the TV a full Jellyfin remote-control target: D-pad navigation, volume, audio and subtitle tracks, and
 * "open this item on the TV" from the Jellyfin phone app or web client. Upstream only shows DisplayMessage /
 * SendString toasts; it keeps doing that, this class handles everything in [SUPPORTED].
 */
@Singleton
class JellyTvRemoteCommands
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
    ) {
        private var keeper: Job? = null
        private var keeperActivity: AppCompatActivity? = null

        /**
         * Holds one websocket subscription for the activity's whole life. Upstream cancels every collector on pause
         * and resubscribes on resume; the SDK closes the socket when its subscriber count reaches zero, and a
         * subscription made in the same moment does not reopen it, so after any pause (a deep link, a system dialog)
         * pushed Play, remote commands and messages stopped arriving. With this subscriber the count never reaches
         * zero. It ends with the activity (its lifecycle scope), not on pause or stop.
         */
        fun keepSocketOpen(
            api: ApiClient,
            activity: AppCompatActivity,
        ) {
            if (keeperActivity === activity && keeper?.isActive == true) return
            keeper?.cancel()
            keeperActivity = activity
            keeper =
                api.webSocket
                    .subscribe<ForceKeepAliveMessage>()
                    .catch { ex -> Timber.w(ex, "JellyTV socket keeper") }
                    .launchIn(activity.lifecycleScope)
        }

        /** Subscribes to the session's general commands until the returned job is canceled (the caller owns it). */
        fun listen(
            api: ApiClient,
            activity: AppCompatActivity,
        ): Job =
            api.webSocket
                .subscribe<GeneralCommandMessage>()
                .onEach { message ->
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            handle(activity, message)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Ignoring remote command %s", message.data?.name)
                    }
                }.catch { ex ->
                    Timber.e(ex, "Error in remote command subscription")
                }.launchIn(activity.lifecycleScope)

        private fun handle(
            activity: AppCompatActivity,
            message: GeneralCommandMessage,
        ) {
            val command = message.data ?: return
            val name = command.name
            Timber.d("General command %s %s", name, command.arguments)
            if (name !in SUPPORTED) return
            val arguments = command.arguments
            when (name) {
                GeneralCommandType.MOVE_UP,
                GeneralCommandType.MOVE_DOWN,
                GeneralCommandType.MOVE_LEFT,
                GeneralCommandType.MOVE_RIGHT,
                GeneralCommandType.SELECT,
                GeneralCommandType.TOGGLE_OSD_MENU,
                -> dispatchPress(activity, name)

                GeneralCommandType.BACK -> activity.onBackPressedDispatcher.onBackPressed()

                GeneralCommandType.GO_HOME -> navigationManager.goToHome()

                GeneralCommandType.VOLUME_UP -> adjustVolume(activity, AudioManager.ADJUST_RAISE)

                GeneralCommandType.VOLUME_DOWN -> adjustVolume(activity, AudioManager.ADJUST_LOWER)

                GeneralCommandType.MUTE -> adjustVolume(activity, AudioManager.ADJUST_MUTE)

                GeneralCommandType.UNMUTE -> adjustVolume(activity, AudioManager.ADJUST_UNMUTE)

                GeneralCommandType.TOGGLE_MUTE -> adjustVolume(activity, AudioManager.ADJUST_TOGGLE_MUTE)

                GeneralCommandType.SET_VOLUME -> setVolume(activity, arguments)

                GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                -> sendTrack(name, arguments)

                GeneralCommandType.DISPLAY_CONTENT -> displayContent(arguments)

                else -> Unit
            }
        }

        private fun dispatchPress(
            activity: AppCompatActivity,
            command: GeneralCommandType,
        ) {
            val keyCode = keyCodeFor(command)
            if (keyCode == null) {
                Timber.w("No key code for %s", command)
                return
            }
            val down = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            val up = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Timber.d("Dispatched %s keyCode=%s downHandled=%s upHandled=%s", command, keyCode, down, up)
        }

        private fun adjustVolume(
            activity: AppCompatActivity,
            direction: Int,
        ) {
            val audio = audioManager(activity) ?: return
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }

        private fun setVolume(
            activity: AppCompatActivity,
            arguments: Map<String, String?>,
        ) {
            val audio = audioManager(activity) ?: return
            val raw = arguments[ARG_VOLUME]
            val percent = raw?.trim()?.toIntOrNull()
            if (percent == null) {
                Timber.w("Ignoring SetVolume, Volume=%s", raw)
                return
            }
            val index = volumeIndexFor(percent, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        }

        private fun sendTrack(
            command: GeneralCommandType,
            arguments: Map<String, String?>,
        ) {
            val track = trackCommandFor(command, arguments)
            if (track == null) {
                Timber.w("Ignoring %s, arguments=%s", command, arguments)
                return
            }
            val sent = JellyTvRemoteBus.send(track)
            Timber.d("Track %s sent=%s", track, sent)
            if (!sent) Timber.w("No open player for %s %s", command, track)
        }

        private fun displayContent(arguments: Map<String, String?>) {
            val target = displayContentFor(arguments)
            if (target == null) {
                Timber.w("Ignoring DisplayContent, arguments=%s", arguments)
                return
            }
            val (itemId, kind) = target
            navigationManager.navigateTo(Destination.MediaItem(itemId, kind))
        }

        private fun audioManager(activity: AppCompatActivity): AudioManager? {
            val audio = activity.getSystemService(AudioManager::class.java)
            if (audio == null) Timber.w("No AudioManager")
            return audio
        }

        companion object {
            private const val ARG_VOLUME = "Volume"

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
