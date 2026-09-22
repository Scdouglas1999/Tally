package com.github.damontecres.wholphin.jellytv

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.preference.PreferenceManager
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time adjustments when this fork first runs over an existing Wholphin/JellyTV install. The JellyTV theme
 * is the default for fresh installs, but a store written by an earlier build already holds a theme value
 * (Purple, the proto default), so the default never applies to it: switch that once. Anyone who picks another
 * theme afterwards keeps it.
 */
@Singleton
class JellyTvFirstRun
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dataStore: DataStore<AppPreferences>,
    ) {
        suspend fun apply() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.getBoolean(KEY_THEME_MIGRATED, false)) return
            val current =
                dataStore.data
                    .first()
                    .interfacePreferences.appThemeColors
            if (current == AppThemeColors.PURPLE || current == AppThemeColors.UNRECOGNIZED) {
                Timber.i("First run of the JellyTV build: switching the theme from %s to JellyTV", current)
                dataStore.updateData { data ->
                    data
                        .toBuilder()
                        .setInterfacePreferences(data.interfacePreferences.toBuilder().setAppThemeColors(AppThemeColors.JELLYTV))
                        .build()
                }
            }
            prefs.edit { putBoolean(KEY_THEME_MIGRATED, true) }
        }

        private companion object {
            const val KEY_THEME_MIGRATED = "jellytv.firstRun.themeMigrated"
        }
    }
