package com.github.damontecres.wholphin.jellytv

import android.content.Context
import androidx.core.content.edit

/**
 * The client name this install reports to Jellyfin (seam W35, `AppModule.clientInfo`).
 *
 * Jellyfin binds an access token to the app name it was issued under. Requests from that token under a different
 * name land in a second session, and server-pushed messages (Watch together / SyncPlay, remote control) go to the
 * other one, so they never arrive (verified against 10.10.6: a token issued as "JellyTV Debug" used as
 * "Tally Debug" receives no SyncPlay messages; the same name both ways does). Installs that signed in while the app
 * was called JellyTV therefore keep reporting "JellyTV" for good, and fresh installs report "Tally".
 *
 * Decided once, on the first launch of a build that has this, and stored: an install that was updated over an
 * older build and already has the app database is a JellyTV-era install.
 */
object JellyTvClientName {
    private const val PREFS = "jellytv_client"
    private const val KEY_NAME = "name"

    fun get(
        context: Context,
        appName: String,
    ): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.let { return it }
        val name = if (isJellyTvEraInstall(context)) appName.replace("Tally", "JellyTV") else appName
        prefs.edit { putString(KEY_NAME, name) }
        return name
    }

    private fun isJellyTvEraInstall(context: Context): Boolean {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val updated = info != null && info.lastUpdateTime > info.firstInstallTime
        return updated && context.getDatabasePath("wholphin").exists()
    }
}
