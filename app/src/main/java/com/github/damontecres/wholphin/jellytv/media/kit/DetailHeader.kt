package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytv.ui.components.tallyUppercase
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.util.StreamFormatting.resolutionString
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val TICKS_PER_SECOND = 10_000_000L

/** `2h 28m`, `52m`, `1m 30s`, `45s`. Seconds are kept only when there are no hours. */
fun formatRuntime(ticks: Long): String {
    if (ticks <= 0L) return "0s"
    val totalSeconds = ticks / TICKS_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/** Clock position for a chapter: `30:00`, `01:00`, `1:02:03`. */
fun formatPosition(ticks: Long): String {
    if (ticks <= 0L) return "00:00"
    val totalSeconds = ticks / TICKS_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, seconds)
    }
}

/**
 * Local end time if playback of [remainingMs] starts at [now].
 * 12-hour: `ENDS 9:41 PM`. 24-hour: `ENDS 21:41`.
 */
fun formatEndsAt(
    now: Instant,
    remainingMs: Long,
    zone: ZoneId,
    is24h: Boolean,
): String {
    val zoned = now.plusMillis(remainingMs).atZone(zone)
    val pattern = if (is24h) "HH:mm" else "h:mm a"
    val formatted =
        DateTimeFormatter
            .ofPattern(pattern, Locale.US)
            .format(zoned)
            .replace('\u202f', ' ')
            .replace('\u00a0', ' ')
    return "ENDS $formatted"
}

/** 0–100. Zero when either tick count is not positive. */
fun resumePercent(
    positionTicks: Long,
    runtimeTicks: Long,
): Int {
    if (positionTicks <= 0L || runtimeTicks <= 0L) return 0
    return ((positionTicks.toDouble() * 100.0) / runtimeTicks.toDouble())
        .roundToInt()
        .coerceIn(0, 100)
}

/**
 * Tech boxes for one source, in order: resolution, video codec, HDR type,
 * `EN · AAC STEREO`, `CC · EN ES` (up to three languages, then `+n`).
 * [video] and [audio] override the source's default streams when the user has chosen them.
 */
fun techBoxes(
    source: MediaSourceInfo?,
    video: MediaStream? = null,
    audio: MediaStream? = null,
): List<String> {
    val streams = source?.mediaStreams.orEmpty()
    val videoStream = video ?: streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audioStream =
        audio
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault }
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
    return buildList {
        if (videoStream != null) {
            val width = videoStream.width
            val height = videoStream.height
            if (width != null && height != null && width > 0 && height > 0) {
                add(resolutionString(width, height, videoStream.isInterlaced).tallyUppercase())
            }
            videoStream.codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
            hdrLabel(videoStream)?.let { add(it) }
        }
        if (audioStream != null) {
            audioLabel(audioStream)?.let { add(it) }
        }
        subtitleLabel(streams)?.let { add(it) }
    }
}

internal fun shortLanguage(code: String?): String? {
    if (code.isNullOrBlank() || code.equals("und", ignoreCase = true)) return null
    if (code.length == 2) return code.uppercase(Locale.US)
    val match =
        Locale.getAvailableLocales().firstOrNull { locale ->
            locale.language.length == 2 && locale.isO3Language.equals(code, ignoreCase = true)
        }
    val language =
        match?.language
            ?: ISO_639_2[code.lowercase(Locale.US)]
            ?: code.take(2)
    return language.uppercase(Locale.US)
}

/** 639-2 codes the test films use, for JVMs whose locale data does not map them. */
private val ISO_639_2 =
    mapOf(
        "eng" to "en",
        "spa" to "es",
        "fra" to "fr",
        "fre" to "fr",
        "deu" to "de",
        "ger" to "de",
        "ita" to "it",
        "por" to "pt",
    )

