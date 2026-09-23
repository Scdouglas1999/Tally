package io.github.scdouglas1999.tally.media.music

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.LyricLine
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lyrics of the playing song. Synced lyrics: one line under the other, the [currentIndex] line in `text`, the
 * others `muted`, all Sans Medium 24sp; the column glides so the current line rests at 40% of the height. Unsynced
 * lyrics: plain Sans 20sp `text` from the top. Lines take focus (a 3dp accent frame): focus moves the column to the
 * focused line instead, and OK on a synced line seeks there ([onSeek]), as upstream's lyrics do. UP from the first
 * line goes to [up].
 */
@Composable
fun TallyLyrics(
    lyrics: LyricDto?,
    currentIndex: Int?,
    onSeek: (LyricLine) -> Unit,
    entryFocus: FocusRequester,
    up: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val lines = lyrics?.lyrics.orEmpty()
    val synced = remember(lines) { MusicFormat.isSynced(lines.map { it.start }) }
    val requesters = remember(lines) { List(lines.size) { FocusRequester() } }
    val tops = remember(lines) { mutableStateListOf<Int>().apply { repeat(lines.size) { add(0) } } }
    val heights = remember(lines) { mutableStateListOf<Int>().apply { repeat(lines.size) { add(0) } } }
    var focusedLine by remember(lines) { mutableStateOf<Int?>(null) }
    BoxWithConstraints(
        contentAlignment = Alignment.TopStart,
        modifier = modifier.clipToBounds(),
    ) {
        val viewport = constraints.maxHeight.toFloat()
        val target = focusedLine ?: if (synced) (currentIndex ?: 0) else null
        val targetOffset =
            if (target == null || target !in lines.indices) {
                0f
            } else {
                val offset = MusicFormat.lyricOffset(viewport, tops[target].toFloat(), heights[target].toFloat())
                // Plain lyrics start at the top and only scroll up as focus moves down.
                if (synced) offset else min(0f, offset)
            }
        val offset by animateFloatAsState(
            targetValue = targetOffset,
            animationSpec = tween(durationMillis = GLIDE_MS, easing = FastOutSlowInEasing),
            label = "lyrics-glide",
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(if (synced) 10.dp else 6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .offset { IntOffset(0, offset.roundToInt()) }
                    .focusRequester(entryFocus)
                    .focusProperties {
                        onEnter = {
                            val index = (if (synced) currentIndex else null) ?: 0
                            requesters.getOrNull(index)?.tryRequestFocus("tally-lyrics")
                        }
                    }.focusGroup(),
        ) {
            lines.forEachIndexed { index, line ->
                LyricText(
                    line = line,
                    current = synced && index == currentIndex,
                    synced = synced,
                    onClick = { if (line.start != null) onSeek(line) },
                    onFocused = { focused ->
                        if (focused) {
                            focusedLine = index
                        } else if (focusedLine == index) {
                            focusedLine = null
                        }
                    },
                    modifier =
                        Modifier
                            .focusRequester(requesters[index])
                            .then(if (index == 0) Modifier.focusProperties { this.up = up } else Modifier)
                            .onPlaced {
                                tops[index] = it.positionInParent().y.roundToInt()
                                heights[index] = it.size.height
                            },
                )
            }
        }
    }
}

@Composable
private fun LyricText(
    line: LyricLine,
    current: Boolean,
    synced: Boolean,
    onClick: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocused(focused) }
    val showFocus = tallyFocusVisible()
    val color =
        when {
            !synced || current || (focused && showFocus) -> TallyColors.text
            else -> TallyColors.muted
        }
    val phone = isPhone()
    Text(
        // An empty line (an instrumental break) keeps its height.
        text = line.text.ifBlank { " " },
        style =
            when {
                // A phone: the lines in PhoneType.title, the current one in `text` (the size never changes, so
                // nothing reflows as the song moves on).
                phone && synced -> PhoneType.title

                phone -> PhoneType.body

                synced -> SyncedStyle

                else -> PlainStyle
            },
        color = color,
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    if (focused && showFocus) {
                        val stroke = kotlin.math.floor(TallyDimens.focusBorder.toPx())
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                }.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = LyricInset, vertical = 6.dp),
    )
}

private const val GLIDE_MS = 300

/** Room between a line's text and its focus frame. */
internal val LyricInset = 12.dp

private val SyncedStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    )

private val PlainStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )
