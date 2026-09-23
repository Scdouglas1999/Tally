package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.updatePlaybackPreferences
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import io.github.scdouglas1999.tally.ui.settings.focusedPreference
import io.github.scdouglas1999.tally.ui.settings.preferenceGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The description panel finds the focused row from upstream's (group, index) exactly as the list lays rows out. */
class SettingsPageTest {
    private val defaults = AppPreferences.getDefaultInstance()

    @Test
    fun basicScreenPositions() {
        val groups = preferenceGroups(PreferenceScreenOption.BASIC)
        assertEquals(AppPreference.SignInAuto, focusedPreference(groups, defaults, 0, 0))
        assertEquals(AppPreference.ThemeColors, focusedPreference(groups, defaults, 0, 4))
        assertEquals(AppPreference.SkipForward, focusedPreference(groups, defaults, 1, 0))
    }

    @Test
    fun outOfRangeIsNothing() {
        val groups = preferenceGroups(PreferenceScreenOption.BASIC)
        assertNull(focusedPreference(groups, defaults, 99, 0))
        assertNull(focusedPreference(groups, defaults, 0, 99))
    }

    @Test
    fun conditionalRowsFollowTheCurrentPreferences() {
        val groups = preferenceGroups(PreferenceScreenOption.ADVANCED)
        val backendGroup = groups.indexOfFirst { it.title == R.string.player_backend }
        val external = defaults.updatePlaybackPreferences { playerBackend = PlayerBackend.EXTERNAL_PLAYER }
        assertEquals(AppPreference.PlayerBackendPref, focusedPreference(groups, external, backendGroup, 0))
        assertEquals(AppPreference.ExternalPlayerApp, focusedPreference(groups, external, backendGroup, 1))
        assertNull(focusedPreference(groups, external, backendGroup, 2))

        val exo = defaults.updatePlaybackPreferences { playerBackend = PlayerBackend.EXO_PLAYER }
        assertEquals(AppPreference.FfmpegPreference, focusedPreference(groups, exo, backendGroup, 1))
    }
}
