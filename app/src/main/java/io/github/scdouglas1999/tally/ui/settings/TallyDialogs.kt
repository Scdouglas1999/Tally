package io.github.scdouglas1999.tally.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogItemDivider
import com.github.damontecres.wholphin.ui.components.DialogItemEntry
import com.github.damontecres.wholphin.ui.preferences.LocaleChoiceViewModel
import com.github.damontecres.wholphin.ui.preferences.StringInput
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneConfirmContent
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhonePanelFrame
import io.github.scdouglas1999.tally.ui.settings.phone.PhonePanelList
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneStringInput
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.settings.phone.phoneSheetMaxHeight
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Ignore clicks right after a panel opens, so the key-up that opened it cannot activate a row. */
internal const val OPEN_GRACE_MS = 400L

/** Upstream's long-press guard: rows ignore clicks this long, or until the held OK key is released. */
private const val LONG_PRESS_WAIT_MS = 1000L

/** Room inside a scrolling list for a focused row's border, so the list's clip never cuts it. */
internal val FOCUS_ROOM = TallyDimens.focusBorder + 1.dp

/** Six and a half 56dp row slots (plus 2 x [FOCUS_ROOM] each), so a seventh row peeks out when there are more. */
private val LIST_MAX_HEIGHT = 416.dp

private val PANEL_WIDTH = 560.dp

/** A confirm title up to this long reads as a kicker; longer ones become the message under a generic kicker. */
private const val KICKER_MAX_CHARS = 32

private val messageStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )

private val trailingStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )

private val overlineStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )

// ---------------------------------------------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------------------------------------------

/** How a confirm dialog splits upstream's title and body into a kicker, a message and a detail line. */
internal data class ConfirmText(
    /** Null: use the generic "Confirm" kicker. */
    val kicker: String?,
    val message: String?,
    val detail: String?,
)

/**
 * A short one-line title ("Discard changes?", "Remove Seerr Server") is the kicker and the body is the message.
 * A sentence ("Are you sure you want to delete this item?") is the message under a generic kicker, and the body
 * goes under it.
 */
internal fun confirmText(
    title: String,
    body: String?,
): ConfirmText {
    // Upstream bodies carry stray spaces at line starts ("…unstable!\n\n These may…"): trim each line.
    val cleanBody =
        body
            ?.lines()
            ?.joinToString("\n") { it.trim() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val cleanTitle = title.trim()
    return if (cleanTitle.isNotEmpty() && cleanTitle.length <= KICKER_MAX_CHARS && '\n' !in cleanTitle) {
        ConfirmText(kicker = cleanTitle, message = cleanBody, detail = null)
    } else {
        ConfirmText(kicker = null, message = cleanTitle.takeIf { it.isNotEmpty() }, detail = cleanBody)
    }
}

/** Where focus starts in a list: the marked row, else the first enabled one; -1 when nothing can take focus. */
internal fun initialListIndex(
    enabled: List<Boolean>,
    marked: List<Boolean>,
): Int {
    val markedIndex = marked.indices.firstOrNull { marked[it] && enabled.getOrElse(it) { false } }
    return markedIndex ?: enabled.indexOfFirst { it }
}

// ---------------------------------------------------------------------------------------------------------------
// Panel family
// ---------------------------------------------------------------------------------------------------------------

/** One entry of a panel list: a row, or a hairline between groups of rows. */
internal sealed interface PanelEntry {
    data class Item(
        val onClick: () -> Unit,
        val headline: @Composable () -> Unit,
        val enabled: Boolean = true,
        val marked: Boolean = false,
        val destructive: Boolean = false,
        val overline: (@Composable () -> Unit)? = null,
        val supporting: (@Composable () -> Unit)? = null,
        val leading: (@Composable BoxScope.() -> Unit)? = null,
        val trailing: (@Composable () -> Unit)? = null,
    ) : PanelEntry

    data object Divider : PanelEntry
}

/** A plain text row. */
internal fun panelItem(
    label: String,
    onClick: () -> Unit,
    marked: Boolean = false,
    destructive: Boolean = false,
    supporting: String? = null,
    trailing: String? = null,
): PanelEntry.Item =
    PanelEntry.Item(
        onClick = onClick,
        headline = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        marked = marked,
        destructive = destructive,
        supporting = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailing = trailing?.let { { Text(it, maxLines = 1) } },
    )

/**
 * The dialog window for a Tally panel: a 60% scrim, the panel centered at the Tally scale. BACK reaches the
 * window as [onDismissRequest].
 */
@Composable
internal fun TallyPanelWindow(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    if (isPhone()) {
        // A phone: the panel is a bottom sheet (its frame and rows draw their phone forms).
        PhoneSheet(onDismiss = onDismissRequest) {
            Box(modifier = Modifier.fillMaxWidth(), content = content)
        }
        return
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = properties.dismissOnBackPress,
                dismissOnClickOutside = properties.dismissOnClickOutside,
                securePolicy = properties.securePolicy,
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
                contentAlignment = contentAlignment,
                content = content,
            )
        }
    }
}

