package io.github.scdouglas1999.tally.ui.settings

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup
import io.github.scdouglas1999.tally.downloads.DownloadQuality
import io.github.scdouglas1999.tally.downloads.DownloadRung
import io.github.scdouglas1999.tally.downloads.StorageLocation
import io.github.scdouglas1999.tally.downloads.ui.DownloadUiViewModel
import io.github.scdouglas1999.tally.downloads.ui.downloadsEnabled
import io.github.scdouglas1999.tally.downloads.ui.formatBytes
import io.github.scdouglas1999.tally.support.TallySupport
import io.github.scdouglas1999.tally.support.TallySupportDialog
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import kotlinx.coroutines.launch

/**
 * Tally's own rows in Settings (seams in upstream's `PreferencesContent`): on a phone a Downloads section after Next
 * up (default quality, Wi-Fi only, simultaneous downloads, delete watched episodes, storage location when a card is
 * in, remove all downloads), and on every device the About group moved to the very bottom with Support Tally after
 * the version rows. The rows are markers in upstream's list; [Row] draws them with the Tally rows.
 */
object TallyExtraSettings {
    val DefaultQuality = AppClickablePreference<AppPreferences>(title = R.string.tally_dlui_set_quality)
    val WifiOnly = AppClickablePreference<AppPreferences>(title = R.string.tally_dlui_set_wifi)
    val Simultaneous = AppClickablePreference<AppPreferences>(title = R.string.tally_dlui_set_simultaneous)
    val DeleteWatched = AppClickablePreference<AppPreferences>(title = R.string.tally_dlui_set_delete_watched)
    val Location = AppClickablePreference<AppPreferences>(title = R.string.tally_dlui_set_location)
    val RemoveAll = AppClickablePreference<AppPreferences>(title = R.string.tally_dlui_set_remove_all)
    val Support =
        AppClickablePreference<AppPreferences>(title = R.string.tally_support_title, summary = R.string.tally_support_summary)

    private val downloadsGroup =
        PreferenceGroup(
            title = R.string.tally_dl_page_title,
            preferences = listOf(DefaultQuality, WifiOnly, Simultaneous, DeleteWatched, Location, RemoveAll),
        )

    /**
     * Upstream's main settings as Tally lays them out: the Downloads section after Next up on a phone, and About
     * (with Support Tally) at the very bottom.
     */
    fun basicGroups(
        upstream: List<PreferenceGroup<AppPreferences>>,
        context: Context,
    ): List<PreferenceGroup<AppPreferences>> {
        val phone = TallyFormFactor.of(context) == TallyFormFactor.PHONE && downloadsEnabled(context)
        val about = upstream.firstOrNull { it.title == R.string.about }
        return buildList {
            upstream.filter { it !== about }.forEach { group ->
                add(group)
                if (phone && group.title == R.string.next_up) add(downloadsGroup)
            }
            add(
                about?.copy(preferences = about.preferences + Support)
                    ?: PreferenceGroup(title = R.string.about, preferences = listOf(Support)),
            )
        }
    }

    /** Draws [preference] when it is one of Tally's rows; false for upstream's own. */
    @Composable
    fun Row(
        preference: AppPreference<*, *>,
        interactionSource: MutableInteractionSource,
        modifier: Modifier,
    ): Boolean {
        when (preference) {
            Support -> {
                SupportRow(interactionSource, modifier)
            }

            DefaultQuality, WifiOnly, Simultaneous, DeleteWatched, Location, RemoveAll -> {
                DownloadRow(
                    preference,
                    interactionSource,
                    modifier,
                )
            }

            else -> {
                return false
            }
        }
        return true
    }
}

@Composable
private fun SupportRow(
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var showCode by remember { mutableStateOf(false) }
    TallyClickPreference(
        title = stringResource(R.string.tally_support_title),
        summary = stringResource(R.string.tally_support_summary),
        onClick = {
            // a phone opens the page in the browser; a TV has none, so it shows the address as a QR code
            if (isPhoneContext(context)) TallySupport.openInBrowser(context) else showCode = true
        },
        interactionSource = interactionSource,
        modifier = modifier,
    )
    if (showCode) TallySupportDialog(onDismiss = { showCode = false })
}

private fun isPhoneContext(context: Context) = TallyFormFactor.of(context) == TallyFormFactor.PHONE

