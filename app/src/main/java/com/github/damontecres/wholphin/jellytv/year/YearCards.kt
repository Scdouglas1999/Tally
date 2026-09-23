package com.github.damontecres.wholphin.jellytv.year

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.ui.components.COUNT_UP_MS
import com.github.damontecres.wholphin.jellytv.ui.components.EaseOutCubic
import com.github.damontecres.wholphin.jellytv.ui.components.LabelBar
import com.github.damontecres.wholphin.jellytv.ui.components.RollingText
import com.github.damontecres.wholphin.jellytv.ui.components.countUpValue
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import org.jellyfin.sdk.model.api.ImageType
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

internal enum class YearCard {
    COVER,
    FILMS,
    SHOWS,
    GENRES,
    MONTHS,
    DECADE,
    SUMMARY,
}

internal fun visibleCards(stats: YearStats): List<YearCard> =
    buildList {
        add(YearCard.COVER)
        if (stats.movies > 0 && stats.topMovies.isNotEmpty()) add(YearCard.FILMS)
        if (stats.episodes > 0 && stats.topSeries.isNotEmpty()) add(YearCard.SHOWS)
        if (stats.topGenres.isNotEmpty()) add(YearCard.GENRES)
        if (stats.busiestMonth != null) add(YearCard.MONTHS)
        if (stats.medianReleaseYear != null) add(YearCard.DECADE)
        add(YearCard.SUMMARY)
    }

internal data class DurationLabel(
    val amount: String,
    @param:StringRes val unitRes: Int,
)

/** Under two hours the recap speaks in minutes; 120 minutes and up, in whole hours. */
internal fun durationLabel(totalMinutes: Long): DurationLabel {
    if (totalMinutes < 120L) {
        val unit = if (totalMinutes == 1L) R.string.jtv_year_minute else R.string.jtv_year_minutes
        return DurationLabel(totalMinutes.toString(), unit)
    }
    val hours = totalMinutes / 60L
    val unit = if (hours == 1L) R.string.jtv_year_hour else R.string.jtv_year_hours
    return DurationLabel(hours.toString(), unit)
}

private val heroNumber =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 132.sp,
        lineHeight = 132.sp,
    )

private val countNumber =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 72.sp,
        lineHeight = 76.sp,
    )

private val monthNameStyle =
    TextStyle(
        fontFamily = JtvType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 60.sp,
    )

private val summaryValue =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 40.sp,
    )

private val monoBody =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    )

private val monoFigure =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    )

private val monthLetter =
    TextStyle(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )

private val columnWidth = 560.dp
private val aboveHints = 72.dp

