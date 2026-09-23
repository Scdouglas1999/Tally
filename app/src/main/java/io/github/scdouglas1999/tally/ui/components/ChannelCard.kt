package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface

/**
 * A channel card: the channel's 16:9 card image cropped onto a [TallyColors.screen]
 * ground, then a black [LabelBar] with the channel name and the live indicator on.
 *
 * When [game] is the live game this channel is showing and scores are not hidden,
 * the label bar's trailing text is the scoreline ("AWY 7 · HOM 0").
 *
 * Same focus treatment as [GameCard]: 3dp accent border on groundRaised when
 * focused, 1dp ruleStrong otherwise; no scale, no glow.
 */
@Composable
fun ChannelCard(
    channel: TallyChannel,
    imageUrl: String?,
    game: TallyGame?,
    hideScores: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
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
                containerColor = TallyColors.ground,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier = modifier.height(TallyDimens.cardHeight),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(TallyColors.screen),
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            LabelBar(
                text = channel.name,
                live = true,
                trailing =
                    if (game != null && !hideScores) {
                        scoreline(game)
                    } else {
                        null
                    },
            )
        }
    }
}

/** "IND 7 · KC 0" — team abbreviation then score, away first. */
private fun scoreline(game: TallyGame): String =
    listOf(game.away, game.home).joinToString(" \u00b7 ") { team ->
        "${team.abbr.ifBlank { team.shortName }} ${team.score ?: "\u2013"}"
    }

@PreviewTvSpec
@Composable
private fun ChannelCardPreview() {
    TallySurface {
        ChannelCard(
            channel = TallySamples.channels[0],
            imageUrl = null,
            game = TallySamples.liveFootball,
            hideScores = false,
            onClick = {},
            onLongClick = {},
            modifier = Modifier.fillMaxWidth(0.3f),
        )
    }
}
