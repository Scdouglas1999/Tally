package com.github.damontecres.wholphin.jellytv.ui.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A scores screensaver: when the JellyTV plugin is present and games are on, the idle screen shows them.
 * Returns false (and draws nothing) when it has nothing to show, so the caller falls back to upstream's screensaver.
 * STUB.
 */
@Composable
fun JellyTvScreensaver(modifier: Modifier = Modifier): Boolean = false
