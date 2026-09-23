package io.github.scdouglas1999.tally.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.playback.isEnterKey
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.QuickConnectResult

/**
 * The setup screens (server and user pickers, sign-in, PIN, the update page) are drawn by Tally while the TALLY
 * theme is selected. The app's theme lives in the app preferences, which load before anyone signs in (MainActivity
 * wraps these screens in `WholphinTheme` with it), and TALLY is the default, so a fresh install gets the Tally look.
 */
@Composable
fun tallySetupActive(): Boolean = LocalTheme.current == AppThemeColors.TALLY

internal val SetupTileSize = 128.dp
internal val SetupTileSlot = 176.dp
internal val SetupFormWidth = 520.dp

private val WORDMARK_SIZE = 28.sp
private val headerWordmark =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = WORDMARK_SIZE,
        letterSpacing = 0.30.em,
    )

// The launch card's proportions (TallyLaunch), at a 28sp wordmark.
private val HeaderLamp = (28 * 0.52).dp
private val HeaderGap = (28 * 0.62).dp
private val HeaderNudge = (28 * 0.026).dp
private val KickerLamp = 10.dp

internal val tileName =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

internal val monoSmall =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

private val tileInitial =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
    )

internal val setupBody =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    )

private val fieldInput =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    )

private val fieldPlaceholder =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 1.5.sp,
    )

private val quickConnectCode =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 48.sp,
        letterSpacing = 0.18.em,
    )

private val pinDigit =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
    )

/**
 * A full-screen setup step: the lit lamp and the TALLY wordmark at the top-left (the launch card's layout, small),
 * the step's [kicker] under it in accent (with [kickerLamp], a small lamp before it), an optional muted [subtitle]
 * line, and the step's content over the whole screen (callers center it).
 */
