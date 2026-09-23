package io.github.scdouglas1999.tally

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.preference.PreferenceManager
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.BackdropStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time adjustments when this fork first runs over an existing Wholphin/JellyTV install. The Tally theme
 * is the default for fresh installs, but a store written by an earlier build already holds a theme value
 * (Purple, the proto default), so the default never applies to it: switch that once. Anyone who picks another
 * theme afterwards keeps it.
 *
 * Second step (Tally UI takeover): the backdrop shows the image only, without Wholphin's full-page color wash
 * (gradients as decoration are not part of the Tally look). Once, so a user who turns the wash back on keeps it.
 */
@Singleton
class TallyFirstRun
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dataStore: DataStore<AppPreferences>,
    ) {
        suspend fun apply() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            migrateBackdrop(prefs)
            if (prefs.getBoolean(KEY_THEME_MIGRATED, false)) return
            val current =
                dataStore.data
                    .first()
                    .interfacePreferences.appThemeColors
            if (current == AppThemeColors.PURPLE || current == AppThemeColors.UNRECOGNIZED) {
                Timber.i("First run of the Tally build: switching the theme from %s to Tally", current)
                dataStore.updateData { data ->
                    data
                        .toBuilder()
                        .setInterfacePreferences(data.interfacePreferences.toBuilder().setAppThemeColors(AppThemeColors.TALLY))
                        .build()
                }
            }
            prefs.edit { putBoolean(KEY_THEME_MIGRATED, true) }
        }

        private suspend fun migrateBackdrop(prefs: android.content.SharedPreferences) {
            if (prefs.getBoolean(KEY_BACKDROP_MIGRATED, false)) return
            val current =
                dataStore.data
                    .first()
                    .interfacePreferences.backdropStyle
            if (current == BackdropStyle.BACKDROP_DYNAMIC_COLOR || current == BackdropStyle.UNRECOGNIZED) {
                Timber.i("Tally look: backdrop color wash off (image only)")
                dataStore.updateData { data ->
                    data
                        .toBuilder()
                        .setInterfacePreferences(
                            data.interfacePreferences.toBuilder().setBackdropStyle(BackdropStyle.BACKDROP_IMAGE_ONLY),
                        ).build()
                }
            }
            prefs.edit { putBoolean(KEY_BACKDROP_MIGRATED, true) }
        }

        private companion object {
            const val KEY_THEME_MIGRATED = "jellytv.firstRun.themeMigrated"
            const val KEY_BACKDROP_MIGRATED = "jellytv.firstRun.backdropImageOnly"
        }
    }
