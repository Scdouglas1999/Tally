package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType
import java.util.Locale

/**
 * Column headers for the first [count] periods. Does not include the total column.
 *
 * Football is quarters: 1 2 3 4, then OT, 2OT, 3OT…
 * Hockey is periods: 1 2 3, then OT, then SO.
 * Baseball and every other sport are just 1..n (innings 10, 11… stay numbers).
 */
fun periodLabels(
    sport: String,
    count: Int,
): List<String> {
    if (count <= 0) return emptyList()
    val kind = sport.lowercase(Locale.ROOT)
    return List(count) { index -> periodLabel(kind, index + 1) }
}

/**
 * How many period columns to reserve. Regulation length is always shown, so a quarter or
 * inning filling in does not add a column and shift the total. Extra time grows the table.
 */
internal fun periodColumnCount(
    sport: String,
    played: Int,
): Int {
    val regulation =
        when (sport.lowercase(Locale.ROOT)) {
            "football" -> 4
            "baseball" -> 9
            "hockey" -> 3
            else -> 0
        }
    return maxOf(regulation, played.coerceAtLeast(0))
}

/** The cell text for one period: the points, or [unplayed] when that period has not been played. */
internal fun periodValue(
    periods: List<Int>,
    index: Int,
    unplayed: String = "\u2013",
): String = if (index in periods.indices) periods[index].toString() else unplayed

/** True when [score] is strictly ahead of [other]. Ties and missing scores accent nobody. */
internal fun isLeading(
    score: Int?,
    other: Int?,
): Boolean {
    if (score == null || other == null) return false
    return score > other
}

private fun periodLabel(
    sport: String,
    n: Int,
): String =
    when (sport) {
        "football" -> {
            when {
                n <= 4 -> n.toString()
                n == 5 -> "OT"
                else -> "${n - 4}OT"
            }
        }

        "hockey" -> {
            when {
                n <= 3 -> n.toString()
                n == 4 -> "OT"
                n == 5 -> "SO"
                else -> n.toString()
            }
        }

        else -> {
            n.toString()
        }
    }

/**
 * The line score: one column per period, a row per team, the total at the end.
 * Renders nothing when both teams have empty [TallyTeam.periods] or when [hideScores].
 *
 * [compact] is the hero and screensaver size (20dp marks). Otherwise marks are 28dp.
 * Period columns have a fixed width so the table does not jump as a number goes from 7 to 14.
 */
@Composable
fun LineScore(
    game: TallyGame,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val played = maxOf(game.away.periods.size, game.home.periods.size)
    if (hideScores || played == 0) return
    val labels = periodLabels(game.sport, periodColumnCount(game.sport, played))
    val metrics = if (compact) LineScoreMetrics.Compact else LineScoreMetrics.Full
    val unplayed = stringResource(R.string.tally_line_unplayed)
    val totalHeader = stringResource(R.string.tally_line_total)
    BoxWithConstraints(modifier = modifier) {
        // Period and total columns narrow (never widen) so the whole table, total included, fits the space it is
        // given: the hero's situation column is narrower than a nine-inning line at full column width, and extra
        // innings add columns.
        val fitted = (maxWidth - metrics.gutter - metrics.totalGap) / (labels.size + 1)
        val columnWidth = if (maxWidth == Dp.Infinity) metrics.columnWidth else minOf(metrics.columnWidth, fitted)
        val tableWidth = metrics.gutter + columnWidth * labels.size + metrics.totalGap + columnWidth
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(metrics.gutter))
                labels.forEach { label ->
                    ScoreCell(
                        text = label,
                        width = columnWidth,
                        style = metrics.header,
                        color = TallyColors.muted,
                    )
                }
                Spacer(Modifier.width(metrics.totalGap))
                ScoreCell(
                    text = totalHeader,
                    width = columnWidth,
                    style = metrics.header,
                    color = TallyColors.muted,
                )
            }
            Spacer(Modifier.height(metrics.ruleGap))
            Box(
                Modifier
                    .width(tableWidth)
                    .height(TallyDimens.hairline)
                    .background(TallyColors.rule),
            )
            Spacer(Modifier.height(metrics.rowGap))
            TeamScoreRow(
                team = game.away,
                other = game.home,
                columnCount = labels.size,
                columnWidth = columnWidth,
                metrics = metrics,
                unplayed = unplayed,
            )
            Spacer(Modifier.height(metrics.rowGap))
            TeamScoreRow(
                team = game.home,
                other = game.away,
                columnCount = labels.size,
                columnWidth = columnWidth,
                metrics = metrics,
                unplayed = unplayed,
            )
        }
    }
}

