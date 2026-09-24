package io.github.scdouglas1999.tally.media.music.phone

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberCurrentMediaItemState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.ui.settings.phone.phoneTouch
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.delay
import javax.inject.Inject

/** The mini player's height (without the rule on top). */
val PhoneMiniPlayerHeight = 56.dp

private const val PROGRESS_POLL_MS = 500L
private val MiniCover = 40.dp
private val ProgressLine = 2.dp

/** Upstream's music state for the mini player: the same [MusicService] the music pages play through. */
@HiltViewModel
class PhoneMiniPlayerViewModel
    @Inject
    constructor(
        val musicService: MusicService,
        val navigationManager: NavigationManager,
    ) : ViewModel()

/** The room the mini player takes above the bottom bar: its height while music is loaded, else nothing. */
@Composable
fun phoneMiniPlayerSpace(viewModel: PhoneMiniPlayerViewModel = hiltViewModel()): Dp {
    val state by viewModel.musicService.state.collectAsState()
    return if (state.currentItemId != null) PhoneMiniPlayerHeight else 0.dp
}

/**
 * The mini player on a phone (in the phone shell, above the bottom bar, on every page while music is loaded; never
 * over video, whose pages have no shell): 56dp on `groundRaised` with a 1dp `rule` line on top and the playback
 * progress as a 2dp accent line along it; the cover (40dp), title · artist, and play/pause. A tap opens now playing.
 */
@OptIn(UnstableApi::class)
@Composable
fun PhoneMiniPlayer(
    modifier: Modifier = Modifier,
    viewModel: PhoneMiniPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.musicService.state.collectAsState()
    if (state.currentItemId == null) return
    val player = viewModel.musicService.player
    val currentMediaItem = rememberCurrentMediaItemState(player)
    val current = currentMediaItem.mediaItem?.localConfiguration?.tag as? AudioItem
    val playPause = rememberPlayPauseButtonState(player)
    val progress = rememberProgress(player)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(PhoneMiniPlayerHeight)
                .background(TallyColors.groundRaised)
                .drawBehind {
                    val rule = PhoneDimens.hairline.toPx()
                    drawLine(TallyColors.rule, Offset(0f, rule / 2f), Offset(size.width, rule / 2f), rule)
                    val line = ProgressLine.toPx()
                    if (progress > 0f) {
                        drawLine(
                            TallyColors.accent,
                            Offset(0f, line / 2f),
                            Offset(size.width * progress, line / 2f),
                            line,
                        )
                    }
                }.phoneTouch(onClick = { viewModel.navigationManager.navigateTo(Destination.NowPlaying) })
                .padding(start = PhoneDimens.margin, end = 4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(MiniCover)
                    .background(TallyColors.screen)
                    .border(PhoneDimens.hairline, TallyColors.rule),
        ) {
            io.github.scdouglas1999.tally.downloads.ui.musicCover(current)?.let {
                AsyncImage(
                    model = it,
                    contentDescription = current?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
                )
            }
        }
        val title = current?.title ?: state.currentItemTitle.orEmpty()
        val artist = current?.artistNames?.takeIf { it.isNotBlank() }
        Text(
            // title · artist, the artist muted
            text =
                buildAnnotatedString {
                    append(title)
                    if (artist != null) {
                        withStyle(SpanStyle(color = TallyColors.muted)) { append(" · $artist") }
                    }
                },
            style = PhoneType.body,
            color = TallyColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PhoneIconSquare(
            glyph =
                stringResource(
                    if (playPause.showPlay) R.string.tally_player_glyph_play else R.string.tally_player_glyph_pause,
                ),
            label = stringResource(if (playPause.showPlay) R.string.tally_music_play else R.string.tally_music_pause),
            onClick = playPause::onClick,
            frame = androidx.compose.ui.graphics.Color.Transparent,
            glyphSize = 18,
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/** The played fraction of the current track, polled while the mini player is shown. */
@Composable
private fun rememberProgress(player: Player): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            progress =
                if (duration == C.TIME_UNSET || duration <= 0L) {
                    0f
                } else {
                    (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                }
            delay(PROGRESS_POLL_MS)
        }
    }
    return progress
}