@Composable
internal fun YearCover(
    stats: YearStats,
    serverName: String,
    modifier: Modifier = Modifier,
) {
    val duration = durationLabel(stats.totalMinutes)
    val kicker =
        if (serverName.isBlank()) {
            stringResource(R.string.jtv_year_name, stats.year)
        } else {
            stringResource(R.string.jtv_year_kicker, serverName)
        }
    Column(modifier.widthIn(max = columnWidth)) {
        Text(
            text = kicker.uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        CountUpNumber(
            stats = stats,
            slot = YearCard.COVER,
            value = duration.amount.toLongOrNull() ?: 0L,
            style = heroNumber,
            color = JtvColors.accent,
        )
        Text(
            text = stringResource(duration.unitRes).uppercase(),
            style = JtvType.labelLarge,
            color = JtvColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text =
                stringResource(
                    R.string.jtv_year_mix,
                    pluralStringResource(R.plurals.jtv_year_film_count, stats.movies, stats.movies),
                    pluralStringResource(R.plurals.jtv_year_episode_count, stats.episodes, stats.episodes),
                ),
            style = JtvType.body,
            color = JtvColors.textSecondary,
        )
    }
}

@Composable
internal fun YearFilms(
    stats: YearStats,
    modifier: Modifier = Modifier,
) {
    val word = if (stats.movies == 1) R.string.jtv_year_film else R.string.jtv_year_films
    Column(modifier.fillMaxSize()) {
        CountLine(stats = stats, slot = YearCard.FILMS, amount = stats.movies.toLong(), unit = stringResource(word))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            stats.topMovies.forEach { movie ->
                Poster(movie)
            }
        }
        stats.topMovies.firstOrNull()?.let { top ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.jtv_year_most_watched, top.name),
                style = JtvType.body,
                color = JtvColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun YearShows(
    stats: YearStats,
    modifier: Modifier = Modifier,
) {
    val lead = stats.topSeries.first()
    Box(modifier.fillMaxSize()) {
        SeriesBackdrop(
            itemId = lead.imageItemId,
            name = lead.name,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.6f),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(0.4f)
                    .fillMaxHeight()
                    .padding(start = JtvDimens.marginHorizontal, end = 20.dp, bottom = aboveHints),
        ) {
            val word = if (stats.episodes == 1) R.string.jtv_year_episode else R.string.jtv_year_episodes
            CountLine(stats = stats, slot = YearCard.SHOWS, amount = stats.episodes.toLong(), unit = stringResource(word))
            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(R.plurals.jtv_year_across, stats.series, stats.series).uppercase(),
                style = JtvType.label,
                color = JtvColors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(22.dp))
            stats.topSeries.forEachIndexed { index, series ->
                SeriesLine(rank = index + 1, series = series)
                if (index != stats.topSeries.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
internal fun YearGenres(
    stats: YearStats,
    modifier: Modifier = Modifier,
) {
    val genres = stats.topGenres
    val widest =
        genres
            .first()
            .minutes
            .toFloat()
            .coerceAtLeast(1f)
    Box(modifier) {
        GenreColumn(genres = genres, widest = widest)
    }
}

@Composable
private fun GenreColumn(
    genres: List<GenreShare>,
    widest: Float,
) {
    Column(
        Modifier
            .widthIn(max = columnWidth)
            .fillMaxHeight()
            .padding(bottom = aboveHints),
    ) {
        Text(
            text = stringResource(R.string.jtv_year_top_genres).uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
        )
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            genres.forEachIndexed { index, share ->
                GenreBar(
                    share = share,
                    widthFraction = (share.minutes / widest).coerceIn(0.02f, 1f),
                    accent = index == 0,
                )
            }
        }
    }
}

@Composable
internal fun YearMonths(
    stats: YearStats,
    modifier: Modifier = Modifier,
) {
    val month = stats.busiestMonth ?: return
    val names = stringArrayResource(R.array.jtv_year_months)
    val letters = stringArrayResource(R.array.jtv_year_month_letters)
    Column(
        modifier
            .fillMaxSize()
            .padding(bottom = aboveHints),
    ) {
        Text(
            text = stringResource(R.string.jtv_year_busiest).uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = names.getOrNull(month - 1).orEmpty().uppercase(),
            style = monthNameStyle,
            color = JtvColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(20.dp))
        MonthChart(
            minutes = stats.monthMinutes,
            busiest = month,
            letters = letters,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
internal fun YearDecade(
    stats: YearStats,
    modifier: Modifier = Modifier,
) {
    val year = stats.medianReleaseYear ?: return
    Column(modifier.widthIn(max = columnWidth)) {
        Text(
            text = stringResource(R.string.jtv_year_like).uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = year.toString(),
            style = heroNumber,
            color = JtvColors.accent,
            maxLines = 1,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.jtv_year_half, year),
            style = JtvType.body,
            color = JtvColors.textSecondary,
        )
    }
}

@Composable
internal fun YearSummary(
    stats: YearStats,
    firstPlayed: Instant?,
    latestPlayed: Instant?,
    modifier: Modifier = Modifier,
) {
    val months = stringArrayResource(R.array.jtv_year_months)
    val duration = durationLabel(stats.totalMinutes)
    val cells = ArrayList<SummaryCellModel>()
    cells += SummaryCellModel(stringResource(duration.unitRes), duration.amount, null)
    cells += SummaryCellModel(stringResource(R.string.jtv_year_films), stats.movies.toString(), null)
    cells += SummaryCellModel(stringResource(R.string.jtv_year_episodes), stats.episodes.toString(), null)
    cells += SummaryCellModel(stringResource(R.string.jtv_year_shows), stats.series.toString(), null)
    stats.topGenres.firstOrNull()?.let { genre ->
        cells += SummaryCellModel(stringResource(R.string.jtv_year_top_genre), genre.genre, null)
    }
    stats.busiestMonth?.let { month ->
        val name = months.getOrNull(month - 1).orEmpty()
        if (name.isNotBlank()) {
            cells += SummaryCellModel(stringResource(R.string.jtv_year_busiest_label), name, null)
        }
    }
    val first = stats.firstWatch
    if (first != null && firstPlayed != null) {
        cells +=
            SummaryCellModel(
                stringResource(R.string.jtv_year_first),
                first.name,
                watchDate(firstPlayed, months),
            )
    }
    val latest = stats.latestWatch
    if (latest != null && latestPlayed != null) {
        cells +=
            SummaryCellModel(
                stringResource(R.string.jtv_year_latest),
                latest.name,
                watchDate(latestPlayed, months),
            )
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(bottom = aboveHints),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        cells.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SummaryCell(row[0], Modifier.weight(1f))
                if (row.size == 2) {
                    SummaryCell(row[1], Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun YearChip(
    year: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
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
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier,
    ) {
        Text(
            text = year.toString(),
            style = JtvType.label,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CountLine(
    stats: YearStats,
    slot: YearCard,
    amount: Long,
    unit: String,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        CountUpNumber(
            stats = stats,
            slot = slot,
            value = amount,
            style = countNumber,
            color = JtvColors.text,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = unit.uppercase(),
            style = JtvType.labelLarge,
            color = JtvColors.text,
            modifier = Modifier.padding(bottom = 10.dp),
            maxLines = 1,
        )
    }
}

/**
 * A big stat number that counts up from 0 the first time its card is shown during a visit to the
 * page ([stats] is one visit's result): the value eases out (cubic) over [COUNT_UP_MS] and runs
 * through [RollingText] like a scoreboard counter. The number holds its final width from the
 * start (right-aligned), so nothing next to it moves.
 */
@Composable
private fun CountUpNumber(
    stats: YearStats,
    slot: YearCard,
    value: Long,
    style: TextStyle,
    color: Color,
) {
    val context = LocalContext.current
    val countUp =
        remember(stats, slot) {
            // With animations off the number is simply there (no frame of 0 first).
            YearCountUps.claim(stats, slot) &&
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
    val shown = remember(stats, slot) { Animatable(if (countUp) 0f else 1f) }
    LaunchedEffect(stats, slot) {
        if (shown.value < 1f) {
            shown.animateTo(1f, tween(durationMillis = COUNT_UP_MS, easing = EaseOutCubic))
        }
    }
    Box(contentAlignment = Alignment.TopEnd) {
        Text(
            text = value.toString(),
            style = style,
            color = Color.Transparent,
            maxLines = 1,
        )
        RollingText(
            text = countUpValue(value, shown.value).toString(),
            style = style,
            color = color,
        )
    }
}

/** Which cards have counted up during the current visit; a new [YearStats] instance is a new visit. */
private object YearCountUps {
    private var visit = WeakReference<YearStats>(null)
    private val counted = mutableSetOf<YearCard>()

    fun claim(
        stats: YearStats,
        card: YearCard,
    ): Boolean {
        if (visit.get() !== stats) {
            visit = WeakReference(stats)
            counted.clear()
        }
        return counted.add(card)
    }
}

@Composable
private fun Poster(movie: RankedItem) {
    val images = LocalImageUrlService.current
    val url =
        images.getItemImageUrl(
            itemId = movie.imageItemId,
            imageType = ImageType.PRIMARY,
            fillWidth = 240,
            fillHeight = 360,
        )
    val caption =
        if (movie.count > 1) {
            stringResource(R.string.jtv_year_rewatch, movie.count)
        } else {
            runtimeLabel(movie.minutes)
        }
    Column(Modifier.width(120.dp)) {
        Box(
            Modifier
                .size(120.dp, 180.dp)
                .background(JtvColors.groundRaised)
                .border(JtvDimens.hairline, JtvColors.rule),
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        LabelBar(text = caption, live = false)
    }
}

@Composable
private fun runtimeLabel(minutes: Long): String {
    if (minutes < 60L) return stringResource(R.string.jtv_year_runtime_m, minutes.toInt())
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (rest == 0L) {
        stringResource(R.string.jtv_year_runtime_h, hours.toInt())
    } else {
        stringResource(R.string.jtv_year_runtime_hm, hours.toInt(), rest.toInt())
    }
}

@Composable
private fun SeriesBackdrop(
    itemId: UUID,
    name: String,
    modifier: Modifier = Modifier,
) {
    val images = LocalImageUrlService.current
    val url =
        images.getItemImageUrl(
            itemId = itemId,
            imageType = ImageType.BACKDROP,
            fillWidth = 1280,
            fillHeight = 720,
        ) ?: return
    Box(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Same scrims as a hero: the picture fades to ground from the left and at the bottom.
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0f to Color.Transparent,
                                    0.55f to Color.Black,
                                ),
                        ),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                        ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SeriesLine(
    rank: Int,
    series: RankedItem,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = rank.toString(),
            style = monoBody,
            color = JtvColors.accent,
            modifier = Modifier.width(36.dp),
            maxLines = 1,
        )
        Text(
            text = series.name,
            style = JtvType.body,
            color = JtvColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.jtv_year_ep, series.count),
            style = monoBody,
            color = JtvColors.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun GenreBar(
    share: GenreShare,
    widthFraction: Float,
    accent: Boolean,
) {
    val percent = (share.fraction * 100f).roundToInt()
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = share.genre.uppercase(),
                style = JtvType.labelLarge,
                color = JtvColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.jtv_year_percent, percent),
                style = monoFigure,
                color = JtvColors.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(widthFraction)
                    .background(if (accent) JtvColors.accent else JtvColors.ruleStrong),
            )
        }
    }
}

@Composable
private fun MonthChart(
    minutes: List<Long>,
    busiest: Int,
    letters: Array<String>,
    modifier: Modifier = Modifier,
) {
    val peak = minutes.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        minutes.forEachIndexed { index, value ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (value <= 0L) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(JtvColors.rule),
                        )
                    } else {
                        val fraction = (value.toFloat() / peak.toFloat()).coerceIn(0.04f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction)
                                .background(
                                    if (index == busiest - 1) JtvColors.accent else JtvColors.ruleStrong,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = letters.getOrNull(index).orEmpty(),
                    style = monthLetter,
                    color = JtvColors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

private data class SummaryCellModel(
    val label: String,
    val value: String,
    val detail: String?,
)

@Composable
private fun SummaryCell(
    cell: SummaryCellModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = cell.label.uppercase(),
            style = JtvType.label,
            color = JtvColors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = cell.value,
            style = summaryValue,
            color = JtvColors.text,
            maxLines = if (cell.detail == null) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (cell.detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = cell.detail,
                style = monoBody,
                color = JtvColors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

private fun watchDate(
    instant: Instant,
    monthNames: Array<String>,
): String {
    val date = instant.atZone(ZoneId.systemDefault())
    val month =
        monthNames
            .getOrNull(date.monthValue - 1)
            ?.take(3)
            ?.uppercase()
            .orEmpty()
    return "$month ${date.dayOfMonth}"
}