/** The hours of "Delete watched episodes": off, a day, a week. */
private val DeleteAfterHours = listOf(0, 24, 24 * 7)

@Composable
private fun DownloadRow(
    preference: AppPreference<*, *>,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
) {
    val viewModel: DownloadUiViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val downloads = viewModel.downloads
    val title = stringResource(preference.title)

    fun save(block: suspend () -> Unit) {
        viewModel.viewModelScope.launch { block() }
    }
    when (preference) {
        TallyExtraSettings.DefaultQuality -> {
            val qualities: List<DownloadQuality?> =
                listOf(null, DownloadQuality.Original) + DownloadRung.entries.map { DownloadQuality.Converted(it) }
            val labels =
                listOf(stringResource(R.string.tally_dlui_ask_each_time), stringResource(R.string.tally_dlui_original)) +
                    DownloadRung.entries.map { it.label.substringBefore(" · ") }
            TallyChoicePreference(
                title = title,
                summary = null,
                possibleValues = labels,
                selectedIndex = qualities.indexOf(settings.defaultQuality).coerceAtLeast(0),
                onValueChange = { index -> save { downloads.setDefaultQuality(qualities[index]) } },
                interactionSource = interactionSource,
                modifier = modifier,
            )
        }

        TallyExtraSettings.WifiOnly -> {
            TallySwitchPreference(
                title = title,
                value = settings.wifiOnly,
                onClick = { save { downloads.setWifiOnly(!settings.wifiOnly) } },
                summary = stringResource(R.string.tally_dlui_set_wifi_summary),
                interactionSource = interactionSource,
                modifier = modifier,
            )
        }

        TallyExtraSettings.Simultaneous -> {
            val counts = listOf(1, 2, 3)
            TallyChoicePreference(
                title = title,
                summary = null,
                possibleValues = counts.map { it.toString() },
                selectedIndex = counts.indexOf(settings.concurrentDownloads).coerceAtLeast(0),
                onValueChange = { index -> save { downloads.setConcurrentDownloads(counts[index]) } },
                interactionSource = interactionSource,
                modifier = modifier,
            )
        }

        TallyExtraSettings.DeleteWatched -> {
            val labels =
                listOf(
                    stringResource(R.string.tally_dlui_off),
                    stringResource(R.string.tally_dlui_after_day),
                    stringResource(R.string.tally_dlui_after_week),
                )
            TallyChoicePreference(
                title = title,
                summary = null,
                possibleValues = labels,
                selectedIndex = DeleteAfterHours.indexOf(settings.autoDeleteWatchedAfterHours ?: 0).coerceAtLeast(0),
                onValueChange = { index -> save { downloads.setAutoDeleteWatchedAfterHours(DeleteAfterHours[index]) } },
                interactionSource = interactionSource,
                modifier = modifier,
            )
        }

        TallyExtraSettings.Location -> {
            // only when a storage card is in
            if (storage?.removableAvailable != true) return
            val locations = listOf(StorageLocation.INTERNAL, StorageLocation.REMOVABLE)
            TallyChoicePreference(
                title = title,
                summary = null,
                possibleValues =
                    listOf(stringResource(R.string.tally_dlui_this_device), stringResource(R.string.tally_dlui_sd_card)),
                selectedIndex = locations.indexOf(settings.location).coerceAtLeast(0),
                onValueChange = { index -> save { downloads.setLocation(locations[index]) } },
                interactionSource = interactionSource,
                modifier = modifier,
            )
        }

        TallyExtraSettings.RemoveAll -> {
            var confirm by remember { mutableStateOf(false) }
            val used = storage?.usedBytes ?: 0L
            TallyClickPreference(
                title = title,
                summary = stringResource(R.string.tally_dlui_set_remove_all_summary, formatBytes(used)),
                onClick = { confirm = true },
                interactionSource = interactionSource,
                modifier = modifier,
            )
            if (confirm) {
                TallyConfirmDialog(
                    title = stringResource(R.string.tally_dlui_remove_all_q),
                    body = stringResource(R.string.tally_dlui_remove_all_body, formatBytes(used)),
                    onCancel = { confirm = false },
                    onConfirm = {
                        confirm = false
                        viewModel.deleteAll()
                    },
                )
            }
        }

        else -> {}
    }
}