@Composable
internal fun TallySetupFrame(
    kicker: String,
    modifier: Modifier = Modifier,
    kickerLamp: LampState? = null,
    subtitle: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    TallySurface(modifier) {
        content()
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = TallyDimens.marginHorizontal, top = TallyDimens.marginVertical),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TallyLamp(
                    state = LampState.Lit,
                    size = HeaderLamp,
                    modifier = Modifier.offset(y = HeaderNudge),
                )
                Spacer(Modifier.width(HeaderGap))
                Text(
                    text = stringResource(R.string.tally_lamp_wordmark),
                    style = headerWordmark,
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (kickerLamp != null) {
                    TallyLamp(state = kickerLamp, size = KickerLamp)
                }
                Text(
                    text = kicker.tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = monoSmall,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A square server or user tile: 128dp on `groundRaised`, 1dp `rule` border, 3dp accent when focused; the [name]
 * under it (Sans Medium) and an optional mono [detail] line (a server address).
 */
@Composable
internal fun SetupTile(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    muted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    tileModifier: Modifier = Modifier,
    face: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(SetupTileSlot),
    ) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = TallyColors.groundRaised,
                    contentColor = TallyColors.text,
                    focusedContainerColor = TallyColors.groundRaised,
                    focusedContentColor = TallyColors.text,
                    pressedContainerColor = TallyColors.groundRaised,
                    pressedContentColor = TallyColors.text,
                ),
            border =
                ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(TallyDimens.hairline, TallyColors.rule), shape = RectangleShape),
                    focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                    pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                ),
            glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
            modifier = tileModifier.size(SetupTileSize),
        ) {
            // A fixed-size tv Surface lays its content out top-left: fill it and center.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = face)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = name,
            style = tileName,
            color = if (muted) TallyColors.muted else TallyColors.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = monoSmall,
                color = TallyColors.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The tile's face: a picture cropped square, or [initialOf]'s first letter in Mono SemiBold 40sp. */
@Composable
internal fun BoxScope.TileFace(
    initialOf: String,
    imageUrl: String? = null,
    initialColor: androidx.compose.ui.graphics.Color = TallyColors.text,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    if (!imageUrl.isNullOrBlank() && !failed) {
        AsyncImage(
            model = imageUrl,
            contentDescription = initialOf,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Text(
            text = initialOf.firstOrNull()?.uppercase() ?: "?",
            style = tileInitial,
            color = initialColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** The `+` face of the Add Server / Add User tiles. */
@Composable
internal fun BoxScope.PlusFace() {
    Text(
        text = "+",
        style = tileInitial,
        color = TallyColors.muted,
        textAlign = TextAlign.Center,
    )
}

/**
 * The Tally text field: `groundRaised`, 1dp `ruleStrong` border (3dp accent while focused), a mono uppercase
 * [placeholder] in `muted` while empty, Sans 20sp input.
 */
@Composable
internal fun TallyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = fieldInput.copy(color = TallyColors.text),
        cursorBrush = SolidColor(TallyColors.accent),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
        keyboardActions = keyboardActions,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = interactionSource,
        // UP / DOWN leave a one-line field. Compose does this itself only for a remote's D-pad device, not for
        // arrow keys from a keyboard or a virtual (adb, IP remote app) input device.
        modifier =
            modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(TallyColors.groundRaised)
                        .border(
                            if (focused) TallyDimens.focusBorder else TallyDimens.hairline,
                            if (focused) TallyColors.accent else TallyColors.ruleStrong,
                        ).padding(horizontal = 16.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder.tallyUppercase(),
                        style = fieldPlaceholder,
                        color = TallyColors.muted,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

/** The lines of an upstream [LoadingState.Error], in red. Nothing for any other state. */
@Composable
internal fun SetupError(
    state: LoadingState,
    modifier: Modifier = Modifier,
) {
    val error = state as? LoadingState.Error ?: return
    val lines =
        buildList {
            error.message?.let(::add)
            error.exception?.localizedMessage?.let(::add)
            error.exception
                ?.cause
                ?.localizedMessage
                ?.let { add(stringResource(R.string.tally_signin_cause, it)) }
        }
    if (lines.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach {
            Text(text = it, style = setupBody, color = TallyColors.liveText)
        }
    }
}

/**
 * Requests focus on [requester] until [isFocused] says it (or something under it) has it. It waits a frame first:
 * a request made before the target's own effects start is lost to its focus visuals (the interaction is emitted
 * before anything collects it, so the element is focused but draws unfocused), and the tiles of a LazyRow are not
 * attached on the first frame (the old "nothing focused on Select Server" bug).
 */
internal suspend fun requestUntilFocused(
    requester: FocusRequester,
    isFocused: () -> Boolean,
    focusManager: FocusManager,
    tag: String,
) {
    delay(50)
    // Compose may already have moved focus here on the first frame (the element focused before was removed), before
    // this element collects focus interactions: it is then focused but draws unfocused. Take focus again.
    if (isFocused()) focusManager.clearFocus(force = true)
    repeat(40) {
        if (isFocused()) return
        requester.tryRequestFocus(tag)
        delay(50)
    }
}

/** A modifier that gives its element focus on arrival (again whenever [key] changes), via [requestUntilFocused]. */
@Composable
internal fun initialFocus(
    tag: String,
    key: Any? = Unit,
): Modifier {
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(key) { requestUntilFocused(requester, { focused }, focusManager, tag) }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
}

/** Address entry for a new server: the field, errors under it, `CONNECT`. */
@Composable
internal fun TallyAddressEntry(
    state: LoadingState,
    onSubmit: (String) -> Unit,
    onUrlChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    LaunchedEffect(url) { onUrlChanged() }
    val submit = { if (url.isNotBlank()) onSubmit(url) }
    Column(
        modifier = modifier.width(SetupFormWidth),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_signin_address_help),
            style = setupBody,
            color = TallyColors.textSecondary,
        )
        TallyField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(R.string.tally_signin_address_placeholder),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = initialFocus("tally-address"),
        )
        SetupError(state)
        TallyButton(
            label =
                stringResource(
                    if (state == LoadingState.Loading) R.string.tally_signin_connecting else R.string.tally_signin_connect,
                ),
            onClick = submit,
            primary = true,
            enabled = url.isNotBlank() && state == LoadingState.Pending,
        )
    }
}

/**
 * The Quick Connect step: the code, huge and split in two (`831 550`), upstream's instruction under it, errors,
 * and the way out to a username and password.
 */
@Composable
internal fun TallyQuickConnect(
    serverName: String,
    status: QuickConnectResult?,
    switchUserState: LoadingState,
    onUsePassword: () -> Unit,
    modifier: Modifier = Modifier,
    trouble: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.width(SetupFormWidth + 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (status == null) {
            if (switchUserState !is LoadingState.Error) {
                Text(
                    text = stringResource(R.string.tally_signin_quick_connect_waiting).tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.muted,
                )
            }
        } else {
            Text(
                text = splitCode(status.code),
                style = quickConnectCode,
                color = TallyColors.text,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.tally_signin_quick_connect_help, serverName),
                style = setupBody,
                color = TallyColors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        SetupError(switchUserState)
        Spacer(Modifier.height(4.dp))
        TallyButton(
            label = stringResource(R.string.tally_signin_use_password),
            onClick = onUsePassword,
            modifier = initialFocus("tally-quick-connect"),
        )
        trouble()
    }
}

/** `831550` → `831 550`; anything that is not six characters is shown as it is. */
internal fun splitCode(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

/** Username and password, errors in red under the password, `SIGN IN`. */
@Composable
internal fun TallyCredentials(
    serverName: String,
    initialUsername: String,
    switchUserState: LoadingState,
    onPasswordChanged: () -> Unit,
    onSubmit: (username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    trouble: @Composable () -> Unit = {},
) {
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    val passwordFocus = remember { FocusRequester() }
    val startOnPassword = remember { initialUsername.isNotBlank() }
    LaunchedEffect(password) { onPasswordChanged() }
    val submit = { if (username.isNotBlank()) onSubmit(username, password) }
    Column(
        modifier = modifier.width(SetupFormWidth),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_signin_credentials_help, serverName),
            style = setupBody,
            color = TallyColors.textSecondary,
        )
        TallyField(
            value = username,
            onValueChange = { username = it },
            placeholder = stringResource(R.string.tally_signin_username),
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { passwordFocus.tryRequestFocus() }),
            modifier = if (startOnPassword) Modifier else initialFocus("tally-username"),
        )
        TallyField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.tally_signin_password),
            password = true,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier =
                Modifier
                    .focusRequester(passwordFocus)
                    .then(if (startOnPassword) initialFocus("tally-password") else Modifier),
        )
        SetupError(switchUserState)
        TallyButton(
            label = stringResource(R.string.tally_signin_sign_in),
            onClick = submit,
            primary = true,
            enabled = username.isNotBlank(),
        )
        trouble()
    }
}

/** After a few failed attempts: upstream's way to the debug page. */
@Composable
internal fun TallyTrouble(
    loginAttempts: Int,
    onShowDebug: () -> Unit,
) {
    if (loginAttempts <= 2) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_signin_trouble),
            style = setupBody,
            color = TallyColors.textSecondary,
        )
        TallyButton(
            label = stringResource(R.string.tally_signin_debug_info),
            onClick = onShowDebug,
        )
    }
}

