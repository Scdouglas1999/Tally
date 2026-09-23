package io.github.scdouglas1999.tally.ui.setup.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneStatusBarPadding
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButtonKind
import io.github.scdouglas1999.tally.ui.settings.phone.phoneTouch
import io.github.scdouglas1999.tally.ui.setup.SetupError
import io.github.scdouglas1999.tally.ui.setup.splitCode
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType

private val WordmarkSize = 22.sp
private val wordmark =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = WordmarkSize,
        letterSpacing = 0.30.em,
    )

// The launch card's proportions (TallyLaunch), at the 22sp wordmark.
private val MarkLamp = (22 * 0.52).dp
private val MarkGap = (22 * 0.62).dp
private val MarkNudge = (22 * 0.026).dp
private val KickerLamp = 8.dp

/** A server or user row's square face. */
private val RowFace = 48.dp

private val faceInitial =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    )

/**
 * A setup step on a phone: under the status bar, the TALLY mark (lit lamp and wordmark) at the top, the step's
 * [kicker] in accent under it (with [kickerLamp] before it), an optional muted [subtitle], then the step's content
 * in the rest of the screen, above the gesture bar and the keyboard.
 */
@Composable
internal fun PhoneSetupFrame(
    kicker: String,
    kickerLamp: LampState?,
    subtitle: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(TallyColors.ground)
                .phoneStatusBarPadding()
                .navigationBarsPadding()
                .imePadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(top = 20.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TallyLamp(state = LampState.Lit, size = MarkLamp, modifier = Modifier.offset(y = MarkNudge))
                Spacer(Modifier.width(MarkGap))
                Text(
                    text = stringResource(R.string.tally_lamp_wordmark),
                    style = wordmark,
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (kickerLamp != null) TallyLamp(state = kickerLamp, size = KickerLamp)
                Text(
                    text = kicker.tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = PhoneType.meta,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

/** A mono label heading a group of setup rows ("FOUND ON YOUR NETWORK"). */
@Composable
internal fun PhoneSetupHeader(
    text: String,
    modifier: Modifier = Modifier,
    lamp: LampState? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin),
    ) {
        if (lamp != null) TallyLamp(state = lamp, size = KickerLamp)
        Text(text = text.tallyUppercase(), style = PhoneType.label, color = TallyColors.muted, maxLines = 1)
    }
}

/**
 * A server as a full-width row: its square face (the initial, colored by its state), the name and the address in
 * mono, and upstream's error in red under them when it has one. Tap connects, long-press opens its menu.
 */
@Composable
internal fun PhoneServerRow(
    name: String,
    address: String,
    initialOf: String,
    initialColor: Color,
    error: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .phoneTouch(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = PhoneDimens.margin, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(RowFace)
                    .background(TallyColors.groundRaised)
                    .border(PhoneDimens.hairline, TallyColors.rule),
        ) {
            Text(
                text = initialOf.firstOrNull()?.uppercase() ?: "?",
                style = faceInitial,
                color = initialColor,
                textAlign = TextAlign.Center,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = PhoneType.headline,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = address,
                style = PhoneType.meta,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (error != null) {
                Text(text = error, style = PhoneType.bodySmall, color = TallyColors.liveText, maxLines = 2)
            }
        }
    }
}

/**
 * A user as a grid square: the picture (or the initial) on `groundRaised` in a 1dp `rule` frame, the name under it
 * and an optional mono detail ("SIGNED IN"). [face] draws the square's content.
 */
@Composable
internal fun PhoneUserSquare(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    muted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    face: @Composable BoxScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.phoneTouch(onClick = onClick, onLongClick = onLongClick).padding(bottom = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(TallyColors.groundRaised)
                    .border(PhoneDimens.hairline, TallyColors.rule),
            content = face,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = PhoneType.body,
            color = if (muted) TallyColors.muted else TallyColors.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = detail?.tallyUppercase() ?: "",
            style = PhoneType.label,
            color = TallyColors.muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The phone's text field: `groundRaised`, 1dp `ruleStrong` (accent while focused), 48dp, the [placeholder] in
 * `muted` (as written: an address hint keeps its case), Sans input. A [password] field has a show/hide button at
 * its right.
 */
@Composable
internal fun PhoneField(
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
    var shown by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PhoneType.headline.copy(color = TallyColors.text, fontWeight = FontWeight.Normal),
        cursorBrush = SolidColor(TallyColors.accent),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
        keyboardActions = keyboardActions,
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(PhoneDimens.touchTarget)
                        .background(TallyColors.groundRaised)
                        .border(PhoneDimens.hairline, if (focused) TallyColors.accent else TallyColors.ruleStrong)
                        .padding(start = 14.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = PhoneType.body, color = TallyColors.muted, maxLines = 1)
                    }
                    inner()
                }
                if (password) {
                    val label =
                        stringResource(
                            if (shown) R.string.tally_phone_system_hide_password else R.string.tally_phone_system_show_password,
                        )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(PhoneDimens.touchTarget)
                                .semantics { contentDescription = label }
                                .phoneTouch(onClick = { shown = !shown }),
                    ) {
                        Text(
                            text = stringResource(if (shown) R.string.fa_eye_slash else R.string.fa_eye),
                            fontFamily = FontAwesome,
                            fontSize = 16.sp,
                            color = TallyColors.muted,
                        )
                    }
                }
            }
        },
    )
}

