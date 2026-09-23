// Modified for Tally (https://github.com/Scdouglas1999/Tally), a fork of Wholphin
// (https://github.com/damontecres/Wholphin), from September 2026. Changes are marked TALLY: begin/end;
// each change and its date is in the git history. See NOTICE.md.
package com.github.damontecres.wholphin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.BlueThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.BoldBlueThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.BrownThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.GreenThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.OledThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.OrangeThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.RedThemeColors

val LocalTheme =
    compositionLocalOf<AppThemeColors> { AppThemeColors.PURPLE }

fun getThemeColors(appThemeColors: AppThemeColors): ThemeColors =
    when (appThemeColors) {
        AppThemeColors.PURPLE -> PurpleThemeColors
        AppThemeColors.BLUE -> BlueThemeColors
        AppThemeColors.GREEN -> GreenThemeColors
        AppThemeColors.ORANGE -> OrangeThemeColors
        AppThemeColors.OLED_BLACK -> OledThemeColors
        AppThemeColors.BOLD_BLUE -> BoldBlueThemeColors
        AppThemeColors.RED -> RedThemeColors
        AppThemeColors.BROWN -> BrownThemeColors
        // TALLY: begin
        AppThemeColors.TALLY -> io.github.scdouglas1999.tally.ui.theme.material.TallyThemeColors
        // TALLY: end
        AppThemeColors.UNRECOGNIZED -> PurpleThemeColors
    }

@Composable
fun WholphinTheme(
    darkTheme: Boolean = true,
    appThemeColors: AppThemeColors = AppThemeColors.PURPLE,
    content: @Composable () -> Unit,
) {
    // TALLY: begin
    val tallyFormFactor =
        io.github.scdouglas1999.tally.ui.formfactor
            .rememberTallyFormFactor()

    @Suppress("NAME_SHADOWING")
    val appThemeColors =
        io.github.scdouglas1999.tally.ui.formfactor
            .tallyThemeInEffect(tallyFormFactor, appThemeColors)

    @Suppress("NAME_SHADOWING")
    val content =
        io.github.scdouglas1999.tally.ui.formfactor
            .withTallyFormFactor(tallyFormFactor, content)
    // TALLY: end
    val themeColors = getThemeColors(appThemeColors)

    val colorScheme =
        when {
            darkTheme -> themeColors.darkScheme
            else -> themeColors.lightScheme
        }
    CompositionLocalProvider(LocalTheme provides appThemeColors) {
        // TALLY: begin
        val tally = appThemeColors == AppThemeColors.TALLY
        // TALLY: end
        androidx.compose.material3.MaterialTheme(
            colorScheme = if (darkTheme) themeColors.darkSchemeMaterial else themeColors.lightSchemeMaterial,
            // TALLY: begin
            typography = if (tally) io.github.scdouglas1999.tally.ui.theme.material.TallyMaterialTypography else androidx.compose.material3.Typography(),
            shapes =
                if (tally) {
                    io.github.scdouglas1999.tally.ui.theme.material.TallyMaterialShapes
                } else {
                    androidx.compose.material3.Shapes()
                },
            // TALLY: end
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                // TALLY: begin
                typography = if (tally) io.github.scdouglas1999.tally.ui.theme.material.TallyTypography else AppTypography,
                shapes =
                    if (tally) {
                        io.github.scdouglas1999.tally.ui.theme.material.TallyShapes
                    } else {
                        androidx.tv.material3.Shapes()
                    },
                // TALLY: end
                content = content,
            )
        }
    }
}
