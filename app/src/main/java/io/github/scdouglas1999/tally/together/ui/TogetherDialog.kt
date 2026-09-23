package io.github.scdouglas1999.tally.together.ui

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.scdouglas1999.tally.together.TogetherGroupSummary
import io.github.scdouglas1999.tally.together.TogetherService
import io.github.scdouglas1999.tally.together.TogetherState
import io.github.scdouglas1999.tally.together.ui.phone.PhoneTogetherSheet
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.TallyRow
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TogetherDialogViewModel
    @Inject
    constructor(
        val together: TogetherService,
        private val api: ApiClient,
    ) : ViewModel() {
        var groups by mutableStateOf<List<TogetherGroupSummary>?>(null)
            private set

        var userName by mutableStateOf("")
            private set

        fun reload() {
            viewModelScope.launch {
                if (together.state.value is TogetherState.InGroup) return@launch
                groups = null
                if (userName.isBlank()) userName = fetchName()
                groups = together.groups()
            }
        }

        fun start(
            itemId: UUID,
            positionMs: Long,
            namePattern: String,
            fallback: String,
        ) {
            viewModelScope.launch {
                val name = userName.ifBlank { fetchName().also { userName = it } }
                val party = if (name.isBlank()) fallback else String.format(namePattern, name)
                Timber.i("Watch together starting %s at %d ms", party, positionMs)
                together.startParty(itemId, positionMs, party)
            }
        }

        fun join(groupId: UUID) {
            viewModelScope.launch {
                Timber.i("Watch together joining %s", groupId)
                together.join(groupId)
            }
        }

        fun leave() {
            viewModelScope.launch {
                Timber.i("Watch together leaving")
                together.leave()
            }
        }

        private suspend fun fetchName(): String =
            try {
                api.userApi
                    .getCurrentUser()
                    .content.name
                    .orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Watch together could not read the user name")
                ""
            }
    }

/**
 * Start, join or leave a Watch Together party. Opened from the player's settings menu.
 * The dialog closes as soon as a request is made; the outcome is a [io.github.scdouglas1999.tally.together.TogetherNotice].
 */
