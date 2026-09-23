package io.github.scdouglas1999.tally.ui.setup.phone

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** Height of a key on the number pad. */
private val KeyHeight = 56.dp

/** Gap between keys. */
private val KeyGap = 8.dp

/** A PIN box and the square that marks an entered key in it. */
private val BoxSize = 44.dp
private val DotSize = 10.dp

/** The pad never grows wider than this (a tablet), so the keys stay a thumb's reach apart. */
private val PadMaxWidth = 360.dp

private val keyDigit =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        textAlign = TextAlign.Center,
    )

/**
 * The PIN boxes at phone size: at least four square boxes, one per key pressed; an entered key shows as a small
 * `text` square, never the key itself.
 */
@Composable
internal fun PhonePinBoxes(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(maxOf(4, count)) { index ->
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(BoxSize)
                        .background(TallyColors.ground)
                        .border(
                            PhoneDimens.hairline,
                            if (index < count) TallyColors.textSecondary else TallyColors.ruleStrong,
                        ),
            ) {
                if (index < count) Box(Modifier.size(DotSize).background(TallyColors.text))
            }
        }
    }
}

/**
 * The number pad a phone types a PIN with. A PIN is the keys pressed on a remote, so it can hold the four arrows
 * as well as digits: the arrows sit in a row above the digits, so a PIN chosen on the TV can be entered here too.
 * Then 1-9, and 0 between an empty cell and delete. Square keys, 1dp `ruleStrong`, mono digits.
 */
@Composable
internal fun PhonePinPad(
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KeyGap),
        modifier = modifier.widthIn(max = PadMaxWidth).fillMaxWidth(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            ARROWS.forEach { (key, glyph, description) ->
                PadKey(
                    onClick = { onKey(key) },
                    description = stringResource(description),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(glyph),
                        fontFamily = FontAwesome,
                        fontSize = 18.sp,
                        color = TallyColors.textSecondary,
                    )
                }
            }
        }
        DIGIT_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEach { key ->
                    when (key) {
                        "" -> {
                            Spacer(Modifier.weight(1f))
                        }

                        DELETE -> {
                            PadKey(
                                onClick = onDelete,
                                description = stringResource(R.string.tally_qa_b_pin_delete),
                                modifier = Modifier.weight(1f),
                                outlined = false,
                            ) {
                                Text(
                                    text = stringResource(R.string.tally_qa_b_fa_delete_left),
                                    fontFamily = FontAwesome,
                                    fontSize = 20.sp,
                                    color = TallyColors.textSecondary,
                                )
                            }
                        }

                        else -> {
                            PadKey(onClick = { onKey(key) }, description = key, modifier = Modifier.weight(1f)) {
                                Text(text = key, style = keyDigit, color = TallyColors.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(KeyHeight)
                .then(if (outlined) Modifier.border(PhoneDimens.hairline, TallyColors.ruleStrong) else Modifier)
                .semantics { contentDescription = description }
                .phoneClickable(onClick = onClick),
    ) {
        content()
    }
}

/**
 * The PIN step of the user picker on a phone: the boxes, the pad, then SIGN IN WITH THE SERVER (outlined) and the
 * TV's note that this removes the PIN. Every key reports the whole PIN so far, as the TV's remote keys do.
 */
@Composable
internal fun PhonePinEntry(
    onTextChange: (String) -> Unit,
    onClickServerAuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 8.dp, bottom = 16.dp),
    ) {
        PhonePinBoxes(input.length)
        Spacer(Modifier.height(24.dp))
        PhonePinPad(
            onKey = {
                input += it
                onTextChange(input)
            },
            onDelete = {
                input = input.dropLast(1)
                onTextChange(input)
            },
        )
        Spacer(Modifier.height(24.dp))
        PhoneButton(
            label = stringResource(R.string.tally_signin_pin_server),
            onClick = onClickServerAuth,
            modifier = Modifier.widthIn(max = PadMaxWidth).fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tally_signin_pin_removes),
            style = PhoneType.bodySmall,
            color = TallyColors.muted,
        )
    }
}

/**
 * Choosing, confirming or removing a PIN in settings on a phone (upstream's `PinEntryCreate`, inside the sheet its
 * dialog becomes): [title] in accent, the boxes, the pad, and CONFIRM (accent) when [onConfirm] is set, which the TV
 * does with OK.
 */
@Composable
internal fun PhonePinEntryCreate(
    @StringRes title: Int,
    onTextChange: (String) -> Unit,
    onConfirm: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable(title) { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin)
                .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(title).tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.accent,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PhonePinBoxes(input.length)
        Spacer(Modifier.height(24.dp))
        PhonePinPad(
            onKey = {
                input += it
                onTextChange(input)
            },
            onDelete = {
                input = input.dropLast(1)
                onTextChange(input)
            },
        )
        if (onConfirm != null) {
            Spacer(Modifier.height(16.dp))
            PhoneButton(
                label = stringResource(R.string.tally_qa_b_pin_confirm),
                primary = true,
                onClick = { onConfirm(input) },
                modifier = Modifier.widthIn(max = PadMaxWidth).fillMaxWidth(),
            )
        }
    }
}

/** Upstream's `PinEntryDialog` on a phone: a sheet with ENTER PIN in accent over [PhonePinEntry]. */
@Composable
internal fun PhonePinSheet(
    onDismissRequest: () -> Unit,
    onTextChange: (String) -> Unit,
    onClickServerAuth: () -> Unit,
) {
    PhoneSheet(onDismiss = onDismissRequest) {
        Text(
            text = stringResource(R.string.tally_signin_kicker_pin).tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.accent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(bottom = 12.dp),
        )
        PhonePinEntry(onTextChange = onTextChange, onClickServerAuth = onClickServerAuth)
    }
}

private const val DELETE = "delete"

private val DIGIT_ROWS =
    listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", DELETE),
    )

/** The remote's arrows as PIN keys (the key upstream records, its glyph, what a screen reader says). */
private val ARROWS =
    listOf(
        Triple("L", R.string.fa_arrow_left_long, R.string.tally_qa_b_pin_left),
        Triple("U", R.string.fa_arrow_up_long, R.string.tally_qa_b_pin_up),
        Triple("R", R.string.fa_arrow_right_long, R.string.tally_qa_b_pin_right),
        Triple("D", R.string.fa_arrow_down_long, R.string.tally_qa_b_pin_down),
    )
