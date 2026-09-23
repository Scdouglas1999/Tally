package io.github.scdouglas1999.tally.together.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.together.TogetherGroupSummary
import io.github.scdouglas1999.tally.together.TogetherState
import io.github.scdouglas1999.tally.together.ui.TogetherPartyCard
import io.github.scdouglas1999.tally.ui.components.phone.PhoneGameFooter
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import java.util.UUID

/** A party card on a phone: a landscape card's width. */
private val PartyCardWidth = PhoneDimens.landscapeCardWidth

/**
 * "Watch parties" on the phone's home: the TV row's cards as a phone card row (the row header with the count, the
 * cards scrolling with the page margin). A tap joins the party, or for the party this phone is in opens the Watch
 * Together sheet (leave / close), as OK does on the TV.
 */
@Composable
fun PhoneTogetherRow(
    parties: List<TogetherPartyCard>,
    onOpen: (TogetherPartyCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    PhoneCardRow(
        title = stringResource(R.string.tally_together_ui_row_title),
        items = parties,
        key = { _, party -> party.id },
        modifier = modifier,
    ) { party, _ ->
        PhonePartyCard(party = party, onClick = { onOpen(party) })
    }
}

/**
 * A party: WATCH PARTY in accent and how many are watching, the party's name (`PhoneType.headline`, two lines) and
 * who is in it, and the black label bar (YOU'RE IN THIS ONE in accent, else JOIN).
 */
@Composable
private fun PhonePartyCard(
    party: TogetherPartyCard,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(PartyCardWidth)
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .background(TallyColors.ground)
                .phoneClickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.tally_together_ui_card_kicker).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.tally_together_ui_watching, party.participants.size).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp),
        ) {
            Text(
                text = party.name,
                style = PhoneType.headline,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (party.participants.isNotEmpty()) {
                Text(
                    text = party.participants.joinToString(" · "),
                    style = PhoneType.bodySmall,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (party.isMine) {
            PhoneGameFooter(text = stringResource(R.string.tally_phone_sports_yours), live = false, accent = true)
        } else {
            PhoneGameFooter(text = stringResource(R.string.tally_phone_sports_join), live = false)
        }
    }
}

/**
 * Watch Together as a bottom sheet (the TV dialog's content): WATCH TOGETHER in accent, the title (the party's name
 * once in one) and the members or the explanation; then in a party: LEAVE THE WATCH PARTY (outlined) and CLOSE; else
 * START A WATCH PARTY (accent, when something is playing), and the parties on the server to join, or "Looking for
 * watch parties…". Every choice closes the sheet, as the TV dialog closes; the outcome is announced by the overlay.
 */
@Composable
fun PhoneTogetherSheet(
    together: TogetherState,
    groups: List<TogetherGroupSummary>?,
    canStart: Boolean,
    onStart: () -> Unit,
    onJoin: (UUID) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inGroup = together as? TogetherState.InGroup
    val margin = Modifier.padding(horizontal = PhoneDimens.margin)
    PhoneSheet(onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.tally_together_kicker).tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.accent,
                maxLines = 1,
                modifier = margin,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = inGroup?.group?.name ?: stringResource(R.string.tally_together_title),
                style = PhoneType.title,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = margin,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    if (inGroup != null) {
                        inGroup.group.participants.joinToString(stringResource(R.string.tally_together_name_separator))
                    } else {
                        stringResource(R.string.tally_together_body)
                    },
                style = PhoneType.body,
                color = TallyColors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = margin,
            )
            Spacer(Modifier.height(16.dp))
            if (inGroup != null) {
                PhoneButton(
                    label = stringResource(R.string.tally_together_leave),
                    onClick = onLeave,
                    modifier = margin.fillMaxWidth(),
                )
                Spacer(Modifier.height(PhoneDimens.cardGap))
                PhoneButton(
                    label = stringResource(R.string.tally_together_close),
                    onClick = onDismiss,
                    modifier = margin.fillMaxWidth(),
                )
            } else {
                if (canStart) {
                    PhoneButton(
                        label = stringResource(R.string.tally_together_start),
                        primary = true,
                        onClick = onStart,
                        modifier = margin.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                when {
                    groups == null -> {
                        Text(
                            text = stringResource(R.string.tally_together_looking),
                            style = PhoneType.body,
                            color = TallyColors.muted,
                            modifier = margin,
                        )
                    }

                    groups.isNotEmpty() -> {
                        Text(
                            text = stringResource(R.string.tally_together_parties).tallyUppercase(),
                            style = PhoneType.label,
                            color = TallyColors.muted,
                            maxLines = 1,
                            modifier = margin,
                        )
                        groups.forEach { group ->
                            PhoneDialogRow(
                                onClick = { onJoin(group.id) },
                                headline = { Text(text = group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailing = {
                                    Text(
                                        text =
                                            stringResource(R.string.tally_together_watching, group.participants.size)
                                                .tallyUppercase(),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
