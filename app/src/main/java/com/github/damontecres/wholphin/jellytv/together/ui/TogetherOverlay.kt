package com.github.damontecres.wholphin.jellytv.together.ui

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.together.TogetherGroup
import com.github.damontecres.wholphin.jellytv.together.TogetherNotice
import com.github.damontecres.wholphin.jellytv.together.TogetherState
import com.github.damontecres.wholphin.jellytv.ui.components.IndicatorSquare
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.findActivity
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.GroupStateType

/**
 * Watch Together above every screen: the party chip (top right, while this TV is in a watch party) and, under
 * it, the last few notices ("SAM JOINED", "PAUSED"…). Nothing here is focusable.
 *
 * The chip dims to 40% once upstream's player controls are gone (no remote key for the player's controls
 * timeout) and nothing has changed for 10 s; a notice or a group change brings it back for another 10 s.
 * Upstream's controls state is private to its player, so key presses are observed on the activity window.
 */
@Composable
fun TogetherOverlay(modifier: Modifier = Modifier) {
    val viewModel: TogetherOverlayViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showClock by viewModel.showClock.collectAsStateWithLifecycle()
    val controlsTimeoutMs by viewModel.controlsTimeoutMs.collectAsStateWithLifecycle()
    val group = (state as? TogetherState.InGroup)?.group

    var lastChangeAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    var dimmed by remember { mutableStateOf(false) }

    // The chip keeps showing the last group while it fades out.
    var shownGroup by remember { mutableStateOf<TogetherGroup?>(null) }
    if (group != null) shownGroup = group
    val chipVisible = remember { MutableTransitionState(false) }
    chipVisible.targetState = group != null

    LaunchedEffect(group?.id, group?.state, group?.participants) {
        lastChangeAt = SystemClock.elapsedRealtime()
    }

    val notices = remember { mutableStateListOf<ShownNotice>() }
    LaunchedEffect(viewModel) {
        var nextId = 0L
        viewModel.notices.collect { notice ->
            val own = viewModel.userName.value
            val name =
                when (notice) {
                    is TogetherNotice.Joined -> notice.name
                    is TogetherNotice.Left -> notice.name
                    else -> null
                }
            if (name != null && own != null && name.equals(own, ignoreCase = true)) return@collect
            if (notice is TogetherNotice.Waiting) notices.removeAll { it.notice is TogetherNotice.Waiting }
            notices.add(0, ShownNotice(nextId++, notice))
            while (notices.size > MAX_NOTICES) notices.removeAt(notices.lastIndex)
            lastChangeAt = SystemClock.elapsedRealtime()
        }
    }

    val inGroup = group != null
    WindowKeyWatch(enabled = inGroup) { lastKeyAt = SystemClock.elapsedRealtime() }
    LaunchedEffect(inGroup, lastChangeAt, lastKeyAt, controlsTimeoutMs) {
        dimmed = false
        if (!inGroup) return@LaunchedEffect
        val dimAt = maxOf(lastChangeAt + BRIGHT_MS, lastKeyAt + controlsTimeoutMs)
        delay((dimAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        dimmed = true
    }
    val chipAlpha by animateFloatAsState(
        targetValue = if (dimmed) DIM_ALPHA else 1f,
        animationSpec = tween(FADE_MS),
        label = "together-chip-dim",
    )

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = JtvDimens.marginHorizontal,
                        top = JtvDimens.marginVertical + if (showClock) CLOCK_OFFSET else 0.dp,
                    ),
        ) {
            ChipSlot(visible = chipVisible, group = shownGroup, alpha = chipAlpha)
            notices.forEach { shown ->
                androidx.compose.runtime.key(shown.id) {
                    NoticeLine(shown = shown, onGone = { notices.remove(shown) })
                }
            }
        }
    }
}

private class ShownNotice(
    val id: Long,
    val notice: TogetherNotice,
) {
    val visible = MutableTransitionState(false).apply { targetState = true }
}

/** Always [BAR_HEIGHT] high, so the notices under it do not move while the chip fades. */
@Composable
private fun ChipSlot(
    visible: MutableTransitionState<Boolean>,
    group: TogetherGroup?,
    alpha: Float,
) {
    Box(Modifier.height(BAR_HEIGHT), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(FADE_MS)),
            exit = fadeOut(tween(FADE_MS)),
        ) {
            group?.let { PartyChip(group = it, modifier = Modifier.alpha(alpha)) }
        }
    }
}

