package io.github.scdouglas1999.tally.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * Settings > Installed version when the release notes could not be fetched (seam W53 in upstream's
 * `PreferencesContent`): a build without a published Tally release has none (the server answers 404), so the dialog
 * says so calmly instead of upstream's red error with its "send logs" button. Returns false outside the Tally look,
 * where upstream's error stays.
 */
@Composable
fun TallyReleaseNotesMissing(): Boolean {
    if (!TallySettings.active) return false
    val text = stringResource(R.string.tally_polish2_no_release_notes)
    if (isPhone()) {
        Text(
            text = text,
            style = PhoneType.body,
            color = TallyColors.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    } else {
        TallyScale {
            Text(
                text = text,
                style = TallyType.body,
                color = TallyColors.textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
    return true
}
