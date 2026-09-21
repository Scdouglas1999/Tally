package com.github.damontecres.wholphin.jellytv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.jellytv.api.JtvEvent
import com.github.damontecres.wholphin.jellytv.ui.player.EventBanner
import com.github.damontecres.wholphin.jellytv.ui.player.GameSwitcher
import com.github.damontecres.wholphin.jellytv.ui.player.JellyTvPlayerViewModel
import com.github.damontecres.wholphin.jellytv.ui.player.ScoreBug
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.PlaybackPage
import com.github.damontecres.wholphin.ui.showToast
import kotlinx.coroutines.delay
import timber.log.Timber

private const val OVERLAY_ANIM_MS = 140

/**
 * JellyTV playback: the unmodified upstream [PlaybackPage] plus in-player overlays —
 * a score bug, a DPAD_DOWN "also on now" switcher, and transient event banners.
 *
 * The wrapper's [Modifier.onPreviewKeyEvent] sees keys before upstream's handler:
 * while the switcher is closed only DPAD_DOWN is intercepted (to open it); while it is
 * open, Back/Escape/B and DPAD_UP on the card row close it, everything else falls
 * through to the focused card or the player untouched.
 */
@Composable
fun JellyTvPlaybackPage(
    preferences: UserPreferences,
    destination: Destination.JellyTvPlayback,
    modifier: Modifier,
) {
    val viewModel = hiltViewModel<JellyTvPlayerViewModel>()
    LaunchedEffect(destination.channelId) { viewModel.bind(destination.channelId) }

    val game by viewModel.game.collectAsState()
    val others by viewModel.others.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val hideScores by viewModel.hideScores.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    var switcherOpen by remember { mutableStateOf(false) }
    var switcherRowFocused by remember { mutableStateOf(false) }
    var switcherWasOpen by remember { mutableStateOf(false) }

    // Upstream's player acts on key-up and owns the D-pad while its controls are showing (DOWN walks from the
    // seek bar to the button row). Its ControllerViewState is private to PlaybackPage, so mirror it from the
    // keys we let through: DOWN belongs to us only while the controls are (as far as we can tell) hidden.
    val controlsTimeoutMs = preferences.appPreferences.playbackPreferences.controllerTimeoutMs
    val upstreamControls = remember { UpstreamControlsMirror() }
    var swallowDownKeyUp by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect {
            showToast(context, context.getString(it), Toast.LENGTH_SHORT)
        }
    }

    // If every other game leaves the air while the switcher is up, close it.
    LaunchedEffect(others.isEmpty()) {
        if (switcherOpen && others.isEmpty()) switcherOpen = false
    }

    // Upstream's FocusRequester is private; once the switcher has left composition the
    // player box is the only focusable left, so moveFocus lands back on it.
    LaunchedEffect(switcherOpen) {
        if (switcherOpen) {
            switcherWasOpen = true
        } else if (switcherWasOpen) {
            switcherWasOpen = false
            delay(OVERLAY_ANIM_MS + 30L)
            Timber.d("JellyTV: game switcher closed, restoring focus to the player")
            focusManager.moveFocus(FocusDirection.Next)
        }
    }

    BackHandler(enabled = switcherOpen) { switcherOpen = false }

    Box(
        modifier
            .onPreviewKeyEvent { event ->
                if (!switcherOpen) {
                    if (event.key == Key.DirectionDown && swallowDownKeyUp && event.type == KeyEventType.KeyUp) {
                        swallowDownKeyUp = false
                        true
                    } else if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        others.isNotEmpty() &&
                        !upstreamControls.likelyVisible(controlsTimeoutMs)
                    ) {
                        Timber.d("JellyTV: opening game switcher, %d other live games", others.size)
                        switcherOpen = true
                        swallowDownKeyUp = true
                        true
                    } else {
                        upstreamControls.onKeyPassedThrough(event)
                        false
                    }
                } else {
                    when (event.key) {
                        Key.Back, Key.Escape, Key.ButtonB -> {
                            if (event.type == KeyEventType.KeyUp) switcherOpen = false
                            true
                        }

                        Key.DirectionUp -> {
                            if (switcherRowFocused) {
                                if (event.type == KeyEventType.KeyUp) switcherOpen = false
                                true
                            } else {
                                false
                            }
                        }

                        else -> false
                    }
                }
            },
    ) {
        PlaybackPage(
            preferences = preferences,
            destination =
                Destination.Playback(
                    itemId = destination.itemId,
                    positionMs = 0L,
                ),
            modifier = Modifier.fillMaxSize(),
        )

        ScoreBug(
            game = game,
            hideScores = hideScores,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = JtvDimens.marginVertical + 64.dp,
                        end = JtvDimens.marginHorizontal,
                    ),
        )

        // Keep the departing banner in composition so its fade-out can play.
        var lastBanner by remember { mutableStateOf<JtvEvent?>(null) }
        LaunchedEffect(banner) { if (banner != null) lastBanner = banner }
        AnimatedVisibility(
            visible = banner != null,
            enter = fadeIn(tween(OVERLAY_ANIM_MS)),
            exit = fadeOut(tween(OVERLAY_ANIM_MS)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            lastBanner?.let {
                EventBanner(
                    event = it,
                    modifier =
                        Modifier.padding(
                            start = JtvDimens.marginHorizontal,
                            top = JtvDimens.marginVertical,
                        ),
                )
            }
        }

        AnimatedVisibility(
            visible = switcherOpen,
            enter = fadeIn(tween(OVERLAY_ANIM_MS)) + slideInVertically(tween(OVERLAY_ANIM_MS)) { it / 3 },
            exit = fadeOut(tween(OVERLAY_ANIM_MS)) + slideOutVertically(tween(OVERLAY_ANIM_MS)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GameSwitcher(
                games = others,
                hideScores = hideScores,
                favorites = favorites,
                onSwitch = {
                    switcherOpen = false
                    viewModel.switchTo(it)
                },
                onAddToMultiview = viewModel::addToMultiview,
                onRowFocusChanged = { switcherRowFocused = it },
            )
        }
    }
}

/** Best-effort mirror of upstream's controller visibility, fed by the key events that reach it. */
private class UpstreamControlsMirror {
    private var shown = false
    private var lastInteractionAt = 0L

    fun likelyVisible(timeoutMs: Long): Boolean = shown && SystemClock.elapsedRealtime() - lastInteractionAt < timeoutMs + 300

    fun onKeyPassedThrough(event: KeyEvent) {
        if (event.type != KeyEventType.KeyUp) return
        lastInteractionAt = SystemClock.elapsedRealtime()
        shown =
            when (event.key) {
                Key.Back, Key.Escape, Key.ButtonB -> false
                else -> true
            }
    }
}
