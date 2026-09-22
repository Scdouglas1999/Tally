package com.github.damontecres.wholphin.jellytv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.tv.material3.ColorScheme
import com.github.damontecres.wholphin.jellytv.ui.theme.wholphin.JellyTvThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JellyTV palette is the whole app's default theme, so the tokens it promises are pinned here: near-black
 * grounds, one amber accent, red only for errors, hairline greys — and, above all, nothing that reads purple,
 * blue or green (the upstream themes it replaces are all built on those hues).
 */
class JellyTvThemeColorsTest {
    private val dark: ColorScheme = JellyTvThemeColors.darkScheme

    private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

    private fun Color.red(): Int = (argb() shr 16 and 0xFF).toInt()

    private fun Color.blue(): Int = (argb() and 0xFF).toInt()

    /** Every role of the dark TV scheme, named, so a failure says which one is wrong. */
    private fun darkRoles(): List<Pair<String, Color>> =
        listOf(
            "primary" to dark.primary,
            "onPrimary" to dark.onPrimary,
            "primaryContainer" to dark.primaryContainer,
            "onPrimaryContainer" to dark.onPrimaryContainer,
            "inversePrimary" to dark.inversePrimary,
            "secondary" to dark.secondary,
            "onSecondary" to dark.onSecondary,
            "secondaryContainer" to dark.secondaryContainer,
            "onSecondaryContainer" to dark.onSecondaryContainer,
            "tertiary" to dark.tertiary,
            "onTertiary" to dark.onTertiary,
            "tertiaryContainer" to dark.tertiaryContainer,
            "onTertiaryContainer" to dark.onTertiaryContainer,
            "background" to dark.background,
            "onBackground" to dark.onBackground,
            "surface" to dark.surface,
            "onSurface" to dark.onSurface,
            "surfaceVariant" to dark.surfaceVariant,
            "onSurfaceVariant" to dark.onSurfaceVariant,
            "surfaceTint" to dark.surfaceTint,
            "inverseSurface" to dark.inverseSurface,
            "inverseOnSurface" to dark.inverseOnSurface,
            "error" to dark.error,
            "onError" to dark.onError,
            "errorContainer" to dark.errorContainer,
            "onErrorContainer" to dark.onErrorContainer,
            "border" to dark.border,
            "borderVariant" to dark.borderVariant,
            "scrim" to dark.scrim,
        )

    @Test
    fun `dark ground is the JellyTV ground`() {
        assertEquals(0xFF0E0F0EL, dark.background.argb())
        assertEquals(0xFF0E0F0EL, dark.surface.argb())
    }

    @Test
    fun `dark accent is the JellyTV amber`() {
        assertEquals(0xFFFFB000L, dark.primary.argb())
    }

    @Test
    fun `dark error is the JellyTV live red`() {
        assertEquals(0xFFFF3B30L, dark.error.argb())
    }

    @Test
    fun `dark border is the strong hairline`() {
        assertEquals(0xFF3A3D38L, dark.border.argb())
    }

    @Test
    fun `dark text is the JellyTV text tokens`() {
        assertEquals(0xFFE3E5DEL, dark.onBackground.argb())
        assertEquals(0xFFE3E5DEL, dark.onSurface.argb())
        assertEquals(0xFFA9ADA3L, dark.onSurfaceVariant.argb())
    }

    @Test
    fun `no role in the dark scheme is purple or blue`() {
        darkRoles().forEach { (name, color) ->
            val skew = color.blue() - color.red()
            assertTrue(
                "$name is ${"#%08X".format(color.argb())}: blue channel is $skew above red, which reads purple/blue",
                skew <= 40,
            )
        }
    }

    @Test
    fun `light scheme exists and keeps the amber accent`() {
        assertEquals(0xFFFFB000L, JellyTvThemeColors.lightScheme.primary.argb())
        assertEquals(0xFFF4F4F0L, JellyTvThemeColors.lightScheme.background.argb())
        assertEquals(0xFF0E0F0EL, JellyTvThemeColors.lightScheme.onBackground.argb())
    }

    @Test
    fun `material schemes mirror the tv schemes`() {
        assertEquals(dark.background.argb(), JellyTvThemeColors.darkSchemeMaterial.background.argb())
        assertEquals(dark.primary.argb(), JellyTvThemeColors.darkSchemeMaterial.primary.argb())
        assertEquals(dark.border.argb(), JellyTvThemeColors.darkSchemeMaterial.outline.argb())
        assertEquals(dark.borderVariant.argb(), JellyTvThemeColors.darkSchemeMaterial.outlineVariant.argb())
    }
}
