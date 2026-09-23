package io.github.scdouglas1999.tally.ui.settings.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.preferences.StringInput
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.kit.TallyPressIndication
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.settings.PanelEntry
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.delay

/** A dialog or menu row in a sheet. */
internal val PhoneDialogRowHeight = 52.dp

/** A phone button: full-width or shared, 48dp (the touch target). */
internal val PhoneButtonHeight = 48.dp

/** What part of the screen a sheet may take before its list scrolls. */
private const val SHEET_MAX_FRACTION = 0.7f

/** The sheet's title line and the rule under it, taken from the height a list may use. */
private val SheetTitleAllowance = 44.dp

/** True on a phone: the settings, dialogs and menus draw their phone forms. */
@Composable
internal fun isPhone(): Boolean = LocalTallyFormFactor.current == TallyFormFactor.PHONE

/** The tallest a sheet's content gets: 70% of the screen. */
@Composable
internal fun phoneSheetMaxHeight(): Dp = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp

/** The tallest a sheet's list gets under its title: taller lists scroll. */
@Composable
internal fun phoneSheetListMaxHeight(): Dp = phoneSheetMaxHeight() - SheetTitleAllowance

/**
 * Tap and long-press for a phone row or button, with an optional [interactionSource] the caller reads (a settings
 * row reports its focus): the flat pressed state, focusable for a keyboard or D-pad, and a
 * [PhoneDimens.focusBorder] accent frame inside only while one is in use (never after a touch).
 */
@Composable
internal fun Modifier.phoneTouch(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    role: Role = Role.Button,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val showFocus = tallyFocusVisible()
    return this
        .drawWithContent {
            drawContent()
            if (focused && showFocus) {
                val w = PhoneDimens.focusBorder.toPx()
                drawRect(
                    color = TallyColors.accent,
                    topLeft = Offset(w / 2f, w / 2f),
                    size = Size(size.width - w, size.height - w),
                    style = Stroke(width = w),
                )
            }
        }.combinedClickable(
            interactionSource = source,
            indication = TallyPressIndication,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * A sheet's frame: the [title] in `PhoneType.label` (uppercase, `muted`) over a 1dp `rule`, then [content]. The
 * keyboard pushes the sheet up (text fields at the top of the sheet stay in view).
 */
@Composable
internal fun PhonePanelFrame(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(TallyColors.groundRaised)
                .imePadding(),
    ) {
        PhoneSheetTitle(title)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/** A sheet's title line and the rule under it. */
@Composable
internal fun PhoneSheetTitle(title: String) {
    Text(
        text = title.tallyUppercase(),
        style = PhoneType.label,
        color = TallyColors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 2.dp, bottom = 12.dp),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(PhoneDimens.hairline)
            .background(TallyColors.rule),
    )
}

/**
 * A 52dp sheet row: a `muted` glyph where the TV row has one, an optional mono overline, the [headline] in
 * `PhoneType.body` (red when [destructive]), a `muted` supporting line, the value at the right in mono and, when
 * [marked], the accent square (the check). No frame: the whole row is the touch target.
 */
@Composable
internal fun PhoneDialogRow(
    onClick: () -> Unit,
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    marked: Boolean = false,
    destructive: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    overline: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val labelColor = if (destructive) TallyColors.liveText else TallyColors.text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = PhoneDialogRowHeight)
                .phoneTouch(
                    onClick = onClick,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    interactionSource = interactionSource,
                ).alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = PhoneDimens.margin, vertical = 6.dp),
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.widthIn(min = 24.dp)) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                    ProvideTextStyle(PhoneType.headline) { leading() }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                    ProvideTextStyle(PhoneType.label) { overline() }
                }
            }
            CompositionLocalProvider(LocalContentColor provides labelColor) {
                ProvideTextStyle(PhoneType.body.copy(color = labelColor)) { headline() }
            }
            if (supporting != null) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                    ProvideTextStyle(PhoneType.bodySmall.copy(color = TallyColors.muted)) { supporting() }
                }
            }
        }
        if (trailing != null) {
            CompositionLocalProvider(LocalContentColor provides TallyColors.textSecondary) {
                ProvideTextStyle(PhoneType.meta.copy(color = TallyColors.textSecondary)) { trailing() }
            }
        }
        if (marked) {
            IndicatorSquare(color = TallyColors.accent, size = 8.dp)
        }
    }
}

private const val DISABLED_ALPHA = 0.4f

/** A hairline between groups of sheet rows. */
@Composable
internal fun PhoneSheetDivider() {
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(PhoneDimens.hairline)
            .background(TallyColors.rule),
    )
}

/** What a [PhoneButton] is: the page's one accent action, a plain outline, or the red outline of a destructive one. */
internal enum class PhoneButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE }

/**
 * A 48dp square button, its mono label centered: [PhoneButtonKind.PRIMARY] is the accent fill with `onAccent`
 * text; SECONDARY a 1dp `ruleStrong` outline; DESTRUCTIVE a 1dp `live` outline with the red label. Full width
 * unless [modifier] says otherwise.
 */
