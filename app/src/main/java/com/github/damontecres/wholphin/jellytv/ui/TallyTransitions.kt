package com.github.damontecres.wholphin.jellytv.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.navigation3.scene.Scene
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.LocalTheme

/**
 * Page changes in the Tally theme (seam W36, `ApplicationContent`'s `NavDisplay`): a broadcast "cut", a dip of
 * 180 ms in total, instead of navigation3's default 700 ms crossfade, which made every page change feel slow on a
 * remote. The outgoing page fades out over [OUT_MS], then the new one fades in over [IN_MS]; the two never overlap,
 * so a half-drawn page is never blended over the old one. Other themes keep the library defaults.
 */
object TallyTransitions {
    const val OUT_MS = 70
    const val IN_MS = 110

    @Composable
    fun active(): Boolean = LocalTheme.current == AppThemeColors.JELLYTV

    private fun cutTransform(): ContentTransform =
        fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = LinearEasing)) togetherWith
            fadeOut(tween(OUT_MS, easing = LinearEasing))

    fun <T : Any> cut(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = { cutTransform() }

    fun <T : Any> predictiveCut(): AnimatedContentTransitionScope<Scene<T>>.(Int) -> ContentTransform = { cutTransform() }
}
