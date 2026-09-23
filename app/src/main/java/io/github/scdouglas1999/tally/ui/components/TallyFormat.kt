package io.github.scdouglas1999.tally.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyGame
import java.time.DateTimeException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UNIT_FIXES =
    listOf(
        Regex("""(\d(?:[\d.]*\d)?) ?MBPS\b""") to "$1 Mbps",
        Regex("""(\d(?:[\d.]*\d)?) ?KBPS\b""") to "$1 kbps",
        Regex("""\b(\d{3,4})P\b""") to "$1p",
        Regex("""\b(\d{3,4})I\b""") to "$1i",
        Regex("""\b(\d+)H\b""") to "$1h",
        Regex("""\b(\d+)M\b""") to "$1m",
        Regex("""\b(\d+)S\b""") to "$1s",
    )

/**
 * Tally's uppercase label style, except for units and technical notation, which keep their standard case:
 * `14.8 Mbps` (MBPS would read as megabytes), `1080p`, `1080i`, and durations `2h 35m`, `1m 30s`.
 * Use it instead of `uppercase()` for any label that can contain one of them.
 */
fun String.tallyUppercase(): String =
    UNIT_FIXES.fold(uppercase(Locale.US)) { text, (regex, replacement) -> regex.replace(text, replacement) }

private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

/**
 * Local start time for an upcoming game: today -> "7:30 PM", other days -> "SUN 7:30 PM".
 * Returns "" when [start] is not parseable ISO-8601.
 */
internal fun formatGameStart(
    start: String,
    today: LocalDate = LocalDate.now(),
): String {
    val zoned =
        try {
            OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.systemDefault())
        } catch (_: DateTimeException) {
            return ""
        }
    val formatted =
        if (zoned.toLocalDate() == today) {
            timeFormatter.format(zoned)
        } else {
            dayTimeFormatter.format(zoned).uppercase()
        }
    return formatted
}

/**
 * Right-hand status for a game: the detail when live, local start time when upcoming, FINAL when post.
 */
@Composable
internal fun gameStatusLabel(game: TallyGame): String =
    when {
        game.isLive -> game.detail

        game.isUpcoming -> formatGameStart(game.start).ifBlank { game.detail }

        // the feed's own wording for finished games: "Final", "Final/12" (extra innings), "Postponed"
        else -> game.detail.ifBlank { stringResource(R.string.tally_final) }.uppercase()
    }

private val NO_RESULT = Regex("postponed|canceled|cancelled|suspended|delayed|forfeit", RegexOption.IGNORE_CASE)

/** A finished game that was never played to a result (postponed, canceled…): its 0–0 is not a score. */
internal val TallyGame.hasNoResult: Boolean
    get() = state == "post" && NO_RESULT.containsMatchIn(detail)
