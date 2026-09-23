package io.github.scdouglas1999.tally.ui.components.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyProgramme
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * A channel on a phone (a cell of the Channels grid): the channel's 16:9 card art cropped on `screen`, the black
 * label bar with the channel name (the indicator `live` red, as the TV card's), then the programme on now and next
 * in mono; while the channel shows a live game and scores are not hidden, its scoreline ("TOR 3 · BAL 2") in accent
 * takes the NOW line, as it takes the TV card's label bar. A tap plays the channel; a long-press adds it to
 * multiview (the TV's long OK).
 */
@Composable
fun PhoneChannelCard(
    channel: TallyChannel,
    imageUrl: String?,
    game: TallyGame?,
    hideScores: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .background(TallyColors.ground)
                .phoneClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(TallyColors.screen)
                    .clipToBounds(),
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PhoneGameFooter(text = channel.name, live = true)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val scoreline = if (game != null && !hideScores) scoreline(game) else null
            ProgrammeLine(
                label = stringResource(R.string.tally_phone_sports_now),
                text = scoreline ?: channel.now?.title,
                color = if (scoreline != null) TallyColors.accent else TallyColors.text,
            )
            ProgrammeLine(
                label = stringResource(R.string.tally_phone_sports_next),
                text = channel.next?.displayTitle(),
                color = TallyColors.textSecondary,
            )
        }
    }
}

private fun TallyProgramme.displayTitle(): String? = title.takeIf { it.isNotBlank() }

@Composable
private fun ProgrammeLine(
    label: String,
    text: String?,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label.tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
        Text(
            text = text?.takeIf { it.isNotBlank() } ?: "–",
            style = PhoneType.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "TOR 3 · BAL 2": team abbreviation then score, away first (the TV card's scoreline). */
private fun scoreline(game: TallyGame): String =
    listOf(game.away, game.home).joinToString(" · ") { team ->
        "${team.abbr.ifBlank { team.shortName }} ${team.score ?: "–"}"
    }
