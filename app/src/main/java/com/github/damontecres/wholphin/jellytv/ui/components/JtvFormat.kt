package com.github.damontecres.wholphin.jellytv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.api.JtvGame
import java.time.DateTimeException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
internal fun gameStatusLabel(game: JtvGame): String =
    when {
        game.isLive -> game.detail
        game.isUpcoming -> formatGameStart(game.start).ifBlank { game.detail }
        else -> stringResource(R.string.jtv_final).uppercase()
    }
