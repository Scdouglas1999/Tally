@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.jellytv.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvBoard
import com.github.damontecres.wholphin.jellytv.api.JtvChannel
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.api.JtvWatch
import com.github.damontecres.wholphin.jellytv.data.BoardOrganizer
import com.github.damontecres.wholphin.jellytv.ui.components.JtvSamples
import com.github.damontecres.wholphin.jellytv.ui.components.LabelBar
import com.github.damontecres.wholphin.jellytv.ui.multiview.MultiviewTilePlayback
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * A second, muted game in the corner of the player. Owns one ExoPlayer on the channel's anonymous HLS
 * playlist, the same way a multiview tile does: M3U8 mime type, TextureView, and a short error retry.
 */
class CornerViewController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val decoderLimitMessage = appContext.getString(R.string.jtv_corner_no_decoder)
    private val unavailableMessage = appContext.getString(R.string.jtv_corner_unavailable)

    private val _channelId = MutableStateFlow<String?>(null)
    val channelId: StateFlow<String?> = _channelId.asStateFlow()

    private val playbackState = mutableStateOf(MultiviewTilePlayback())

    /** Buffering, playing, or a fatal error. The tile reads this during composition. */
    val playback: State<MultiviewTilePlayback> = playbackState

    private var session: Session? = null
    private var released = false

    fun show(
        channelId: String,
        hlsUrl: String,
    ) {
        if (released) return
        val current = session
        if (current != null && current.channelId == channelId && current.url == hlsUrl) return
        current?.destroy()
        Timber.d("JellyTV corner: playing %s", channelId)
        // Drop the released player before the tile recomposes, or PlayerSurface binds a dead one.
        playbackState.value = MultiviewTilePlayback()
        _channelId.value = channelId
        val created = Session(channelId, hlsUrl)
        session = created
        created.publish()
    }

    fun hide() {
        session?.destroy()
        session = null
        _channelId.value = null
        playbackState.value = MultiviewTilePlayback(buffering = false, playing = false)
    }

    fun release() {
        released = true
        hide()
    }

    fun pause() {
        session?.player?.pause()
    }

    fun resume() {
        session?.player?.play()
    }

    private fun isDecoderInitFailure(error: PlaybackException): Boolean {
        if (error !is ExoPlaybackException || error.type != ExoPlaybackException.TYPE_RENDERER) {
            return false
        }
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is MediaCodecRenderer.DecoderInitializationException) return true
            cause = cause.cause
        }
        return false
    }

    private inner class Session(
        val channelId: String,
        val url: String,
    ) {
        val player: ExoPlayer = ExoPlayer.Builder(appContext).build()
        var retries = 0
        var errorMessage: String? = null
        var pendingRetry: Runnable? = null

        // prepare() can report to the listener synchronously, and the listener reads the fields above.
        init {
            player.setMediaItem(
                MediaItem
                    .Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
            player.volume = 0f
            // The full-screen player owns the audio. A muted corner must not take focus and duck it.
            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // handleAudioFocus =
                false,
            )
            player.addListener(TileListener())
            player.prepare()
            player.playWhenReady = true
        }

        fun publish() {
            playbackState.value =
                when {
                    errorMessage != null -> {
                        MultiviewTilePlayback(player = player, buffering = false, error = errorMessage)
                    }

                    player.playbackState == Player.STATE_READY -> {
                        MultiviewTilePlayback(player = player, buffering = false, playing = player.isPlaying)
                    }

                    else -> {
                        MultiviewTilePlayback(player = player, buffering = true)
                    }
                }
        }

        fun destroy() {
            pendingRetry?.let { handler.removeCallbacks(it) }
            player.release()
        }

        private fun scheduleRetry() {
            retries++
            pendingRetry =
                Runnable {
                    pendingRetry = null
                    errorMessage = null
                    player.prepare()
                    player.playWhenReady = true
                    publish()
                }.also { handler.postDelayed(it, RETRY_DELAY_MS) }
        }

        private inner class TileListener : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retries = 0
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "Corner stream failed on %s (attempt %d)", channelId, retries)
                when {
                    isDecoderInitFailure(error) -> errorMessage = decoderLimitMessage
                    retries >= MAX_RETRIES -> errorMessage = unavailableMessage
                    else -> scheduleRetry()
                }
                publish()
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_RETRIES = 5
    }
}

/**
 * The corner tile: a 400×225dp picture, black label bar, focusable (OK = [onSwap], long press = [onClose]).
 * Draws nothing while the controller has no channel.
 */