/**
 * The panel: hairline frame on ground, accent kicker, a rule, [content], and the black key bar. Focus stays
 * inside. [trapHorizontal] swallows LEFT/RIGHT (lists); panels with side-by-side controls leave them alone.
 */
@Composable
internal fun TallyPanelFrame(
    kicker: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = PANEL_WIDTH,
    trapHorizontal: Boolean = true,
    onPreviewKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isPhone()) {
        PhonePanelFrame(title = kicker, content = content)
        return
    }
    Column(
        modifier =
            modifier
                .width(width)
                .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                .background(TallyColors.ground, RectangleShape)
                .onPreviewKeyEvent { event ->
                    if (onPreviewKey(event)) return@onPreviewKeyEvent true
                    when (event.key) {
                        Key.Escape -> {
                            if (event.type == KeyEventType.KeyUp) onBack()
                            true
                        }

                        Key.DirectionLeft, Key.DirectionRight -> {
                            trapHorizontal
                        }

                        else -> {
                            false
                        }
                    }
                }
                // Before the group, so it applies to the panel itself: focus cannot leave the panel, but moves
                // freely inside it (after the group it would also hold focus inside a scrolling list).
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
    ) {
        Text(
            text = kicker.tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp),
        )
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 12.dp - FOCUS_ROOM)
                .fillMaxWidth()
                .height(TallyDimens.hairline)
                .background(TallyColors.rule),
        )
        content()
        KeyBar()
    }
}

@Composable
private fun KeyBar() {
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
            key = stringResource(R.string.tally_prefs_key_back),
            label = stringResource(R.string.tally_prefs_close),
        )
    }
}

/**
 * The scrolling rows of a panel. Each row sits in a slot with [FOCUS_ROOM] around it, and the slot is what is
 * scrolled into view, so the focused row's whole border stays visible. Focus starts on [initialIndex].
 */
