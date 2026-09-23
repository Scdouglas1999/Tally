package io.github.scdouglas1999.tally.ui.player.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.playback.PlaybackViewModel
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.ui.components.LowerThird
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch

private val StillWidth = 160.dp
private val StillHeight = 90.dp

private val lineStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )

/**
 * Upstream's next-up card (`NextUpEpisode`) as a [LowerThird] at the bottom right: kicker `UP NEXT`, `S1 E4 · Cancer
 * Man`, a 160x90 still, `PLAY NOW` (primary, focused) and `DISMISS`, and a 2dp accent bar at the foot that empties
 * over upstream's countdown ([timeLeft] seconds of [countdownSeconds], only while [autoPlayEnabled]).
 *
 * PLAY NOW is upstream's card click ([onPlayNow]). DISMISS does what upstream's BACK does with the card up: hides the
 * card while the episode is still playing, otherwise leaves the player. The countdown itself, and BACK, stay
 * upstream's.
 */
@Composable
fun TallyNextUp(
    item: BaseItem,
    viewModel: PlaybackViewModel,
    player: Player,
    timeLeft: Long,
    countdownSeconds: Long,
    autoPlayEnabled: Boolean,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.tryRequestFocus() }
    val counting = autoPlayEnabled && timeLeft > 0 && countdownSeconds > 0
    // The bar moves continuously: each second it slides to where the next tick will leave it.
    val bar = remember { Animatable(PlayerFormat.countdownFraction(timeLeft, countdownSeconds)) }
    LaunchedEffect(timeLeft, counting) {
        if (counting) {
            bar.animateTo(
                PlayerFormat.countdownFraction(timeLeft - 1, countdownSeconds),
                tween(durationMillis = 1000, easing = LinearEasing),
            )
        }
    }
    TallyScale {
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
        ) {
            LowerThird(visible = true) {
                Column(
                    modifier =
                        Modifier
                            .width(520.dp)
                            .background(TallyColors.ground),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Still(item)
                        Column(
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f).height(StillHeight),
                        ) {
                            Text(
                                text = stringResource(R.string.tally_player_up_next).tallyUppercase(),
                                style = TallyType.label,
                                color = TallyColors.accent,
                                maxLines = 1,
                            )
                            Text(
                                text = PlayerFormat.nextUpLine(item.data).orEmpty(),
                                style = lineStyle,
                                color = TallyColors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TallyButton(
                                    label = stringResource(R.string.tally_player_play_now),
                                    onClick = onPlayNow,
                                    primary = true,
                                    modifier = Modifier.focusRequester(playFocus),
                                )
                                TallyButton(
                                    label = stringResource(R.string.tally_player_dismiss),
                                    onClick = {
                                        if (player.isPlaying) {
                                            scope.launch(ExceptionHandler()) { viewModel.cancelUpNextEpisode() }
                                        } else {
                                            viewModel.navigationManager.goBack()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(TallyColors.rule),
                    ) {
                        if (autoPlayEnabled && (counting || bar.value > 0f)) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(bar.value)
                                    .background(TallyColors.accent),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Still(item: BaseItem) {
    val url = rememberWideImageUrl(item)
    Box(
        modifier =
            Modifier
                .size(StillWidth, StillHeight)
                .background(TallyColors.screen)
                .border(TallyDimens.hairline, TallyColors.ruleStrong),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                onError = { logCoilError(url, it.result) },
                modifier = Modifier.fillMaxSize().padding(TallyDimens.hairline),
            )
        }
    }
}