private fun hdrLabel(stream: MediaStream): String? {
    if (stream.videoRange != VideoRange.HDR && stream.videoRangeType != VideoRangeType.HDR10 &&
        stream.videoRangeType != VideoRangeType.HDR10_PLUS &&
        stream.videoRangeType != VideoRangeType.HLG &&
        stream.videoRangeType != VideoRangeType.DOVI &&
        stream.videoRangeType != VideoRangeType.DOVI_WITH_HDR10 &&
        stream.videoRangeType != VideoRangeType.DOVI_WITH_HLG &&
        stream.videoRangeType != VideoRangeType.DOVI_WITH_SDR
    ) {
        return null
    }
    if (!stream.videoDoViTitle.isNullOrBlank()) return "DOLBY VISION"
    return when (stream.videoRangeType) {
        VideoRangeType.HDR10 -> "HDR10"

        VideoRangeType.HDR10_PLUS -> "HDR10+"

        VideoRangeType.HLG -> "HLG"

        VideoRangeType.DOVI,
        VideoRangeType.DOVI_WITH_HDR10,
        VideoRangeType.DOVI_WITH_HLG,
        VideoRangeType.DOVI_WITH_SDR,
        -> "DOLBY VISION"

        else -> if (stream.videoRange == VideoRange.HDR) "HDR" else null
    }
}

private fun audioLabel(stream: MediaStream): String? {
    val language = shortLanguage(stream.language)
    val codec = stream.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
    val channels =
        stream.channelLayout?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
            ?: stream.channels?.takeIf { it > 0 }?.toString()
    val codecAndChannels = listOfNotNull(codec, channels).joinToString(" ").ifBlank { null }
    return listOfNotNull(language, codecAndChannels).joinToString(" · ").ifBlank { null }
}

private fun subtitleLabel(streams: List<MediaStream>): String? {
    val ordered =
        streams
            .filter { it.type == MediaStreamType.SUBTITLE }
            .sortedWith(compareBy<MediaStream> { it.isExternal }.thenBy { it.index })
    val codes = LinkedHashSet<String>()
    for (stream in ordered) {
        shortLanguage(stream.language)?.let { codes.add(it) }
    }
    if (codes.isEmpty()) return null
    val shown = codes.take(SUBTITLE_LANGS)
    val extra = codes.size - shown.size
    val more = if (extra > 0) " +$extra" else ""
    return "CC · ${shown.joinToString(" ")}$more"
}

private const val SUBTITLE_LANGS = 3

sealed interface DetailMetaPart {
    data class Plain(
        val text: String,
    ) : DetailMetaPart

    data class Boxed(
        val text: String,
    ) : DetailMetaPart
}

private val reading15 =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

private val taglineStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

private val titleStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    )

private val techStyle =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    )

