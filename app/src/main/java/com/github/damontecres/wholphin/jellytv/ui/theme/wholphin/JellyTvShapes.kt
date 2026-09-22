package com.github.damontecres.wholphin.jellytv.ui.theme.wholphin

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The JellyTV look has square corners. These replace the theme shapes that tv-material3 (cards, surfaces, chips,
 * dialogs) and material3 read their defaults from, so upstream screens that use theme defaults square up too.
 * Components that hard-code their own shape (tv-material3 buttons are pill-shaped by token) are not affected here.
 */
private val Square = RoundedCornerShape(0.dp)

val JellyTvShapes =
    androidx.tv.material3.Shapes(
        extraSmall = Square,
        small = Square,
        medium = Square,
        large = Square,
        extraLarge = Square,
    )

val JellyTvMaterialShapes =
    androidx.compose.material3.Shapes(
        extraSmall = Square,
        small = Square,
        medium = Square,
        large = Square,
        extraLarge = Square,
    )
