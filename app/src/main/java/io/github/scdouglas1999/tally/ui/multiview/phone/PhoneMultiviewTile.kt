@file:OptIn(markerClass = [UnstableApi::class])

package io.github.scdouglas1999.tally.ui.multiview.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.media.kit.TallyPressIndication
import io.github.scdouglas1999.tally.ui.components.phone.PhoneGameFooter
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTile
import io.github.scdouglas1999.tally.ui.multiview.MultiviewTilePlayback
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/** Height of a tile's label bar on a phone ([PhoneGameFooter]). */
val PhoneTileLabelHeight = 28.dp

/**
 * A multiview tile on a phone: the stream on a `screen` face (TextureView, as on the TV), the AUDIO / MUTED tag
 * top-left, and the black label bar with the channel and the live score line. The tile with the sound has the 2dp
 * accent frame (on the TV the accent frame is focus, and the sound follows it); the others a 1dp `ruleStrong` one.
 * A tap calls [onTap] (move the sound here), a double tap [onDoubleTap] (full screen), a long-press [onLongPress]
 * (the tile's game sheet). [labelBar] false draws the picture alone (full screen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneMultiviewTile(
    tile: MultiviewTile,
    playback: MultiviewTilePlayback,
    hasAudio: Boolean,
    hideScores: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    labelBar: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .background(TallyColors.ground)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = TallyPressIndication,
                    role = Role.Button,
                    onLongClick = onLongPress,
                    onDoubleClick = onDoubleTap,
                    onClick = onTap,
                ).drawWithContent {
                    drawContent()
                    val w = (if (hasAudio) PhoneDimens.focusBorder else PhoneDimens.hairline).toPx()
                    drawRect(
                        color = if (hasAudio) TallyColors.accent else TallyColors.ruleStrong,
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        style = Stroke(width = w),
                    )
                },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(TallyColors.screen),
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
                        playback.error != null -> playback.error
                        playback.player == null || playback.buffering -> stringResource(R.string.tally_mv_buffering)
                        else -> null
                    }
                if (status != null) {
                    Text(
                        text = status.tallyUppercase(),
                        style = PhoneType.label,
                        color = if (playback.error != null) TallyColors.liveText else TallyColors.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    )
                }
            }
            if (labelBar) {
                PhoneGameFooter(
                    text = tile.name.ifBlank { stringResource(R.string.tally_mv_unknown_channel) },
                    live = tile.game?.isLive == true,
                    trailing = phoneTileScoreLine(tile.game, hideScores),
                )
            }
        }
        Text(
            text = stringResource(if (hasAudio) R.string.tally_mv_audio else R.string.tally_mv_muted).tallyUppercase(),
            style = PhoneType.label,
            color = if (hasAudio) TallyColors.onAccent else TallyColors.muted,
            maxLines = 1,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(if (hasAudio) TallyColors.accent else TallyColors.labelBar)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "TOR 3 · BAL 2": only for a live game when scores are shown (the TV tile's score line, without the clock). */
private fun phoneTileScoreLine(
    game: TallyGame?,
    hideScores: Boolean,
): String? {
    if (game == null || !game.isLive || hideScores) return null
    val away = "${game.away.abbr} ${game.away.score?.toString() ?: "–"}"
    val home = "${game.home.abbr} ${game.home.score?.toString() ?: "–"}"
    return "$away · $home"
}
