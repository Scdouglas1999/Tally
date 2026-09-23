package io.github.scdouglas1999.tally.quality

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Ignore the key-up that opened the dialog so it does not activate the focused row. */
private const val OPEN_GRACE_MS = 400L

/** Room inside the scrolling list for a focused row's border, so the list's clip never cuts it. */
private val FOCUS_ROOM = TallyDimens.focusBorder + 1.dp

/**
 * Height of the scrolling list: six and a half row slots (a 58dp row plus 2 x FOCUS_ROOM), so a seventh row
 * peeks out when there are more. With the header and key bar the panel stays well inside a 1080p screen.
 */
private val LIST_MAX_HEIGHT = 416.dp

/**
 * In-player quality picker. Same panel family as the sleep timer: centered over a 60% scrim, focus
 * trapped, BACK closes, focus starts on the current choice.
 */
@Composable
fun QualityDialog(
    onDismiss: () -> Unit,
    viewModel: QualityDialogViewModel = hiltViewModel(),
) {
    val playback by TallyQuality.nowPlaying.collectAsStateWithLifecycle()
    val choice by TallyQuality.choice.collectAsStateWithLifecycle()
    val now = QualityStatus.now(playback)
    val options =
        QualityLadder.options(
            QualityStatus.sourceHeight(playback),
            QualityStatus.sourceBitrate(playback),
        )
    var saveAsDefault by remember { mutableStateOf(false) }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(playback?.playMethod, now?.resolution, now?.bitrateLabel) {
        Timber.i(
            "Quality dialog sourceHeight=%s sourceBitrate=%s choice=%s now=%s reasons=%s",
            QualityStatus.sourceHeight(playback),
            QualityStatus.sourceBitrate(playback),
            choice,
            now,
            now?.reasons,
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.6f)
        }
        TallyScale {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                QualityPanel(
                    now = now,
                    options = options,
                    choice = choice,
                    saveAsDefault = saveAsDefault,
                    onToggleDefault = { saveAsDefault = !saveAsDefault },
                    onChoose = choose@{ bits, megabits ->
                        if (SystemClock.elapsedRealtime() - openedAt < OPEN_GRACE_MS) return@choose
                        viewModel.choose(bits, megabits, saveAsDefault, onDismiss)
                    },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun QualityPanel(
    now: QualityStatus.Playing?,
    options: List<QualityOption>,
    choice: Int?,
    saveAsDefault: Boolean,
    onToggleDefault: () -> Unit,
    onChoose: (bitsPerSecond: Int?, megabits: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndex = options.indexOfFirst { it.bitsPerSecond == choice }.let { if (it < 0) 0 else it }
    val rowCount = options.size + 1
    val requesters = remember(options.map { it.bitsPerSecond }) { List(rowCount) { FocusRequester() } }
    val bringers = remember(options.map { it.bitsPerSecond }) { List(rowCount) { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedIndex, options.map { it.bitsPerSecond }) {
        val target = requesters.getOrNull(selectedIndex) ?: return@LaunchedEffect
        repeat(8) {
            if (target.tryRequestFocus("quality")) return@LaunchedEffect
            delay(40)
        }
    }
    val methodLabel =
        when (now?.method) {
            null -> stringResource(R.string.tally_quality_loading)
            QualityStatus.Method.DIRECT_PLAY -> stringResource(R.string.tally_quality_direct_play)
            QualityStatus.Method.DIRECT_STREAM -> stringResource(R.string.tally_quality_direct_stream)
            QualityStatus.Method.TRANSCODING -> stringResource(R.string.tally_quality_transcoding)
        }
    val nowLine =
        if (now == null) {
            methodLabel
        } else {
            QualityStatus.formatNowLine(methodLabel, now.resolution, now.bitrateLabel)
        }
    val reasonLine =
        now
            ?.reasons
            ?.map { reason ->
                QualityStatus.reasonRes(reason)?.let { stringResource(it) }
                    ?: QualityStatus.enumWords(reason.name)
            }?.distinct()
            ?.joinToString(", ")
            .orEmpty()
    Column(
        modifier =
            Modifier
                .width(640.dp)
                .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                .background(TallyColors.ground, RectangleShape)
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.Back, Key.Escape, Key.ButtonB -> {
                            if (event.type == KeyEventType.KeyUp) onDismiss()
                            true
                        }

                        Key.DirectionLeft, Key.DirectionRight -> {
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.focusGroup()
                .focusProperties { onExit = { cancelFocusChange() } },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 12.dp - FOCUS_ROOM),
        ) {
            Text(
                text = stringResource(R.string.tally_quality_kicker),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = nowLine,
                style = TallyType.label,
                color = TallyColors.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
            )
            if (reasonLine.isNotEmpty()) {
                Text(
                    text = reasonLine,
                    style = TallyType.hint.copy(fontSize = 15.sp),
                    color = TallyColors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 6.dp),
                )
            }
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 12.dp - FOCUS_ROOM)
                    .fillMaxWidth()
                    .height(TallyDimens.hairline)
                    .background(TallyColors.rule),
            )
            // Six and a half rows, then the list scrolls inside the panel; the half row shows there is more.
            // Each row sits in a slot with FOCUS_ROOM around it: the slot is what is scrolled into view, so the
            // focused row's whole border stays visible, and the scroll clip never cuts it.
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = 20.dp - FOCUS_ROOM)
                        .heightIn(max = LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = FOCUS_ROOM),
            ) {
                val last = rowCount - 1
                options.forEachIndexed { index, option ->
                    val selected = option.bitsPerSecond == choice
                    RowSlot(bringers[index]) {
                        QualityChoiceRow(
                            label = option.label,
                            selected = selected,
                            nowTag = if (selected) stringResource(R.string.tally_quality_now) else null,
                            onClick = {
                                onChoose(option.bitsPerSecond, option.megabits)
                            },
                            onFocused = { scope.launch { bringers[index].bringIntoView() } },
                            modifier =
                                Modifier
                                    .focusRequester(requesters[index])
                                    .rowFocus(index, last),
                        )
                    }
                }
                RowSlot(bringers[last]) {
                    QualityChoiceRow(
                        label = stringResource(R.string.tally_quality_default),
                        selected = saveAsDefault,
                        nowTag =
                            stringResource(
                                if (saveAsDefault) R.string.tally_quality_on else R.string.tally_quality_off,
                            ),
                        onClick = onToggleDefault,
                        onFocused = { scope.launch { bringers[last].bringIntoView() } },
                        modifier =
                            Modifier
                                .focusRequester(requesters[last])
                                .rowFocus(last, last),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(TallyColors.labelBar)
                    .drawBehind {
                        val stroke = TallyDimens.hairline.toPx()
                        drawLine(
                            color = TallyColors.rule,
                            start = Offset(0f, stroke / 2f),
                            end = Offset(size.width, stroke / 2f),
                            strokeWidth = stroke,
                        )
                    }.padding(horizontal = 14.dp),
        ) {
            KeyHint(
                key = stringResource(R.string.tally_quality_key_back),
                label = stringResource(R.string.tally_quality_close),
            )
        }
    }
}

/** A row plus FOCUS_ROOM above and below (rows end up 2 x FOCUS_ROOM apart); scrolled into view whole. */
@Composable
private fun RowSlot(
    bringer: BringIntoViewRequester,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .bringIntoViewRequester(bringer)
                .padding(vertical = FOCUS_ROOM),
    ) {
        content()
    }
}

@Composable
private fun QualityChoiceRow(
    label: String,
    selected: Boolean,
    nowTag: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TallyRow(
        label = label,
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier,
        trailing = {
            if (selected || nowTag != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (selected) {
                        IndicatorSquare(color = TallyColors.accent, size = 8.dp)
                    }
                    if (nowTag != null) {
                        Text(
                            text = nowTag,
                            style = TallyType.label,
                            color = TallyColors.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
}

private fun Modifier.rowFocus(
    index: Int,
    last: Int,
): Modifier =
    focusProperties {
        start = FocusRequester.Cancel
        end = FocusRequester.Cancel
        left = FocusRequester.Cancel
        right = FocusRequester.Cancel
        up = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
        down = if (index == last) FocusRequester.Cancel else FocusRequester.Default
    }

@HiltViewModel
class QualityDialogViewModel
    @Inject
    constructor(
        private val dataStore: DataStore<AppPreferences>,
    ) : ViewModel() {
        fun choose(
            bitsPerSecond: Int?,
            megabits: Int?,
            saveAsDefault: Boolean,
            onDone: () -> Unit,
        ) {
            viewModelScope.launch {
                if (saveAsDefault) {
                    try {
                        val index = maxBitratePreferenceIndex(megabits)
                        dataStore.updateData { prefs ->
                            AppPreference.MaxBitrate.setter(prefs, index)
                        }
                        Timber.i("Quality saved as default index=%s megabits=%s", index, megabits)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Could not save quality as the default bitrate")
                    }
                }
                Timber.i("Quality choose bps=%s", bitsPerSecond)
                TallyQuality.choose(bitsPerSecond)
                onDone()
            }
        }
    }