/**
 * Top of a detail page. The backdrop is the app-wide one; this draws only a scrim so the
 * left column stays readable. [actions] follows the text: 24dp under the tech boxes, then
 * 24dp before the next section. [ends] is its own line under the meta line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailHeader(
    kicker: String,
    title: String,
    logoUrl: String?,
    meta: List<DetailMetaPart>,
    genres: List<String>,
    tagline: String?,
    overview: String?,
    director: String?,
    tech: List<String>,
    onOverviewClick: () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    ends: String? = null,
    kickerColor: Color = JtvColors.muted,
    textMaxWidth: Dp = 480.dp,
) {
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush =
                                Brush.linearGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to JtvColors.ground.copy(alpha = 0.88f),
                                            0.45f to JtvColors.ground.copy(alpha = 0.5f),
                                            0.85f to Color.Transparent,
                                        ),
                                    start = Offset.Zero,
                                    end = Offset(size.width * 0.95f, size.height * 0.15f),
                                ),
                        )
                    },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = JtvDimens.marginHorizontal)
                    .padding(top = JtvDimens.marginVertical),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = textMaxWidth),
            ) {
                Text(
                    text = kicker.tallyUppercase(),
                    style = JtvType.label,
                    color = kickerColor,
                    maxLines = 1,
                )
                LogoOrTitle(title = title, logoUrl = logoUrl)
                if (meta.isNotEmpty()) MetaLine(meta)
                if (ends.isNotNullOrBlank()) {
                    Text(
                        text = ends,
                        style = JtvType.label,
                        color = JtvColors.textSecondary,
                        maxLines = 1,
                    )
                }
                if (genres.isNotEmpty()) {
                    Text(
                        text = genres.joinToString(" / "),
                        style = reading15,
                        color = JtvColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tagline.isNotNullOrBlank()) {
                    Text(
                        text = tagline,
                        style = taglineStyle,
                        color = JtvColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (overview.isNotNullOrBlank()) {
                    OverviewBlock(overview = overview, onClick = onOverviewClick)
                }
                if (director.isNotNullOrBlank()) {
                    Text(
                        text = director,
                        style = reading15,
                        color = JtvColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tech.isNotEmpty()) TechLine(tech)
            }
            Box(modifier = Modifier.padding(top = 24.dp, bottom = 24.dp)) {
                actions()
            }
        }
    }
}

@Composable
private fun LogoOrTitle(
    title: String,
    logoUrl: String?,
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !failed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = {
                logCoilError(logoUrl, it.result)
                failed = true
            },
            modifier =
                Modifier
                    .widthIn(max = 360.dp)
                    .heightIn(max = 96.dp)
                    .fillMaxWidth(),
        )
    } else {
        Text(
            text = title,
            style = titleStyle,
            color = JtvColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaLine(parts: List<DetailMetaPart>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gapPx = with(density) { 6.dp.toPx() }
        val boxPadPx = with(density) { 14.dp.toPx() }
        val dropCritic =
            !metaFits(measurer, parts, constraints.maxWidth, gapPx, boxPadPx) &&
                parts.any { it is DetailMetaPart.Plain && it.text.startsWith("RT ") }
        val shown =
            if (dropCritic) {
                parts.filterNot { it is DetailMetaPart.Plain && it.text.startsWith("RT ") }
            } else {
                parts
            }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clipToBounds(),
        ) {
            shown.forEachIndexed { index, part ->
                if (index > 0) {
                    Text(
                        text = "·",
                        style = JtvType.label,
                        color = JtvColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                when (part) {
                    is DetailMetaPart.Plain -> {
                        Text(
                            text = part.text.tallyUppercase(),
                            style = JtvType.label,
                            color = JtvColors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    is DetailMetaPart.Boxed -> {
                        Text(
                            text = part.text.tallyUppercase(),
                            style = JtvType.label,
                            color = JtvColors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier =
                                Modifier
                                    .border(JtvDimens.hairline, JtvColors.ruleStrong)
                                    .padding(start = 6.dp, end = 6.dp, top = 0.5.dp, bottom = 1.5.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun metaFits(
    measurer: TextMeasurer,
    parts: List<DetailMetaPart>,
    maxPx: Int,
    gapPx: Float,
    boxPadPx: Float,
): Boolean {
    if (parts.isEmpty()) return true
    var width = 0f
    parts.forEachIndexed { index, part ->
        if (index > 0) {
            width += gapPx
            width += measurer.measure(text = "·", style = JtvType.label).size.width
            width += gapPx
        }
        val label =
            when (part) {
                is DetailMetaPart.Plain -> part.text
                is DetailMetaPart.Boxed -> part.text
            }.tallyUppercase()
        width += measurer.measure(text = label, style = JtvType.label).size.width
        if (part is DetailMetaPart.Boxed) width += boxPadPx
    }
    return width <= maxPx
}

@Composable
private fun OverviewBlock(
    overview: String,
    onClick: () -> Unit,
) {
    var truncated by remember(overview) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text = overview,
        style = JtvType.body,
        color = JtvColors.text,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { truncated = it.hasVisualOverflow },
        modifier =
            Modifier
                .drawBehind {
                    if (focused) {
                        val stroke = JtvDimens.focusBorder.toPx()
                        val inset = stroke / 2f
                        drawRect(
                            color = JtvColors.accent,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                }.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = truncated,
                    onClick = onClick,
                ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechLine(labels: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = techStyle,
                color = JtvColors.muted,
                maxLines = 1,
                modifier =
                    Modifier
                        .border(JtvDimens.hairline, JtvColors.rule)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}