@Composable
private fun TeamScoreRow(
    team: TallyTeam,
    other: TallyTeam,
    columnCount: Int,
    columnWidth: Dp,
    metrics: LineScoreMetrics,
    unplayed: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamMark(team = team, size = metrics.mark)
        Spacer(Modifier.width(metrics.gap))
        Text(
            text = team.abbr.uppercase(),
            style = metrics.number,
            color = TallyColors.text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(metrics.abbrWidth),
        )
        Spacer(Modifier.width(metrics.gap))
        repeat(columnCount) { index ->
            val played = index in team.periods.indices
            ScoreCell(
                text = periodValue(team.periods, index, unplayed),
                width = columnWidth,
                style = metrics.number,
                color = if (played) TallyColors.textSecondary else TallyColors.muted,
            )
        }
        Spacer(Modifier.width(metrics.totalGap))
        ScoreCell(
            text = team.score?.toString() ?: unplayed,
            width = columnWidth,
            style = metrics.total,
            color = if (isLeading(team.score, other.score)) TallyColors.accent else TallyColors.text,
        )
    }
}

@Composable
private fun ScoreCell(
    text: String,
    width: Dp,
    style: TextStyle,
    color: Color,
) {
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier.width(width),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Column widths at most; [LineScore] narrows the columns when the table would not fit its space (the hero's
 * situation column holds about 380dp). Full is the box-score overlay, which has the whole picture.
 */
private class LineScoreMetrics(
    val mark: Dp,
    val gap: Dp,
    val abbrWidth: Dp,
    val columnWidth: Dp,
    val totalGap: Dp,
    val rowGap: Dp,
    val ruleGap: Dp,
    val number: TextStyle,
    val total: TextStyle,
    val header: TextStyle,
) {
    val gutter: Dp = mark + gap + abbrWidth + gap

    companion object {
        val Compact =
            LineScoreMetrics(
                mark = 20.dp,
                gap = 6.dp,
                abbrWidth = 40.dp,
                columnWidth = 32.dp,
                totalGap = 8.dp,
                rowGap = 4.dp,
                ruleGap = 3.dp,
                number =
                    TallyType.clock.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                    ),
                total =
                    TallyType.clock.copy(
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                    ),
                header =
                    TallyType.clock.copy(
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 1.sp,
                    ),
            )

        val Full =
            LineScoreMetrics(
                mark = 28.dp,
                gap = 10.dp,
                abbrWidth = 56.dp,
                columnWidth = 48.dp,
                totalGap = 12.dp,
                rowGap = 6.dp,
                ruleGap = 4.dp,
                number =
                    TallyType.clock.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 20.sp,
                        lineHeight = 22.sp,
                    ),
                total =
                    TallyType.clock.copy(
                        fontSize = 20.sp,
                        lineHeight = 22.sp,
                    ),
                header =
                    TallyType.clock.copy(
                        fontSize = 16.sp,
                        lineHeight = 18.sp,
                        letterSpacing = 1.5.sp,
                    ),
            )
    }
}

/** DET @ BUF from board-sample-periods.json, for previews. */
private fun previewFootballFinal(): TallyGame =
    TallySamples.final.copy(
        sport = "football",
        league = "NFL",
        name = "DET @ BUF",
        detail = "Final",
        away =
            TallySamples.final.away.copy(
                abbr = "DET",
                shortName = "Lions",
                score = 31,
                winner = false,
                periods = listOf(0, 10, 7, 14),
            ),
        home =
            TallySamples.final.home.copy(
                abbr = "BUF",
                shortName = "Bills",
                score = 41,
                winner = true,
                periods = listOf(14, 13, 7, 7),
            ),
    )

/** GB @ NYJ overtime final from board-sample-periods.json, for previews. */
private fun previewFootballOt(): TallyGame =
    previewFootballFinal().copy(
        name = "GB @ NYJ",
        detail = "Final/OT",
        away =
            previewFootballFinal().away.copy(
                abbr = "GB",
                shortName = "Packers",
                score = 20,
                winner = true,
                periods = listOf(0, 7, 0, 10, 3),
            ),
        home =
            previewFootballFinal().home.copy(
                abbr = "NYJ",
                shortName = "Jets",
                score = 17,
                winner = false,
                periods = listOf(0, 7, 7, 3, 0),
            ),
    )

/** TOR @ BAL from board-sample-periods.json: the home side did not bat in the 9th. */
private fun previewBaseball(): TallyGame =
    TallySamples.liveBaseball.copy(
        state = "post",
        detail = "Final",
        period = 9,
        away =
            TallySamples.liveBaseball.away.copy(
                abbr = "TOR",
                shortName = "Blue Jays",
                score = 3,
                winner = false,
                periods = listOf(3, 0, 0, 0, 0, 0, 0, 0, 0),
            ),
        home =
            TallySamples.liveBaseball.home.copy(
                abbr = "BAL",
                shortName = "Orioles",
                score = 4,
                winner = true,
                periods = listOf(1, 0, 0, 0, 0, 1, 0, 2),
            ),
    )

@PreviewTvSpec
@Composable
private fun LineScorePreview() {
    TallySurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            LineScore(game = previewFootballFinal(), hideScores = false, compact = true)
            LineScore(game = previewFootballOt(), hideScores = false)
            LineScore(game = previewBaseball(), hideScores = false)
        }
    }
}
