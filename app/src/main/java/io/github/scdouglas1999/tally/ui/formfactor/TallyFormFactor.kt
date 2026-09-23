package io.github.scdouglas1999.tally.ui.formfactor

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import com.github.damontecres.wholphin.preferences.AppThemeColors

/**
 * What Tally is running on. TV keeps the TV app exactly as it is; PHONE gets the phone layouts (tablets included:
 * they use the phone layouts with more columns).
 */
enum class TallyFormFactor {
    TV,
    PHONE,
    ;

    companion object {
        @Volatile
        private var detected: TallyFormFactor? = null

        /**
         * TV when the UI mode type is television, the device has `FEATURE_LEANBACK`, or it has no touchscreen;
         * PHONE otherwise. It cannot change while the process lives, so it is worked out once.
         */
        fun of(context: Context): TallyFormFactor {
            detected?.let { return it }
            val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            val packageManager = context.packageManager
            val tv =
                uiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            return (if (tv) TV else PHONE).also { detected = it }
        }

        /**
         * The form factor for code that has no composition or context (suspend helpers). Known once [of] has run,
         * which the root of the composition does before anything else draws; TV until then.
         */
        internal val current: TallyFormFactor
            get() = detected ?: TV
    }
}

/** True when [context]'s device is a phone or tablet ([TallyFormFactor.PHONE]). */
fun isTallyPhone(context: Context): Boolean = TallyFormFactor.of(context) == TallyFormFactor.PHONE

/** The form factor, provided at the root of the composition (the theme, see [withTallyFormFactor]). */
val LocalTallyFormFactor = staticCompositionLocalOf { TallyFormFactor.TV }

/** [TallyFormFactor.of] the current context, remembered. */
@Composable
fun rememberTallyFormFactor(): TallyFormFactor {
    val context = LocalContext.current
    return remember(context) { TallyFormFactor.of(context) }
}

/** [content] with [LocalTallyFormFactor] provided as [formFactor]. Used by the theme, the root of every screen. */
@Composable
fun withTallyFormFactor(
    formFactor: TallyFormFactor,
    content: @Composable () -> Unit,
): @Composable () -> Unit =
    remember(formFactor, content) {
        { CompositionLocalProvider(LocalTallyFormFactor provides formFactor, content = content) }
    }

/**
 * The theme in effect: in the app's window on a phone ([TallyPhoneWindow]) always the Tally look (Wholphin's themes
 * are TV layouts), whatever the saved preference says; otherwise the preference (a TV, and themes drawn outside the
 * app's window, e.g. by tests of upstream screens). The preference itself is never changed.
 */
@Composable
fun tallyThemeInEffect(
    formFactor: TallyFormFactor,
    preference: AppThemeColors,
): AppThemeColors {
    val context = LocalContext.current
    val phoneWindow = remember(context) { TallyPhoneWindow.isPhoneWindow(context) }
    return if (formFactor == TallyFormFactor.PHONE && phoneWindow) AppThemeColors.TALLY else preference
}

/**
 * Whether focus is drawn right now. Always on a TV. On a phone only while the input mode is keyboard / D-pad: a
 * touch never leaves a focus ring behind, and programmatic focus in touch mode draws nothing.
 */
@Composable
fun tallyFocusVisible(): Boolean {
    if (LocalTallyFormFactor.current == TallyFormFactor.TV) return true
    return LocalInputModeManager.current.inputMode == InputMode.Keyboard
}
