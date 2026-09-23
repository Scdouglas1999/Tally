package io.github.scdouglas1999.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** Whether the settings components hand over to their Tally versions: only while the Tally theme is selected. */
object TallySettings {
    val active: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalTheme.current == AppThemeColors.TALLY
}

/**
 * The page's title as a kicker (`SETTINGS`, `ADVANCED`): mono label in accent, 24dp from the top, left-aligned
 * with the rows under it (the settings list pads its content by 16dp at the real density, 20dp at the Tally scale).
 */
@Composable
fun TallySettingsTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    TallyScale {
        Text(
            text = title.tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 4.dp),
        )
    }
}

/** A group of settings: a 1dp rule, then the group's name as a mono row header. */
@Composable
fun TallySettingsGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    TallyScale {
        Column(modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TallyDimens.hairline)
                    .background(TallyColors.rule),
            )
            RowHeader(title = title, modifier = Modifier.padding(top = 14.dp))
        }
    }
}
