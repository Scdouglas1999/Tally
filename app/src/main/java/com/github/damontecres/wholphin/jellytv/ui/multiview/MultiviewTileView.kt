@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.jellytv.ui.multiview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.ui.components.JtvSamples
import com.github.damontecres.wholphin.jellytv.ui.components.LabelBar
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.PreviewTvSpec

/**
 * One multiview tile: the stream on a `screen` face (TextureView — four hardware
 * SurfaceViews exceed many TV chipsets), an AUDIO/MUTED tag top-start, and a black
 * [LabelBar] with the channel name plus the live score line when known.
 *
 * Same focus treatment as GameCard: 1dp ruleStrong idle, 3dp accent focused, square,
 * no scale, no glow.
 */
@Composable
fun MultiviewTileView(
    tile: MultiviewTile,
    playback: MultiviewTilePlayback,
    hasAudio: Boolean,
    hideScores: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
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
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        // The tile takes its height from its width: a 16:9 picture plus the label bar, never letterboxed.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(JtvColors.screen),
                ) {
                    playback.player?.let { player ->
                        PlayerSurface(
                            player = player,
                            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            modifier =
                                Modifier.fillMaxSize(),
                        )
                    }
                    val status =
                        when {
                            playback.error != null -> playback.error
                            playback.player == null || playback.buffering ->
                                stringResource(R.string.jtv_mv_buffering)
                            else -> null
                        }
                    if (status != null) {
                        Text(
                            text = status.uppercase(),
                            style = JtvType.label,
                            color =
                                if (playback.error != null) JtvColors.liveText else JtvColors.muted,
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
                    text = tile.name.ifBlank { stringResource(R.string.jtv_mv_unknown_channel) },
                    live = tile.game?.isLive == true,
                    trailing = tileScoreLine(tile.game, hideScores),
                )
            }
            Text(
                text =
                    stringResource(
                        if (hasAudio) R.string.jtv_mv_audio else R.string.jtv_mv_muted,
                    ).uppercase(),
                style = JtvType.label,
                color = if (hasAudio) JtvColors.onAccent else JtvColors.muted,
                maxLines = 1,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(if (hasAudio) JtvColors.accent else JtvColors.labelBar)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** "IND 7 · KC 0 · 8:25 1ST" — only for a live game when scores aren't hidden. */
private fun tileScoreLine(
    game: JtvGame?,
    hideScores: Boolean,
): String? {
    if (game == null || !game.isLive || hideScores) return null
    val away = "${game.away.abbr} ${game.away.score?.toString() ?: "–"}"
    val home = "${game.home.abbr} ${game.home.score?.toString() ?: "–"}"
    val detail = game.detail.replace(" - ", " ").takeIf { it.isNotBlank() }
    return listOfNotNull(away, home, detail).joinToString(" · ")
}

@PreviewTvSpec
@Composable
private fun MultiviewTileViewPreview() {
    JtvSurface {
        MultiviewTileView(
            tile =
                MultiviewTile(
                    channelId = "dea2bdfac2d6739c",
                    name = "Indianapolis Colts Kansas City Chiefs",
                    hlsUrl = null,
                    game = JtvSamples.liveFootball,
                ),
            playback = MultiviewTilePlayback(),
            hasAudio = true,
            hideScores = false,
            onFocused = {},
            onClick = {},
            onLongClick = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
