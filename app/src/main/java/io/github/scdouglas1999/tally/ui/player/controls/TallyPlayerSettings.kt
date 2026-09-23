package io.github.scdouglas1999.tally.ui.player.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.playback.PlaybackSettings
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackAction
import com.github.damontecres.wholphin.ui.playback.playbackScaleOptions
import com.github.damontecres.wholphin.ui.playback.playbackSpeedOptions
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.formatSleepClock
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.quality.QualityLadder
import io.github.scdouglas1999.tally.quality.TallyQuality
import io.github.scdouglas1999.tally.ui.TallyGlobalOverlaysViewModel
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.player.TallyPlayerMenu
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val PanelWidth = 360.dp

/** Upstream's subtitle delay steps (`SubtitleDelay`), smallest last. */
private val DelaySteps = listOf(1.seconds, 250.milliseconds, 50.milliseconds)

/** One row of the panel. [onClick] null means the row is shown but cannot be chosen (upstream grays it). */
private data class PanelRow(
    val key: Any,
    val label: String,
    val value: String? = null,
    val current: Boolean = false,
    val onClick: (() -> Unit)?,
)

/**
 * The player's settings as a Tally side panel (the gear, and the CC and audio buttons, which open their page
 * directly). Same parameters as upstream's `PlaybackDialog`, which hands over to this while the Tally theme is
 * selected. Pages follow upstream's dialog type: `SETTINGS` is the list; `AUDIO`, `CAPTIONS`, `PLAYBACK_SPEED`,
 * `VIDEO_SCALE` and `SUBTITLE_DELAY` replace it in the same panel. BACK on a page returns to the list, BACK on the
 * list closes. Choosing does exactly what upstream's dialog does; the Tally entries open their own dialogs.
 */
@Composable
fun TallyPlayerSettings(
    enableSubtitleDelay: Boolean,
    enableVideoScale: Boolean,
    type: PlaybackDialogType,
    settings: PlaybackSettings,
    onDismissRequest: () -> Unit,
    onControllerInteraction: () -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onChangeSubtitleDelay: (Duration) -> Unit,
) {
    val page =
        when (type) {
            PlaybackDialogType.SETTINGS,
            PlaybackDialogType.AUDIO,
            PlaybackDialogType.CAPTIONS,
            PlaybackDialogType.PLAYBACK_SPEED,
            PlaybackDialogType.VIDEO_SCALE,
            PlaybackDialogType.SUBTITLE_DELAY,
            -> type

            // DEBUG is an action, never a dialog (upstream throws); the Tally entries are drawn by TallyGlobalOverlays.
            else -> return
        }
    // The page the list was left for, so coming back puts focus on its row.
    var lastPage by remember { mutableStateOf<PlaybackDialogType?>(null) }
    SideEffect { if (page != PlaybackDialogType.SETTINGS) lastPage = page }

    val back = {
        if (page == PlaybackDialogType.SETTINGS) {
            onDismissRequest()
        } else {
            onClickPlaybackDialogType(PlaybackDialogType.SETTINGS)
        }
    }
    val rows =
        when (page) {
            PlaybackDialogType.SETTINGS -> {
                listRows(
                    settings = settings,
                    enableSubtitleDelay = enableSubtitleDelay,
                    enableVideoScale = enableVideoScale,
                    onDismissRequest = onDismissRequest,
                    onClickPlaybackDialogType = onClickPlaybackDialogType,
                    onPlaybackActionClick = onPlaybackActionClick,
                )
            }

            PlaybackDialogType.AUDIO -> {
                audioRows(settings) { index ->
                    onControllerInteraction()
                    onDismissRequest()
                    onPlaybackActionClick(PlaybackAction.ToggleAudio(index))
                }
            }

            PlaybackDialogType.CAPTIONS -> {
                subtitleRows(
                    settings = settings,
                    onChoose = { index ->
                        onDismissRequest()
                        onPlaybackActionClick(PlaybackAction.ToggleCaptions(index))
                    },
                    onSearch = {
                        onDismissRequest()
                        onPlaybackActionClick(PlaybackAction.SearchCaptions)
                    },
                )
            }

            PlaybackDialogType.PLAYBACK_SPEED -> {
                playbackSpeedOptions.map { option ->
                    val value = option.toFloat()
                    PanelRow(
                        key = option,
                        label = PlayerFormat.speed(value),
                        current = value == settings.playbackSpeed,
                    ) {
                        onControllerInteraction()
                        onDismissRequest()
                        onPlaybackActionClick(PlaybackAction.PlaybackSpeed(value))
                    }
                }
            }

            PlaybackDialogType.VIDEO_SCALE -> {
                playbackScaleOptions.map { (scale, name) ->
                    PanelRow(key = name, label = stringResource(name), current = scale == settings.contentScale) {
                        onControllerInteraction()
                        onDismissRequest()
                        onPlaybackActionClick(PlaybackAction.Scale(scale))
                    }
                }
            }

            else -> {
                delayRows(settings.subtitleDelay, onChangeSubtitleDelay)
            }
        }
    val kicker =
        when (page) {
            PlaybackDialogType.AUDIO -> R.string.tally_player_audio
            PlaybackDialogType.CAPTIONS -> R.string.tally_player_subtitles
            PlaybackDialogType.PLAYBACK_SPEED -> R.string.tally_player_speed
            PlaybackDialogType.VIDEO_SCALE -> R.string.tally_player_video_scale
            PlaybackDialogType.SUBTITLE_DELAY -> R.string.tally_player_subtitle_delay
            else -> R.string.tally_player_settings
        }
    val readout =
        if (page == PlaybackDialogType.SUBTITLE_DELAY) PlayerFormat.subtitleDelay(settings.subtitleDelay) else null
    val focusIndex =
        when {
            page == PlaybackDialogType.SETTINGS -> rows.indexOfFirst { it.key == lastPage }.coerceAtLeast(0)
            page == PlaybackDialogType.SUBTITLE_DELAY -> rows.indexOfFirst { it.key == "reset" }.coerceAtLeast(0)
            else -> rows.indexOfFirst { it.current }.coerceAtLeast(0)
        }

    Dialog(
        onDismissRequest = back,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            // The picture stays as it is; the panel brings its own scrim, on the right half only.
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        TallyScale {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.5f)
                            .background(Color.Black.copy(alpha = 0.4f)),
                )
                Panel(
                    kicker = stringResource(kicker),
                    readout = readout,
                    rows = rows,
                    focusIndex = focusIndex,
                    pageKey = page,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Panel(
    kicker: String,
    readout: String?,
    rows: List<PanelRow>,
    focusIndex: Int,
    pageKey: PlaybackDialogType,
    modifier: Modifier = Modifier,
) {
    val requesters = remember(pageKey, rows.size) { List(rows.size) { FocusRequester() } }
    LaunchedEffect(pageKey, rows.size) {
        val target = requesters.getOrNull(focusIndex) ?: return@LaunchedEffect
        repeat(8) {
            if (target.tryRequestFocus("tally-player-settings")) return@LaunchedEffect
            delay(40)
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .width(PanelWidth)
                .background(TallyColors.ground)
                .drawBehind {
                    val stroke = TallyDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.ruleStrong,
                        start = Offset(stroke / 2f, 0f),
                        end = Offset(stroke / 2f, size.height),
                        strokeWidth = stroke,
                    )
                }.padding(top = TallyDimens.marginVertical),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Text(
                text = kicker.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (readout != null) {
                Text(
                    text = readout.tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .padding(top = 16.dp - FocusEdge)
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(vertical = FocusEdge)
                        .focusGroup(),
            ) {
                val last = rows.lastIndex
                rows.forEachIndexed { index, row ->
                    TallyRow(
                        label = row.label,
                        onClick = row.onClick ?: {},
                        enabled = row.onClick != null,
                        modifier =
                            Modifier
                                .focusRequester(requesters[index])
                                .focusProperties {
                                    // Like upstream's grayed entries, a row that cannot be chosen is skipped.
                                    canFocus = row.onClick != null
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                    up = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
                                    down = if (index == last) FocusRequester.Cancel else FocusRequester.Default
                                },
                        trailing =
                            if (row.value != null || row.current) {
                                {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        if (row.value != null) {
                                            Text(
                                                text = row.value.tallyUppercase(),
                                                style = TallyType.label,
                                                color = TallyColors.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (row.current) {
                                            IndicatorSquare(color = TallyColors.accent, size = 8.dp)
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

/** The list: upstream's entries in upstream's order, then Tally's. */
@Composable
private fun listRows(
    settings: PlaybackSettings,
    enableSubtitleDelay: Boolean,
    enableVideoScale: Boolean,
    onDismissRequest: () -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
): List<PanelRow> {
    val streams =
        TallyQuality.nowPlaying
            .collectAsStateWithLifecycle()
            .value
            ?.mediaSourceInfo
            ?.mediaStreams
            .orEmpty()
    val choice by TallyQuality.choice.collectAsStateWithLifecycle()
    val overlays: TallyGlobalOverlaysViewModel = hiltViewModel()
    val sleep by overlays.sleepTimer.remaining.collectAsStateWithLifecycle()

    val audioValue =
        settings.audioIndex?.let { index ->
            streams
                .firstOrNull { it.type == MediaStreamType.AUDIO && it.index == index }
                ?.let { PlayerFormat.audioTrack(it) }
                ?: settings.audioStreams.firstOrNull { it.index == index }?.let { it.streamTitle ?: it.displayTitle }
        }
    val subtitleValue =
        when (val index = settings.subtitleIndex) {
            null, TrackIndex.DISABLED, TrackIndex.UNSPECIFIED -> {
                stringResource(R.string.tally_player_off)
            }

            TrackIndex.ONLY_FORCED -> {
                stringResource(R.string.tally_player_forced_only)
            }

            else -> {
                streams
                    .firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
                    ?.let { PlayerFormat.subtitleTrack(it) }
                    ?: settings.subtitleStreams.firstOrNull { it.index == index }?.let { it.streamTitle ?: it.displayTitle }
            }
        }
    val qualityValue =
        choice?.let { bits -> QualityLadder.options(null, null).firstOrNull { it.bitsPerSecond == bits }?.label }
            ?: stringResource(R.string.tally_player_original)
    val sleepValue =
        when {
            sleep == null -> stringResource(R.string.tally_player_off)
            sleep!!.isInfinite() -> stringResource(R.string.tally_player_until_end)
            else -> formatSleepClock(sleep!!)
        }
    val scaleName = playbackScaleOptions[settings.contentScale]?.let { stringResource(it) }
    val debugLabel = stringResource(if (settings.showDebugInfo) R.string.hide_debug_info else R.string.show_debug_info)
    val tally = { request: TallyPlayerMenu.Request ->
        TallyPlayerMenu.request.value = request
        onDismissRequest()
    }
    return buildList {
        if (settings.audioStreams.isNotEmpty()) {
            add(
                PanelRow(PlaybackDialogType.AUDIO, stringResource(R.string.tally_player_audio), audioValue) {
                    onClickPlaybackDialogType(PlaybackDialogType.AUDIO)
                },
            )
        }
        add(
            PanelRow(PlaybackDialogType.CAPTIONS, stringResource(R.string.tally_player_subtitles), subtitleValue) {
                onClickPlaybackDialogType(PlaybackDialogType.CAPTIONS)
            },
        )
        add(
            PanelRow(
                key = PlaybackDialogType.PLAYBACK_SPEED,
                label = stringResource(R.string.tally_player_speed),
                value = PlayerFormat.speed(settings.playbackSpeed),
                onClick =
                    if (settings.playbackSpeedEnabled) {
                        { onClickPlaybackDialogType(PlaybackDialogType.PLAYBACK_SPEED) }
                    } else {
                        null
                    },
            ),
        )
        if (enableVideoScale) {
            add(
                PanelRow(PlaybackDialogType.VIDEO_SCALE, stringResource(R.string.tally_player_video_scale), scaleName) {
                    onClickPlaybackDialogType(PlaybackDialogType.VIDEO_SCALE)
                },
            )
        }
        if (enableSubtitleDelay) {
            add(
                PanelRow(
                    PlaybackDialogType.SUBTITLE_DELAY,
                    stringResource(R.string.tally_player_subtitle_delay),
                    PlayerFormat.subtitleDelay(settings.subtitleDelay),
                ) {
                    onClickPlaybackDialogType(PlaybackDialogType.SUBTITLE_DELAY)
                },
            )
        }
        add(
            PanelRow(PlaybackDialogType.DEBUG, debugLabel) {
                onDismissRequest()
                onPlaybackActionClick(PlaybackAction.ShowDebug)
            },
        )
        add(
            PanelRow(PlaybackDialogType.TALLY_QUALITY, stringResource(R.string.tally_player_quality), qualityValue) {
                tally(TallyPlayerMenu.Request.QUALITY)
            },
        )
        add(
            PanelRow(PlaybackDialogType.TALLY_SLEEP_TIMER, stringResource(R.string.tally_player_sleep_timer), sleepValue) {
                tally(TallyPlayerMenu.Request.SLEEP_TIMER)
            },
        )
        add(
            PanelRow(PlaybackDialogType.TALLY_TOGETHER, stringResource(R.string.tally_player_together)) {
                tally(TallyPlayerMenu.Request.TOGETHER)
            },
        )
        add(
            PanelRow(PlaybackDialogType.TALLY_SEND_TO, stringResource(R.string.tally_player_send_to)) {
                tally(TallyPlayerMenu.Request.SEND_TO)
            },
        )
    }
}

@Composable
private fun audioRows(
    settings: PlaybackSettings,
    onChoose: (Int) -> Unit,
): List<PanelRow> {
    val streams =
        TallyQuality.nowPlaying
            .collectAsStateWithLifecycle()
            .value
            ?.mediaSourceInfo
            ?.mediaStreams
            .orEmpty()
    return settings.audioStreams.map { stream ->
        val full = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == stream.index }
        PanelRow(
            key = stream.index,
            label = stream.streamTitle ?: full?.languageName() ?: stream.displayTitle,
            value = full?.let { PlayerFormat.audioTrack(it) },
            current = stream.index == settings.audioIndex,
        ) { onChoose(stream.index) }
    }
}

@Composable
private fun subtitleRows(
    settings: PlaybackSettings,
    onChoose: (Int) -> Unit,
    onSearch: () -> Unit,
): List<PanelRow> {
    val streams =
        TallyQuality.nowPlaying
            .collectAsStateWithLifecycle()
            .value
            ?.mediaSourceInfo
            ?.mediaStreams
            .orEmpty()
    val current = settings.subtitleIndex
    return buildList {
        add(
            PanelRow(
                key = TrackIndex.DISABLED,
                label = stringResource(R.string.tally_player_subtitles_off),
                current = current == TrackIndex.DISABLED,
            ) { onChoose(TrackIndex.DISABLED) },
        )
        add(
            PanelRow(
                key = TrackIndex.ONLY_FORCED,
                label = stringResource(R.string.tally_player_subtitles_forced),
                current = current == TrackIndex.ONLY_FORCED,
            ) { onChoose(TrackIndex.ONLY_FORCED) },
        )
        settings.subtitleStreams.forEach { stream ->
            val full = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == stream.index }
            add(
                PanelRow(
                    key = stream.index,
                    label = stream.streamTitle ?: full?.languageName() ?: stream.displayTitle,
                    value = full?.let { PlayerFormat.subtitleTrack(it) },
                    current = stream.index == current,
                ) { onChoose(stream.index) },
            )
        }
        add(
            PanelRow(
                key = "search",
                label = stringResource(R.string.tally_player_subtitles_search),
                onClick = if (settings.hasSubtitleDownloadPermission) onSearch else null,
            ),
        )
    }
}

/** Upstream's subtitle delay buttons as rows: later at the top, earlier at the bottom, Reset between. */
@Composable
private fun delayRows(
    delay: Duration,
    onChange: (Duration) -> Unit,
): List<PanelRow> =
    buildList {
        DelaySteps.forEach { step -> add(PanelRow("+$step", PlayerFormat.subtitleDelay(step)) { onChange(step) }) }
        add(PanelRow("reset", stringResource(R.string.tally_player_delay_reset)) { onChange(-delay) })
        DelaySteps.reversed().forEach { step -> add(PanelRow("-$step", PlayerFormat.subtitleDelay(-step)) { onChange(-step) }) }
    }

/** `English`, `Spanish` for a stream's language code, or null. */
private fun MediaStream.languageName(): String? {
    val code = language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.US)
    return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
}
