package io.github.scdouglas1999.tally.downloads

import android.content.Context
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.scdouglas1999.tally.lan.ServerRouteMonitor
import io.github.scdouglas1999.tally.ui.formfactor.isTallyPhone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.IOException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OfflineStartEntryPoint {
    fun appPreferences(): DataStore<AppPreferences>

    fun serverRepository(): ServerRepository

    fun navigationManager(): NavigationManager

    fun setupNavigationManager(): SetupNavigationManager
}

/**
 * The startup decision when the server cannot be reached (seam in `MainActivityViewModel.appStart`): with completed
 * downloads for the saved user, the app opens on the downloads page in offline mode instead of dead-ending on the
 * server list; the server is retried in the background and offline mode ends when it answers.
 */
object TallyOfflineStart {
    /**
     * Called at app start: starts the server route (home network or internet, on a TV too) and downloads
     * (idempotent).
     */
    fun onAppStart(context: Context) {
        ServerRouteMonitor.start(context)
        entryPointOrNull(context)?.downloads()?.start()
    }

    /**
     * Null where the app's Hilt graph is not there (Wholphin's unit tests), and on a TV (downloads and offline mode are
     * a phone feature): then startup is exactly Wholphin's.
     */
    private fun entryPointOrNull(context: Context): DownloadsEntryPoint? =
        try {
            // Hilt first: in Wholphin's unit tests (a plain or mocked context) this throws before the device is asked.
            // Downloads are a phone feature: a TV plays and starts exactly as Wholphin does, even with old downloads.
            downloadsEntryPoint(context).takeIf { isTallyPhone(context) }
        } catch (e: RuntimeException) {
            // not a Hilt app (a test's plain or mocked context); in the app this lookup cannot fail
            null
        }

    /**
     * Called at app start before the session is restored: with no network, or the server already known to be
     * unreachable (the app was reopened while offline), go straight to offline mode instead of waiting on the server.
     * Returns true when it did (the caller stops).
     */
    suspend fun enterIfOffline(context: Context): Boolean {
        val downloads = entryPointOrNull(context) ?: return false
        val offline = downloads.downloads().offlineMode.value || !downloads.engine().hasNetwork()
        if (!offline) return false
        return try {
            enterOffline(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Offline start failed")
            false
        }
    }

    /**
     * Called when restoring the session failed with [error]. Returns true when it took the app to offline mode (the
     * caller stops), false to carry on as upstream does.
     */
    suspend fun enter(
        context: Context,
        error: Throwable,
    ): Boolean {
        if (!isUnreachable(error)) return false
        if (entryPointOrNull(context) == null) return false
        return try {
            enterOffline(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Offline start failed")
            false
        }
    }

    private suspend fun enterOffline(context: Context): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, OfflineStartEntryPoint::class.java)
        val downloads = downloadsEntryPoint(context)
        val prefs = entryPoint.appPreferences().data.firstOrNull() ?: return false
        if (!prefs.signInAutomatically) return false
        val serverId = prefs.currentServerId?.toUUIDOrNull() ?: return false
        val userId = prefs.currentUserId?.toUUIDOrNull() ?: return false
        val repository = entryPoint.serverRepository()
        val serverAndUsers = withContext(Dispatchers.IO) { repository.serverDao.getServer(serverId) } ?: return false
        val user = serverAndUsers.users.firstOrNull { it.id == userId } ?: return false
        if (user.isProtected) return false
        val engine = downloads.engine()
        val hasDownloads =
            engine.dao.all().any {
                it.serverId == serverId.hex() && it.userId == userId.hex() && it.completedAt != null
            }
        if (!hasDownloads) return false
        Timber.i("Server unreachable at start: offline mode with downloads")
        val current = CurrentUser(serverAndUsers.server, user)
        repository.tallyRestoreOffline(current)
        val tally = downloads.downloads()
        tally.start()
        // offline now, retried in the background; offline mode ends when the server answers
        tally.markOffline()
        withContext(Dispatchers.Main) {
            entryPoint.navigationManager().replace(tally.destination)
            entryPoint.setupNavigationManager().navigateTo(SetupDestination.AppContent(current))
        }
        return true
    }

    /** No answer from the server (network, timeout, 5xx), as opposed to an answer that refuses (401, 403...). */
    internal fun isUnreachable(error: Throwable): Boolean =
        when (error) {
            is InvalidStatusException -> error.status >= 500
            is InvalidContentException -> false
            is ApiClientException, is IOException -> true
            else -> error.cause?.let { isUnreachable(it) } ?: false
        }
}
