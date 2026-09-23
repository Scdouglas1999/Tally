package io.github.scdouglas1999.tally.ui.phone

import android.content.Context
import com.github.damontecres.wholphin.preferences.AppPreference
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor

/**
 * Settings rows a phone does not show (seam W45 in `PreferencesContent`): the application theme, since a phone always
 * uses the Tally look (the saved preference is kept as it is, for a TV restored from the same account), and the
 * screensaver settings, since the in-app screensaver is always off on a phone (W46).
 */
fun hiddenOnPhone(
    context: Context,
    preference: AppPreference<*, *>,
): Boolean =
    (preference == AppPreference.ThemeColors || preference == AppPreference.ScreensaverSettings) &&
        TallyFormFactor.of(context) == TallyFormFactor.PHONE