/** Remote keys a PIN is made of: the four directions and the digits. Null for any other key. */
private fun pinChar(event: KeyEvent): String? =
    when (event.key) {
        Key.DirectionUp -> "U"
        Key.DirectionRight -> "R"
        Key.DirectionDown -> "D"
        Key.DirectionLeft -> "L"
        Key.Zero, Key.NumPad0 -> "0"
        Key.One, Key.NumPad1 -> "1"
        Key.Two, Key.NumPad2 -> "2"
        Key.Three, Key.NumPad3 -> "3"
        Key.Four, Key.NumPad4 -> "4"
        Key.Five, Key.NumPad5 -> "5"
        Key.Six, Key.NumPad6 -> "6"
        Key.Seven, Key.NumPad7 -> "7"
        Key.Eight, Key.NumPad8 -> "8"
        Key.Nine, Key.NumPad9 -> "9"
        else -> null
    }

/** What can be pressed: the four arrows and the digits (upstream's hint row). */
@Composable
private fun PinHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(R.string.fa_arrow_left_long, R.string.fa_arrow_up_long, R.string.fa_arrow_right_long, R.string.fa_arrow_down_long)
            .forEach {
                Text(text = stringResource(it), fontFamily = FontAwesome, fontSize = 16.sp, color = TallyColors.muted)
            }
        Text(text = stringResource(R.string.tally_signin_pin_digits), style = TallyType.label, color = TallyColors.muted)
    }
}

