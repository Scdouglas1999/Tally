package io.github.scdouglas1999.tally.ui.settings

import android.os.SystemClock
import android.view.Gravity
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.preferences.NavDrawerPin
import com.github.damontecres.wholphin.ui.preferences.user.PreferredLanguageType
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButtonKind
import io.github.scdouglas1999.tally.ui.settings.phone.isPhone
import io.github.scdouglas1999.tally.ui.settings.phone.phoneSheetListMaxHeight
import io.github.scdouglas1999.tally.ui.settings.phone.phoneTouch
import io.github.scdouglas1999.tally.ui.setup.TallyField
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Square glyph buttons beside a row (move up, move down, delete). */
private val GLYPH_BUTTON = 40.dp

/** Space between a row and its buttons, and between the buttons. */
private val BUTTON_GAP = 8.dp

/** Upstream's language filter waits this long after the last key before filtering. */
private const val FILTER_DELAY_MS = 500L

private val captionStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
    )

private val errorStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

// ---------------------------------------------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------------------------------------------

/**
 * Upstream's language filter: a blank query shows every option; otherwise only languages whose name or ISO code
 * contains it, the ones whose name starts with it first ("en": English before Armenian).
 */
internal fun filterLanguages(
    options: List<PreferredLanguageType>,
    query: String,
): List<PreferredLanguageType> {
    if (query.isBlank()) return options
    val q = query.lowercase()
    return options
        .filterIsInstance<PreferredLanguageType.Language>()
        .filter { it.name.lowercase().contains(q) || it.iso.contains(q) }
        .sortedByDescending { it.name.lowercase().startsWith(q) }
}

// ---------------------------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------------------------

/**
 * Inside upstream's dialog window (a `BasicDialog`): the 60% scrim every Tally panel has. [top]: the window sits at
 * the top of the screen (panels with text fields, which open the TV keyboard over the lower half).
 */
@Composable
private fun PanelScrim(top: Boolean = false) {
    // A phone draws these panels in a bottom sheet, which has its own scrim and place.
    if (isPhone()) return
    val view = LocalView.current
    // 64dp at the Tally scale, as the text input panel sits; this runs at the real density.
    val topOffset = with(LocalDensity.current) { (TOP_OFFSET * TALLY_SCALE).roundToPx() }
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0.6f)
            if (top) {
                window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                window.attributes = window.attributes.apply { y = topOffset }
            }
        }
    }
}

private val TOP_OFFSET = 64.dp
private const val TALLY_SCALE = 0.8f

/** A 40dp square glyph button: TallyIconButton's frame (1dp ruleStrong, focused 3dp accent), no caption under it. */
@Composable
internal fun TallyGlyphButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    if (isPhone()) {
        // A 48dp touch target with the TV button's frame.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                modifier
                    .size(PhoneDimens.touchTarget)
                    .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                    .phoneTouch(onClick = onClick, interactionSource = interactionSource),
        ) {
            Text(text = glyph, fontFamily = FontAwesome, fontSize = 18.sp, color = TallyColors.text)
        }
        return
    }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong), shape = RectangleShape),
                focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier.size(GLYPH_BUTTON),
    ) {
        // A fixed-size tv Surface lays its content out top-left: fill it and center.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = glyph, fontFamily = FontAwesome, fontSize = 18.sp, color = TallyColors.text)
        }
    }
}

/** What a row's buttons are: shown as the focused button's caption, and the move bar while a move button has focus. */
internal enum class RowButton(
    @param:StringRes val label: Int,
) {
    MOVE_UP(R.string.tally_settings_move_up),
    MOVE_DOWN(R.string.tally_settings_move_down),
    DELETE(R.string.delete),
}

/**
 * Move up / move down (and delete) beside a row, as the playlist rundown has them: arrows, and an empty slot where
 * upstream disables one (first row up, last row down), so the buttons never shift. [onFocused] reports the button
 * that has focus (null: none), read from the buttons' own focus state, so it survives the row moving.
 */
