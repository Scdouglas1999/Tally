package com.github.damontecres.wholphin.jellytv

import android.os.SystemClock
import androidx.media3.common.Player
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlayerFactory
import com.github.damontecres.wholphin.services.isReleased
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Process-wide sleep timer for both players. Survives navigation; it is not tied to a screen.
 *
 * [remaining] is null when no timer is set. It is [Duration.INFINITE] when the timer is waiting
 * for the current item to end and that item's length is not known (a live stream). Otherwise it
 * is the time left, updated once a second.
 *
 * When the timer fires it pauses the current player, leaves the player if a playback destination
 * is on top of the back stack, and clears itself. It also clears, without pausing, when the
 * current player is released or replaced.
 */
@Singleton
class SleepTimerService
    @Inject
    constructor(
        private val playerFactory: PlayerFactory,
        private val navigationManager: NavigationManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val logic = SleepTimerLogic()
        private var job: Job? = null
        private var generation = 0
        private var listened: Player? = null

        private val _remaining = MutableStateFlow<Duration?>(null)
        val remaining: StateFlow<Duration?> = _remaining.asStateFlow()

        private val endListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) return
                    // Hop to Main. Player callbacks are not always on the main thread, and the
                    // ticker mutates [logic] there.
                    scope.launch {
                        if (logic.untilEnd && logic.markFired()) {
                            performFire()
                            teardown()
                        }
                    }
                }
            }

        /** Stop after [duration]. */
        fun start(duration: Duration) {
            Timber.i("Sleep timer start %s", duration)
            restart { snap ->
                val now = SystemClock.elapsedRealtime()
                logic.start(duration.inWholeMilliseconds, now, snap.identity)
                logic.tick(
                    nowMs = now,
                    player = snap.identity,
                    playerReleased = snap.released,
                    ended = false,
                    mediaRemainingMs = null,
                )
            }
        }

        /** Stop when the current item ends. */
        fun startUntilEnd() {
            restart { snap ->
                Timber.i(
                    "Sleep timer until end; mediaRemainingMs=%s ended=%s live=%s",
                    snap.mediaRemainingMs,
                    snap.ended,
                    snap.live,
                )
                logic.startUntilEnd(snap.identity, snap.mediaRemainingMs)
                if (snap.identity != null && !snap.released) attachListener(snap.identity)
                logic.tick(
                    nowMs = SystemClock.elapsedRealtime(),
                    player = snap.identity,
                    playerReleased = snap.released,
                    ended = snap.ended,
                    mediaRemainingMs = snap.mediaRemainingMs,
                )
            }
        }

        fun cancel() {
            Timber.i("Sleep timer canceled")
            generation++
            teardown()
        }

        private fun restart(arm: (PlayerSnap) -> SleepTimerEffect) {
            generation++
            job?.cancel()
            job = null
            detachListener()
            val snap = snapshot()
            when (val effect = arm(snap)) {
                SleepTimerEffect.Fire -> {
                    performFire()
                    teardown()
                }

                SleepTimerEffect.Clear -> {
                    Timber.i("Sleep timer cleared; player released or replaced")
                    teardown()
                }

                SleepTimerEffect.None,
                SleepTimerEffect.Running,
                -> {
                    publish()
                    armTicker()
                }
            }
        }

        private fun armTicker() {
            val generation = this.generation
            job?.cancel()
            job =
                scope.launch {
                    while (isActive && this@SleepTimerService.generation == generation && logic.active) {
                        delay(TICK_MS)
                        if (this@SleepTimerService.generation != generation || !logic.active) return@launch
                        val snap = snapshot()
                        when (
                            logic.tick(
                                nowMs = SystemClock.elapsedRealtime(),
                                player = snap.identity,
                                playerReleased = snap.released,
                                ended = snap.ended,
                                mediaRemainingMs = snap.mediaRemainingMs,
                            )
                        ) {
                            SleepTimerEffect.None -> {
                                return@launch
                            }

                            SleepTimerEffect.Running -> {
                                publish()
                            }

                            SleepTimerEffect.Fire -> {
                                performFire()
                                teardown()
                                return@launch
                            }

                            SleepTimerEffect.Clear -> {
                                Timber.i("Sleep timer cleared; player released or replaced")
                                teardown()
                                return@launch
                            }
                        }
                    }
                }
        }

        private fun performFire() {
            val player = playerFactory.currentPlayer
            runCatching {
                if (player != null && !player.releasedOrGone()) player.pause()
            }.onFailure { Timber.w(it, "Sleep timer could not pause") }
            val top = navigationManager.backStack.lastOrNull()
            val leave = sleepTimerShouldLeave(top)
            Timber.i("Sleep timer fired; leavePlayer=%s top=%s", leave, top)
            if (leave) navigationManager.goBack()
        }

        private fun teardown() {
            job?.cancel()
            job = null
            detachListener()
            logic.cancel()
            _remaining.value = null
        }

        private fun publish() {
            _remaining.value = displayedSleepRemaining(logic.active, logic.untilEnd, logic.remainingMs)
        }

        private fun snapshot(): PlayerSnap {
            val player = playerFactory.currentPlayer
            val released = player.releasedOrGone()
            if (player == null || released) {
                return PlayerSnap(player, released = released, ended = false, live = false, mediaRemainingMs = null)
            }
            val live = runCatching { player.isCurrentMediaItemLive }.getOrDefault(false)
            val ended = runCatching { player.playbackState == Player.STATE_ENDED }.getOrDefault(false)
            val media =
                runCatching {
                    SleepTimerLogic.mediaRemainingMs(
                        durationMs = player.duration,
                        positionMs = player.currentPosition,
                        live = live,
                    )
                }.getOrNull()
            return PlayerSnap(player, released = false, ended = ended, live = live, mediaRemainingMs = media)
        }

        private fun attachListener(player: Player) {
            detachListener()
            runCatching { player.addListener(endListener) }
                .onSuccess { listened = player }
                .onFailure { Timber.w(it, "Sleep timer listener not attached") }
        }

        private fun detachListener() {
            val player = listened ?: return
            listened = null
            runCatching {
                if (!player.releasedOrGone()) player.removeListener(endListener)
            }
        }

        private data class PlayerSnap(
            val identity: Player?,
            val released: Boolean,
            val ended: Boolean,
            val live: Boolean,
            val mediaRemainingMs: Long?,
        )
    }