/** Square boxes, one per key pressed (at least four); a pressed key shows as a mono dot, never the key itself. */
@Composable
internal fun PinBoxes(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(maxOf(4, count)) { index ->
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(TallyColors.groundRaised)
                        .border(
                            TallyDimens.hairline,
                            if (index < count) TallyColors.textSecondary else TallyColors.ruleStrong,
                        ),
            ) {
                if (index < count) {
                    // The bullet sits below the middle of its line box; lift it to the box's center.
                    Text(text = "•", style = pinDigit, color = TallyColors.text, modifier = Modifier.offset(y = (-3).dp))
                }
            }
        }
    }
}

/**
 * PIN entry for a profile: the hint, the boxes, and the server sign-in button, which is also where the keys are
 * read (upstream's design: the focused button takes the arrows and digits, OK signs in with the server instead).
 */
@Composable
internal fun TallyPinEntry(
    onTextChange: (String) -> Unit,
    onClickServerAuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PinHint()
        PinBoxes(input.length)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TallyButton(
                label = stringResource(R.string.tally_signin_pin_server),
                onClick = onClickServerAuth,
                modifier =
                    initialFocus("tally-pin")
                        .onKeyEvent {
                            if (it.type != KeyEventType.KeyUp) return@onKeyEvent false
                            val c = pinChar(it) ?: return@onKeyEvent false
                            input += c
                            onTextChange(input)
                            true
                        },
            )
            Text(
                text = stringResource(R.string.tally_signin_pin_removes),
                style = monoSmall,
                color = TallyColors.muted,
            )
        }
    }
}

/**
 * Choosing (or confirming, or removing) a PIN in settings: [title], the hint, the boxes, and "press OK to confirm"
 * when [onConfirm] is set. The whole block takes the keys, as upstream's `PinEntryCreate` does.
 */
@Composable
fun TallyPinEntryCreate(
    @StringRes title: Int,
    onTextChange: (String) -> Unit,
    onConfirm: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val interactionSource = remember { MutableInteractionSource() }
    // [modifier] (upstream passes padding for its own dialog) goes inside the panel, so the panel fills the dialog.
    TallyPanel {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier =
                modifier
                    .then(initialFocus("tally-pin-create"))
                    .onKeyEvent {
                        if (isEnterKey(it)) {
                            onConfirm?.invoke(input)
                            return@onKeyEvent true
                        }
                        if (it.type != KeyEventType.KeyUp) return@onKeyEvent false
                        val c = pinChar(it) ?: return@onKeyEvent false
                        input += c
                        onTextChange(input)
                        true
                    }.focusable(interactionSource = interactionSource),
        ) {
            Text(
                text = stringResource(title).tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
            )
            PinHint()
            PinBoxes(input.length)
            if (onConfirm != null) {
                Text(
                    text = stringResource(R.string.tally_signin_pin_confirm).tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.muted,
                )
            }
        }
    }
}

/** The PIN step as a dialog, for callers outside the Tally user picker (upstream's `PinEntryDialog`). */
@Composable
fun TallyPinDialog(
    onDismissRequest: () -> Unit,
    onTextChange: (String) -> Unit,
    onClickServerAuth: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TallyPanel {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = stringResource(R.string.tally_signin_kicker_pin).tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.accent,
                )
                TallyPinEntry(onTextChange = onTextChange, onClickServerAuth = onClickServerAuth)
            }
        }
    }
}

/** A square Tally panel for dialog content: `ground`, 1dp `ruleStrong`, at the Tally scale. */
@Composable
private fun TallyPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    io.github.scdouglas1999.tally.ui.theme.TallyScale {
        Box(
            modifier =
                modifier
                    .background(TallyColors.ground)
                    .border(TallyDimens.hairline, TallyColors.ruleStrong)
                    .padding(32.dp),
        ) {
            content()
        }
    }
}