@Composable
internal fun TallyPanelList(
    entries: List<PanelEntry>,
    initialIndex: Int,
    canClick: () -> Boolean,
    focusKey: Any? = null,
    onUpFromFirst: (() -> Unit)? = null,
    refocusOnChange: Boolean = true,
) {
    if (isPhone()) {
        PhonePanelList(entries = entries, initialIndex = initialIndex, canClick = canClick)
        return
    }
    val requesters = remember(focusKey, entries.size) { List(entries.size) { FocusRequester() } }
    val bringers = remember(focusKey, entries.size) { List(entries.size) { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val focusable = entries.map { it is PanelEntry.Item && it.enabled }
    val first = focusable.indexOfFirst { it }
    val last = focusable.indexOfLast { it }
    // A filtered list (refocusOnChange = false) keeps focus where it is (in the filter field) as its rows change.
    LaunchedEffect(focusKey, if (refocusOnChange) entries.size else Unit) {
        val start = initialIndex.takeIf { focusable.getOrElse(it) { false } } ?: first
        val target = requesters.getOrNull(start) ?: return@LaunchedEffect
        repeat(8) {
            if (target.tryRequestFocus("tally-panel-list")) return@LaunchedEffect
            delay(40)
        }
    }
    Column(
        modifier =
            Modifier
                .padding(horizontal = 20.dp - FOCUS_ROOM)
                .heightIn(max = LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FOCUS_ROOM),
    ) {
        entries.forEachIndexed { index, entry ->
            when (entry) {
                PanelEntry.Divider -> {
                    Box(
                        Modifier
                            .padding(vertical = 8.dp)
                            .fillMaxWidth()
                            .height(TallyDimens.hairline)
                            .background(TallyColors.rule),
                    )
                }

                is PanelEntry.Item -> {
                    Box(
                        modifier =
                            Modifier
                                .bringIntoViewRequester(bringers[index])
                                .padding(vertical = FOCUS_ROOM),
                    ) {
                        TallyDialogRow(
                            onClick = { if (canClick()) entry.onClick() },
                            enabled = entry.enabled,
                            marked = entry.marked,
                            destructive = entry.destructive,
                            overline = entry.overline,
                            supporting = entry.supporting,
                            leading = entry.leading,
                            trailing = entry.trailing,
                            headline = entry.headline,
                            onFocused = { scope.launch { bringers[index].bringIntoView() } },
                            modifier =
                                Modifier
                                    .focusRequester(requesters[index])
                                    .then(
                                        if (index == first && onUpFromFirst != null) {
                                            // UP from the first row goes to the control above the list (a filter
                                            // field), taken before the row's own focus search.
                                            Modifier.onPreviewKeyEvent { event ->
                                                if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                                                if (event.type == KeyEventType.KeyDown) onUpFromFirst()
                                                true
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ).focusProperties {
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                        if (index == first) up = FocusRequester.Cancel
                                        if (index == last) down = FocusRequester.Cancel
                                    },
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp - FOCUS_ROOM))
}

/**
 * A panel row in the TallyRow look (1dp ruleStrong, focused 3dp accent on groundRaised), with slots so upstream's
 * composable dialog items render inside it. The headline is Sans 20sp, supporting text Sans 15sp muted, and
 * [marked] adds the accent square at the right.
 */
@Composable
internal fun TallyDialogRow(
    onClick: () -> Unit,
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    marked: Boolean = false,
    destructive: Boolean = false,
    onFocused: () -> Unit = {},
    overline: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (isPhone()) {
        PhoneDialogRow(
            onClick = onClick,
            headline = headline,
            modifier = modifier,
            enabled = enabled,
            marked = marked,
            destructive = destructive,
            overline = overline,
            supporting = supporting,
            leading = leading,
            trailing = trailing,
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    val labelColor = if (destructive) TallyColors.liveText else TallyColors.text
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors = rowColors(labelColor),
        border = rowBorder(),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interactionSource,
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (leading != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.widthIn(min = 20.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                        leading()
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                if (overline != null) {
                    CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                        ProvideTextStyle(overlineStyle) { overline() }
                    }
                }
                CompositionLocalProvider(LocalContentColor provides labelColor) {
                    ProvideTextStyle(TallyType.body) { headline() }
                }
                if (supporting != null) {
                    CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                        ProvideTextStyle(TallyType.hint) { supporting() }
                    }
                }
            }
            if (trailing != null) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.textSecondary) {
                    ProvideTextStyle(trailingStyle) { trailing() }
                }
            }
            if (marked) {
                IndicatorSquare(color = TallyColors.accent, size = 8.dp)
            }
        }
    }
}

@Composable
internal fun rowColors(labelColor: Color = TallyColors.text) =
    ClickableSurfaceDefaults.colors(
        containerColor = TallyColors.ground,
        contentColor = labelColor,
        focusedContainerColor = TallyColors.groundRaised,
        focusedContentColor = labelColor,
        pressedContainerColor = TallyColors.groundRaised,
        pressedContentColor = labelColor,
        disabledContainerColor = TallyColors.ground,
        disabledContentColor = labelColor,
    )

@Composable
internal fun rowBorder() =
    ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong), shape = RectangleShape),
        focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
        pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
        disabledBorder = Border(BorderStroke(TallyDimens.hairline, TallyColors.rule), shape = RectangleShape),
        focusedDisabledBorder = Border(BorderStroke(TallyDimens.hairline, TallyColors.rule), shape = RectangleShape),
    )

// ---------------------------------------------------------------------------------------------------------------
// List dialog (upstream DialogPopup / DialogPopupContent)
// ---------------------------------------------------------------------------------------------------------------

/** Upstream's [DialogItemEntry] list as panel entries; the item's `selected` becomes the accent square. */
internal fun dialogEntries(
    dialogItems: List<DialogItemEntry>,
    dismissOnClick: Boolean,
    onDismissRequest: () -> Unit,
): List<PanelEntry> =
    dialogItems.map { entry ->
        when (entry) {
            DialogItemDivider -> {
                PanelEntry.Divider
            }

            is DialogItem -> {
                PanelEntry.Item(
                    onClick = {
                        if (dismissOnClick || entry.dismissOnClick) onDismissRequest()
                        entry.onClick()
                    },
                    headline = entry.headlineContent,
                    enabled = entry.enabled,
                    marked = entry.selected,
                    overline = entry.overlineContent,
                    supporting = entry.supportingContent,
                    leading = entry.leadingContent,
                    trailing = entry.trailingContent,
                )
            }
        }
    }

/** Tally [com.github.damontecres.wholphin.ui.components.DialogPopup]: the same inputs, the panel look. */
@Composable
fun TallyDialogPopup(
    showDialog: Boolean,
    title: String,
    dialogItems: List<DialogItemEntry>,
    onDismissRequest: () -> Unit,
    dismissOnClick: Boolean = true,
    waitToLoad: Boolean = true,
    properties: DialogProperties = DialogProperties(),
) {
    if (!showDialog) return
    // On a phone the long press that opened it ends outside the sheet: no wait.
    val phone = isPhone()
    var waiting by remember { mutableStateOf(waitToLoad && !phone) }
    LaunchedEffect(waitToLoad) {
        if (waitToLoad && !phone) {
            delay(LONG_PRESS_WAIT_MS)
        }
        waiting = false
    }
    TallyPanelWindow(onDismissRequest = onDismissRequest, properties = properties) {
        TallyListPanel(
            title = title,
            entries = dialogEntries(dialogItems, dismissOnClick, onDismissRequest),
            waiting = waiting,
            onWaitReleased = { waiting = false },
            onBack = onDismissRequest,
        )
    }
}

/** Tally [com.github.damontecres.wholphin.ui.components.DialogPopupContent], for callers that own the window. */
@Composable
fun TallyDialogPopupContent(
    title: String,
    dialogItems: List<DialogItemEntry>,
    waiting: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClick: Boolean = true,
) {
    var released by remember { mutableStateOf(false) }
    TallyScale {
        TallyListPanel(
            title = title,
            entries = dialogEntries(dialogItems, dismissOnClick, onDismissRequest),
            waiting = waiting && !released,
            onWaitReleased = { released = true },
            onBack = onDismissRequest,
            modifier = modifier,
        )
    }
}

/**
 * A list panel. While [waiting] (opened by a long press), the release of the held OK key is swallowed so it
 * cannot choose the first row.
 */
@Composable
internal fun TallyListPanel(
    title: String,
    entries: List<PanelEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    waiting: Boolean = false,
    onWaitReleased: () -> Unit = {},
    initialIndex: Int? = null,
    message: String? = null,
) {
    val isWaiting by rememberUpdatedState(waiting)
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val start =
        initialIndex ?: initialListIndex(
            enabled = entries.map { it is PanelEntry.Item && it.enabled },
            marked = entries.map { it is PanelEntry.Item && it.marked },
        )
    TallyPanelFrame(
        kicker = title,
        onBack = onBack,
        modifier = modifier,
        onPreviewKey = { event ->
            val ok = event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter
            if (ok && isWaiting && event.type == KeyEventType.KeyUp) {
                onWaitReleased()
                true
            } else {
                false
            }
        },
    ) {
        if (message != null || entries.isEmpty()) {
            PanelMessage(message ?: "")
        }
        TallyPanelList(
            entries = entries,
            initialIndex = start,
            canClick = { !isWaiting && SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS },
            focusKey = title,
        )
    }
}

/** A focusable message line, so BACK still reaches a panel that has no rows yet. */
@Composable
private fun PanelMessage(text: String) {
    val requester = remember { FocusRequester() }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .padding(horizontal = 20.dp)
                .padding(vertical = FOCUS_ROOM)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRequester(requester)
                .focusable(),
    ) {
        Text(
            text = text.tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
    }
    LaunchedEffect(Unit) { requester.tryRequestFocus("tally-panel-message") }
}

// ---------------------------------------------------------------------------------------------------------------
// Choice dialog
// ---------------------------------------------------------------------------------------------------------------

/**
 * Pick one of [count] values: the current one is marked with the accent square and takes focus. Rows draw
 * upstream's own [label] / [supporting] composables at the Tally sizes.
 */
@Composable
internal fun TallyChoiceDialog(
    title: String,
    count: Int,
    selectedIndex: Int,
    onChoose: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    label: @Composable (Int) -> Unit,
    supporting: (Int) -> (@Composable () -> Unit)? = { null },
) {
    val entries =
        List(count) { index ->
            PanelEntry.Item(
                onClick = { onChoose(index) },
                headline = { label(index) },
                marked = index == selectedIndex,
                supporting = supporting(index),
            )
        }
    TallyPanelWindow(onDismissRequest = onDismissRequest) {
        TallyListPanel(
            title = title,
            entries = entries,
            onBack = onDismissRequest,
            initialIndex = selectedIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
        )
    }
}

/** Upstream's language picker in the panel look: native name, English name under it, current one marked. */
@Composable
fun TallyLocaleChoiceDialog(
    onDismissRequest: () -> Unit,
    viewModel: LocaleChoiceViewModel,
) {
    val state by viewModel.state.collectAsState()
    val title = stringResource(R.string.user_interface_language)
    TallyPanelWindow(onDismissRequest = onDismissRequest) {
        when (val loading = state.loading) {
            LoadingState.Success -> {
                val userLanguage = state.userLocale.toLanguageTag()
                val entries =
                    state.availableLocales.map { locale ->
                        panelItem(
                            label = locale.getDisplayName(locale),
                            supporting = locale.getDisplayName(Locale.ENGLISH),
                            marked = locale.toLanguageTag() == userLanguage,
                            onClick = {
                                onDismissRequest()
                                viewModel.changeLocale(locale)
                            },
                        )
                    }
                TallyListPanel(title = title, entries = entries, onBack = onDismissRequest)
            }

            is LoadingState.Error -> {
                TallyListPanel(
                    title = title,
                    entries = emptyList(),
                    onBack = onDismissRequest,
                    message = loading.message ?: loading.exception?.localizedMessage ?: "",
                )
            }

            else -> {
                TallyListPanel(
                    title = title,
                    entries = emptyList(),
                    onBack = onDismissRequest,
                    message = stringResource(R.string.tally_prefs_loading),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Confirm dialog
// ---------------------------------------------------------------------------------------------------------------

/** Tally [com.github.damontecres.wholphin.ui.components.ConfirmDialog]. */
@Composable
fun TallyConfirmDialog(
    title: String,
    body: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    bodyIsError: Boolean = false,
) {
    TallyPanelWindow(onDismissRequest = onCancel, properties = properties) {
        TallyConfirmDialogContent(title, body, onCancel, onConfirm, bodyIsError = bodyIsError)
    }
}

/** Tally [com.github.damontecres.wholphin.ui.components.ConfirmDialogContent], for callers that own the window. */
@Composable
fun TallyConfirmDialogContent(
    title: String,
    body: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    bodyIsError: Boolean = false,
) {
    val text = confirmText(title, body)
    TallyScale {
        TallyConfirmPanel(
            kicker = text.kicker ?: stringResource(R.string.tally_prefs_confirm_kicker),
            message = text.message,
            detail = text.detail,
            detailIsError = bodyIsError,
            confirmLabel = stringResource(R.string.confirm),
            onCancel = onCancel,
            onConfirm = onConfirm,
            modifier = modifier,
        )
    }
}

/** Tally [com.github.damontecres.wholphin.ui.components.ConfirmDeleteDialog]: `DELETE?`, the item, a red Delete. */
@Composable
fun TallyConfirmDeleteDialog(
    itemTitle: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    TallyPanelWindow(onDismissRequest = onCancel) {
        TallyConfirmPanel(
            kicker = stringResource(R.string.tally_prefs_delete_kicker),
            message = itemTitle.takeIf { it.isNotBlank() },
            detail = null,
            confirmLabel = stringResource(R.string.delete),
            onCancel = onCancel,
            onConfirm = onConfirm,
        )
    }
}

/**
 * Kicker, message (Sans 17sp), an optional detail line, then Cancel and the confirming action in red. Focus starts
 * on Cancel, the safe choice.
 */
@Composable
internal fun TallyConfirmPanel(
    kicker: String,
    message: String?,
    detail: String?,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    detailIsError: Boolean = false,
) {
    val openedAt = remember { SystemClock.elapsedRealtime() }
    if (isPhone()) {
        PhoneConfirmContent(
            kicker = kicker,
            message = message,
            detail = detail,
            confirmLabel = confirmLabel,
            cancelLabel = stringResource(R.string.cancel),
            onCancel = onCancel,
            onConfirm = { if (SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS) onConfirm() },
            detailIsError = detailIsError,
        )
        return
    }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            if (cancelFocus.tryRequestFocus("tally-confirm")) return@LaunchedEffect
            delay(40)
        }
    }
    TallyPanelFrame(
        kicker = kicker,
        onBack = onCancel,
        modifier = modifier,
        width = 520.dp,
        trapHorizontal = false,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = FOCUS_ROOM),
        ) {
            if (message != null) {
                Text(text = message, style = messageStyle, color = TallyColors.text)
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = messageStyle,
                    color = if (detailIsError) TallyColors.liveText else TallyColors.textSecondary,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp),
        ) {
            TallyButton(
                label = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancelFocus),
            )
            TallyDestructiveButton(
                label = confirmLabel,
                onClick = {
                    if (SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS) onConfirm()
                },
            )
        }
    }
}

/** TallyButton's shape and metrics for the action that removes or overwrites something: red label and frame. */
@Composable
internal fun TallyDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = TallyColors.liveText,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = TallyColors.liveText,
                pressedContainerColor = Color.Transparent,
                pressedContentColor = TallyColors.liveText,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(TallyDimens.hairline, TallyColors.live), shape = RectangleShape),
                focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier.height(40.dp),
    ) {
        // tv-material3 Surface lays its content out top-start: fill the 40dp so the label is centered.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp),
        ) {
            Text(
                text = label.tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.liveText,
                maxLines = 1,
                // Same lift as TallyButton: Plex Mono capitals sit low in their line box.
                modifier = Modifier.offset(y = (-1).dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Text input dialog
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.StringInputDialog]: the field (groundRaised box, accent
 * border when focused) opens the TV keyboard as upstream's does; Cancel and Save. Unsaved edits ask first when
 * upstream asks ([StringInput.confirmDiscard]).
 */
@Composable
fun TallyStringInputDialog(
    input: StringInput,
    onSave: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val state = rememberTextFieldState(input.value ?: "")
    var showConfirm by remember { mutableStateOf(false) }
    val onDone = { onSave(state.text.toString()) }
    val dismiss = {
        if (input.confirmDiscard && state.text.toString() != input.value) {
            showConfirm = true
        } else {
            onDismissRequest()
        }
    }
    if (isPhone()) {
        // A phone: a sheet with the field at its top and the keyboard up; the discard question replaces it.
        if (!showConfirm) {
            PhoneSheet(onDismiss = dismiss) {
                PhoneStringInput(input = input, state = state, onSave = onDone, onCancel = dismiss)
            }
        }
    } else {
        // The field takes focus on open, so the TV keyboard comes up at once (as upstream's does) over the lower half
        // of the screen: the panel sits in the upper half, where the keyboard cannot cover it.
        TallyPanelWindow(onDismissRequest = dismiss, contentAlignment = Alignment.TopCenter) {
            val fieldFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                repeat(8) {
                    if (fieldFocus.tryRequestFocus("tally-text-input")) return@LaunchedEffect
                    delay(40)
                }
            }
            TallyPanelFrame(
                kicker = input.title,
                onBack = dismiss,
                modifier = Modifier.padding(top = 64.dp),
                width = 640.dp,
                trapHorizontal = false,
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                val focused by interactionSource.collectIsFocusedAsState()
                BasicTextField(
                    state = state,
                    keyboardOptions = input.keyboardOptions,
                    onKeyboardAction = { onDone() },
                    lineLimits =
                        if (input.maxLines > 1) {
                            TextFieldLineLimits.MultiLine(input.maxLines, input.maxLines)
                        } else {
                            TextFieldLineLimits.SingleLine
                        },
                    textStyle = TallyType.body.copy(color = TallyColors.text),
                    cursorBrush = SolidColor(TallyColors.accent),
                    interactionSource = interactionSource,
                    decorator = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .background(TallyColors.groundRaised, RectangleShape)
                                    .border(
                                        if (focused) TallyDimens.focusBorder else TallyDimens.hairline,
                                        if (focused) TallyColors.accent else TallyColors.ruleStrong,
                                        RectangleShape,
                                    ).padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            innerTextField()
                        }
                    },
                    modifier =
                        Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = FOCUS_ROOM)
                            .fillMaxWidth()
                            .focusRequester(fieldFocus),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp),
                ) {
                    TallyButton(label = stringResource(R.string.cancel), onClick = dismiss)
                    TallyButton(label = stringResource(R.string.save), onClick = onDone, primary = true)
                }
            }
        }
    }
    if (showConfirm) {
        TallyConfirmDialog(
            title = stringResource(R.string.discard_change),
            body = null,
            onCancel = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                onDismissRequest()
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Frames for upstream's free-form dialogs
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.components.BasicDialog]: upstream's content in a square hairline
 * frame on ground (no rounded card, no shadow).
 */
@Composable
fun TallyBasicDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    if (isPhone()) {
        PhoneSheet(onDismiss = onDismissRequest) { content() }
        return
    }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Box(
            modifier =
                Modifier
                    .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                    .background(TallyColors.ground, RectangleShape),
        ) {
            content()
        }
    }
}

