package io.github.scdouglas1999.tally.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.ui.handleDPadKeyEvents
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** Settings row height at the Tally scale. */
private val ROW_HEIGHT = 48.dp

/** A long current value ("Direct play with libass") ellipsizes here rather than squeezing the title. */
private val VALUE_MAX_WIDTH = 184.dp

/** Space above and below each row, so neighboring frames do not merge into a double line. */
private val ROW_GAP = 3.dp

internal val prefTitleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

internal val prefSummaryStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )

internal val prefValueStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )

private const val CHEVRON = "›"
private const val CHEVRON_BACK = "‹"

// ---------------------------------------------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------------------------------------------

/** What a choice row shows: the current choice at the right and, when it says something else, a summary. */
internal data class ChoiceDisplay(
    val value: String?,
    val summary: String?,
)

/**
 * Upstream hands a choice row one `summary`, which is usually the current choice ("1080p") and sometimes a
 * description or a richer form of it ("Use user profile - Default"). The choice goes at the right; a summary that
 * adds something stays under the title.
 */
internal fun choiceDisplay(
    summary: String?,
    current: String?,
): ChoiceDisplay {
    val cleanSummary = summary?.takeIf { it.isNotBlank() }
    val cleanCurrent = current?.takeIf { it.isNotBlank() }
    return when {
        cleanCurrent == null -> ChoiceDisplay(value = cleanSummary, summary = null)
        cleanSummary == null || cleanSummary == cleanCurrent -> ChoiceDisplay(value = cleanCurrent, summary = null)
        cleanSummary.startsWith(cleanCurrent) -> ChoiceDisplay(value = cleanSummary, summary = null)
        else -> ChoiceDisplay(value = cleanCurrent, summary = cleanSummary)
    }
}

/**
 * A switch row already says ON or OFF, so upstream's summaries that only restate that ("Enabled", "Disabled",
 * "Show", "Hide": [restatements]) are left out; a summary that explains something stays.
 */
internal fun switchSummary(
    summary: String?,
    restatements: Set<String>,
): String? = summary?.takeIf { it.isNotBlank() && it !in restatements }

/** A slider step as upstream's SliderBar takes it: clamped to [min, max], no wrap-around. */
internal fun sliderStep(
    current: Long,
    min: Long,
    max: Long,
    interval: Int,
    forward: Boolean,
): Long =
    if (forward) {
        (current + interval).coerceAtMost(max)
    } else {
        (current - interval).coerceAtLeast(min)
    }

// ---------------------------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------------------------

/**
 * The settings row: title (Sans Medium 17sp) with an optional muted summary under it, a trailing value at the
 * right. Focused: groundRaised and the 3dp accent border; nothing moves.
 */
@Composable
private fun PreferenceRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    TallyScale {
        Box(Modifier.padding(vertical = ROW_GAP)) {
            Surface(
                onClick = onClick,
                onLongClick = onLongClick,
                shape = ClickableSurfaceDefaults.shape(RectangleShape),
                scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
                colors = rowColors(),
                border = rowBorder(),
                glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
                interactionSource = interactionSource,
                modifier = modifier.fillMaxWidth(),
            ) {
                RowBody(title = title, summary = summary, trailing = trailing)
            }
        }
    }
}

/** Title, summary and trailing value, vertically centered in the 48dp row. */
@Composable
private fun RowBody(
    title: String,
    summary: String?,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    // tv-material3 Surface lays its content out top-start: the Row sets the height and centers its children.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // Two lines before an ellipsis: the title says what the row is, so it wins over a long value.
            Text(
                text = title,
                style = prefTitleStyle,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = prefSummaryStyle,
                    color = TallyColors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (extra != null) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                    ProvideTextStyle(prefSummaryStyle) { extra() }
                }
            }
        }
        trailing()
    }
}

@Composable
private fun ValueText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = prefValueStyle,
        color = TallyColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(max = VALUE_MAX_WIDTH),
    )
}

@Composable
private fun Chevron(text: String = CHEVRON) {
    Text(text = text, style = prefValueStyle, color = TallyColors.muted, maxLines = 1)
}

/** The square switch: a 36x18 track (rule off, accent on) with a 14dp square thumb, and ON/OFF before it. */
@Composable
internal fun TallySquareSwitch(checked: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(if (checked) R.string.tally_prefs_on else R.string.tally_prefs_off),
            style = TallyType.label,
            color = if (checked) TallyColors.text else TallyColors.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            // Plex Mono capitals sit low in their line box: lift them to the track's center, as TallyButton does.
            modifier = Modifier.width(36.dp).offset(y = (-1).dp),
        )
        Box(
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            modifier =
                Modifier
                    .size(width = 36.dp, height = 18.dp)
                    .background(if (checked) TallyColors.accent else TallyColors.rule, RectangleShape)
                    .padding(2.dp),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (checked) TallyColors.onAccent else TallyColors.muted, RectangleShape),
            )
        }
    }
}