@Composable
fun CornerView(
    controller: CornerViewController,
    game: JtvGame?,
    channelName: String,
    hideScores: Boolean,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channelId by controller.channelId.collectAsState()
    if (channelId == null) return
    val playback by controller.playback
    CornerTile(
        playback = playback,
        game = game,
        channelName = channelName,
        hideScores = hideScores,
        onSwap = onSwap,
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
private fun CornerTile(
    playback: MultiviewTilePlayback,
    game: JtvGame?,
    channelName: String,
    hideScores: Boolean,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSwap,
        onLongClick = onClose,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = JtvColors.ground,
                contentColor = JtvColors.text,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = JtvColors.text,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = JtvColors.text,
                disabledContainerColor = JtvColors.ground,
                disabledContentColor = JtvColors.textSecondary,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                disabledBorder =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedDisabledBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier,
    ) {
        Box(Modifier.width(TILE_WIDTH)) {
            Column(Modifier.width(TILE_WIDTH)) {
                Box(
                    modifier =
                        Modifier
                            .width(TILE_WIDTH)
                            .height(TILE_HEIGHT)
                            .background(JtvColors.screen),
                ) {
                    playback.player?.let { player ->
                        PlayerSurface(
                            player = player,
                            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    val status =
                        when {
                            playback.error != null -> {
                                playback.error
                            }

                            playback.player == null || playback.buffering -> {
                                stringResource(R.string.jtv_corner_buffering)
                            }

                            else -> {
                                null
                            }
                        }
                    if (status != null) {
                        Text(
                            text = status.uppercase(),
                            style = JtvType.label,
                            color = if (playback.error != null) JtvColors.liveText else JtvColors.muted,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                        )
                    }
                }
                LabelBar(
                    text = channelName,
                    live = game?.isLive == true,
                    trailing = cornerScoreLine(game, hideScores),
                )
            }
            Text(
                text = stringResource(R.string.jtv_corner_muted).uppercase(),
                style = JtvType.label,
                color = JtvColors.muted,
                maxLines = 1,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(JtvColors.labelBar)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** "IND 7 · KC 0 · 8:25 1ST" — only for a live game when scores are visible. */
internal fun cornerScoreLine(
    game: JtvGame?,
    hideScores: Boolean,
): String? {
    if (game == null || !game.isLive || hideScores) return null
    val away = "${game.away.abbr} ${game.away.score?.toString() ?: "\u2013"}"
    val home = "${game.home.abbr} ${game.home.score?.toString() ?: "\u2013"}"
    val detail = game.detail.replace(" - ", " ").takeIf { it.isNotBlank() }
    return listOfNotNull(away, home, detail).joinToString(" · ").uppercase()
}

/** A request for the channel already filling the screen belongs to the next page (a swap hand-off). */
internal fun shouldShowCornerRequest(
    requestedChannelId: String?,
    playingChannelId: String,
): Boolean = !requestedChannelId.isNullOrBlank() && requestedChannelId != playingChannelId

/**
 * Absolute HLS url for [channelId], from that channel's `hlsPath` on the board.
 * Null when the channel is missing or the path is blank.
 */
internal fun cornerStreamUrl(
    channels: List<JtvChannel>,
    channelId: String,
    absoluteUrl: (String) -> String?,
): String? {
    val path = channels.firstOrNull { it.id == channelId }?.hlsPath?.takeIf { it.isNotBlank() } ?: return null
    return absoluteUrl(path)
}

internal fun cornerChannelName(
    board: JtvBoard?,
    channelId: String?,
    fallback: String?,
): String {
    if (!channelId.isNullOrBlank()) {
        board
            ?.channels
            ?.firstOrNull { it.id == channelId }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return fallback.orEmpty()
}

/** The live game on [channelId], if the board currently has one. */
internal fun cornerLiveGame(
    board: JtvBoard?,
    channelId: String?,
): JtvGame? {
    if (board == null || channelId.isNullOrBlank()) return null
    return BoardOrganizer.gameFor(channelId, board.games)
}

/**
 * The picture that should move into the corner when the viewer swaps: the live game on
 * [playingChannelId], otherwise any watchable game on it, otherwise the channel itself.
 */
internal fun gameToCornerAfterSwap(
    board: JtvBoard?,
    playingChannelId: String,
): JtvGame? {
    if (board == null || playingChannelId.isBlank()) return null
    val live = BoardOrganizer.gameFor(playingChannelId, board.games)
    if (!live?.watch?.liveTvItemId.isNullOrBlank()) return live
    val watched =
        board.games.firstOrNull {
            it.watch?.channelId == playingChannelId && !it.watch?.liveTvItemId.isNullOrBlank()
        }
    if (watched != null) return watched
    val channel = board.channels.firstOrNull { it.id == playingChannelId } ?: return null
    return channel.toCornerGame()
}

/**
 * Channels with a stream and a Live TV item but no game. Used so the switcher can still
 * offer a second picture when nothing on the board is live.
 */
internal fun gamelessChannelGames(
    board: JtvBoard?,
    playingChannelId: String,
): List<JtvGame> {
    if (board == null) return emptyList()
    return board.channels
        .filter {
            it.gameId.isNullOrBlank() &&
                it.id != playingChannelId &&
                !it.liveTvItemId.isNullOrBlank() &&
                it.hlsPath.isNotBlank()
        }.sortedBy { it.name.lowercase() }
        .mapNotNull { it.toCornerGame() }
}

private fun JtvChannel.toCornerGame(): JtvGame? {
    val itemId = liveTvItemId?.takeIf { it.isNotBlank() } ?: return null
    return JtvGame(
        id = gameId?.takeIf { it.isNotBlank() } ?: id,
        name = name,
        watch =
            JtvWatch(
                channelId = id,
                channelName = name,
                liveTvItemId = itemId,
                hlsPath = hlsPath,
                cardPath = cardPath,
            ),
    )
}

private val TILE_WIDTH = 400.dp
private val TILE_HEIGHT = 225.dp

@PreviewTvSpec
@Composable
private fun CornerTilePreview() {
    JtvSurface {
        Box(Modifier.fillMaxSize()) {
            CornerTile(
                playback = MultiviewTilePlayback(),
                game = JtvSamples.liveFootball,
                channelName =
                    JtvSamples.liveFootball.watch
                        ?.channelName
                        .orEmpty(),
                hideScores = false,
                onSwap = {},
                onClose = {},
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(48.dp),
            )
        }
    }
}