/**
 * Tally [com.github.damontecres.wholphin.ui.components.ScrollableDialog]: the same scrolling text panel (UP/DOWN
 * scroll it), square and hairline-framed.
 */
@Composable
fun TallyScrollableDialog(
    onDismissRequest: () -> Unit,
    width: Dp,
    maxHeight: Dp,
    itemSpacing: Dp,
    content: LazyListScope.() -> Unit,
) {
    if (isPhone()) {
        PhoneSheet(onDismiss = onDismissRequest) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = PhoneDimens.margin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
                content = content,
                modifier = Modifier.fillMaxWidth().heightIn(max = phoneSheetMaxHeight()),
            )
        }
        return
    }
    val scrollAmount = 100f
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LazyColumn(
            state = columnState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            content = content,
            modifier =
                Modifier
                    .width(width)
                    .heightIn(max = maxHeight)
                    .focusable()
                    .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                    .background(TallyColors.ground, RectangleShape)
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyUp) return@onKeyEvent false
                        val amount =
                            when (it.key) {
                                Key.DirectionDown -> scrollAmount
                                Key.DirectionUp -> -scrollAmount
                                else -> return@onKeyEvent false
                            }
                        scope.launch(ExceptionHandler()) { columnState.scrollBy(amount) }
                        true
                    },
        )
    }
}
