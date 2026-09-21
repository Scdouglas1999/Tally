package com.github.damontecres.wholphin.jellytv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.PlaybackPage

@Composable
fun JellyTvPage(
    preferences: UserPreferences,
    modifier: Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = "JellyTV",
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun JellyTvSettingsPage(
    preferences: UserPreferences,
    modifier: Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = "JellyTV Settings",
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun JellyTvMultiviewPage(
    preferences: UserPreferences,
    modifier: Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = "JellyTV Multiview",
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun JellyTvPlaybackPage(
    preferences: UserPreferences,
    destination: Destination.JellyTvPlayback,
    modifier: Modifier,
) {
    Box(modifier) {
        PlaybackPage(
            preferences = preferences,
            destination =
                Destination.Playback(
                    itemId = destination.itemId,
                    positionMs = 0L,
                ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
