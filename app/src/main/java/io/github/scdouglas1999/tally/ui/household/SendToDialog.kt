package io.github.scdouglas1999.tally.ui.household

import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.household.HouseholdRepository
import io.github.scdouglas1999.tally.household.HouseholdSession
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.household.phone.PhoneSendToSheet
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Lists the other controllable screens and sends the current item to one of them.
 * Polls only while the dialog is composed. The view model itself may outlive one opening
 * (the overlay host is above navigation), so start/stop is reference-counted on the repository
 * from [DisposableEffect], not from [ViewModel.onCleared].
 */
@HiltViewModel
class SendToViewModel
    @Inject
    constructor(
        private val repository: HouseholdRepository,
    ) : ViewModel() {
        val sessions = repository.sessions
        val hasFetched = repository.hasFetched

        private val events = Channel<SendToEvent>(capacity = Channel.BUFFERED)
        private var sending = false

        internal val results = events.receiveAsFlow()

        fun start() = repository.startPolling()

        fun stop() = repository.stopPolling()

        fun send(
            session: HouseholdSession,
            itemId: UUID,
            positionMs: Long,
        ) {
            if (sending) return
            sending = true
            viewModelScope.launch {
                try {
                    val ok = repository.sendTo(session, itemId, positionMs)
                    events.send(if (ok) SendToEvent.Sent(session.deviceName) else SendToEvent.Failed(session.deviceName))
                } finally {
                    sending = false
                }
            }
        }
    }

internal sealed interface SendToEvent {
    data class Sent(
        val deviceName: String,
    ) : SendToEvent

    data class Failed(
        val deviceName: String,
    ) : SendToEvent
}

/**
 * Tally panel of other screens that support remote control. OK sends [itemId] there at
 * [positionMs], shows a SENT lower third with the device name, and closes. BACK dismisses and focus returns to the
 * player (the dialog window takes it on open and the platform restores it on close).
 */
@Composable
fun SendToDialog(
    itemId: UUID,
    positionMs: Long,
    onDismiss: () -> Unit,
    viewModel: SendToViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val hasFetched by viewModel.hasFetched.collectAsStateWithLifecycle()
    val targets = sessions.filter { it.supportsRemoteControl }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(viewModel) {
        viewModel.results.collect { event ->
            when (event) {
                is SendToEvent.Sent -> {
                    SentNotices.post(event.deviceName, sent = true)
                    onDismiss()
                }

                is SendToEvent.Failed -> {
                    SentNotices.post(event.deviceName, sent = false)
                }
            }
        }
    }

    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhoneSendToSheet(
            targets = targets,
            hasFetched = hasFetched,
            failure = SentNotices.current,
            label = { sessionLabel(it) },
            onSend = { viewModel.send(it, itemId, positionMs) },
            onDismiss = onDismiss,
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                setDimAmount(0f)
            }
        }
        // A dialog window does not inherit the page's TallyScale: scale here, as the other Tally dialogs do,
        // or the panel is laid out at the raw TV density (a size larger than every other dialog).
        TallyScale {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f)),
            ) {
                SendToPanel(
                    targets = targets,
                    hasFetched = hasFetched,
                    onSend = { viewModel.send(it, itemId, positionMs) },
                )
                // A failure keeps the dialog open: show its notice above the scrim.
                SentNoticeHost(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
                )
            }
        }
    }
}

@Composable
private fun SendToPanel(
    targets: List<HouseholdSession>,
    hasFetched: Boolean,
    onSend: (HouseholdSession) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val focusKey = if (!hasFetched) "loading" else targets.firstOrNull()?.sessionId ?: "empty"
    LaunchedEffect(focusKey) {
        var attempts = 0
        while (!firstFocus.tryRequestFocus("household-send") && attempts < 5) {
            attempts++
            delay(50)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .width(720.dp)
                .background(TallyColors.ground)
                .border(TallyDimens.hairline, TallyColors.ruleStrong, RectangleShape)
                .padding(24.dp)
                .focusGroup()
                .focusProperties { onExit = { cancelFocusChange() } },
    ) {
        // The dialog title as on the other Tally dialogs (sleep timer, game actions): Sans SemiBold 28sp.
        Text(
            text = stringResource(R.string.tally_household_send_title),
            style = TallyType.teamCard.copy(fontSize = 28.sp, lineHeight = 34.sp),
            color = TallyColors.text,
            maxLines = 1,
        )
        when {
            !hasFetched -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .focusRequester(firstFocus)
                            .focusable(),
                )
            }

            targets.isEmpty() -> {
                EmptyScreens(modifier = Modifier.focusRequester(firstFocus))
            }

            else -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    targets.forEachIndexed { index, session ->
                        TallyRow(
                            label = sessionLabel(session),
                            onClick = { onSend(session) },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
        }
        // With no screen to send to, OK does nothing: only BACK is offered.
        RowHints(canSend = hasFetched && targets.isNotEmpty())
    }
}

@Composable
private fun EmptyScreens(modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.tally_household_send_empty),
        style = TallyType.body,
        color = TallyColors.textSecondary,
        textAlign = TextAlign.Start,
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .border(
                    width = if (focused) TallyDimens.focusBorder else TallyDimens.hairline,
                    color = if (focused) TallyColors.accent else TallyColors.ruleStrong,
                    shape = RectangleShape,
                ).background(if (focused) TallyColors.groundRaised else TallyColors.ground)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun RowHints(canSend: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        if (canSend) {
            KeyHint(
                key = stringResource(R.string.tally_key_ok),
                label = stringResource(R.string.tally_household_send_action),
            )
        }
        KeyHint(
            key = stringResource(R.string.tally_household_key_back),
            label = stringResource(R.string.tally_household_close),
        )
    }
}

@Composable
private fun sessionLabel(session: HouseholdSession): String {
    val device = session.deviceName
    val client = session.client
    val user = session.userName
    return if (device.isNotBlank() && client.isNotBlank() && user.isNotBlank()) {
        stringResource(R.string.tally_household_session, device, client, user)
    } else {
        listOf(device, client, user).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
