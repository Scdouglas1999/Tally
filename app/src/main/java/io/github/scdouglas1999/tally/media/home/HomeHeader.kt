package io.github.scdouglas1999.tally.media.home

import android.text.format.DateFormat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.logCoilError
import io.github.scdouglas1999.tally.media.kit.formatEndsAt
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.media.series.airDate
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.media.series.seriesYears
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Height of the home header band. Fixed, so the rows under it never move as focus changes. */
val HomeHeaderHeight = 232.dp

/** Widest the header's text runs; the app backdrop fills the space to its right. */
private val HeaderTextWidth = 640.dp

/** The film page's title style (Sans SemiBold 40/46), one line here. */
private val HeaderTitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    )

private val TitleSlotHeight = 56.dp
private val LogoMaxWidth = 360.dp

/**
 * The top of the home page for the focused library item: kicker (the focused row's title, accent),
 * logo or title, a mono meta line, two lines of overview. [item] null keeps the band empty at the same
 * height. The band has a fixed height and every line a fixed slot, so nothing below it moves.
 */
@Composable
fun HomeHeader(
    item: BaseItem?,
    rowTitle: String?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(HomeHeaderHeight)
                .clipToBounds(),
    ) {
        if (item == null) return@Box
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .padding(start = TallyDimens.marginHorizontal, top = TallyDimens.marginVertical)
                    .widthIn(max = HeaderTextWidth),
        ) {
            Text(
                text = rowTitle.orEmpty().uppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.height(TitleSlotHeight),
            ) {
                val title = item.title ?: item.name ?: ""
                val logoUrl = rememberLogoUrl(item)
                var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                if (showLogo && logoUrl != null && !logoFailed) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onError = {
                            logCoilError(logoUrl, it.result)
                            logoFailed = true
                        },
                        modifier =
                            Modifier
                                .width(LogoMaxWidth)
                                .heightIn(max = TitleSlotHeight),
                    )
                } else {
                    Text(
                        text = title,
                        style = HeaderTitleStyle,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HeaderMetaLine(homeMeta(item))
            Text(
                text = item.data.overview.orEmpty(),
                style = TallyType.body,
                color = TallyColors.textSecondary,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A part of the meta line, already in its final case: words uppercase, units in their standard case (`2h 35m`).
 * [boxed] parts (the official rating) sit in a 1dp `ruleStrong` box, as on the film page.
 */
internal data class HomeMetaPart(
    val text: String,
    val boxed: Boolean = false,
)

/**
 * `S1 E3 · FEB 10, 2008 · 47m · TV-14 · ★ 8.4 · ENDS 6:59 PM` for an episode, `2021 · PG-13 · 2h 35m · ★ 7.8 ·
 * ENDS 9:41 PM` for a film, years for a series. Month-first dates; durations keep their lowercase units.
 */
@Composable
private fun homeMeta(item: BaseItem): List<HomeMetaPart> {
    val context = LocalContext.current
    val special = stringResource(R.string.tally_series_special)
    val community =
        item.data.communityRating?.let {
            stringResource(R.string.tally_media_community, String.format(Locale.US, "%.1f", it))
        }
    val seasons =
        item.data.childCount
            ?.takeIf { item.type == BaseItemKind.SERIES && it > 0 }
            ?.let { pluralStringResource(R.plurals.tally_media_seasons, it, it).uppercase(Locale.US) }
    val runtimeTicks = item.data.runTimeTicks ?: 0L
    val positionTicks = item.data.userData?.playbackPositionTicks ?: 0L
    val remainingMs = ((runtimeTicks - positionTicks).coerceAtLeast(0L)) / 10_000L
    val is24h = DateFormat.is24HourFormat(context)
    val ends =
        remember(item.id, remainingMs, is24h, item.played) {
            if (item.played || remainingMs <= 0L || !item.type.hasRuntime()) {
                null
            } else {
                formatEndsAt(Instant.now(), remainingMs, ZoneId.systemDefault(), is24h)
            }
        }
    return buildList {
        when (item.type) {
            BaseItemKind.EPISODE -> {
                episodeCode(item.data.parentIndexNumber, item.indexNumber, item.data.indexNumberEnd, special)
                    ?.let { add(HomeMetaPart(it.uppercase(Locale.US))) }
                airDate(item.data.premiereDate)?.let { add(HomeMetaPart(it)) }
            }

            BaseItemKind.SERIES -> {
                seriesYears(item.data.productionYear, item.data.endDate, item.data.status)
                    ?.let { add(HomeMetaPart(it)) }
            }

            else -> {
                item.data.productionYear?.let { add(HomeMetaPart(it.toString())) }
            }
        }
        item.data.officialRating
            ?.takeIf { it.isNotBlank() }
            ?.let { add(HomeMetaPart(it.uppercase(Locale.US), boxed = true)) }
        if (runtimeTicks > 0L && item.type.hasRuntime()) {
            add(HomeMetaPart(formatRuntime(runtimeTicks).lowercase(Locale.US)))
        }
        seasons?.let { add(HomeMetaPart(it)) }
        community?.let { add(HomeMetaPart(it)) }
        ends?.let { add(HomeMetaPart(it)) }
    }
}

private fun BaseItemKind.hasRuntime() =
    this == BaseItemKind.MOVIE ||
        this == BaseItemKind.EPISODE ||
        this == BaseItemKind.VIDEO ||
        this == BaseItemKind.MUSIC_VIDEO ||
        this == BaseItemKind.RECORDING ||
        this == BaseItemKind.AUDIO

/**
 * One mono line that never wraps: parts that do not fit are dropped from the end (the end time
 * goes first), never cut in half. Always takes its line so the overview below stays put.
 */
@Composable
private fun HeaderMetaLine(parts: List<HomeMetaPart>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gapPx = with(density) { 6.dp.toPx() }
        val boxPadPx = with(density) { 14.dp.toPx() }
        val dotPx = measurer.measure(text = "·", style = TallyType.label).size.width
        var shown = parts
        while (shown.size > 1) {
            var width = 0f
            shown.forEachIndexed { index, part ->
                if (index > 0) width += 2 * gapPx + dotPx
                width += measurer.measure(text = part.text, style = TallyType.label).size.width
                if (part.boxed) width += boxPadPx
            }
            if (width <= constraints.maxWidth) break
            shown = shown.dropLast(1)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MetaLineHeight)
                    .clipToBounds(),
        ) {
            shown.forEachIndexed { index, part ->
                if (index > 0) {
                    Text(
                        text = "·",
                        style = TallyType.label,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = part.text,
                    style = TallyType.label,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier =
                        if (part.boxed) {
                            Modifier
                                .border(TallyDimens.hairline, TallyColors.ruleStrong)
                                .padding(start = 6.dp, end = 6.dp, top = 0.5.dp, bottom = 1.5.dp)
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

private val MetaLineHeight = 24.dp
