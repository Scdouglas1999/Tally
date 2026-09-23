package io.github.scdouglas1999.tally.together.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.home.HomeRowTitle
import io.github.scdouglas1999.tally.together.ui.phone.PhoneTogetherRow
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import java.util.UUID

/**
 * "Watch parties" on Wholphin's home screen: one card per SyncPlay group on this server. OK joins it (the
 * engine then opens the party's item); OK on the party this TV is in opens the Together dialog.
 * Draws nothing (zero height, no header) when there are no parties and this TV is not in one.
 *
 * Polls while composed (see [TogetherRowViewModel.poll]); the poll restarts at once when this TV joins or
 * leaves a party. The row never asks for focus: UP/DOWN from the neighboring rows reaches it, like
 * [io.github.scdouglas1999.tally.ui.household.HouseholdRow].
 */
@Composable
fun TogetherRow(modifier: Modifier = Modifier) {
    val viewModel: TogetherRowViewModel = hiltViewModel()
    val parties by viewModel.parties.collectAsStateWithLifecycle()
    val mine = parties.firstOrNull { it.isMine }?.id
    LaunchedEffect(viewModel, mine) { viewModel.poll() }
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        if (parties.isNotEmpty()) PhoneTogetherRow(parties = parties, onOpen = viewModel::open, modifier = modifier)
        return
    }

    // A party can end while its card has focus. Move focus off that card BEFORE it leaves the row (to its
    // neighbor, or out of the row when it was the last one); otherwise Compose drops focus on the drawer.
    var shown by remember { mutableStateOf(parties) }
    var focusedId by remember { mutableStateOf<UUID?>(null) }
    var rowFocused by remember { mutableStateOf(false) }
    val requesters = remember { mutableMapOf<UUID, FocusRequester>() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(parties) {
        val focused = focusedId
        if (rowFocused && focused != null && parties.none { it.id == focused }) {
            val index = shown.indexOfFirst { it.id == focused }
            val neighbor =
                (shown.drop(index + 1) + shown.take(index).reversed()).firstOrNull { card -> parties.any { it.id == card.id } }
            val moved = neighbor?.let { requesters[it.id]?.tryRequestFocus("jtv-together-row") } ?: false
            if (!moved) focusManager.moveFocus(FocusDirection.Down) || focusManager.moveFocus(FocusDirection.Up)
        }
        shown = parties
        requesters.keys.retainAll(parties.map { it.id }.toSet())
    }
    if (shown.isEmpty()) return

    // Titled and spaced like the Tally and household rows above it (Wholphin's home-row title plus a muted
    // count): 8.dp under the title, 8.dp under the row.
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
    ) {
        val title = stringResource(R.string.tally_together_ui_row_title)
        HomeRowTitle(title = title, count = shown.size, start = RowStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemRowTitle(title = title)
                Text(
                    text = stringResource(R.string.tally_together_ui_row_count, shown.size),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        TallyScale {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(TallyDimens.cardGap),
                contentPadding = PaddingValues(horizontal = RowStart, vertical = 10.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .focusGroup()
                        .focusRestorer(),
            ) {
                items(shown, key = { it.id }) { party ->
                    PartyCard(
                        party = party,
                        onClick = { viewModel.open(party) },
                        modifier =
                            Modifier
                                .focusRequester(requesters.getOrPut(party.id) { FocusRequester() })
                                .onFocusChanged { if (it.isFocused) focusedId = party.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun PartyCard(
    party: TogetherPartyCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.ground,
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
        modifier = modifier.size(TallyDimens.cardWidth, TallyDimens.cardHeight),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.tally_together_ui_card_kicker),
                    style = TallyType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.tally_together_ui_watching, party.participants.size),
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = party.name,
                    style = TallyType.teamCard,
                    color = TallyColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (party.participants.isNotEmpty()) {
                    Text(
                        text = party.participants.joinToString(stringResource(R.string.tally_together_ui_name_separator)),
                        style = TallyType.body,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PartyBar(isMine = party.isMine)
        }
    }
}

/** The card's black label bar, like [io.github.scdouglas1999.tally.ui.components.LabelBar]. */
@Composable
private fun PartyBar(isMine: Boolean) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(TallyColors.labelBar)
                .drawBehind {
                    val stroke = TallyDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, stroke / 2f),
                        end = Offset(size.width, stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.padding(horizontal = 10.dp),
    ) {
        if (isMine) {
            Text(
                text = stringResource(R.string.tally_together_ui_yours),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = CAP_NUDGE),
            )
        } else {
            KeyHint(
                key = stringResource(R.string.tally_together_ui_key_ok),
                label = stringResource(R.string.tally_together_ui_join),
            )
        }
    }
}

/** Card inset from the home page's edge, inside [TallyScale] (matches the household and Tally rows). */
private val RowStart = 20.dp
