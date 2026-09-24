package io.github.scdouglas1999.tally.downloads.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor

/*
 * What the phone shell shows of downloads: offline mode (the bottom bar offers only Downloads and Settings) and the
 * More sheet's Downloads row. Nothing on a TV.
 */

/** True while the server cannot be reached (offline mode); always false on a TV. */
@Composable
fun rememberOfflineMode(): Boolean {
    if (LocalTallyFormFactor.current != TallyFormFactor.PHONE) return false
    val context = LocalContext.current
    val downloads = remember(context) { downloadsOrNull(context) } ?: return false
    val offline by downloads.offlineMode.collectAsState()
    return offline
}

/**
 * The More sheet's Downloads row detail: the number of downloads, and while some run the share done (`3 · 42%`);
 * null when there are none.
 */
@Composable
fun downloadsSummary(): String? {
    val context = LocalContext.current
    val downloads = remember(context) { downloadsOrNull(context) } ?: return null
    val entries by downloads.all.collectAsState(emptyList())
    if (entries.isEmpty()) return null
    val running = statusOf(entries.filter { !it.isDone }) as? DownloadStatus.Active ?: return entries.size.toString()
    return "${entries.size} · ${stringResource(R.string.tally_dlui_percent, running.percent)}"
}

/** `wholphin://downloads`: the download notifications open the Downloads page (seam in upstream's `IntentService`). */
object DownloadsLink {
    const val HOST = "downloads"
}

/** The cover the music player shows for [item]: the downloaded one on the device when there is one (offline too). */
@Composable
fun musicCover(item: com.github.damontecres.wholphin.data.model.AudioItem?): String? {
    val context = LocalContext.current
    if (item == null) return null
    val local =
        remember(item.id) {
            io.github.scdouglas1999.tally.downloads.TallyDownloadPlayback
                .localArtwork(context, item.id)
        }
    return local ?: item.imageUrl
}
