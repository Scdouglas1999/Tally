package com.github.damontecres.wholphin.jellytv.ui.theme.wholphin

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType

/**
 * IBM Plex for the whole app when the JellyTV theme is active, so Wholphin's screens read as the same design
 * as the JellyTV section: IBM Plex Sans for anything you read, IBM Plex Mono for the instrument-like label
 * styles (uppercase-ish kickers, badges, buttons), letter-spaced.
 *
 * Every style is derived from upstream's own default text style, so the font size, line height and the
 * `includeFontPadding = false` platform style are byte-for-byte upstream's — only the family, the weight and
 * (for the label styles) the letter spacing change. Wholphin's layouts are built against those metrics, so
 * nothing here resizes anything.
 */
private val upstreamTv = androidx.tv.material3.Typography()

private val upstreamMaterial = androidx.compose.material3.Typography()

/** IBM Plex Sans at [weight], keeping this style's size, line height and letter spacing. */
private fun TextStyle.sans(weight: FontWeight): TextStyle =
    copy(
        fontFamily = JtvType.Sans,
        fontWeight = weight,
    )

/** IBM Plex Mono Medium with the 1sp tracking that the JellyTV labels use. */
private fun TextStyle.mono(): TextStyle =
    copy(
        fontFamily = JtvType.Mono,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    )

val JellyTvTypography: androidx.tv.material3.Typography =
    androidx.tv.material3.Typography(
        displayLarge = upstreamTv.displayLarge.sans(FontWeight.SemiBold),
        displayMedium = upstreamTv.displayMedium.sans(FontWeight.SemiBold),
        displaySmall = upstreamTv.displaySmall.sans(FontWeight.SemiBold),
        headlineLarge = upstreamTv.headlineLarge.sans(FontWeight.SemiBold),
        headlineMedium = upstreamTv.headlineMedium.sans(FontWeight.SemiBold),
        headlineSmall = upstreamTv.headlineSmall.sans(FontWeight.SemiBold),
        titleLarge = upstreamTv.titleLarge.sans(FontWeight.Medium),
        titleMedium = upstreamTv.titleMedium.sans(FontWeight.Medium),
        titleSmall = upstreamTv.titleSmall.sans(FontWeight.Medium),
        bodyLarge = upstreamTv.bodyLarge.sans(FontWeight.Normal),
        bodyMedium = upstreamTv.bodyMedium.sans(FontWeight.Normal),
        bodySmall = upstreamTv.bodySmall.sans(FontWeight.Normal),
        labelLarge = upstreamTv.labelLarge.mono(),
        labelMedium = upstreamTv.labelMedium.mono(),
        labelSmall = upstreamTv.labelSmall.mono(),
    )

val JellyTvMaterialTypography: androidx.compose.material3.Typography =
    androidx.compose.material3.Typography(
        displayLarge = upstreamMaterial.displayLarge.sans(FontWeight.SemiBold),
        displayMedium = upstreamMaterial.displayMedium.sans(FontWeight.SemiBold),
        displaySmall = upstreamMaterial.displaySmall.sans(FontWeight.SemiBold),
        headlineLarge = upstreamMaterial.headlineLarge.sans(FontWeight.SemiBold),
        headlineMedium = upstreamMaterial.headlineMedium.sans(FontWeight.SemiBold),
        headlineSmall = upstreamMaterial.headlineSmall.sans(FontWeight.SemiBold),
        titleLarge = upstreamMaterial.titleLarge.sans(FontWeight.Medium),
        titleMedium = upstreamMaterial.titleMedium.sans(FontWeight.Medium),
        titleSmall = upstreamMaterial.titleSmall.sans(FontWeight.Medium),
        bodyLarge = upstreamMaterial.bodyLarge.sans(FontWeight.Normal),
        bodyMedium = upstreamMaterial.bodyMedium.sans(FontWeight.Normal),
        bodySmall = upstreamMaterial.bodySmall.sans(FontWeight.Normal),
        labelLarge = upstreamMaterial.labelLarge.mono(),
        labelMedium = upstreamMaterial.labelMedium.mono(),
        labelSmall = upstreamMaterial.labelSmall.mono(),
    )
