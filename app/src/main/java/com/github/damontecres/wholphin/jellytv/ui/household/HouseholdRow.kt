package com.github.damontecres.wholphin.jellytv.ui.household

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytv.household.HouseholdSession
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.handleDPadKeyEvents

private val HouseholdCardWidth = 300.dp
private val HouseholdCardHeight = 120.dp

/**
 * "Playing in the house" on Wholphin's home screen: one card per other device that is playing
 * something. OK joins it here (same item, same position). Zero height when nothing is playing
 * elsewhere.
 *
 * The title matches [com.github.damontecres.wholphin.ui.cards.ItemRow]'s title; the cards sit
 * inside [JtvScale] so they stay the size they are in the JellyTV section. The row never asks
 * for focus — UP/DOWN from the neighboring rows reaches it.
 */
@Composable
fun HouseholdRow(
    modifier: Modifier = Modifier,
    viewModel: HouseholdRowViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
    ) {
        ItemRowTitle(title = stringResource(R.string.jtv_household_row_title))
        JtvScale {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(JtvDimens.cardGap),
                contentPadding =
                    PaddingValues(
                        horizontal = 20.dp,
                        vertical = 10.dp,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusGroup()
                        .focusRestorer(),
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    HouseholdCard(
                        session = session,
                        onClick = { viewModel.join(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HouseholdCard(
    session: HouseholdSession,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = JtvColors.ground,
                contentColor = JtvColors.text,
                focusedContainerColor = JtvColors.groundRaised,
                focusedContentColor = JtvColors.text,
                pressedContainerColor = JtvColors.groundRaised,
                pressedContentColor = JtvColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(JtvDimens.hairline, JtvColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(JtvDimens.focusBorder, JtvColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier =
            Modifier
                .size(HouseholdCardWidth, HouseholdCardHeight)
                .semantics {
                    contentDescription = listOf(session.deviceName, session.itemName).filterNot { it.isNullOrBlank() }.joinToString(" ")
                }.handleDPadKeyEvents(onCenter = onClick),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = session.deviceName.uppercase(),
                    style = JtvType.label.copy(fontSize = 16.sp, letterSpacing = 1.5.sp),
                    color = JtvColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (session.isPaused) {
                    Text(
                        text = stringResource(R.string.jtv_household_paused).uppercase(),
                        style = JtvType.label.copy(fontSize = 16.sp, letterSpacing = 1.5.sp),
                        color = JtvColors.accent,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = session.itemName.orEmpty(),
                style = JtvType.teamCard.copy(fontSize = 24.sp, lineHeight = 28.sp),
                color = JtvColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val series = session.seriesName
            if (!series.isNullOrBlank()) {
                Text(
                    text = series,
                    style = JtvType.body.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = JtvColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressHairline(fraction = progressFraction(session.positionMs, session.runtimeMs))
        }
    }
}

/** Played portion of the item, or null when the session has no runtime to measure against. */
internal fun progressFraction(
    positionMs: Long?,
    runtimeMs: Long?,
): Float? {
    if (positionMs == null || runtimeMs == null || runtimeMs <= 0L) return null
    return (positionMs.toFloat() / runtimeMs.toFloat()).coerceIn(0f, 1f)
}

@Composable
private fun ProgressHairline(fraction: Float?) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(JtvColors.muted),
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(JtvColors.accent),
            )
        }
    }
}
