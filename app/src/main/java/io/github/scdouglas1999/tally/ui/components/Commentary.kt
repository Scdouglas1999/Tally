package io.github.scdouglas1999.tally.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyFeed
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyLanguage
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.watch.commentary
import io.github.scdouglas1999.tally.watch.spanishWatch

/** "English" / "Español" for a commentary language; null for one the app does not name itself. */
@StringRes
fun languageNameRes(language: String): Int? =
    when (language.ifBlank { TallyLanguage.ENGLISH }) {
        TallyLanguage.ENGLISH -> R.string.tally_23_language_en
        TallyLanguage.SPANISH -> R.string.tally_23_language_es
        else -> null
    }

/** A commentary language's own name ("Español"); the server's [label], or the code, for one the app does not know. */
@Composable
fun languageName(
    language: String,
    label: String = "",
): String = languageNameRes(language)?.let { stringResource(it) } ?: label.ifBlank { language.uppercase() }

/** A feed's language name ("Español"). */
@Composable
fun feedName(feed: TallyFeed): String = languageName(feed.commentary, feed.label)

/**
 * The card's ES chip: `ES` outlined in `ruleStrong` (the scheduled REC tag's look) while [game] plays with Spanish
 * commentary. Nothing for English, and nothing from a server that does not say.
 */
@Composable
fun LanguageTag(
    game: TallyGame,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (!game.spanishWatch) return
    Text(
        text = stringResource(R.string.tally_23_es_chip),
        style = style,
        color = TallyColors.textSecondary,
        maxLines = 1,
        modifier =
            modifier
                .border(1.dp, TallyColors.ruleStrong)
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Why RedZone is on its game ("RED ZONE"), from the status endpoint's reason; null for none or one it does not know. */
@StringRes
fun redZoneReasonRes(reason: String?): Int? =
    when (reason?.lowercase()) {
        "red zone" -> R.string.tally_23_redzone_reason_red_zone
        "score" -> R.string.tally_23_redzone_reason_score
        "two-minute drill" -> R.string.tally_23_redzone_reason_two_minute
        "overtime" -> R.string.tally_23_redzone_reason_overtime
        "close" -> R.string.tally_23_redzone_reason_close
        "hottest" -> R.string.tally_23_redzone_reason_hottest
        else -> null
    }