@Composable
internal fun RowButtons(
    moveUpAllowed: Boolean,
    moveDownAllowed: Boolean,
    onMove: (MoveDirection) -> Unit,
    onFocused: (RowButton?) -> Unit,
    modifier: Modifier = Modifier,
    deleteAllowed: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val upSource = remember { MutableInteractionSource() }
    val downSource = remember { MutableInteractionSource() }
    val deleteSource = remember { MutableInteractionSource() }
    val upFocused by upSource.collectIsFocusedAsState()
    val downFocused by downSource.collectIsFocusedAsState()
    val deleteFocused by deleteSource.collectIsFocusedAsState()
    val focusedButton =
        when {
            upFocused -> RowButton.MOVE_UP
            downFocused -> RowButton.MOVE_DOWN
            deleteFocused -> RowButton.DELETE
            else -> null
        }
    LaunchedEffect(focusedButton) { onFocused(focusedButton) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        GlyphSlot(
            allowed = moveUpAllowed,
            glyph = stringResource(R.string.tally_settings_fa_arrow_up),
            onClick = { onMove(MoveDirection.UP) },
            interactionSource = upSource,
        )
        GlyphSlot(
            allowed = moveDownAllowed,
            glyph = stringResource(R.string.tally_settings_fa_arrow_down),
            onClick = { onMove(MoveDirection.DOWN) },
            interactionSource = downSource,
        )
        if (onDelete != null) {
            GlyphSlot(
                allowed = deleteAllowed,
                glyph = stringResource(R.string.tally_settings_fa_trash),
                onClick = onDelete,
                interactionSource = deleteSource,
            )
        }
    }
}

@Composable
private fun GlyphSlot(
    allowed: Boolean,
    glyph: String,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
) {
    if (allowed) {
        TallyGlyphButton(glyph = glyph, onClick = onClick, interactionSource = interactionSource)
    } else {
        Box(Modifier.size(if (isPhone()) PhoneDimens.touchTarget else GLYPH_BUTTON))
    }
}

