package io.github.scdouglas1999.tally.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.downloads.DownloadEntry
import io.github.scdouglas1999.tally.downloads.DownloadState
import io.github.scdouglas1999.tally.downloads.TallyDownloads
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class DownloadsPageViewModel
    @Inject
    constructor(
        private val downloads: TallyDownloads,
        private val navigationManager: NavigationManager,
        private val musicService: MusicService,
    ) : ViewModel() {
        val entries = downloads.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        val offline = downloads.offlineMode

        /** A film or episode opens the player at its position; a track plays with the album's downloaded tracks after it. */
        fun play(entry: DownloadEntry) {
            if (entry.state != DownloadState.Done) return
            viewModelScope.launch {
                if (entry.type == BaseItemKind.AUDIO) {
                    val album =
                        entries.value.filter {
                            it.type == BaseItemKind.AUDIO && it.state == DownloadState.Done && it.albumId == entry.albumId
                        }
                    val start = album.indexOfFirst { it.itemId == entry.itemId }.coerceAtLeast(0)
                    val items = album.drop(start).mapNotNull { downloads.localItem(it.itemId) }.map { BaseItem(it, false) }
                    musicService.setQueue(items, false)
                    navigationManager.navigateTo(Destination.NowPlaying)
                } else {
                    navigationManager.navigateTo(Destination.Playback(entry.itemId, entry.positionMs))
                }
            }
        }
    }

/**
 * The downloads destination (also where an offline start lands). Deliberately minimal: a list with each download's
 * state; a completed one plays on a tap. The downloads UI task replaces this page.
 */
@Composable
fun DownloadsPage(
    modifier: Modifier = Modifier,
    viewModel: DownloadsPageViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val phone = LocalTallyFormFactor.current == TallyFormFactor.PHONE
    val bottom = if (phone) LocalPhoneContentPadding.current else PaddingValues(0.dp)
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = stringResource(R.string.tally_dl_page_title),
            kicker = if (offline) stringResource(R.string.tally_dl_page_offline) else null,
        )
        LazyColumn(
            contentPadding = bottom,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tally_dl_page_empty),
                        style = PhoneType.body,
                        color = TallyColors.textSecondary,
                        modifier = Modifier.padding(PhoneDimens.margin),
                    )
                }
            }
            items(entries, key = { it.itemId }) { entry ->
                DownloadRow(entry = entry, onClick = { viewModel.play(entry) })
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .drawBehind {
                    drawLine(TallyColors.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }.padding(horizontal = PhoneDimens.margin, vertical = 10.dp),
    ) {
        val heading =
            if (entry.seriesName != null && entry.episodeNumber != null) {
                "${entry.seriesName} · S${entry.seasonNumber ?: 0}E${entry.episodeNumber} · ${entry.title}"
            } else {
                listOfNotNull(entry.title, entry.album?.takeIf { entry.type == BaseItemKind.AUDIO }).joinToString(" · ")
            }
        Text(
            text = heading,
            style = PhoneType.headline,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${entry.qualityLabel} · ${stateText(entry.state)}",
            style = PhoneType.meta,
            color = if (entry.state is DownloadState.Failed) TallyColors.liveText else TallyColors.muted,
            maxLines = 2,
        )
    }
}

@Composable
private fun stateText(state: DownloadState): String =
    when (state) {
        DownloadState.Done -> stringResource(R.string.tally_dl_state_done)
        is DownloadState.Downloading -> stringResource(R.string.tally_dl_state_downloading, (state.progress * 100).roundToInt())
        is DownloadState.Paused -> stringResource(R.string.tally_dl_state_paused)
        is DownloadState.Queued -> state.waitingFor ?: stringResource(R.string.tally_dl_state_queued)
        is DownloadState.Failed -> state.reason
    }