@Composable
fun TogetherDialog(
    itemId: UUID?,
    positionMs: Long,
    onDismiss: () -> Unit,
    viewModel: TogetherDialogViewModel = hiltViewModel(),
) {
    val together by viewModel.together.state.collectAsStateWithLifecycle()
    val groups = viewModel.groups
    val inGroup = together as? TogetherState.InGroup
    val partyNamePattern = stringResource(R.string.tally_together_party_name)
    val partyFallback = stringResource(R.string.tally_together_party_fallback)
    LaunchedEffect(inGroup == null) {
        if (inGroup == null) viewModel.reload()
    }
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        PhoneTogetherSheet(
            together = together,
            groups = groups,
            canStart = itemId != null,
            onStart = {
                if (itemId != null) {
                    viewModel.start(itemId, positionMs, partyNamePattern, partyFallback)
                }
                onDismiss()
            },
            onJoin = { id ->
                viewModel.join(id)
                onDismiss()
            },
            onLeave = {
                viewModel.leave()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        return
    }
    val firstRow = remember { FocusRequester() }
    var acceptClicks by remember { mutableStateOf(false) }
    val focusKey =
        when {
            inGroup != null -> "leave"
            itemId != null -> "start"
            groups == null -> "looking"
            groups.isNotEmpty() -> groups.first().id.toString()
            else -> "none"
        }
    LaunchedEffect(focusKey) {
        if (!acceptClicks) {
            delay(CLICK_ARM_MS)
            acceptClicks = true
        }
        var attempts = 0
        while (!firstRow.tryRequestFocus("jtv-together") && attempts < 8) {
            attempts++
            delay(50)
        }
    }
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.decorView.elevation = 0f
        }
        // A dialog window does not inherit the overlay's TallyScale.
        TallyScale {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier =
                        Modifier
                            .width(560.dp)
                            .background(TallyColors.ground)
                            .border(TallyDimens.hairline, TallyColors.rule)
                            .padding(20.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.tally_together_kicker),
                            style = TallyType.label,
                            color = TallyColors.accent,
                            maxLines = 1,
                        )
                        Text(
                            text = inGroup?.group?.name ?: stringResource(R.string.tally_together_title),
                            style = dialogTitle,
                            color = TallyColors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                if (inGroup != null) {
                                    inGroup.group.participants.joinToString(stringResource(R.string.tally_together_name_separator))
                                } else {
                                    stringResource(R.string.tally_together_body)
                                },
                            style = TallyType.body,
                            color = TallyColors.textSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                // A scroll container clips to its bounds: keep room for the focus border of the
                                // first and last rows, or their outer edge is cut (found at full resolution).
                                .padding(vertical = TallyDimens.focusBorder + 1.dp),
                    ) {
                        if (inGroup != null) {
                            GroupRows(
                                firstRow = firstRow,
                                acceptClicks = acceptClicks,
                                onLeave = {
                                    viewModel.leave()
                                    onDismiss()
                                },
                                onClose = onDismiss,
                            )
                        } else {
                            LobbyRows(
                                itemId = itemId,
                                positionMs = positionMs,
                                groups = groups,
                                firstRow = firstRow,
                                acceptClicks = acceptClicks,
                                onStart = { id, position ->
                                    viewModel.start(
                                        itemId = id,
                                        positionMs = position,
                                        namePattern = partyNamePattern,
                                        fallback = partyFallback,
                                    )
                                    onDismiss()
                                },
                                onJoin = { id ->
                                    viewModel.join(id)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LobbyRows(
    itemId: UUID?,
    positionMs: Long,
    groups: List<TogetherGroupSummary>?,
    firstRow: FocusRequester,
    acceptClicks: Boolean,
    onStart: (UUID, Long) -> Unit,
    onJoin: (UUID) -> Unit,
) {
    var index = 0
    if (itemId != null) {
        val row = index
        index += 1
        TallyRow(
            label = stringResource(R.string.tally_together_start),
            onClick = {
                if (!acceptClicks) return@TallyRow
                onStart(itemId, positionMs)
            },
            modifier =
                Modifier.rowFocus(
                    first = row == 0,
                    last = groups.isNullOrEmpty(),
                    requester = firstRow,
                ),
        )
    }
    if (groups == null) {
        Text(
            text = stringResource(R.string.tally_together_looking),
            style = TallyType.body,
            color = TallyColors.muted,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .then(
                        if (index == 0) {
                            Modifier
                                .focusRequester(firstRow)
                                .focusable()
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                }
                        } else {
                            Modifier
                        },
                    ),
        )
        return
    }
    if (groups.isEmpty()) return
    RowHeader(title = stringResource(R.string.tally_together_parties))
    val last = index + groups.lastIndex
    groups.forEach { group ->
        val row = index
        index += 1
        TallyRow(
            label = group.name,
            onClick = {
                if (!acceptClicks) return@TallyRow
                onJoin(group.id)
            },
            trailing = {
                Text(
                    text = stringResource(R.string.tally_together_watching, group.participants.size),
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
            },
            modifier = Modifier.rowFocus(first = row == 0, last = row == last, requester = firstRow),
        )
    }
}

@Composable
private fun GroupRows(
    firstRow: FocusRequester,
    acceptClicks: Boolean,
    onLeave: () -> Unit,
    onClose: () -> Unit,
) {
    TallyRow(
        label = stringResource(R.string.tally_together_leave),
        onClick = {
            if (!acceptClicks) return@TallyRow
            onLeave()
        },
        modifier = Modifier.rowFocus(first = true, requester = firstRow),
    )
    TallyRow(
        label = stringResource(R.string.tally_together_close),
        onClick = {
            if (!acceptClicks) return@TallyRow
            onClose()
        },
        modifier = Modifier.rowFocus(first = false, last = true, requester = firstRow),
    )
}

private fun Modifier.rowFocus(
    first: Boolean,
    requester: FocusRequester,
    last: Boolean = false,
): Modifier =
    this
        .then(if (first) Modifier.focusRequester(requester) else Modifier)
        .focusProperties {
            if (first) up = FocusRequester.Cancel
            if (last) down = FocusRequester.Cancel
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
        }

private const val CLICK_ARM_MS = 400L

private val dialogTitle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )
