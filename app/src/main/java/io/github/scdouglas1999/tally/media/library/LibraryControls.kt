package io.github.scdouglas1999.tally.media.library

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.CommunityRatingFilter
import com.github.damontecres.wholphin.data.filter.FavoriteFilter
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.filter.PlayedFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsType
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.getStringRes
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.kit.CapsLift
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/** Height of the header controls: the tab strip's height, so labels line up across the strip. */
internal val ControlHeight = 40.dp

/** The mono label at the low end of the letter-spacing range: the strip has to hold tabs, count and controls. */
private val ControlLabelStyle = TallyType.label.copy(letterSpacing = 1.5.sp)

/**
 * A secondary control in the header strip: square, hairline `ruleStrong`, mono uppercase label; focused = 3dp
 * accent border. The kit's `TallyButton`, plus the long press upstream's sort button has (reverse the order).
 */
@Composable
internal fun LibraryControlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    suffix: String? = null,
    maxLabelWidth: Dp = Dp.Unspecified,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors = controlColors(),
        border = controlBorder(),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier.height(ControlHeight),
    ) {
        // tv-material3 Surface lays its content out top-start: fill the height so the label is centered.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
        ) {
            val color = if (enabled) TallyColors.text else TallyColors.muted
            Text(
                text = label.tallyUppercase(),
                style = ControlLabelStyle,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxLabelWidth).offset(y = CapsLift),
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    style = ControlLabelStyle,
                    color = color,
                    maxLines = 1,
                    modifier = Modifier.offset(y = CapsLift),
                )
            }
        }
    }
}

/**
 * A square glyph control (random, play, shuffle). While focused its [label] is shown under it as a mono `muted`
 * caption, drawn over the space below the strip so the strip keeps its height. [captionAtEnd] lines the caption up
 * with the control's right edge (the last control, so the caption stays off the screen edge).
 */
@Composable
internal fun LibraryIconButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    captionAtEnd: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
            colors = controlColors(),
            border = controlBorder(),
            glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
            interactionSource = interactionSource,
            modifier = Modifier.size(ControlHeight),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 16.sp,
                    color = if (enabled) TallyColors.text else TallyColors.muted,
                )
            }
        }
        if (focused) {
            Text(
                text = label.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.muted,
                maxLines = 1,
                modifier =
                    Modifier
                        .align(if (captionAtEnd) Alignment.TopEnd else Alignment.TopCenter)
                        .overflowBelow(ControlHeight + 4.dp, atEnd = captionAtEnd),
            )
        }
    }
}

/**
 * Places this [top] below its anchor without taking layout room (the caption hangs under the control): centered on
 * the anchor, or ending at it when [atEnd].
 */
private fun Modifier.overflowBelow(
    top: Dp,
    atEnd: Boolean,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE, minHeight = 0))
        layout(0, 0) {
            placeable.place(if (atEnd) -placeable.width else -placeable.width / 2, top.roundToPx())
        }
    }

@Composable
private fun controlColors() =
    ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = TallyColors.text,
        focusedContainerColor = TallyColors.groundRaised,
        focusedContentColor = TallyColors.text,
        pressedContainerColor = TallyColors.groundRaised,
        pressedContentColor = TallyColors.text,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = TallyColors.muted,
    )

@Composable
private fun controlBorder() =
    ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong), shape = RectangleShape),
        focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
        pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
        disabledBorder = Border(BorderStroke(TallyDimens.hairline, TallyColors.rule), shape = RectangleShape),
    )

/** `SORT · NAME` (the direction arrow follows it, see [directionArrow]). */
@Composable
internal fun sortLabel(sort: SortAndDirection): String =
    stringResource(R.string.tally_library_sort, stringResource(getStringRes(sort.sort)))

/** `↑` ascending, `↓` descending. */
@Composable
internal fun directionArrow(direction: SortOrder): String =
    stringResource(
        if (direction == SortOrder.ASCENDING) R.string.tally_library_ascending else R.string.tally_library_descending,
    )

// ---------------------------------------------------------------------------------------------------------------
// Dialogs: the Quality dialog's panel family.
// ---------------------------------------------------------------------------------------------------------------

/** Ignore clicks right after a panel opens, so the key-up that opened it cannot activate a row. */
private const val OPEN_GRACE_MS = 400L

/** Room inside the scrolling list for a focused row's border, so the list's clip never cuts it. */
private val FOCUS_ROOM = TallyDimens.focusBorder + 1.dp

/** Six and a half row slots (a 56dp row plus 2 x FOCUS_ROOM), so a seventh row peeks out when there are more. */
private val LIST_MAX_HEIGHT = 416.dp

private val PANEL_WIDTH = 560.dp

/** One row of a panel. [marked] draws the 8dp accent square; [value] is the mono text at the right. */
internal data class PanelRow(
    val label: String,
    val onClick: () -> Unit,
    val marked: Boolean = false,
    val value: String? = null,
    val valueAccent: Boolean = false,
)

/**
 * A centered panel over a 60% scrim: accent kicker, hairline, a scrolling list of [rows], and a key bar. Focus
 * starts on [initialIndex] and stays inside; BACK calls [onBack] ([backLabel] names what it does).
 */
@Composable
internal fun LibraryPanel(
    kicker: String,
    rows: List<PanelRow>,
    initialIndex: Int,
    onBack: () -> Unit,
    backLabel: String,
    message: String? = null,
) {
    // BACK reaches a dialog as a dismiss request (the platform handles it before the content sees the key): it
    // means "go back one level", which closes the panel only on its first level.
    Dialog(
        onDismissRequest = onBack,
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PanelContent(
                    kicker = kicker,
                    rows = rows,
                    initialIndex = initialIndex,
                    onBack = onBack,
                    backLabel = backLabel,
                    message = message,
                )
            }
        }
    }
}

@Composable
private fun PanelContent(
    kicker: String,
    rows: List<PanelRow>,
    initialIndex: Int,
    onBack: () -> Unit,
    backLabel: String,
    message: String?,
) {
    val openedAt = remember(kicker) { SystemClock.elapsedRealtime() }
    // Keyed by the panel's content (the kicker names the level), so a new level starts on its own row.
    val requesters = remember(kicker, rows.size) { List(rows.size) { FocusRequester() } }
    val bringers = remember(kicker, rows.size) { List(rows.size) { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(kicker, rows.size) {
        val target = requesters.getOrNull(initialIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
        if (target != null) {
            repeat(8) {
                if (target.tryRequestFocus("tally-library-panel")) return@LaunchedEffect
                delay(40)
            }
        }
    }
    val emptyRequester = remember { FocusRequester() }
    Column(
        modifier =
            Modifier
                .width(PANEL_WIDTH)
                .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                .background(TallyColors.ground, RectangleShape)
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.Escape -> {
                            if (event.type == KeyEventType.KeyUp) onBack()
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
                text = kicker.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 12.dp - FOCUS_ROOM)
                    .fillMaxWidth()
                    .height(TallyDimens.hairline)
                    .background(TallyColors.rule),
            )
            if (message != null) {
                // Nothing to choose (still loading, or no values): the panel still holds focus so BACK works.
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier
                            .padding(horizontal = 20.dp)
                            .padding(vertical = FOCUS_ROOM)
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .focusRequester(emptyRequester)
                            // Focusable only so BACK reaches the panel while it has no rows.
                            .focusable(),
                ) {
                    Text(
                        text = message.tallyUppercase(),
                        style = TallyType.label,
                        color = TallyColors.muted,
                        maxLines = 1,
                    )
                }
                LaunchedEffect(Unit) { emptyRequester.tryRequestFocus("tally-library-panel-empty") }
            }
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = 20.dp - FOCUS_ROOM)
                        .heightIn(max = LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = FOCUS_ROOM),
            ) {
                val last = rows.lastIndex
                rows.forEachIndexed { index, row ->
                    Box(
                        modifier =
                            Modifier
                                .bringIntoViewRequester(bringers[index])
                                .padding(vertical = FOCUS_ROOM),
                    ) {
                        TallyRow(
                            label = row.label,
                            onClick = {
                                if (SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS) row.onClick()
                            },
                            onFocused = { scope.launch { bringers[index].bringIntoView() } },
                            modifier =
                                Modifier
                                    .focusRequester(requesters[index])
                                    .focusProperties {
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                        up = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
                                        down = if (index == last) FocusRequester.Cancel else FocusRequester.Default
                                    },
                            trailing =
                                if (row.marked || row.value != null) {
                                    {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            if (row.value != null) {
                                                Text(
                                                    text = row.value.tallyUppercase(),
                                                    style = TallyType.label,
                                                    color = if (row.valueAccent) TallyColors.accent else TallyColors.muted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 220.dp),
                                                )
                                            }
                                            if (row.marked) {
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
            KeyHint(key = stringResource(R.string.tally_library_key_back), label = backLabel)
        }
    }
}

/** Sort choices; the current one is marked with its direction. Choosing it again reverses the order. */
@Composable
internal fun SortDialog(
    sortOptions: List<ItemSortBy>,
    current: SortAndDirection,
    onSortChange: (SortAndDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    val arrow = directionArrow(current.direction)
    val rows =
        sortOptions.map { option ->
            val label = stringResource(getStringRes(option))
            PanelRow(
                label = label,
                marked = option == current.sort,
                value = if (option == current.sort) arrow else null,
                onClick = {
                    onDismiss()
                    onSortChange(nextSort(current, option))
                },
            )
        }
    LibraryPanel(
        kicker = stringResource(R.string.tally_library_sort_kicker),
        rows = rows,
        initialIndex = sortOptions.indexOf(current.sort).coerceAtLeast(0),
        onBack = onDismiss,
        backLabel = stringResource(R.string.tally_library_close),
    )
}

/**
 * Filters, two levels as upstream's menus: the filter kinds (marked when on, with what is on), then a kind's values
 * as ON/OFF toggles. Changes apply at once. A single-choice kind (played, favorites) goes back after a choice.
 */
@Composable
internal fun FilterDialog(
    filterOptions: List<ItemFilterBy<*>>,
    current: GetItemsFilter,
    onFilterChange: (GetItemsFilter) -> Unit,
    getPossibleValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf<ItemFilterBy<*>?>(null) }
    var lastOpened by remember { mutableStateOf<ItemFilterBy<*>?>(null) }
    val option = open
    if (option == null) {
        val count = current.countFilters(filterOptions)
        val rows =
            buildList {
                if (count > 0) {
                    add(
                        PanelRow(
                            label = stringResource(R.string.tally_library_clear_filters),
                            onClick = {
                                onFilterChange(current.delete(filterOptions))
                                onDismiss()
                            },
                        ),
                    )
                }
                filterOptions.forEach { filterOption ->
                    val on = filterOption.get(current) != null
                    add(
                        PanelRow(
                            label = stringResource(filterOption.stringRes),
                            marked = on,
                            value = if (on) filterSummary(filterOption, current) else null,
                            onClick = {
                                lastOpened = filterOption
                                open = filterOption
                            },
                        ),
                    )
                }
            }
        val offset = if (count > 0) 1 else 0
        LibraryPanel(
            kicker = stringResource(R.string.tally_library_filter_kicker),
            rows = rows,
            initialIndex = (lastOpened?.let { filterOptions.indexOf(it) }?.takeIf { it >= 0 } ?: 0) + offset,
            onBack = onDismiss,
            backLabel = stringResource(R.string.tally_library_close),
        )
    } else {
        var values by remember(option) { mutableStateOf<List<FilterValueOption>?>(null) }
        LaunchedEffect(option) { values = getPossibleValues(option) }
        val loaded = values
        val on = option.get(current) != null
        val onText = stringResource(R.string.tally_library_on)
        val offText = stringResource(R.string.tally_library_off)
        val rows =
            buildList {
                if (loaded != null && on) {
                    add(
                        PanelRow(
                            label = stringResource(R.string.tally_library_clear_filter),
                            onClick = {
                                @Suppress("UNCHECKED_CAST")
                                onFilterChange((option as ItemFilterBy<Any>).set(null, current))
                                open = null
                            },
                        ),
                    )
                }
                loaded.orEmpty().forEach { value ->
                    val selected = isFilterValueOn(option, current, value)
                    add(
                        PanelRow(
                            label = filterValueLabel(option, value),
                            value = if (selected) onText else offText,
                            valueAccent = selected,
                            onClick = {
                                onFilterChange(toggleFilterValue(option, current, value))
                                if (!option.supportMultiple) open = null
                            },
                        ),
                    )
                }
            }
        val firstOn = loaded.orEmpty().indexOfFirst { isFilterValueOn(option, current, it) }
        val clearOffset = if (loaded != null && on) 1 else 0
        LibraryPanel(
            kicker = stringResource(R.string.tally_library_filter_kicker_option, stringResource(option.stringRes)),
            rows = rows,
            initialIndex = if (firstOn >= 0) firstOn + clearOffset else 0,
            onBack = { open = null },
            backLabel = stringResource(R.string.tally_library_back),
            message =
                when {
                    loaded == null -> stringResource(R.string.tally_library_loading)
                    loaded.isEmpty() -> stringResource(R.string.tally_library_no_values)
                    else -> null
                },
        )
    }
}

/** A filter value's name: Yes/No for the true/false kinds, `7 and up` for the minimum rating. */
@Composable
private fun filterValueLabel(
    option: ItemFilterBy<*>,
    value: FilterValueOption,
): String =
    when (option) {
        PlayedFilter, FavoriteFilter -> {
            stringResource(if (value.name.toBoolean()) R.string.tally_library_yes else R.string.tally_library_no)
        }

        CommunityRatingFilter -> {
            stringResource(R.string.tally_library_rating_min, value.name)
        }

        else -> {
            value.name
        }
    }

/** What is on for [option], for the first level's right column: `YES`, `7 AND UP`, or how many values are on. */
@Composable
private fun filterSummary(
    option: ItemFilterBy<*>,
    current: GetItemsFilter,
): String? =
    when (val value = option.get(current)) {
        null -> {
            null
        }

        is Boolean -> {
            stringResource(if (value) R.string.tally_library_yes else R.string.tally_library_no)
        }

        is List<*> -> {
            value.size.toString()
        }

        is Int -> {
            if (option ==
                CommunityRatingFilter
            ) {
                stringResource(R.string.tally_library_rating_min, value.toString())
            } else {
                value.toString()
            }
        }

        else -> {
            value.toString()
        }
    }

/**
 * View options, two levels: every option of upstream's view options dialog for the current layout (choices show
 * their value, switches ON/OFF, Reset), then a choice's values with the current one marked. Changes apply at once.
 */
@Composable
internal fun ViewDialog(
    viewOptions: ViewOptions,
    defaultViewOptions: ViewOptions,
    onViewOptionsChange: (ViewOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs =
        when (viewOptions.type) {
            ViewOptionsType.GRID -> ViewOptions.GRID_OPTIONS
            ViewOptionsType.LIST, ViewOptionsType.DENSE_LIST -> ViewOptions.LIST_OPTIONS
        }
    var open by remember { mutableStateOf<AppPreference<ViewOptions, *>?>(null) }
    var lastOpened by remember { mutableStateOf<AppPreference<ViewOptions, *>?>(null) }
    val onText = stringResource(R.string.tally_library_on)
    val offText = stringResource(R.string.tally_library_off)
    val openPref = open
    if (openPref == null) {
        val rows =
            prefs.map { pref ->
                val title = stringResource(pref.title)
                @Suppress("UNCHECKED_CAST")
                when (pref) {
                    is AppSwitchPreference<*> -> {
                        val p = pref as AppSwitchPreference<ViewOptions>
                        val on = p.getter(viewOptions)
                        PanelRow(
                            label = title,
                            value = if (on) onText else offText,
                            valueAccent = on,
                            onClick = { onViewOptionsChange(p.setter(viewOptions, !on)) },
                        )
                    }

                    is AppChoicePreference<*, *> -> {
                        val p = pref as AppChoicePreference<ViewOptions, Any?>
                        val names = stringArrayResource(p.displayValues)
                        PanelRow(
                            label = title,
                            value = names.getOrNull(p.valueToIndex(p.getter(viewOptions))),
                            onClick = {
                                lastOpened = pref
                                open = pref
                            },
                        )
                    }

                    is AppSliderPreference<*> -> {
                        val p = pref as AppSliderPreference<ViewOptions>
                        PanelRow(
                            label = title,
                            value = p.getter(viewOptions).toString(),
                            onClick = {
                                lastOpened = pref
                                open = pref
                            },
                        )
                    }

                    is AppClickablePreference<*> -> {
                        PanelRow(label = title, onClick = { onViewOptionsChange(defaultViewOptions) })
                    }

                    else -> {
                        PanelRow(label = title, onClick = {})
                    }
                }
            }
        LibraryPanel(
            kicker = stringResource(R.string.tally_library_view_kicker),
            rows = rows,
            initialIndex = lastOpened?.let { prefs.indexOf(it) }?.takeIf { it >= 0 } ?: 0,
            onBack = onDismiss,
            backLabel = stringResource(R.string.tally_library_close),
        )
    } else {
        @Suppress("UNCHECKED_CAST")
        val choices: List<Pair<String, () -> ViewOptions>> =
            when (openPref) {
                is AppChoicePreference<*, *> -> {
                    val p = openPref as AppChoicePreference<ViewOptions, Any?>
                    stringArrayResource(p.displayValues).mapIndexed { index, name ->
                        name to { p.setter(viewOptions, p.indexToValue(index)) }
                    }
                }

                is AppSliderPreference<*> -> {
                    val p = openPref as AppSliderPreference<ViewOptions>
                    (p.min..p.max step p.interval.toLong()).map { value ->
                        value.toString() to { p.setter(viewOptions, value) }
                    }
                }

                else -> {
                    emptyList()
                }
            }

        @Suppress("UNCHECKED_CAST")
        val currentIndex =
            when (openPref) {
                is AppChoicePreference<*, *> -> {
                    val p = openPref as AppChoicePreference<ViewOptions, Any?>
                    p.valueToIndex(p.getter(viewOptions))
                }

                is AppSliderPreference<*> -> {
                    val p = openPref as AppSliderPreference<ViewOptions>
                    ((p.getter(viewOptions) - p.min) / p.interval.coerceAtLeast(1)).toInt()
                }

                else -> {
                    0
                }
            }
        val rows =
            choices.mapIndexed { index, (name, apply) ->
                PanelRow(
                    label = name,
                    marked = index == currentIndex,
                    onClick = {
                        onViewOptionsChange(apply())
                        open = null
                    },
                )
            }
        LibraryPanel(
            kicker = stringResource(R.string.tally_library_view_kicker_option, stringResource(openPref.title)),
            rows = rows,
            initialIndex = currentIndex,
            onBack = { open = null },
            backLabel = stringResource(R.string.tally_library_back),
        )
    }
}