/** Address entry for a new server on a phone: the help, the address field (URL keyboard, up at once), `CONNECT`. */
@Composable
internal fun PhoneAddressEntry(
    state: LoadingState,
    onSubmit: (String) -> Unit,
    onUrlChanged: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    LaunchedEffect(url) { onUrlChanged() }
    val submit = { if (url.isNotBlank()) onSubmit(url) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.tryRequestFocus("tally-phone-address") }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin),
    ) {
        Text(
            text = stringResource(R.string.tally_signin_address_help),
            style = PhoneType.body,
            color = TallyColors.textSecondary,
        )
        PhoneField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(R.string.tally_phone_system_address_hint),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.focusRequester(fieldFocus),
        )
        SetupError(state)
        PhoneButton(
            label =
                stringResource(
                    if (state == LoadingState.Loading) R.string.tally_signin_connecting else R.string.tally_signin_connect,
                ),
            onClick = submit,
            kind = PhoneButtonKind.PRIMARY,
            enabled = url.isNotBlank() && state == LoadingState.Pending,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Username and password on a phone (the password with show/hide), errors in red under them, `SIGN IN` full width in
 * accent, then [below] (Quick Connect, and upstream's way to the debug page after failed attempts).
 */
@Composable
internal fun PhoneCredentials(
    serverName: String,
    initialUsername: String,
    switchUserState: LoadingState,
    onPasswordChanged: () -> Unit,
    onSubmit: (username: String, password: String) -> Unit,
    below: @Composable () -> Unit,
) {
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(password) { onPasswordChanged() }
    val submit = { if (username.isNotBlank()) onSubmit(username, password) }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin)
                .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.tally_signin_credentials_help, serverName),
            style = PhoneType.body,
            color = TallyColors.textSecondary,
        )
        PhoneField(
            value = username,
            onValueChange = { username = it },
            placeholder = stringResource(R.string.tally_signin_username),
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { passwordFocus.tryRequestFocus() }),
        )
        PhoneField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.tally_signin_password),
            password = true,
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.focusRequester(passwordFocus),
        )
        SetupError(switchUserState)
        PhoneButton(
            label = stringResource(R.string.tally_signin_sign_in),
            onClick = submit,
            kind = PhoneButtonKind.PRIMARY,
            enabled = username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        below()
    }
}

/**
 * Quick Connect under the sign-in form on a phone: "OR USE QUICK CONNECT" with its lamp (sputtering while it waits,
 * catching when approved), the code in `PhoneType.scoreHero` mono split in two, and the TV's explanation.
 */
@Composable
internal fun PhoneQuickConnect(
    serverName: String,
    code: String?,
    lamp: LampState,
    showWaiting: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PhoneDimens.hairline)
                .background(TallyColors.rule),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            TallyLamp(state = lamp, size = KickerLamp)
            Text(
                text = stringResource(R.string.tally_phone_system_or_quick_connect).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
        if (code == null) {
            if (showWaiting) {
                Text(
                    text = stringResource(R.string.tally_signin_quick_connect_waiting).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                )
            }
        } else {
            Text(
                text = splitCode(code),
                style = PhoneType.scoreHero.copy(letterSpacing = 0.12.em),
                color = TallyColors.text,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.tally_signin_quick_connect_help, serverName),
                style = PhoneType.body,
                color = TallyColors.textSecondary,
            )
        }
    }
}
