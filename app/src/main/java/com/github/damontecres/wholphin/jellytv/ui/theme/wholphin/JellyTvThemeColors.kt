package com.github.damontecres.wholphin.jellytv.ui.theme.wholphin

import com.github.damontecres.wholphin.ui.theme.ThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.OledThemeColors

/**
 * The JellyTV look as a Wholphin theme, selected as `AppThemeColors.JELLYTV` (the fork's default).
 * STUB: delegates to the OLED theme until the real palette lands. The contract is the [ThemeColors] interface.
 */
val JellyTvThemeColors: ThemeColors = object : ThemeColors by OledThemeColors {}