/** Tally [com.github.damontecres.wholphin.ui.preferences.SwitchPreference]. */
@Composable
fun TallySwitchPreference(
    title: String,
    value: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val restatements =
        setOf(
            stringResource(R.string.enabled),
            stringResource(R.string.disabled),
            stringResource(R.string.show),
            stringResource(R.string.hide),
        )
    PreferenceRow(
        title = title,
        summary = switchSummary(summary, restatements),
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        TallySquareSwitch(checked = value)
    }
}

/** Tally [com.github.damontecres.wholphin.ui.preferences.ClickPreference]: a `›` at the right. */
@Composable
fun TallyClickPreference(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    PreferenceRow(
        title = title,
        summary = summary,
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        Chevron()
    }
}

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.ChoicePreference]: the current choice and `›` at the
 * right; OK opens the choice panel with the current choice marked and focused.
 */
@Composable
fun <T> TallyChoicePreference(
    title: String,
    summary: String?,
    possibleValues: List<T>,
    selectedIndex: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    valueDisplay: @Composable (index: Int, item: T) -> Unit = { _, item -> Text(item.toString()) },
    subtitleDisplay: (index: Int, item: T) -> @Composable (() -> Unit)? = { _, _ -> null },
) {
    var showDialog by remember { mutableStateOf(false) }
    val display = choiceDisplay(summary, possibleValues.getOrNull(selectedIndex) as? String)
    PreferenceRow(
        title = title,
        summary = display.summary,
        onClick = { showDialog = true },
        onLongClick = null,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (display.value != null) ValueText(display.value)
            Chevron()
        }
    }
    if (showDialog) {
        TallyChoiceDialog(
            title = title,
            count = possibleValues.size,
            selectedIndex = selectedIndex,
            onChoose = { index ->
                onValueChange(index)
                showDialog = false
            },
            onDismissRequest = { showDialog = false },
            label = { index -> valueDisplay(index, possibleValues[index]) },
            supporting = { index -> subtitleDisplay(index, possibleValues[index]) },
        )
    }
}

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.MultiChoicePreference]: OK opens a panel of ON/OFF rows;
 * each change applies at once, as upstream's does.
 */
@Composable
fun <T> TallyMultiChoicePreference(
    title: String,
    summary: String?,
    possibleValues: List<T>,
    selectedValues: Set<T>,
    onValueChange: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    valueDisplay: @Composable (index: Int, item: T) -> Unit = { _, item -> Text(item.toString()) },
    subtitleDisplay: @Composable (index: Int, item: T) -> Unit = { _, _ -> },
) {
    val selected =
        remember {
            mutableStateSetOf<T>().apply { addAll(selectedValues) }
        }
    var showDialog by remember { mutableStateOf(false) }
    PreferenceRow(
        title = title,
        summary = summary,
        onClick = { showDialog = true },
        onLongClick = null,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        Chevron()
    }
    if (showDialog) {
        val entries =
            possibleValues.mapIndexed { index, item ->
                PanelEntry.Item(
                    onClick = {
                        if (!selected.remove(item)) selected.add(item)
                        onValueChange(selected.toList())
                    },
                    headline = { valueDisplay(index, item) },
                    supporting = { subtitleDisplay(index, item) },
                    trailing = { TallySquareSwitch(checked = item in selected) },
                )
            }
        TallyPanelWindow(onDismissRequest = { showDialog = false }) {
            TallyListPanel(
                title = title,
                entries = entries,
                onBack = { showDialog = false },
                initialIndex = 0,
            )
        }
    }
}

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.SliderPreference]: the value between `‹ ›` at the right;
 * LEFT/RIGHT step it and save each step, OK saves, as upstream's SliderBar does.
 */
@Composable
fun TallySliderPreference(
    preference: AppSliderPreference<*>,
    title: String,
    summary: String?,
    value: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    additionalSummary: @Composable (ColumnScope.() -> Unit)? = null,
) {
    var current by remember(value) { mutableLongStateOf(value) }

    fun step(forward: Boolean) {
        current = sliderStep(current, preference.min, preference.max, preference.interval, forward)
        onChange(current)
    }
    TallyScale {
        Box(Modifier.padding(vertical = ROW_GAP)) {
            // Same Surface as the other rows (so the focus frame is drawn identically); OK saves, as upstream's
            // SliderBar does, and LEFT/RIGHT are taken before focus can move.
            Surface(
                onClick = { onChange(current) },
                shape = ClickableSurfaceDefaults.shape(RectangleShape),
                scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
                colors = rowColors(),
                border = rowBorder(),
                glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
                interactionSource = interactionSource,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .handleDPadKeyEvents(
                            triggerOnAction = KeyEvent.ACTION_DOWN,
                            onLeft = { step(forward = false) },
                            onRight = { step(forward = true) },
                        ),
            ) {
                RowBody(title = title, summary = null, extra = additionalSummary) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Chevron(CHEVRON_BACK)
                        ValueText(summary ?: current.toString())
                        Chevron()
                    }
                }
            }
        }
    }
}
