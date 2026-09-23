package io.github.scdouglas1999.tally

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * Upstream announces an update with a toast and leaves the user to find Settings > Install update. This app is
 * sideloaded by people who will never look there, so when a newer release exists we open upstream's own install
 * page for them: one press on "Install". Declining a release silences it for a few days; a newer one asks again.
 */
@Singleton
class TallyUpdatePrompt
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val updateChecker: UpdateChecker,
        private val navigationManager: NavigationManager,
        private val serverRepository: ServerRepository,
    ) {
        suspend fun maybePrompt(updateUrl: String) {
            val latest = updateChecker.getLatestRelease(updateUrl) ?: return
            val installed = updateChecker.getInstalledVersion()
            if (latest.downloadUrl == null || !latest.version.isGreaterThan(installed)) return

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val version = latest.version.toString()
            val sinceAsked = (System.currentTimeMillis() - prefs.getLong(KEY_ASKED_AT, 0)).milliseconds
            if (prefs.getString(KEY_ASKED_VERSION, null) == version && sinceAsked < REMIND_AFTER) {
                Timber.i("Update %s was already offered %s ago", version, sinceAsked)
                return
            }

            // Let sign-in and the first screen settle; never interrupt setup or something that is playing.
            delay(SETTLE_MS)
            val top = navigationManager.backStack.lastOrNull()
            if (serverRepository.current.value == null || top == null || top.fullScreen) {
                Timber.i("Update %s available, not a good moment to offer it (%s)", version, top)
                return
            }
            prefs.edit {
                putString(KEY_ASKED_VERSION, version)
                putLong(KEY_ASKED_AT, System.currentTimeMillis())
            }
            Timber.i("Offering update %s => %s", installed, version)
            withContext(Dispatchers.Main) { navigationManager.navigateTo(Destination.UpdateApp) }
        }

        private companion object {
            const val KEY_ASKED_VERSION = "jellytv.update.askedVersion"
            const val KEY_ASKED_AT = "jellytv.update.askedAt"
            const val SETTLE_MS = 5_000L
            val REMIND_AFTER = 3.days
        }
    }
