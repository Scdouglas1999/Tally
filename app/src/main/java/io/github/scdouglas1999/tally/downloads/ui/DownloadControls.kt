package io.github.scdouglas1999.tally.downloads.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.downloads.DownloadEntry
import io.github.scdouglas1999.tally.downloads.DownloadOption
import io.github.scdouglas1999.tally.downloads.DownloadQuality
import io.github.scdouglas1999.tally.downloads.DownloadSettings
import io.github.scdouglas1999.tally.downloads.DownloadTarget
import io.github.scdouglas1999.tally.downloads.EnqueueResult
import io.github.scdouglas1999.tally.downloads.StorageInfo
import io.github.scdouglas1999.tally.downloads.TallyDownloads
import io.github.scdouglas1999.tally.media.movie.phone.PhoneAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID

/**
 * The downloads UI's view model: the signed-in user's downloads, the settings and storage, and every action the
 * DOWNLOAD controls, sheets, menus and the Downloads page take. Talks only to [TallyDownloads].
 */
@HiltViewModel
class DownloadUiViewModel
    @javax.inject.Inject
    constructor(
        val downloads: TallyDownloads,
        val navigationManager: NavigationManager,
        private val musicService: MusicService,
        private val keyValueService: KeyValueService,
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        val entries: StateFlow<List<DownloadEntry>> =
            downloads.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        val settings: StateFlow<DownloadSettings> get() = downloads.settings
        val offline: StateFlow<Boolean> get() = downloads.offlineMode
        val storage: StateFlow<StorageInfo?> =
            downloads.storage.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** The sheet's qualities for [target]; an empty list and the reason when the server cannot say. */
        suspend fun options(target: DownloadTarget): Result<List<DownloadOption>> =
            try {
                Result.success(downloads.options(target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Download options for %s", target)
                Result.failure(e)
            }

        suspend fun enqueue(
            target: DownloadTarget,
            quality: DownloadQuality,
        ): EnqueueResult = downloads.enqueue(target, quality)

        fun pause(ids: Collection<UUID>) {
            viewModelScope.launch { ids.forEach { downloads.pause(it) } }
        }

        fun resume(ids: Collection<UUID>) {
            viewModelScope.launch { ids.forEach { downloads.resume(it) } }
        }

        fun delete(ids: Collection<UUID>) {
            viewModelScope.launch { downloads.delete(ids) }
        }

        fun deleteAll() {
            viewModelScope.launch { downloads.deleteAll() }
        }

        fun retryServer() = downloads.checkServer()

        /**
         * Back online after an offline start: the session was restored without the server, so the user's details (and
         * with them the libraries and the bar) are fetched now.
         */
        suspend fun completeSession() {
            val current = serverRepository.current.value
            if (current != null && serverRepository.currentUserDto == null) {
                try {
                    serverRepository.changeUser(current.server, current.user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not complete the session after an offline start")
                }
            }
        }

        /** The full app again, from Home (reloaded: it could not load while offline). */
        fun returnToFullApp() = navigationManager.reloadHome()

        fun openDownloads() = navigationManager.navigateTo(Destination.TallyDownloads)

        /**
         * Plays downloads from the device: a film or episode opens the player at this device's position (the player
         * prefers the download); tracks play as a queue in album / playlist order. For several videos, the first
         * one not yet watched.
         */
        fun play(entries: List<DownloadEntry>) {
            val done = entries.filter { it.isDone }
            if (done.isEmpty()) return
            viewModelScope.launch {
                val tracks = done.filter { it.type == BaseItemKind.AUDIO }
                if (tracks.isNotEmpty()) {
                    val items =
                        tracks
                            .mapNotNull { entry -> downloads.localItem(entry.itemId)?.let { entry to it } }
                            .sortedWith(
                                compareBy(
                                    { it.first.playlistIndex ?: Int.MAX_VALUE },
                                    { it.first.album ?: "" },
                                    { it.second.parentIndexNumber ?: 0 },
                                    { it.second.indexNumber ?: 0 },
                                ),
                            ).map { BaseItem(it.second, false) }
                    if (items.isEmpty()) return@launch
                    musicService.setQueue(items, false)
                    navigationManager.navigateTo(Destination.NowPlaying)
                } else {
                    val ordered =
                        done.sortedWith(
                            compareBy<DownloadEntry>(
                                { it.playlistIndex ?: Int.MAX_VALUE },
                                { it.seasonNumber ?: 0 },
                                { it.episodeNumber ?: 0 },
                            ),
                        )
                    val next = ordered.firstOrNull { !it.played } ?: ordered.first()
                    navigationManager.navigateTo(Destination.Playback(next.itemId, if (next.played) 0L else next.positionMs))
                }
            }
        }

        /** True the first time a download starts on this device: ask for the notification permission then. */
        suspend fun firstDownload(): Boolean {
            val asked = keyValueService.get(NOTIFICATIONS_ASKED, false).first()
            if (!asked) keyValueService.save(NOTIFICATIONS_ASKED, true)
            return !asked
        }

        private companion object {
            const val NOTIFICATIONS_ASKED = "tally.downloads.notificationsAsked"
        }
    }

/** What a DOWNLOAD control has open. */
sealed interface DownloadSheet {
    val subject: DownloadSubject

    /** The quality sheet. */
    data class Quality(
        override val subject: DownloadSubject,
    ) : DownloadSheet

    /** Pause / resume / cancel, or play / remove. */
    data class Status(
        override val subject: DownloadSubject,
    ) : DownloadSheet
}

/**
 * The downloads state of a page: which sheet is open, and the taps of its DOWNLOAD controls. A tap on DOWNLOAD
 * downloads at the default quality when the settings name one (else it opens the quality sheet); a long-press always
 * opens the sheet; a tap on a download in progress or done opens its status sheet.
 */
@Stable
class DownloadUi internal constructor(
    val viewModel: DownloadUiViewModel,
) {
    var sheet by mutableStateOf<DownloadSheet?>(null)
    internal var onStarted: (() -> Unit)? = null

    fun onTap(
        subject: DownloadSubject,
        status: DownloadStatus,
    ) {
        if (status != DownloadStatus.None) {
            sheet = DownloadSheet.Status(subject)
            return
        }
        val quality = viewModel.settings.value.defaultQuality
        // a show always asks how much of it
        if (quality == null || subject is DownloadSubject.Show) {
            sheet = DownloadSheet.Quality(subject)
        } else {
            val target = targetOf(subject, ShowScope.NEXT_3) ?: return
            viewModel.viewModelScope.launch {
                val result = viewModel.enqueue(target, quality)
                if (result.error != null) {
                    // what went wrong is said in the sheet
                    sheet = DownloadSheet.Quality(subject)
                } else {
                    onStarted?.invoke()
                }
            }
        }
    }

    fun onLongPress(subject: DownloadSubject) {
        sheet = DownloadSheet.Quality(subject)
    }
}

/**
 * The page's [DownloadUi], with its sheets: call it once in a phone layout. On the first download of this device it
 * asks for the notification permission (Android 13+); downloads run whatever the answer.
 */
@Composable
fun rememberDownloadUi(viewModel: DownloadUiViewModel = hiltViewModel()): DownloadUi {
    val ui = remember(viewModel) { DownloadUi(viewModel) }
    val askNotifications = rememberNotificationAsk(viewModel)
    ui.onStarted = askNotifications
    ui.sheet?.let { sheet ->
        when (sheet) {
            is DownloadSheet.Quality -> {
                DownloadQualitySheet(
                    subject = sheet.subject,
                    viewModel = viewModel,
                    onStarted = {
                        ui.sheet = null
                        askNotifications()
                    },
                    onDismiss = { ui.sheet = null },
                )
            }

            is DownloadSheet.Status -> {
                DownloadStatusSheet(
                    subject = sheet.subject,
                    viewModel = viewModel,
                    onDownloadMore = { ui.sheet = DownloadSheet.Quality(sheet.subject) },
                    onDismiss = { ui.sheet = null },
                )
            }
        }
    }
    return ui
}

/** Asks for POST_NOTIFICATIONS the first time a download starts on this device (Android 13+). */
@Composable
internal fun rememberNotificationAsk(viewModel: DownloadUiViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.i("Notification permission for downloads: %s", granted)
        }
    return remember(viewModel, launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.viewModelScope.launch {
                    if (viewModel.firstDownload()) {
                        try {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } catch (e: IllegalStateException) {
                            Timber.w(e, "Could not ask for the notification permission")
                        }
                    }
                }
            }
        }
    }
}

