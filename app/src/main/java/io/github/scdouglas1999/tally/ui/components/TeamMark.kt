package io.github.scdouglas1999.tally.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType

/**
 * A team's logo. Logos are drawn with [ContentScale.Fit] and are never cropped.
 * Falls back to a bordered square with the abbreviation when [TallyTeam.logo] is blank or fails.
 */
@Composable
fun TeamMark(
    team: TallyTeam,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    var failed by remember(team.logo) { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size),
    ) {
        if (team.logo.isNotBlank() && !failed) {
            AsyncImage(
                model = team.logo,
                contentDescription = team.name.ifBlank { team.abbr },
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, TallyColors.ruleStrong),
            ) {
                Text(
                    text = team.abbr.uppercase(),
                    style =
                        TallyType.label.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (size >= 56.dp) 18.sp else 14.sp,
                        ),
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

@PreviewTvSpec
@Composable
private fun TeamMarkPreview() {
    TallySurface {
        TeamMark(team = TallySamples.liveFootball.away, size = 78.dp)
    }
}

@PreviewTvSpec
@Composable
private fun TeamMarkFallbackPreview() {
    TallySurface {
        TeamMark(
            team = TallySamples.liveFootball.home.copy(logo = ""),
            size = 78.dp,
        )
    }
}
