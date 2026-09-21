package com.github.damontecres.wholphin.jellytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import com.github.damontecres.wholphin.jellytv.ui.components.GameCard
import com.github.damontecres.wholphin.jellytv.ui.components.JtvSamples
import com.github.damontecres.wholphin.jellytv.ui.components.KeyHint
import com.github.damontecres.wholphin.jellytv.ui.components.RowHeader
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

/**
 * The in-player "also on now" switcher: a bottom-anchored scrim with a header and a
 * horizontal row of live game cards over the picture. Focus lands on the first card.
 * OK switches channel; hold adds the focused game to multiview.
 *
 * [onRowFocusChanged] reports whether focus is inside the card row so the page can
 * decide whether DPAD_UP should close the switcher.
 */
@Composable
fun GameSwitcher(
    games: List<JtvGame>,
    hideScores: Boolean,
    favorites: Set<String>,
    onSwitch: (JtvGame) -> Unit,
    onAddToMultiview: (JtvGame) -> Unit,
    onRowFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The cards may need a frame to become focusable; retry briefly rather than
        // leaving focus stranded on the player underneath.
        var attempts = 0
        while (!firstCardFocus.tryRequestFocus("jtv-player-switcher") && attempts < 5) {
            attempts++
            delay(50)
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    // Functional scrim (as in the mockup): fully dark by the time the header starts, so the
                    // header and key hints stay legible over a bright pitch or a daytime sky.
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = 0.78f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                ).onFocusChanged { onRowFocusChanged(it.hasFocus) }
                .padding(top = 112.dp, bottom = JtvDimens.marginVertical),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = JtvDimens.marginHorizontal),
        ) {
            RowHeader(
                title = stringResource(R.string.jtv_player_also_on_now),
                count = games.size,
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                KeyHint(
                    key = stringResource(R.string.jtv_key_ok),
                    label = stringResource(R.string.jtv_player_switch),
                )
                KeyHint(
                    key = stringResource(R.string.jtv_key_hold),
                    label = stringResource(R.string.jtv_add_to_multiview),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = JtvDimens.marginHorizontal),
            horizontalArrangement = Arrangement.spacedBy(JtvDimens.cardGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                GameCard(
                    game = game,
                    hideScores = hideScores,
                    isFavorite = game.watch?.channelId in favorites,
                    onClick = { onSwitch(game) },
                    onLongClick = { onAddToMultiview(game) },
                    modifier =
                        if (index == 0) {
                            Modifier.focusRequester(firstCardFocus)
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

@PreviewTvSpec
@Composable
private fun GameSwitcherPreview() {
    JtvSurface {
        GameSwitcher(
            games = JtvSamples.games.filter { it.isLive },
            hideScores = false,
            favorites = setOf("dea2bdfac2d6739c"),
            onSwitch = {},
            onAddToMultiview = {},
            onRowFocusChanged = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