/** [subject]'s status, live. */
@Composable
fun DownloadUi.status(subject: DownloadSubject): DownloadStatus {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    return remember(entries, subject) { statusOf(subject, entries) }
}

/**
 * The DOWNLOAD button of a phone detail page's icon-over-label row: DOWNLOAD; while downloading a thin progress bar
 * with the percentage (PAUSED when paused); DOWNLOADED with a check when done. Tap and long-press as [DownloadUi].
 */
@Composable
fun DownloadUi.phoneAction(subject: DownloadSubject): PhoneAction {
    val status = status(subject)
    val onClick = { onTap(subject, status) }
    val onLongClick = { onLongPress(subject) }
    return when (status) {
        DownloadStatus.None -> {
            PhoneAction(
                key = "download",
                glyph = stringResource(R.string.fa_download),
                label = stringResource(R.string.tally_dlui_download),
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }

        is DownloadStatus.Active -> {
            PhoneAction(
                key = "download",
                glyph = stringResource(R.string.fa_download),
                label =
                    when {
                        status.failed -> stringResource(R.string.tally_dlui_failed)
                        status.paused -> stringResource(R.string.tally_dlui_paused)
                        status.queued -> stringResource(R.string.tally_dlui_queued)
                        else -> stringResource(R.string.tally_dlui_percent, status.percent)
                    },
                onClick = onClick,
                onLongClick = onLongClick,
                progress = status.progress,
                failed = status.failed,
            )
        }

        is DownloadStatus.Done -> {
            PhoneAction(
                key = "download",
                glyph = stringResource(R.string.fa_check),
                label = stringResource(R.string.tally_dlui_downloaded),
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
    }
}
