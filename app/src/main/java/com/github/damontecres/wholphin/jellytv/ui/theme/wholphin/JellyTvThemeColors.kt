package com.github.damontecres.wholphin.jellytv.ui.theme.wholphin

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.github.damontecres.wholphin.ui.theme.ThemeColors

/**
 * The JellyTV look as a Wholphin theme, selected as `AppThemeColors.JELLYTV` (the fork's default).
 *
 * Same palette as the JellyTV section and the JellyTV web UI (see JELLYTV.md): flat near-black grounds,
 * hairline grays, one amber accent, red only for errors and LIVE. There is deliberately no purple, blue or
 * green anywhere — Wholphin paints focus with `colorScheme.border` and the focused drawer item, switches and
 * sliders with `colorScheme.primary`, so both of those are the amber accent.
 *
 * The dark schemes are what people see. The light schemes exist so that switching to light does not crash or
 * produce an unreadable screen; they are the same palette inverted (ground `#F4F4F0`, text `#0E0F0E`).
 */
val JellyTvThemeColors: ThemeColors =
    object : ThemeColors {
        // --- dark: the JellyTV tokens verbatim ------------------------------------------------------------

        /** `ground`: screen background. */
        val groundDark = Color(0xFF0E0F0E)

        /** `groundRaised`: focused / raised card ground, also the surface tint. */
        val groundRaisedDark = Color(0xFF1B1C1A)

        /** One step above `groundRaised`, for the highest Material container levels. */
        val groundRaisedHighDark = Color(0xFF232523)

        /** `rule`: structural hairlines. */
        val ruleDark = Color(0xFF2A2C2A)

        /** `ruleStrong`: control / card borders, idle indicator squares. */
        val ruleStrongDark = Color(0xFF3A3D38)

        /** `text`: primary text. */
        val textDark = Color(0xFFE3E5DE)

        /** `textSecondary`: secondary text. */
        val textSecondaryDark = Color(0xFFA9ADA3)

        /** `accent`: focus frame, "now", active tab. */
        val accentDark = Color(0xFFFFB000)

        /** A dark amber wash that [accentDark] text sits on. */
        val accentContainerDark = Color(0xFF3A2A00)

        /** The accent dimmed, for the inverse (light-on-dark) pairing. */
        val accentDimDark = Color(0xFF7A5400)

        /** `live`: LIVE indicators and failures. */
        val liveDark = Color(0xFFFF3B30)

        /** A dark red wash that [liveTextDark] sits on. */
        val liveContainerDark = Color(0xFF3A1210)

        /** Small live-red text. */
        val liveTextDark = Color(0xFFFF6A61)

        val scrim = Color(0xFF000000)

        // --- light: the same palette inverted ------------------------------------------------------------

        val groundLight = Color(0xFFF4F4F0)
        val groundSunkLight = Color(0xFFFFFFFF)
        val groundLoweredLight = Color(0xFFEDEDE8)
        val groundRaisedLight = Color(0xFFE6E6E1)
        val groundRaisedHighLight = Color(0xFFDFDFD9)
        val ruleLight = Color(0xFFD6D7D0)
        val ruleStrongLight = Color(0xFFB9BAB3)
        val textLight = Color(0xFF0E0F0E)
        val textSecondaryLight = Color(0xFF4A4D47)
        val accentLight = Color(0xFFFFB000)
        val accentContainerLight = Color(0xFFFFE2A8)
        val accentBrightLight = Color(0xFFFFD27A)
        val secondaryLight = Color(0xFF5A5D56)
        val errorLight = Color(0xFFC42B22)
        val errorContainerLight = Color(0xFFFFDAD6)
        val onErrorContainerLight = Color(0xFF7A1710)

        override val lightSchemeMaterial: ColorScheme =
            androidx.compose.material3.lightColorScheme(
                primary = accentLight,
                onPrimary = textLight,
                primaryContainer = accentContainerLight,
                onPrimaryContainer = Color(0xFF3A2A00),
                inversePrimary = accentBrightLight,
                secondary = secondaryLight,
                onSecondary = groundLight,
                secondaryContainer = groundRaisedLight,
                onSecondaryContainer = textLight,
                tertiary = ruleDark,
                onTertiary = groundLight,
                tertiaryContainer = ruleLight,
                onTertiaryContainer = textLight,
                error = errorLight,
                onError = groundLight,
                errorContainer = errorContainerLight,
                onErrorContainer = onErrorContainerLight,
                background = groundLight,
                onBackground = textLight,
                surface = groundLight,
                onSurface = textLight,
                surfaceVariant = groundRaisedLight,
                onSurfaceVariant = textSecondaryLight,
                surfaceTint = groundRaisedLight,
                outline = ruleStrongLight,
                outlineVariant = ruleLight,
                scrim = scrim,
                inverseSurface = groundRaisedDark,
                inverseOnSurface = groundLight,
                surfaceDim = groundRaisedHighLight,
                surfaceBright = groundSunkLight,
                surfaceContainerLowest = groundSunkLight,
                surfaceContainerLow = groundLight,
                surfaceContainer = groundLoweredLight,
                surfaceContainerHigh = groundRaisedLight,
                surfaceContainerHighest = groundRaisedHighLight,
            )

        override val lightScheme =
            lightColorScheme(
                primary = accentLight,
                onPrimary = textLight,
                primaryContainer = accentContainerLight,
                onPrimaryContainer = Color(0xFF3A2A00),
                inversePrimary = accentBrightLight,
                secondary = secondaryLight,
                onSecondary = groundLight,
                secondaryContainer = groundRaisedLight,
                onSecondaryContainer = textLight,
                tertiary = ruleDark,
                onTertiary = groundLight,
                tertiaryContainer = ruleLight,
                onTertiaryContainer = textLight,
                error = errorLight,
                onError = groundLight,
                errorContainer = errorContainerLight,
                onErrorContainer = onErrorContainerLight,
                background = groundLight,
                onBackground = textLight,
                surface = groundLight,
                onSurface = textLight,
                surfaceVariant = groundRaisedLight,
                onSurfaceVariant = textSecondaryLight,
                surfaceTint = groundRaisedLight,
                border = ruleStrongLight,
                borderVariant = ruleLight,
                scrim = scrim,
                inverseSurface = groundRaisedDark,
                inverseOnSurface = groundLight,
            )

        override val darkSchemeMaterial =
            androidx.compose.material3.darkColorScheme(
                primary = accentDark,
                onPrimary = groundDark,
                primaryContainer = accentContainerDark,
                onPrimaryContainer = accentDark,
                inversePrimary = accentDimDark,
                secondary = textSecondaryDark,
                onSecondary = groundDark,
                secondaryContainer = ruleDark,
                onSecondaryContainer = textDark,
                tertiary = textDark,
                onTertiary = groundDark,
                tertiaryContainer = ruleStrongDark,
                onTertiaryContainer = textDark,
                error = liveDark,
                onError = groundDark,
                errorContainer = liveContainerDark,
                onErrorContainer = liveTextDark,
                background = groundDark,
                onBackground = textDark,
                surface = groundDark,
                onSurface = textDark,
                surfaceVariant = groundRaisedDark,
                onSurfaceVariant = textSecondaryDark,
                surfaceTint = groundRaisedDark,
                outline = ruleStrongDark,
                outlineVariant = ruleDark,
                scrim = scrim,
                inverseSurface = accentDark,
                inverseOnSurface = groundDark,
                surfaceDim = groundDark,
                surfaceBright = groundRaisedHighDark,
                surfaceContainerLowest = groundDark,
                surfaceContainerLow = groundRaisedDark,
                surfaceContainer = groundRaisedDark,
                surfaceContainerHigh = groundRaisedHighDark,
                surfaceContainerHighest = groundRaisedHighDark,
            )

        override val darkScheme =
            darkColorScheme(
                primary = accentDark,
                onPrimary = groundDark,
                primaryContainer = accentContainerDark,
                onPrimaryContainer = accentDark,
                inversePrimary = accentDimDark,
                secondary = textSecondaryDark,
                onSecondary = groundDark,
                secondaryContainer = ruleDark,
                onSecondaryContainer = textDark,
                tertiary = textDark,
                onTertiary = groundDark,
                tertiaryContainer = ruleStrongDark,
                onTertiaryContainer = textDark,
                error = liveDark,
                onError = groundDark,
                errorContainer = liveContainerDark,
                onErrorContainer = liveTextDark,
                background = groundDark,
                onBackground = textDark,
                surface = groundDark,
                onSurface = textDark,
                surfaceVariant = groundRaisedDark,
                onSurfaceVariant = textSecondaryDark,
                surfaceTint = groundRaisedDark,
                border = ruleStrongDark,
                borderVariant = ruleDark,
                scrim = scrim,
                inverseSurface = accentDark,
                inverseOnSurface = groundDark,
            )
    }