private const val TICK_MS = 1_000L

private fun Player?.releasedOrGone(): Boolean {
    this ?: return false
    return try {
        isReleased
    } catch (ex: IllegalStateException) {
        Timber.w(ex, "Sleep timer: could not read player release state")
        true
    }
}

/** True when firing the timer should pop the player off the back stack. */
internal fun sleepTimerShouldLeave(destination: Destination?): Boolean =
    destination is Destination.Playback || destination is Destination.JellyTvPlayback

/**
 * What [SleepTimerService.remaining] should publish.
 * [Duration.INFINITE] means "until this item ends" when the length is unknown.
 */
internal fun displayedSleepRemaining(
    active: Boolean,
    untilEnd: Boolean,
    remainingMs: Long?,
): Duration? =
    when {
        !active -> null
        untilEnd && remainingMs == null -> Duration.INFINITE
        remainingMs != null -> remainingMs.milliseconds
        else -> null
    }

/**
 * "23:10", or "1:30:00" once an hour is left. Rounds up so the full second just started
 * stays on screen. Infinite (unknown until-end) is an empty string; the chip uses its own label.
 */
fun formatSleepClock(remaining: Duration): String {
    if (remaining.isInfinite() || remaining.isNegative()) return ""
    val totalSeconds = (remaining.inWholeMilliseconds.coerceAtLeast(0) + 999) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.US, minutes, seconds)
    }
}

/** The chip turns accent in the final minute. */
fun sleepChipUrgent(remaining: Duration): Boolean = !remaining.isInfinite() && remaining.inWholeMilliseconds < 60_000

internal enum class SleepTimerEffect {
    None,
    Running,
    Fire,
    Clear,
}

/**
 * Pure sleep-timer state. Time and the player are passed in so tests can step a clock
 * without a dispatcher. [player] is compared by identity.
 */
internal class SleepTimerLogic {
    var active: Boolean = false
        private set

    var untilEnd: Boolean = false
        private set

    var remainingMs: Long? = null
        private set

    private var deadlineMs: Long? = null
    private var playerIdentity: Any? = null

    fun start(
        durationMs: Long,
        nowMs: Long,
        player: Any?,
    ) {
        val safe = durationMs.coerceAtLeast(0)
        active = true
        untilEnd = false
        playerIdentity = player
        deadlineMs = nowMs + safe
        remainingMs = safe
    }

    fun startUntilEnd(
        player: Any?,
        mediaRemainingMs: Long?,
    ) {
        active = true
        untilEnd = true
        playerIdentity = player
        deadlineMs = null
        remainingMs = mediaRemainingMs
    }

    fun cancel() {
        active = false
        untilEnd = false
        deadlineMs = null
        remainingMs = null
        playerIdentity = null
    }

    /**
     * Returns true the first time the timer is consumed by a fire. Later calls return false
     * so a listener and the ticker cannot both pause and navigate.
     */
    fun markFired(): Boolean {
        if (!active) return false
        active = false
        remainingMs = 0
        return true
    }

    fun tick(
        nowMs: Long,
        player: Any?,
        playerReleased: Boolean,
        ended: Boolean,
        mediaRemainingMs: Long?,
    ): SleepTimerEffect {
        if (!active) return SleepTimerEffect.None
        if (player !== playerIdentity || playerReleased) {
            cancel()
            return SleepTimerEffect.Clear
        }
        if (untilEnd) {
            if (ended) {
                return if (markFired()) SleepTimerEffect.Fire else SleepTimerEffect.None
            }
            if (mediaRemainingMs != null) {
                remainingMs = mediaRemainingMs
                if (mediaRemainingMs <= 0L) {
                    return if (markFired()) SleepTimerEffect.Fire else SleepTimerEffect.None
                }
            }
            return SleepTimerEffect.Running
        }
        val deadline = deadlineMs ?: return SleepTimerEffect.None
        val left = (deadline - nowMs).coerceAtLeast(0)
        remainingMs = left
        if (left == 0L) {
            return if (markFired()) SleepTimerEffect.Fire else SleepTimerEffect.None
        }
        return SleepTimerEffect.Running
    }

    companion object {
        /**
         * Milliseconds of media left, or null when the length is unknown.
         *
         * A non-positive duration covers Media3's TIME_UNSET. Live items report a sliding window
         * whose end is the live edge, a few seconds ahead of the playhead, so duration - position
         * would sleep as soon as "when this ends" was chosen. Those wait for playback to end.
         */
        fun mediaRemainingMs(
            durationMs: Long,
            positionMs: Long,
            live: Boolean,
        ): Long? {
            if (live || durationMs <= 0L) return null
            return (durationMs - positionMs).coerceAtLeast(0)
        }
    }
}
