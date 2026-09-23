package io.github.scdouglas1999.tally.ui.player.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import io.github.scdouglas1999.tally.api.TallyEvent
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LowerThird
import io.github.scdouglas1999.tally.ui.components.RollingText
import io.github.scdouglas1999.tally.ui.components.rememberScoreColor
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBarAction
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** The TV bug's border: a step lighter than `ruleStrong`, so the box holds its edge over a dark picture. */
private val BugBorder = Color(0xFF5A5D57)

private val BugScore =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    )

/** Width of a side panel over the live picture (the switcher, the box score). */
val LivePanelWidth: Dp = 360.dp

private const val PANEL_ANIM_MS = 160

/**
 * The score bug at phone size: "TOR 3 · BAL 2" (the scores rolling and flashing accent when they go up, as on the
 * TV) over the status and situation line in accent. Nothing unless [game] is live and scores are shown.
 */
@Composable
fun PhoneScoreBug(
    game: TallyGame?,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null || !game.isLive || hideScores) return
    Column(
        modifier =
            modifier
                .border(PhoneDimens.hairline, BugBorder)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (game.away.possession) IndicatorSquare(color = TallyColors.accent, size = 6.dp)
            key(game.id) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = game.away.abbr.ifBlank { game.away.shortName } + " ",
                        style = BugScore,
                        color = TallyColors.text,
                        maxLines = 1,
                    )
                    BugDigits(game.away.score)
                    Text(
                        text = " · " + game.home.abbr.ifBlank { game.home.shortName } + " ",
                        style = BugScore,
                        color = TallyColors.text,
                        maxLines = 1,
                    )
                    BugDigits(game.home.score)
                }
            }
            if (game.home.possession) IndicatorSquare(color = TallyColors.accent, size = 6.dp)
        }
        val situation = bugSituation(game)
        if (situation.isNotBlank()) {
            Text(
                text = situation.tallyUppercase(),
                style = PhoneType.meta,
                color = TallyColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BugDigits(score: Int?) {
    RollingText(
        text = score?.toString() ?: "–",
        style = BugScore.copy(fontWeight = FontWeight.SemiBold),
        color = rememberScoreColor(score, TallyColors.text),
    )
}

/** The status detail and the sport's situation: down and distance, or count and outs. */
@Composable
private fun bugSituation(game: TallyGame): String {
    val situation =
        when (game.sport) {
            "football" -> {
                game.downDistance
            }

            "baseball" -> {
                val count = if (game.balls != null && game.strikes != null) "${game.balls}-${game.strikes}" else null
                val outs = game.outs?.let { pluralStringResource(R.plurals.tally_outs, it, it) }
                listOfNotNull(count, outs).joinToString(" · ").ifBlank { null }
            }

            else -> {
                null
            }
        }
    return listOfNotNull(game.detail.ifBlank { null }, situation).joinToString(" · ")
}

/**
 * A notable event in another game, at phone size: black with a `live` hairline, the title in small red mono, the
 * event in mono, and where to find the switcher. Enters and leaves as the TV's lower third.
 */
@Composable
fun PhoneEventBanner(
    event: TallyEvent,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    LowerThird(visible = visible, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .border(PhoneDimens.hairline, TallyColors.live)
                    .background(TallyColors.labelBar)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (event.title.isNotBlank()) {
                Text(
                    text = event.title.tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.liveText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (event.text.isNotBlank()) {
                Text(
                    text = event.text,
                    style = PhoneType.meta.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = TallyColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = stringResource(R.string.tally_phone_sports_banner_hint),
                style = PhoneType.bodySmall,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * A side panel over the live picture: [LivePanelWidth] from the right edge, `groundRaised` with a 1dp `ruleStrong`
 * left edge, a header (the mono [title] and [count], a close button), then [content]. It slides in from the right;
 * the picture beside it is dimmed and a tap there closes the panel.
 */
@Composable
fun PhoneLivePanel(
    visible: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(PANEL_ANIM_MS)),
            exit = fadeOut(tween(PANEL_ANIM_MS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(PANEL_ANIM_MS)) { it },
            exit = slideOutHorizontally(tween(PANEL_ANIM_MS)) { it },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(LivePanelWidth)
                        .fillMaxHeight()
                        .background(TallyColors.groundRaised)
                        .drawBehind {
                            val stroke = PhoneDimens.hairline.toPx()
                            drawLine(
                                color = TallyColors.ruleStrong,
                                start = Offset(stroke / 2f, 0f),
                                end = Offset(stroke / 2f, size.height),
                                strokeWidth = stroke,
                            )
                        }
                        // the panel itself takes its taps (they must not reach the scrim or the player under it)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(PhoneDimens.topBarHeight)
                            .padding(start = PhoneDimens.margin, end = 4.dp),
                ) {
                    Text(
                        text = title.tallyUppercase(),
                        style = PhoneType.labelLarge,
                        color = TallyColors.text,
                        maxLines = 1,
                    )
                    if (count != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = count.toString(),
                            style = PhoneType.labelLarge,
                            color = TallyColors.muted,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    PhoneTopBarAction(
                        glyph = R.string.tally_phone_sports_fa_xmark,
                        label = stringResource(R.string.tally_phone_sports_close),
                        onClick = onClose,
                    )
                }
                content()
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.45f