@Composable
private fun PartyChip(
    group: TogetherGroup,
    modifier: Modifier = Modifier,
) {
    val waiting = group.state == GroupStateType.WAITING
    val square =
        when (group.state) {
            GroupStateType.PLAYING -> JtvColors.accent
            GroupStateType.WAITING -> JtvColors.live
            else -> JtvColors.muted
        }
    BlackBar(modifier = modifier) {
        IndicatorSquare(color = square)
        Text(
            text = stringResource(R.string.jtv_together_ui_chip),
            style = JtvType.label,
            color = JtvColors.text,
            maxLines = 1,
            modifier = Modifier.offset(y = CAP_NUDGE),
        )
        Box(
            Modifier
                .width(JtvDimens.hairline)
                .height(SEPARATOR_HEIGHT)
                .background(JtvColors.ruleStrong),
        )
        Text(
            text =
                if (waiting) {
                    stringResource(R.string.jtv_together_ui_waiting)
                } else {
                    stringResource(R.string.jtv_together_ui_watching, group.participants.size)
                },
            style = JtvType.label,
            color = if (waiting) JtvColors.liveText else JtvColors.muted,
            maxLines = 1,
            modifier = Modifier.offset(y = CAP_NUDGE),
        )
    }
}

@Composable
private fun NoticeLine(
    shown: ShownNotice,
    onGone: () -> Unit,
) {
    LaunchedEffect(shown.id) {
        delay(NOTICE_MS)
        shown.visible.targetState = false
    }
    if (shown.visible.isIdle && !shown.visible.currentState && !shown.visible.targetState) {
        LaunchedEffect(shown.id) { onGone() }
    }
    val (text, color) = noticeText(shown.notice)
    AnimatedVisibility(
        visibleState = shown.visible,
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(NOTICE_FADE_MS)),
    ) {
        BlackBar {
            Text(
                text = text,
                style = JtvType.label,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = NOTICE_MAX_TEXT_WIDTH).offset(y = CAP_NUDGE),
            )
        }
    }
}

@Composable
private fun noticeText(notice: TogetherNotice): Pair<String, Color> =
    when (notice) {
        is TogetherNotice.Joined -> stringResource(R.string.jtv_together_ui_joined, notice.name.uppercase()) to JtvColors.text
        is TogetherNotice.Left -> stringResource(R.string.jtv_together_ui_left, notice.name.uppercase()) to JtvColors.text
        TogetherNotice.Waiting -> stringResource(R.string.jtv_together_ui_notice_waiting) to JtvColors.text
        TogetherNotice.Paused -> stringResource(R.string.jtv_together_ui_paused) to JtvColors.text
        TogetherNotice.Resumed -> stringResource(R.string.jtv_together_ui_playing) to JtvColors.text
        TogetherNotice.Ended -> stringResource(R.string.jtv_together_ui_ended) to JtvColors.text
        is TogetherNotice.Error -> notice.message.uppercase() to JtvColors.liveText
    }

/** A black label bar, 32dp high, no border; its content is centred vertically. */
@Composable
private fun BlackBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .height(BAR_HEIGHT)
                .background(JtvColors.labelBar)
                .padding(horizontal = 10.dp),
    ) {
        content()
    }
}

/**
 * Calls [onKey] for every key the activity window receives while [enabled], without consuming it: the
 * window's callback is wrapped and handed back on dispose.
 */
@Composable
private fun WindowKeyWatch(
    enabled: Boolean,
    onKey: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val latest = remember { mutableStateOf(onKey) }
    latest.value = onKey
    DisposableEffect(enabled, context, view) {
        val window = (context.findActivity() ?: view.context.findActivity())?.window
        val original = window?.callback
        if (!enabled || window == null || original == null) return@DisposableEffect onDispose {}
        val tap = KeyTap(original) { latest.value() }
        window.callback = tap
        onDispose {
            tap.active = false
            if (window.callback === tap) window.callback = original
        }
    }
}

private class KeyTap(
    private val delegate: Window.Callback,
    private val onKey: () -> Unit,
) : Window.Callback by delegate {
    var active = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (active) onKey()
        return delegate.dispatchKeyEvent(event)
    }

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

private val BAR_HEIGHT = 32.dp

/**
 * IBM Plex Mono capitals sit ~0.75dp (at JtvScale) below the centre of their line box (ascent 1025, descent 275,
 * cap height 698 per 1000), so a centred UPPERCASE label reads low by ~2px at 1080p. Lifting it makes the space
 * above and below the capitals equal, as UI.md asks.
 */
internal val CAP_NUDGE = (-0.75).dp
private val SEPARATOR_HEIGHT = 14.dp
private val CLOCK_OFFSET = 40.dp
private val NOTICE_MAX_TEXT_WIDTH = 480.dp
private const val MAX_NOTICES = 3
private const val NOTICE_MS = 4_000L
private const val NOTICE_FADE_MS = 300
private const val FADE_MS = 200
private const val BRIGHT_MS = 10_000L
private const val DIM_ALPHA = 0.4f
