package com.github.damontecres.wholphin.jellytv.together

import android.os.SystemClock
import androidx.media3.common.Player
import com.github.damontecres.wholphin.jellytv.ui.player.JellyTvPlayerMenu
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlayerFactory
import com.github.damontecres.wholphin.services.isReleased
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/**
 * Keeps whichever upstream player [PlayerFactory.currentPlayer] currently is in step with the group.
 * The service drives it; it never wraps the player, it only listens.
 *
 * Our own play, pause and seek calls set a flag and a 400 ms grace window so their callbacks are not
 * reported back to the server as something the person on the sofa did. A seek we issued also stays
 * marked until the player reports that discontinuity (or two seconds pass): ExoPlayer often delivers
 * it after the grace window, and treating it as a user seek would loop.
 */
internal class TogetherPlayerSync(
    private val api: ApiClient,
    private val playerFactory: PlayerFactory,
    private val navigationManager: NavigationManager,
    private val clock: ServerClock,
    private val scope: CoroutineScope,
    private val onNotice: (TogetherNotice) -> Unit,
    private val onLeftPlayback: () -> Unit,
) {
    private val listener = Listener()
    private var running = false
    private var attached: Player? = null
    private var watchJob: Job? = null
    private var driftJob: Job? = null
    private var speedJob: Job? = null
    private val commandJobs = mutableListOf<Job>()

    private var holding = false
    private var expectedPlaying = false
    private var announcedHold = false
    private var announcedPause = false
    private var localPause = false

    private var playlistItemId: UUID? = null
    private var wantedItemId: UUID? = null
    private var startPositionTicks = 0L
    private var sawQueue = false
    private var playerBeingReplaced: Player? = null
    private var heldPlayer: Player? = null

    private var commandPositionTicks = 0L
    private var commandWhen: Instant? = null

    private var awaitingReady = false
    private var readySent = false
    private var suppressBuffering = false
    private var ownUntilElapsed = 0L
    private var ownSeekTargetMs: Long? = null
    private var ownSeekUntilElapsed = 0L

    fun start() {
        if (running) return
        running = true
        watchJob =
            scope.launch {
                var sawPlayer = false
                var missingSince: Long? = null
                while (isActive && running) {
                    val player = livePlayer()
                    if (player != null) {
                        sawPlayer = true
                        missingSince = null
                        onPlayer(player)
                    } else if (sawPlayer) {
                        val now = SystemClock.elapsedRealtime()
                        val since = missingSince ?: now.also { missingSince = it }
                        if (now - since >= PLAYER_GONE_MS) {
                            Timber.tag(TOGETHER_SYNC_LOG).i("player gone, leaving the party")
                            running = false
                            onLeftPlayback()
                            return@launch
                        }
                    }
                    delay(PLAYER_POLL_MS)
                }
            }
    }

    fun stop() {
        running = false
        watchJob?.cancel()
        watchJob = null
        cancelDrift()
        commandJobs.forEach { it.cancel() }
        commandJobs.clear()
        val player = attached
        detach()
        if (player != null && !player.isReleased) {
            runCatching { player.setPlaybackSpeed(1f) }
        }
        holding = false
        expectedPlaying = false
        announcedHold = false
        announcedPause = false
        localPause = false
        sawQueue = false
        heldPlayer = null
        playerBeingReplaced = null
        awaitingReady = false
        readySent = false
        suppressBuffering = false
    }

    fun noteAnnouncedHold() {
        announcedHold = true
    }

    fun onQueue(
        queue: TogetherQueue,
        reason: PlayQueueUpdateReason,
    ) {
        if (!running) return
        val first = !sawQueue
        sawQueue = true
        playlistItemId = queue.playingPlaylistItemId
        startPositionTicks = queue.startPositionTicks
        wantedItemId = queue.playingItemId
        val follow = first || reason in FOLLOW_REASONS
        val itemId = queue.playingItemId
        if (!follow || itemId == null) return
        holding = true
        expectedPlaying = false
        cancelDrift()
        heldPlayer = null
        readySent = false
        awaitingReady = false
        openOrHold(itemId, queue.startPositionTicks / SyncPolicy.TICKS_PER_MS)
    }

    fun onCommand(command: SendCommand) {
        if (!running) return
        if (command.command != SendCommandType.STOP && command.playlistItemId != playlistItemId) return
        val whenInstant = command.`when`.toTogetherInstant()
        val positionTicks = command.positionTicks ?: 0L
        val leadMs = Duration.between(clock.serverNow(Instant.now()), whenInstant).toMillis()
        Timber.tag(TOGETHER_SYNC_LOG).i(
            "command=%s positionMs=%d leadMs=%d",
            command.command.serialName,
            positionTicks / SyncPolicy.TICKS_PER_MS,
            leadMs,
        )
        when (command.command) {
            SendCommandType.UNPAUSE -> {
                schedule(whenInstant) { applyUnpause(whenInstant, positionTicks) }
            }

            SendCommandType.PAUSE -> {
                schedule(whenInstant) { applyPause(positionTicks) }
            }

            SendCommandType.SEEK -> {
                applySeek(positionTicks)
            }

            SendCommandType.STOP -> {
                applyStop()
            }
        }
    }

    private fun openOrHold(
        itemId: UUID,
        positionMs: Long,
    ) {
        val player = livePlayer()
        if (player != null && JellyTvPlayerMenu.nowPlayingItemId == itemId) {
            playerBeingReplaced = null
            applyHold(player)
            return
        }
        playerBeingReplaced = player
        val top = navigationManager.backStack.lastOrNull()
        if (top is Destination.Playback || top is Destination.JellyTvPlayback) {
            navigationManager.backStack.removeLastOrNull()
        }
        navigationManager.navigateTo(Destination.Playback(itemId = itemId, positionMs = positionMs))
    }

    private fun onPlayer(player: Player) {
        if (playerBeingReplaced != null && player === playerBeingReplaced) {
            ensureAttached(player)
            return
        }
        if (holding) applyHold(player) else ensureAttached(player)
    }

    private fun applyHold(player: Player) {
        ensureAttached(player)
        val playlistId = playlistItemId ?: return
        if (heldPlayer === player) return
        heldPlayer = player
        playerBeingReplaced = null
        val startMs = startPositionTicks / SyncPolicy.TICKS_PER_MS
        Timber.tag(TOGETHER_SYNC_LOG).i("hold item=%s startMs=%d", wantedItemId, startMs)
        markOwn()
        player.pause()
        if (abs(player.currentPosition - startMs) > HOLD_SEEK_TOLERANCE_MS) {
            seekOwn(player, startMs)
        }
        beginReadyWait()
        scope.launch {
            postBuffering(playlistId, startPositionTicks, playing = false)
            if (player.playbackState == Player.STATE_READY) reportReady(player, playing = false)
        }
    }

    private fun applyUnpause(
        whenInstant: Instant,
        positionTicks: Long,
    ) {
        holding = false
        expectedPlaying = true
        commandPositionTicks = positionTicks
        commandWhen = whenInstant
        val player = attached?.takeUnless { it.isReleased } ?: return
        val target =
            SyncPolicy.expectedPositionMs(
                commandPositionTicks = positionTicks,
                commandWhen = whenInstant,
                serverNow = clock.serverNow(Instant.now()),
            )
        markOwn()
        player.setPlaybackSpeed(1f)
        if (abs(player.currentPosition - target) >= SyncPolicy.MIN_DRIFT_MS) {
            seekOwn(player, target)
        }
        player.play()
        announceResumed()
        startDrift()
    }

    private fun applyPause(positionTicks: Long) {
        val requested = localPause
        localPause = false
        expectedPlaying = false
        holding = true
        cancelDrift()
        val player = attached?.takeUnless { it.isReleased }
        if (player != null) {
            val positionMs = positionTicks / SyncPolicy.TICKS_PER_MS
            markOwn()
            player.setPlaybackSpeed(1f)
            player.pause()
            if (abs(player.currentPosition - positionMs) >= SyncPolicy.MIN_DRIFT_MS) {
                seekOwn(player, positionMs)
            }
        }
        if (!requested) announcePaused()
    }

    private fun applySeek(positionTicks: Long) {
        expectedPlaying = false
        holding = true
        cancelDrift()
        val player = attached?.takeUnless { it.isReleased } ?: return
        val playlistId = playlistItemId ?: return
        val positionMs = positionTicks / SyncPolicy.TICKS_PER_MS
        markOwn()
        player.setPlaybackSpeed(1f)
        player.pause()
        seekOwn(player, positionMs)
        beginReadyWait()
        scope.launch {
            postBuffering(playlistId, positionTicks, playing = false)
            if (player.playbackState == Player.STATE_READY) reportReady(player, playing = false)
        }
    }

    private fun applyStop() {
        expectedPlaying = false
        holding = true
        cancelDrift()
        val player = attached?.takeUnless { it.isReleased } ?: return
        markOwn()
        player.setPlaybackSpeed(1f)
        player.pause()
    }

    private fun announcePaused() {
        // A joining TV gets Pause, a buffering round, then Pause again: say PAUSED once until playback resumes.
        if (announcedPause) return
        announcedPause = true
        announcedHold = true
        onNotice(TogetherNotice.Paused)
    }

    private fun announceResumed() {
        announcedPause = false
        if (!announcedHold) return
        announcedHold = false
        onNotice(TogetherNotice.Resumed)
    }

    private fun startDrift() {
        driftJob?.cancel()
        val whenInstant = commandWhen ?: return
        val ticks = commandPositionTicks
        driftJob =
            scope.launch {
                while (isActive && running && expectedPlaying) {
                    delay(SyncPolicy.CHECK_INTERVAL_MS)
                    if (!expectedPlaying || !running) return@launch
                    val player = attached?.takeUnless { it.isReleased } ?: return@launch
                    val expected =
                        SyncPolicy.expectedPositionMs(
                            commandPositionTicks = ticks,
                            commandWhen = whenInstant,
                            serverNow = clock.serverNow(Instant.now()),
                        )
                    val actual = player.currentPosition
                    val drift = expected - actual
                    val ready = player.playbackState == Player.STATE_READY
                    val correction =
                        if (ready) {
                            SyncPolicy.correction(actual, expected)
                        } else {
                            SyncPolicy.Correction.None
                        }
                    val action =
                        when (correction) {
                            SyncPolicy.Correction.None -> "none"
                            is SyncPolicy.Correction.Speed -> "speed"
                            is SyncPolicy.Correction.Seek -> "seek"
                        }
                    Timber.tag(TOGETHER_SYNC_LOG).d("TogetherSync drift=%d action=%s", drift, action)
                    when (correction) {
                        SyncPolicy.Correction.None -> {}

                        is SyncPolicy.Correction.Speed -> {
                            if (speedJob?.isActive != true) {
                                markOwn()
                                player.setPlaybackSpeed(correction.speed)
                                speedJob =
                                    scope.launch {
                                        delay(correction.forMs)
                                        val current = attached?.takeUnless { it.isReleased } ?: return@launch
                                        markOwn()
                                        current.setPlaybackSpeed(1f)
                                    }
                            }
                        }

                        is SyncPolicy.Correction.Seek -> {
                            speedJob?.cancel()
                            markOwn()
                            player.setPlaybackSpeed(1f)
                            seekOwn(player, correction.toMs)
                        }
                    }
                }
            }
    }

    private fun cancelDrift() {
        driftJob?.cancel()
        driftJob = null
        speedJob?.cancel()
        speedJob = null
        attached?.takeUnless { it.isReleased }?.let { player ->
            runCatching {
                markOwn()
                player.setPlaybackSpeed(1f)
            }
        }
    }

    private fun schedule(
        fireAt: Instant,
        block: () -> Unit,
    ) {
        val localFire = SyncPolicy.localFireTime(fireAt, clock, Instant.now())
        val job =
            scope.launch {
                val wait = Duration.between(Instant.now(), localFire).toMillis()
                if (wait > 0) delay(wait)
                if (!running) return@launch
                block()
            }
        commandJobs += job
        job.invokeOnCompletion { commandJobs.remove(job) }
    }

    private fun onUserPlayPause(playWhenReady: Boolean) {
        val player = attached?.takeUnless { it.isReleased } ?: return
        if (playWhenReady == expectedPlaying) return
        markOwn()
        if (expectedPlaying) player.play() else player.pause()
        if (!playWhenReady) localPause = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (playWhenReady) {
                        api.syncPlayApi.syncPlayUnpause()
                    } else {
                        api.syncPlayApi.syncPlayPause()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!playWhenReady) localPause = false
                Timber.tag(TOGETHER_SYNC_LOG).w(e, "play/pause request failed")
            }
        }
    }

    private fun onUserSeek(positionMs: Long) {
        val player = attached?.takeUnless { it.isReleased } ?: return
        expectedPlaying = false
        holding = true
        cancelDrift()
        markOwn()
        player.pause()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.syncPlayApi.syncPlaySeek(SeekRequestDto(positionMs * SyncPolicy.TICKS_PER_MS))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).w(e, "seek request failed")
            }
        }
    }

    private fun onBuffering() {
        if (!expectedPlaying || holding) return
        val playlistId = playlistItemId ?: return
        val player = attached?.takeUnless { it.isReleased } ?: return
        beginReadyWait()
        scope.launch {
            postBuffering(playlistId, player.currentPosition * SyncPolicy.TICKS_PER_MS, playing = true)
        }
    }

    private fun beginReadyWait() {
        readySent = false
        awaitingReady = true
    }

    private suspend fun postBuffering(
        playlistId: UUID,
        positionTicks: Long,
        playing: Boolean,
    ) {
        try {
            withContext(Dispatchers.IO) {
                api.syncPlayApi.syncPlayBuffering(
                    BufferRequestDto(
                        `when` = serverNowLocal(),
                        positionTicks = positionTicks,
                        isPlaying = playing,
                        playlistItemId = playlistId,
                    ),
                )
            }
            Timber.tag(TOGETHER_SYNC_LOG).i("buffering playing=%s positionMs=%d", playing, positionTicks / SyncPolicy.TICKS_PER_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TOGETHER_SYNC_LOG).w(e, "buffering report failed")
        }
    }

    private fun reportReady(
        player: Player,
        playing: Boolean,
    ) {
        if (readySent) return
        val playlistId = playlistItemId ?: return
        readySent = true
        awaitingReady = false
        val positionTicks = player.currentPosition * SyncPolicy.TICKS_PER_MS
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.syncPlayApi.syncPlayReady(
                        ReadyRequestDto(
                            `when` = serverNowLocal(),
                            positionTicks = positionTicks,
                            isPlaying = playing,
                            playlistItemId = playlistId,
                        ),
                    )
                }
                Timber.tag(TOGETHER_SYNC_LOG).i("ready playing=%s positionMs=%d", playing, positionTicks / SyncPolicy.TICKS_PER_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readySent = false
                Timber.tag(TOGETHER_SYNC_LOG).w(e, "ready report failed")
            }
        }
    }

    private fun serverNowLocal(): LocalDateTime = LocalDateTime.ofInstant(clock.serverNow(Instant.now()), ZoneId.systemDefault())

    private fun ensureAttached(player: Player) {
        if (attached === player) return
        detach()
        attached = player
        player.addListener(listener)
    }

    private fun detach() {
        val player = attached ?: return
        attached = null
        if (!player.isReleased) runCatching { player.removeListener(listener) }
    }

    private fun livePlayer(): Player? = playerFactory.currentPlayer?.takeUnless { it.isReleased }

    private fun markOwn() {
        ownUntilElapsed = SystemClock.elapsedRealtime() + OWN_GRACE_MS
    }

    private fun seekOwn(
        player: Player,
        positionMs: Long,
    ) {
        ownSeekTargetMs = positionMs
        ownSeekUntilElapsed = SystemClock.elapsedRealtime() + OWN_SEEK_MS
        suppressBuffering = true
        markOwn()
        player.seekTo(positionMs)
    }

    private fun ignoring(): Boolean = SystemClock.elapsedRealtime() < ownUntilElapsed

    private fun ignoringSeek(positionMs: Long): Boolean {
        val target = ownSeekTargetMs ?: return false
        if (SystemClock.elapsedRealtime() > ownSeekUntilElapsed) {
            ownSeekTargetMs = null
            return false
        }
        if (abs(positionMs - target) <= HOLD_SEEK_TOLERANCE_MS) {
            ownSeekTargetMs = null
            return true
        }
        return false
    }

    private inner class Listener : Player.Listener {
        override fun onPlayWhenReadyChanged(
            playWhenReady: Boolean,
            reason: Int,
        ) {
            if (!running || ignoring()) return
            scope.launch { onUserPlayPause(playWhenReady) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!running || reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (ignoring() || ignoringSeek(newPosition.positionMs)) return
            scope.launch { onUserSeek(newPosition.positionMs) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!running) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (ignoring() || suppressBuffering) return
                    scope.launch { onBuffering() }
                }

                Player.STATE_READY -> {
                    suppressBuffering = false
                    if (!awaitingReady) return
                    val player = attached ?: return
                    scope.launch { reportReady(player, playing = expectedPlaying && !holding) }
                }
            }
        }
    }

    private companion object {
        const val OWN_GRACE_MS = 400L
        const val OWN_SEEK_MS = 2_000L
        const val HOLD_SEEK_TOLERANCE_MS = 1_000L
        const val PLAYER_GONE_MS = 5_000L
        const val PLAYER_POLL_MS = 200L
        val FOLLOW_REASONS =
            setOf(
                PlayQueueUpdateReason.NEW_PLAYLIST,
                PlayQueueUpdateReason.SET_CURRENT_ITEM,
                PlayQueueUpdateReason.NEXT_ITEM,
                PlayQueueUpdateReason.PREVIOUS_ITEM,
            )
    }
}

internal const val TOGETHER_SYNC_LOG = "TogetherSync"