@Composable
internal fun PhoneButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PhoneButtonKind = PhoneButtonKind.SECONDARY,
    enabled: Boolean = true,
    glyph: (@Composable () -> Unit)? = null,
) {
    val fill = if (kind == PhoneButtonKind.PRIMARY) TallyColors.accent else Color.Transparent
    val frame =
        when (kind) {
            PhoneButtonKind.PRIMARY -> TallyColors.accent
            PhoneButtonKind.SECONDARY -> TallyColors.ruleStrong
            PhoneButtonKind.DESTRUCTIVE -> TallyColors.live
        }
    val labelColor =
        when {
            !enabled -> TallyColors.muted
            kind == PhoneButtonKind.PRIMARY -> TallyColors.onAccent
            kind == PhoneButtonKind.DESTRUCTIVE -> TallyColors.liveText
            else -> TallyColors.text
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(PhoneButtonHeight)
                .background(if (enabled) fill else Color.Transparent)
                .border(PhoneDimens.hairline, if (enabled) frame else TallyColors.rule)
                .phoneTouch(onClick = onClick, enabled = enabled)
                .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (glyph != null) {
                CompositionLocalProvider(LocalContentColor provides labelColor) { glyph() }
            }
            Text(
                text = label.tallyUppercase(),
                style = PhoneType.labelLarge,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A confirmation as a sheet: the [kicker] as the title, the [message] and an optional [detail], then two full-width
 * buttons: the confirming one outlined in `live`, and Cancel under it (nearest the thumb).
 */
@Composable
internal fun PhoneConfirmContent(
    kicker: String,
    message: String?,
    detail: String?,
    confirmLabel: String,
    cancelLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    detailIsError: Boolean = false,
) {
    PhonePanelFrame(title = kicker) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneDimens.margin)
                    .padding(top = 16.dp),
        ) {
            if (message != null) {
                Text(text = message, style = PhoneType.headline, color = TallyColors.text)
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = PhoneType.body,
                    color = if (detailIsError) TallyColors.liveText else TallyColors.textSecondary,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneDimens.margin)
                    .padding(top = 20.dp, bottom = 8.dp),
        ) {
            PhoneButton(
                label = confirmLabel,
                onClick = onConfirm,
                kind = PhoneButtonKind.DESTRUCTIVE,
                modifier = Modifier.fillMaxWidth(),
            )
            PhoneButton(label = cancelLabel, onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A square the size of a phone glyph slot, empty (keeps icon rows from shifting). */
@Composable
internal fun PhoneEmptySlot(size: Dp = PhoneDimens.touchTarget) {
    Box(Modifier.size(size))
}

/**
 * A panel's rows in a sheet: 52dp rows and hairline dividers, scrolling once taller than the sheet may be. The
 * [initialIndex] row (the current choice) is scrolled into view on arrival.
 */
@Composable
internal fun PhonePanelList(
    entries: List<PanelEntry>,
    initialIndex: Int,
    canClick: () -> Boolean,
) {
    val scroll = rememberScrollState()
    val bringers = remember(entries.size) { List(entries.size) { BringIntoViewRequester() } }
    LaunchedEffect(entries.size, initialIndex) {
        bringers.getOrNull(initialIndex)?.bringIntoView()
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = phoneSheetListMaxHeight())
                .verticalScroll(scroll),
    ) {
        entries.forEachIndexed { index, entry ->
            when (entry) {
                PanelEntry.Divider -> {
                    PhoneSheetDivider()
                }

                is PanelEntry.Item -> {
                    PhoneDialogRow(
                        onClick = { if (canClick()) entry.onClick() },
                        headline = entry.headline,
                        enabled = entry.enabled,
                        marked = entry.marked,
                        destructive = entry.destructive,
                        overline = entry.overline,
                        supporting = entry.supporting,
                        leading = entry.leading,
                        trailing = entry.trailing,
                        modifier = Modifier.bringIntoViewRequester(bringers[index]),
                    )
                }
            }
        }
    }
}

/**
 * A text entry sheet: the title, the field at the top with the keyboard up at once, then CANCEL and SAVE side by
 * side (SAVE in accent). The keyboard's action saves, as on the TV.
 */
@Composable
internal fun PhoneStringInput(
    input: StringInput,
    state: TextFieldState,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        repeat(8) {
            if (fieldFocus.tryRequestFocus("tally-phone-text-input")) {
                keyboard?.show()
                return@LaunchedEffect
            }
            delay(40)
        }
    }
    PhonePanelFrame(title = input.title) {
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        BasicTextField(
            state = state,
            keyboardOptions = input.keyboardOptions,
            onKeyboardAction = { onSave() },
            lineLimits =
                if (input.maxLines > 1) {
                    TextFieldLineLimits.MultiLine(1, input.maxLines)
                } else {
                    TextFieldLineLimits.SingleLine
                },
            textStyle = PhoneType.headline.copy(color = TallyColors.text),
            cursorBrush = SolidColor(TallyColors.accent),
            interactionSource = interactionSource,
            decorator = { innerTextField ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = PhoneButtonHeight)
                            .background(TallyColors.ground)
                            .border(PhoneDimens.hairline, if (focused) TallyColors.accent else TallyColors.ruleStrong)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    innerTextField()
                }
            },
            modifier =
                Modifier
                    .padding(horizontal = PhoneDimens.margin)
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .focusRequester(fieldFocus),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneDimens.margin)
                    .padding(top = 16.dp, bottom = 8.dp),
        ) {
            PhoneButton(
                label = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            PhoneButton(
                label = stringResource(R.string.save),
                onClick = onSave,
                kind = PhoneButtonKind.PRIMARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
