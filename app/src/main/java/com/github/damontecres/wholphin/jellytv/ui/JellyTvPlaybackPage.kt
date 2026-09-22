package com.github.damontecres.wholphin.jellytv.ui

import android.os.SystemClock
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import com.github.damontecres.wholphin.jellytv.api.JtvEvent
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import com.github.damontecres.wholphin.jellytv.ui.components.GameActionsDialog
import com.github.damontecres.wholphin.jellytv.ui.components.gameActions
import com.github.damontecres.wholphin.jellytv.ui.player.CornerRequests
import com.github.damontecres.wholphin.jellytv.ui.player.CornerView
import com.github.damontecres.wholphin.jellytv.ui.player.CornerViewController
import com.github.damontecres.wholphin.jellytv.ui.player.EventBanner
import com.github.damontecres.wholphin.jellytv.ui.player.GameSwitcher
import com.github.damontecres.wholphin.jellytv.ui.player.JellyTvPlayerViewModel
import com.github.damontecres.wholphin.jellytv.ui.player.ScoreBug
import com.github.damontecres.wholphin.jellytv.ui.player.cornerChannelName
import com.github.damontecres.wholphin.jellytv.ui.player.cornerLiveGame
import com.github.damontecres.wholphin.jellytv.ui.player.cornerStreamUrl
import com.github.damontecres.wholphin.jellytv.ui.player.gameToCornerAfterSwap
import com.github.damontecres.wholphin.jellytv.ui.player.gamelessChannelGames
import com.github.damontecres.wholphin.jellytv.ui.player.shouldShowCornerRequest
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.PlaybackPage
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
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
    val favoriteTeams by viewModel.favoriteTeams.collectAsState()

    var switcherOpen by remember { mutableStateOf(false) }
    var actionsGameId by remember { mutableStateOf<String?>(null) }
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
    val repository =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, CornerBoardEntryPoint::class.java)
                .jellyTvRepository()
        }
    val board by repository.board.collectAsState()
    val controller = remember { CornerViewController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controller.resume() }

    val cornerChannel by controller.channelId.collectAsState()
    var cornerGame by remember { mutableStateOf<JtvGame?>(null) }
    var cornerFocused by remember { mutableStateOf(false) }
    var cornerFocusEnabled by remember { mutableStateOf(true) }
    val cornerFocus = remember { FocusRequester() }
    var restorePlayerFocusAt by remember { mutableStateOf(0L) }

    // Other live games when there are any. With none, the looping channels (no gameId)
    // so a second picture can still be put in the corner.
    val switcherGames =
        if (others.isNotEmpty()) {
            others
        } else {
            gamelessChannelGames(board, destination.channelId)
        }

    LaunchedEffect(Unit) {
        viewModel.messages.collect {
            showToast(context, context.getString(it), Toast.LENGTH_SHORT)
        }
    }

    LaunchedEffect(destination.channelId) {
        combine(CornerRequests.requested, repository.board) { requested, boardNow ->
            requested to boardNow
        }.collect { (requested, boardNow) ->
            adoptCornerRequest(
                requested = requested,
                boardNow = boardNow,
                playingChannelId = destination.channelId,
                absoluteUrl = repository::absoluteUrl,
                controller = controller,
                onShown = { game ->
                    cornerGame = game
                    switcherOpen = false
                },
            )
        }
    }

    // If every other game leaves the air while the switcher is up, close it.
    LaunchedEffect(switcherGames.isEmpty()) {
        if (switcherOpen && switcherGames.isEmpty()) switcherOpen = false
    }

    // Upstream's FocusRequester is private; once the switcher has left composition the
    // player box is the only focusable left, so moveFocus lands back on it. The corner
    // stays unfocusable until that move finishes, or Next would land on the tile.
    LaunchedEffect(switcherOpen) {
        if (switcherOpen) {
            switcherWasOpen = true
            cornerFocusEnabled = false
        } else if (switcherWasOpen) {
            switcherWasOpen = false
            cornerFocusEnabled = false
            delay(OVERLAY_ANIM_MS + 30L)
            Timber.d("JellyTV: game switcher closed, restoring focus to the player")
            focusManager.moveFocus(FocusDirection.Next)
            cornerFocusEnabled = true
        }
    }

    LaunchedEffect(restorePlayerFocusAt) {
        if (restorePlayerFocusAt == 0L) return@LaunchedEffect
        cornerFocusEnabled = false
        delay(48)
        focusManager.moveFocus(FocusDirection.Next)
        cornerFocusEnabled = true
    }

    BackHandler(enabled = switcherOpen || actionsGameId != null || cornerFocused) {
        when {
            actionsGameId != null -> actionsGameId = null
            switcherOpen -> switcherOpen = false
            else -> restorePlayerFocusAt = SystemClock.elapsedRealtime()
        }
    }

    Box(
        modifier
            .onPreviewKeyEvent { event ->
                // The key-up of the DOWN that opened the switcher must not reach upstream (it would show its controls).
                if (swallowDownKeyUp && event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp) {
                    swallowDownKeyUp = false
                    true
                } else if (!switcherOpen) {
                    val toCorner =
                        cornerChannel != null &&
                            cornerFocusEnabled &&
                            !cornerFocused &&
                            (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                    if (toCorner) {
                        if (event.type == KeyEventType.KeyDown) {
                            cornerFocus.tryRequestFocus("jtv-corner")
                        }
                        true
                    } else if (
                        cornerFocused &&
                        (event.key == Key.Back || event.key == Key.Escape || event.key == Key.ButtonB)
                    ) {
                        // BACK returns to the player. Consuming it here keeps playback on screen.
                        if (event.type == KeyEventType.KeyUp) {
                            restorePlayerFocusAt = SystemClock.elapsedRealtime()
                        }
                        true
                    } else if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        switcherGames.isNotEmpty() &&
                        !upstreamControls.likelyVisible(controlsTimeoutMs)
                    ) {
                        Timber.d("JellyTV: opening game switcher, %d other pictures", switcherGames.size)
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

                        else -> {
                            false
                        }
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

        // Overlays share the JellyTV canvas scale; the upstream player above must not.
        JtvScale {
            // The bug is not a permanent fixture over the picture: it shows when the game opens, whenever the
            // score, period or situation changes, while the switcher is up, and for a moment after any key.
            var bugShownAt by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
            val bugKey = game?.let { "${it.away.score}-${it.home.score}|${it.detail}|${it.downDistance}" }
            LaunchedEffect(bugKey, upstreamControls.lastKeyAt) { bugShownAt = SystemClock.elapsedRealtime() }
            var bugVisible by remember { mutableStateOf(true) }
            LaunchedEffect(bugShownAt, switcherOpen) {
                bugVisible = true
                if (!switcherOpen) {
                    delay(BUG_LINGER_MS)
                    bugVisible = false
                }
            }
            AnimatedVisibility(
                visible = bugVisible,
                enter = fadeIn(tween(OVERLAY_ANIM_MS)),
                exit = fadeOut(tween(OVERLAY_ANIM_MS * 3)),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = JtvDimens.marginVertical + 64.dp,
                            end = JtvDimens.marginHorizontal,
                        ),
            ) {
                ScoreBug(game = game, hideScores = hideScores)
            }

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

            if (cornerChannel != null) {
                CornerView(
                    controller = controller,
                    game = cornerLiveGame(board, cornerChannel),
                    channelName = cornerChannelName(board, cornerChannel, cornerGame?.watch?.channelName),
                    hideScores = hideScores,
                    onSwap = {
                        val incoming = cornerGame ?: return@CornerView
                        if (incoming.watch?.liveTvItemId?.toUUIDOrNull() == null) {
                            viewModel.switchTo(incoming)
                        } else {
                            // switchTo replaces this NavEntry, which releases this controller.
                            // The picture now full-screen is requested so the next page's controller shows it.
                            val playing = gameToCornerAfterSwap(board, destination.channelId)
                            viewModel.switchTo(incoming)
                            if (playing != null) CornerRequests.request(playing)
                        }
                    },
                    onClose = {
                        controller.hide()
                        cornerGame = null
                        if (cornerFocused) restorePlayerFocusAt = SystemClock.elapsedRealtime()
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 48.dp, bottom = 48.dp)
                            .focusRequester(cornerFocus)
                            .onFocusChanged { cornerFocused = it.isFocused }
                            .focusProperties {
                                canFocus = cornerFocusEnabled && !switcherOpen && actionsGameId == null
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                )
            }

            AnimatedVisibility(
                visible = switcherOpen,
                enter = fadeIn(tween(OVERLAY_ANIM_MS)) + slideInVertically(tween(OVERLAY_ANIM_MS)) { it / 3 },
                exit = fadeOut(tween(OVERLAY_ANIM_MS)) + slideOutVertically(tween(OVERLAY_ANIM_MS)) { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                GameSwitcher(
                    games = switcherGames,
                    hideScores = hideScores,
                    favorites = favorites,
                    favoriteTeams = favoriteTeams,
                    onSwitch = {
                        switcherOpen = false
                        viewModel.switchTo(it)
                    },
                    onLongClick = { game ->
                        if (others.isEmpty()) {
                            // Nothing live: the card is a channel. Long-press puts it in the corner
                            // so the tile can be tried before a game is on the air.
                            CornerRequests.request(game)
                        } else {
                            actionsGameId = game.id
                        }
                    },
                    onRowFocusChanged = { switcherRowFocused = it },
                )
            }
            val actionsGame = actionsGameId?.let { id -> others.firstOrNull { it.id == id } }
            if (actionsGame != null) {
                GameActionsDialog(
                    game = actionsGame,
                    actions =
                        gameActions(
                            game = actionsGame,
                            favoriteTeams = favoriteTeams,
                            hideScores = hideScores,
                            onWatch = {
                                switcherOpen = false
                                viewModel.switchTo(it)
                            },
                            onAddToMultiview = viewModel::addToMultiview,
                            onWatchInCorner = viewModel::watchInCorner,
                            onToggleFollow = viewModel::toggleFollow,
                            onToggleHideScores = viewModel::toggleHideScores,
                        ),
                    onDismiss = { actionsGameId = null },
                )
            }
        }
    }
}

/**
 * Starts [requested] in the corner when it is a different channel from the one filling the screen.
 * A same-channel request is a swap hand-off and is left for the page that will play the other game.
 */
private fun adoptCornerRequest(
    requested: JtvGame?,
    boardNow: JtvBoard?,
    playingChannelId: String,
    absoluteUrl: (String) -> String?,
    controller: CornerViewController,
    onShown: (JtvGame) -> Unit,
) {
    val pendingId = requested?.watch?.channelId
    if (requested != null && shouldShowCornerRequest(pendingId, playingChannelId)) {
        if (boardNow == null) return
        val channelId = pendingId ?: return
        val url = cornerStreamUrl(boardNow.channels, channelId, absoluteUrl)
        if (url == null) {
            Timber.w("JellyTV corner: no HLS for channel %s", channelId)
            if (CornerRequests.requested.value === requested) {
                CornerRequests.requested.value = null
            }
            return
        }
        controller.show(channelId, url)
        onShown(requested)
        if (CornerRequests.requested.value === requested) {
            CornerRequests.requested.value = null
        }
    } else if (boardNow != null) {
        val showing = controller.channelId.value ?: return
        val url = cornerStreamUrl(boardNow.channels, showing, absoluteUrl) ?: return
        controller.show(showing, url)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CornerBoardEntryPoint {
    fun jellyTvRepository(): JellyTvRepository
}

/** Best-effort mirror of upstream's controller visibility, fed by the key events that reach it. */
private const val BUG_LINGER_MS = 8_000L

private class UpstreamControlsMirror {
    private var shown = false
    private var lastInteractionAt = 0L

    /** Time of the last key-up seen, as state so overlays can react to "the viewer touched the remote". */
    var lastKeyAt by mutableStateOf(0L)
        private set

    fun likelyVisible(timeoutMs: Long): Boolean = shown && SystemClock.elapsedRealtime() - lastInteractionAt < timeoutMs + 300

    fun onKeyPassedThrough(event: KeyEvent) {
        if (event.type != KeyEventType.KeyUp) return
        lastInteractionAt = SystemClock.elapsedRealtime()
        lastKeyAt = lastInteractionAt
        shown =
            when (event.key) {
                Key.Back, Key.Escape, Key.ButtonB -> false
                else -> true
            }
    }
}
