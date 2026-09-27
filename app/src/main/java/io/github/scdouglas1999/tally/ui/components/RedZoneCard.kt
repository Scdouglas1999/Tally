package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyRedZone
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.data.RedZoneTile
import io.github.scdouglas1999.tally.media.kit.tallyClickable
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * The RedZone channel's tile, first in the Games board's live row: a game card's frame (same size, same focus
 * treatment) with `■ TALLY REDZONE` and the game's clock in its strip, ON NOW and the game RedZone is showing, a muted
 * line saying it is the lighter choice (one stream, where multiview plays several), and the channel's label bar with
 * why it is on that game (RED ZONE, SCORE, …). OK plays the channel full screen, like any channel.
 */
@Composable
fun RedZoneCard(
    tile: RedZoneTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val showFocus = tallyFocusVisible()
    val idleBorder = Border(border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong), shape = RectangleShape)
    val focusBorder = Border(border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.ground,
                contentColor = TallyColors.text,
                focusedContainerColor = if (showFocus) TallyColors.groundRaised else TallyColors.ground,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = idleBorder,
                focusedBorder = if (showFocus) focusBorder else idleBorder,
                pressedBorder = focusBorder,
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .size(TallyDimens.cardWidth, TallyDimens.cardHeight)
                .tallyClickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = 10.dp),
            ) {
                IndicatorSquare(color = TallyColors.live, size = 8.dp)
                Text(
                    text = stringResource(R.string.tally_23_redzone).tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                tile.game?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    // as a live game card's clock
                    Text(
                        text = detail,
                        style = TallyType.label,
                        color = TallyColors.accent,
                        maxLines = 1,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = redZoneTitle(tile),
                    style = TallyType.teamCard,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.tally_23_redzone_desc),
                    style = TallyType.hint,
                    color = TallyColors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LabelBar(
                text = tile.channel.name.ifBlank { stringResource(R.string.tally_23_redzone) },
                live = true,
                trailing = redZoneReasonRes(tile.status.reason)?.let { stringResource(it) },
            )
        }
    }
}

/** "On now: Chiefs at Bills": the status's title, else the board's game, else the channel's name. */
@Composable
fun redZoneTitle(tile: RedZoneTile): String {
    val matchup = redZoneMatchup(tile.status, tile.game)
    return if (matchup.isBlank()) tile.channel.name else stringResource(R.string.tally_23_redzone_on_now, matchup)
}

/** "Chiefs at Bills" for the game RedZone is on: the status's title, else the board's game; empty when neither says. */
@Composable
fun redZoneMatchup(
    status: TallyRedZone,
    game: TallyGame?,
): String {
    status.title
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    if (game == null) return ""
    val away = game.away.redZoneName()
    val home = game.home.redZoneName()
    return if (away.isBlank() || home.isBlank()) game.name else stringResource(R.string.tally_actions_at, away, home)
}

private fun TallyTeam.redZoneName(): String = shortName.ifBlank { abbr.ifBlank { name } }

@PreviewTvSpec
@Composable
private fun RedZoneCardPreview() {
    TallySurface {
        RedZoneCard(
            tile =
                RedZoneTile(
                    channel = TallyChannel(id = "redzone", name = "Tally RedZone", kind = TallyChannel.KIND_REDZONE),
                    status = TallyRedZone(active = true, gameId = "1", title = "Chiefs at Bills", reason = "red zone"),
                    game = TallySamples.liveFootball,
                ),
            onClick = {},
        )
    }
}