/** The focused button's label, mono muted, inside the row it belongs to (so icon-only buttons stay legible). */
@Composable
internal fun RowButtonCaption(button: RowButton?) {
    if (button == null) return
    Text(
        text = stringResource(button.label).tallyUppercase(),
        style = captionStyle,
        color = TallyColors.muted,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Home rows (ui/main/settings)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.main.settings.HomeRowConfigContent]: the row's title as a settings row,
 * then move up, move down and delete. While a move button has focus the row shows the accent move bar.
 */
@Composable
fun TallyHomeRowConfigItem(
    title: String,
    moveUpAllowed: Boolean,
    moveDownAllowed: Boolean,
    deleteAllowed: Boolean,
    onClick: () -> Unit,
    onClickMove: (MoveDirection) -> Unit,
    onClickDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedButton by remember { mutableStateOf<RowButton?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    TallyScale {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
            modifier = modifier,
        ) {
            PreferenceRowContent(
                title = title,
                summary = null,
                onClick = onClick,
                onLongClick = null,
                interactionSource = interactionSource,
                modifier = Modifier.weight(1f),
                moving = focusedButton == RowButton.MOVE_UP || focusedButton == RowButton.MOVE_DOWN,
            ) {
                RowButtonCaption(focusedButton)
            }
            RowButtons(
                moveUpAllowed = moveUpAllowed,
                moveDownAllowed = moveDownAllowed,
                onMove = onClickMove,
                onFocused = { focusedButton = it },
                deleteAllowed = deleteAllowed,
                onDelete = onClickDelete,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Navigation drawer pins
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.NavDrawerPreferenceDialog]: one row per drawer entry with
 * its pinned switch (OK toggles it), move up / move down beside it. Closing saves, as upstream's does.
 */
@Composable
fun TallyNavDrawerPinsDialog(
    items: List<NavDrawerPin>,
    onDismissRequest: () -> Unit,
    onClick: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isEmpty()) return@LaunchedEffect
        repeat(8) {
            if (firstFocus.tryRequestFocus("tally-nav-pins")) return@LaunchedEffect
            delay(40)
        }
    }
    TallyPanelWindow(onDismissRequest = onDismissRequest) {
        TallyPanelFrame(
            kicker = stringResource(R.string.nav_drawer_pins),
            onBack = onDismissRequest,
            width = 600.dp,
            trapHorizontal = false,
        ) {
            val phone = isPhone()
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = if (phone) 0.dp else 20.dp - FOCUS_ROOM)
                        .heightIn(max = if (phone) phoneSheetListMaxHeight() else 416.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (phone) 0.dp else FOCUS_ROOM),
            ) {
                items.forEachIndexed { index, pin ->
                    key(pin.id) {
                        PinRow(
                            pin = pin,
                            index = index,
                            last = index == items.lastIndex,
                            onClick = {
                                if (SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS) onClick(index)
                            },
                            onMove = { if (it == MoveDirection.UP) onMoveUp(index) else onMoveDown(index) },
                            rowModifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp - FOCUS_ROOM))
        }
    }
}

@Composable
private fun PinRow(
    pin: NavDrawerPin,
    index: Int,
    last: Boolean,
    onClick: () -> Unit,
    onMove: (MoveDirection) -> Unit,
    rowModifier: Modifier,
) {
    val bringer = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var hasFocus by remember { mutableStateOf(false) }
    var focusedButton by remember { mutableStateOf<RowButton?>(null) }
    // A move keeps focus on the same button while the row changes place: keep the row in view.
    LaunchedEffect(index) {
        if (hasFocus) bringer.bringIntoView()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        modifier =
            Modifier
                .bringIntoViewRequester(bringer)
                .padding(vertical = FOCUS_ROOM)
                .onFocusChanged {
                    hasFocus = it.hasFocus
                    if (it.hasFocus) scope.launch { bringer.bringIntoView() }
                }.focusProperties {
                    if (index == 0) up = FocusRequester.Cancel
                    if (last) down = FocusRequester.Cancel
                },
    ) {
        TallyDialogRow(
            onClick = onClick,
            headline = { Text(pin.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RowButtonCaption(focusedButton)
                    TallySquareSwitch(checked = pin.pinned)
                }
            },
            modifier =
                rowModifier
                    .weight(1f)
                    .moveBar(focusedButton != null),
        )
        RowButtons(
            moveUpAllowed = index > 0,
            moveDownAllowed = !last,
            onMove = onMove,
            onFocused = { focusedButton = it },
            modifier = if (isPhone()) Modifier.padding(end = PhoneDimens.margin) else Modifier,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Preferred language (user profile page)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.user.FilterableLanguagePreference], drawn inside upstream's
 * dialog: the filter field, then the languages as panel rows with the current one marked. Typing filters as
 * upstream's does (half a second after the last key); DOWN from the field reaches the list, UP from its first row
 * returns.
 */
@Composable
fun TallyFilterableLanguagePreference(
    @StringRes title: Int,
    selectedOption: PreferredLanguageType,
    options: List<PreferredLanguageType>,
    onClickOption: (PreferredLanguageType) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filtered by remember { mutableStateOf(options) }
    LaunchedEffect(query) {
        delay(FILTER_DELAY_MS)
        filtered = withContext(WholphinDispatchers.Default) { filterLanguages(options, query) }
    }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val entries =
        filtered.map { option ->
            when (option) {
                PreferredLanguageType.Divider -> {
                    PanelEntry.Divider
                }

                is PreferredLanguageType.ServerProfile -> {
                    PanelEntry.Item(
                        onClick = { onClickOption(option) },
                        headline = { Text(stringResource(R.string.use_user_profile), maxLines = 1) },
                        marked = option == selectedOption,
                        supporting = option.name?.let { name -> { Text(name, maxLines = 1) } },
                    )
                }

                else -> {
                    PanelEntry.Item(
                        onClick = { onClickOption(option) },
                        headline = {
                            Text(option.displayString.getString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        marked = option == selectedOption,
                    )
                }
            }
        }
    PanelScrim()
    TallyScale {
        TallyPanelFrame(
            kicker = stringResource(title),
            onBack = {},
            trapHorizontal = false,
        ) {
            TallyField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search),
                imeAction = ImeAction.Search,
                // Search: to the list, as upstream's search button does.
                keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier =
                    Modifier
                        .padding(horizontal = if (isPhone()) PhoneDimens.margin else 20.dp)
                        .padding(top = FOCUS_ROOM, bottom = 12.dp - FOCUS_ROOM)
                        .focusRequester(fieldFocus),
            )
            val start =
                initialListIndex(
                    enabled = entries.map { it is PanelEntry.Item },
                    marked = entries.map { it is PanelEntry.Item && it.marked },
                )
            TallyPanelList(
                entries = entries,
                initialIndex = start,
                canClick = { SystemClock.elapsedRealtime() - openedAt >= OPEN_GRACE_MS },
                focusKey = title,
                onUpFromFirst = { fieldFocus.tryRequestFocus("tally-language-field") },
                refocusOnChange = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Quick Connect (settings: authorize another device)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.preferences.QuickConnectDialog], on upstream's state: the code field
 * (number keyboard, opened at once), upstream's error under it, `SUBMIT`. The panel sits in the upper half, above
 * the TV keyboard.
 */
@Composable
fun TallyQuickConnectDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    showError: Boolean,
    onSubmit: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            if (fieldFocus.tryRequestFocus("tally-quick-connect-code")) return@LaunchedEffect
            delay(40)
        }
    }
    TallyPanelWindow(onDismissRequest = onDismissRequest, contentAlignment = Alignment.TopCenter) {
        TallyPanelFrame(
            kicker = stringResource(R.string.quick_connect_code),
            onBack = onDismissRequest,
            modifier = Modifier.padding(top = 64.dp),
            width = 520.dp,
            trapHorizontal = false,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = if (isPhone()) PhoneDimens.margin else 20.dp).padding(top = FOCUS_ROOM),
            ) {
                TallyField(
                    value = code,
                    onValueChange = onCodeChange,
                    placeholder = "",
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.focusRequester(fieldFocus),
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.quick_connect_code_error),
                        style = errorStyle,
                        color = TallyColors.liveText,
                    )
                }
            }
            if (isPhone()) {
                PhoneButton(
                    label = stringResource(R.string.submit),
                    onClick = onSubmit,
                    kind = PhoneButtonKind.PRIMARY,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PhoneDimens.margin)
                            .padding(top = 18.dp, bottom = 8.dp),
                )
            } else {
                Row(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp)) {
                    TallyButton(label = stringResource(R.string.submit), onClick = onSubmit, primary = true)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Seerr server (settings: add a server)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.setup.seerr.AddSeerrServerApiKey]: URL and API key fields, upstream's
 * error, `SUBMIT` (enabled as upstream's is).
 */
@Composable
fun TallySeerrApiKeyForm(
    onSubmit: (url: String, apiKey: String) -> Unit,
    status: LoadingState,
) {
    var error by remember(status) { mutableStateOf((status as? LoadingState.Error)?.localizedMessage) }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val urlFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { urlFocus.tryRequestFocus("tally-seerr-url") }
    SeerrForm(
        kicker = stringResource(R.string.enter_url_api_key),
        error = error,
        submitEnabled = error.isNullOrBlank() && url.isNotNullOrBlank() && apiKey.isNotNullOrBlank(),
        loading = false,
        onSubmit = { onSubmit(url, apiKey) },
    ) {
        TallyField(
            value = url,
            onValueChange = {
                error = null
                url = it
            },
            placeholder = stringResource(R.string.url),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { keyFocus.tryRequestFocus() }),
            modifier = Modifier.focusRequester(urlFocus),
        )
        TallyField(
            value = apiKey,
            onValueChange = {
                error = null
                apiKey = it
            },
            placeholder = stringResource(R.string.api_key),
            password = true,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { onSubmit(url, apiKey) }),
            modifier = Modifier.focusRequester(keyFocus),
        )
    }
}

/**
 * Tally [com.github.damontecres.wholphin.ui.setup.seerr.AddSeerrServerUsername]: URL, username (prefilled with the
 * signed-in user, as upstream's) and password; upstream's error; `SUBMIT` (enabled as upstream's is).
 */
@Composable
fun TallySeerrUsernameForm(
    onSubmit: (url: String, username: String, password: String) -> Unit,
    username: String,
    status: LoadingState,
) {
    var error by remember(status) { mutableStateOf((status as? LoadingState.Error)?.localizedMessage) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(username) }
    var password by remember { mutableStateOf("") }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { urlFocus.tryRequestFocus("tally-seerr-url") }
    val loading = status == LoadingState.Loading
    SeerrForm(
        kicker = stringResource(R.string.username_or_password),
        error = error,
        submitEnabled = error.isNullOrBlank() && url.isNotNullOrBlank() && user.isNotNullOrBlank() && !loading,
        loading = loading,
        onSubmit = { onSubmit(url, user, password) },
    ) {
        TallyField(
            value = url,
            onValueChange = {
                error = null
                url = it
            },
            placeholder = stringResource(R.string.url),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { userFocus.tryRequestFocus() }),
            modifier = Modifier.focusRequester(urlFocus),
        )
        TallyField(
            value = user,
            onValueChange = {
                error = null
                user = it
            },
            placeholder = stringResource(R.string.username),
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { passwordFocus.tryRequestFocus() }),
            modifier = Modifier.focusRequester(userFocus),
        )
        TallyField(
            value = password,
            onValueChange = {
                error = null
                password = it
            },
            placeholder = stringResource(R.string.password),
            password = true,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { onSubmit(url, user, password) }),
            modifier = Modifier.focusRequester(passwordFocus),
        )
    }
}

/** The Seerr forms' panel: kicker, the fields, the error in red, `SUBMIT`. */
@Composable
private fun SeerrForm(
    kicker: String,
    error: String?,
    submitEnabled: Boolean,
    loading: Boolean,
    onSubmit: () -> Unit,
    fields: @Composable () -> Unit,
) {
    PanelScrim(top = true)
    TallyScale {
        TallyPanelFrame(kicker = kicker, onBack = {}, width = 560.dp, trapHorizontal = false) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = FOCUS_ROOM),
            ) {
                fields()
                if (!error.isNullOrBlank()) {
                    Text(text = error, style = errorStyle, color = TallyColors.liveText)
                }
            }
            Row(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp)) {
                TallyButton(
                    label = stringResource(if (loading) R.string.tally_settings_connecting else R.string.submit),
                    onClick = onSubmit,
                    primary = true,
                    enabled = submitEnabled,
                )
            }
        }
    }
}
