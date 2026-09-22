package com.github.damontecres.wholphin.jellytv.ui.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A second, muted game in the corner of the player. Owns its own ExoPlayer on the channel's anonymous HLS
 * playlist (see MultiviewPlayers for the pattern). STUB: keeps the chosen channel, plays nothing.
 */
class CornerViewController(
    context: Context,
) {
    private val _channelId = MutableStateFlow<String?>(null)
    val channelId: StateFlow<String?> = _channelId.asStateFlow()

    fun show(
        channelId: String,
        hlsUrl: String,
    ) {
        _channelId.value = channelId
    }

    fun hide() {
        _channelId.value = null
    }

    fun release() = hide()
}

/**
 * The corner tile: 16:9 picture, black label bar with the channel/game and a live score line, focusable
 * (OK = [onSwap], long press = [onClose]). Draws nothing while the controller has no channel. STUB.
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
}
