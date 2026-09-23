package io.github.scdouglas1999.tally.ui.launch

import android.os.Build
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import com.github.damontecres.wholphin.ui.findActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Process-wide: the launch card is shown from the first frame of a cold start and, once dismissed, never again in
 * this process (not on activity recreation, user switch or return from the background).
 */
private object TallyLaunchSession {
    var showing by mutableStateOf(true)
}

@HiltViewModel
class TallyLaunchViewModel
    @Inject
    constructor(
        private val setupNavigationManager: SetupNavigationManager,
        private val serverRepository: ServerRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /** The app has left upstream's Loading step (home, the server list or the user list are all "ready"). */
        val ready: Boolean
            get() = setupNavigationManager.backStack.lastOrNull().let { it != null && it != SetupDestination.Loading }

        /** The server being connected to, when this TV has one saved. */
        suspend fun serverName(): String? =
            withContext(ioDispatcher) {
                serverRepository.current.value
                    ?.server
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: try {
                        serverRepository.userPreferencesDataStore.data
                            .first()
                            .currentServerId
                            ?.toUUIDOrNull()
                            ?.let { serverRepository.serverDao.getServer(it) }
                            ?.server
                            ?.name
                            ?.takeIf { it.isNotBlank() }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Tally launch: no server name")
                        null
                    }
            }
    }

/**
 * The launch card: the tally lamp and the TALLY wordmark on an opaque ground, above everything. The lamp sputters
 * while the app gets ready and catches once it is; when the catch is complete the card crossfades away (no top
 * accent line: the user preferred the card without it). Keys are swallowed while it is up.
 */
@Composable
fun TallyLaunch(modifier: Modifier = Modifier) {
    if (!TallyLaunchSession.showing) return
    val viewModel: TallyLaunchViewModel = hiltViewModel()

    var lamp by remember { mutableStateOf(LampState.Off) }
    var connecting by remember { mutableStateOf(false) }
    var serverName by remember { mutableStateOf<String?>(null) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        delay(DARK_MS)
        lamp = LampState.Sputtering
        snapshotFlow { viewModel.ready }.first { it }
        lamp = LampState.Lit
    }
    LaunchedEffect(Unit) {
        delay(CONNECTING_AFTER_MS)
        if (!viewModel.ready) {
            serverName = viewModel.serverName()
            connecting = !viewModel.ready
        }
    }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(settled) {
        if (settled) {
            alpha.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
            TallyLaunchSession.showing = false
        }
    }

    SwallowKeys()
    BackHandler { }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(TallyColors.ground),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.Center),
        ) {
            TallyLamp(
                state = lamp,
                size = SQUARE,
                modifier = Modifier.offset(y = CAP_CENTER_NUDGE),
                onSettled = { settled = true },
            )
            Spacer(Modifier.width(GAP))
            Text(
                text = stringResource(R.string.tally_lamp_wordmark),
                style = wordmark,
                color = TallyColors.text,
                maxLines = 1,
            )
        }
        if (connecting) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(top = CONNECTING_TOP),
            ) {
                Text(
                    text =
                        serverName?.let { stringResource(R.string.tally_lamp_connecting_to, it.uppercase()) }
                            ?: stringResource(R.string.tally_lamp_connecting),
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Swallows every key the activity window receives while composed: the page below must not see them. */
@Composable
private fun SwallowKeys() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view) {
        val window = (context.findActivity() ?: view.context.findActivity())?.window
        val original = window?.callback
        if (window == null || original == null) return@DisposableEffect onDispose {}
        val swallow = KeySwallow(original)
        window.callback = swallow
        onDispose {
            swallow.active = false
            if (window.callback === swallow) window.callback = original
        }
    }
}

private class KeySwallow(
    private val delegate: Window.Callback,
) : Window.Callback by delegate {
    var active = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (active) true else delegate.dispatchKeyEvent(event)

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        delegate.onPointerCaptureChanged(hasCapture)
    }
}

private val EaseOutCubic = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }

/** The wordmark's font size; the banner's other measures are fractions of it (make-readme-art.py). */
private val WORDMARK_SIZE = 50.sp
private val wordmark =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = WORDMARK_SIZE,
        letterSpacing = 0.30.em,
    )
private val SQUARE = (50 * 0.52).dp
private val GAP = (50 * 0.62).dp

/** IBM Plex Sans capitals sit slightly below the center of their line box; the lamp follows the capitals. */
private val CAP_CENTER_NUDGE = (50 * 0.026).dp
private val CONNECTING_TOP = 120.dp

private const val DARK_MS = 120L
private const val CONNECTING_AFTER_MS = 4_000L
private const val FADE_MS = 250
