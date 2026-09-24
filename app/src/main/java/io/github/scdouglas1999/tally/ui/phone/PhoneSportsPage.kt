package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.showToast
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.data.TallyRepository
import io.github.scdouglas1999.tally.dvr.ui.phone.PhoneRecordingsTab
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.ui.TallySettingsContent
import io.github.scdouglas1999.tally.ui.TallyViewModel
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.TallyTab
import io.github.scdouglas1999.tally.ui.components.tallyTabs
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/** Height of the SPORTS kicker row and of the tab strip. */
private val KickerHeight = 44.dp
private val TabHeight = PhoneDimens.touchTarget

/**
 * The Sports section on a phone ([io.github.scdouglas1999.tally.ui.TallyPage]'s phone layout, same [TallyViewModel],
 * same toasts): the SPORTS kicker and the tabs (GAMES · CHANNELS · MULTIVIEW · SETTINGS) pinned at the top under the
 * status bar, the selected tab's content under them, ending above the bottom bar.
 */
@Composable
fun PhoneSportsPage(
    viewModel: TallyViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { resId -> showToast(context, context.getString(resId)) }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(TallyColors.ground),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .phoneStatusBarPadding(),
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(KickerHeight)
                        .padding(horizontal = PhoneDimens.margin),
            ) {
                Text(
                    text = stringResource(R.string.tally_phone_sports_kicker).tallyUppercase(),
                    style = PhoneType.labelLarge,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
            }
            PhoneTabStrip(
                tabs = tallyTabs(state.hasDvr),
                selected = state.selectedTab,
                onSelect = viewModel::selectTab,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            when (state.selectedTab) {
                TallyTab.GAMES -> {
                    PhoneGamesBoard(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                TallyTab.CHANNELS -> {
                    PhoneChannelsGrid(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                TallyTab.MULTIVIEW -> {
                    PhoneMultiviewQueue(
                        channelIds = state.multiview,
                        channels = state.channels,
                        onRemove = viewModel::removeFromMultiview,
                        onOpen = viewModel::openMultiview,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                TallyTab.RECORDINGS -> {
                    PhoneRecordingsTab(modifier = Modifier.fillMaxSize())
                }

                TallyTab.SETTINGS -> {
                    // The settings rows are drawn by the settings screens' phone layout; this page only hosts them.
                    TallySettingsContent(
                        onlyWatchable = state.onlyWatchable,
                        hideScores = state.hideScores,
                        info = (state.availability as? TallyRepository.Availability.Available)?.info,
                        onToggleOnlyWatchable = viewModel::toggleOnlyWatchable,
                        onHideScoresChange = viewModel::setHideScores,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The Tally tab style at phone size, scrolling sideways when it does not fit: each tab a 48dp tall target with the
 * indicator square and the mono label; the selected one in accent with a 2dp accent underline, the others
 * `textSecondary`; a 1dp `rule` under the strip.
 */
@Composable
internal fun PhoneTabStrip(
    tabs: List<TallyTab>,
    selected: TallyTab,
    onSelect: (TallyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TabHeight)
                .drawBehind {
                    val stroke = PhoneDimens.hairline.toPx()
                    drawLine(
                        color = TallyColors.rule,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }.horizontalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin - 10.dp),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            val color = if (isSelected) TallyColors.accent else TallyColors.textSecondary
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .semantics { this.selected = isSelected }
                        .phoneClickable(role = Role.Tab) { onSelect(tab) }
                        .drawBehind {
                            if (isSelected) {
                                val h = 2.dp.toPx()
                                drawRect(
                                    color = TallyColors.accent,
                                    topLeft = Offset(0f, size.height - h),
                                    size = Size(size.width, h),
                                )
                            }
                        }.padding(horizontal = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IndicatorSquare(color = color, size = 6.dp)
                    Text(
                        text = stringResource(tab.title).tallyUppercase(),
                        style = PhoneType.labelLarge,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The multiview queue on a phone: OPEN MULTIVIEW (accent, full width) first, then the queued channels as rows with
 * REMOVE at the right. Empty: the TV's title and how to add one on a phone.
 */
@Composable
private fun PhoneMultiviewQueue(
    channelIds: List<String>,
    channels: List<TallyChannel>,
    onRemove: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channelIds.isEmpty()) {
        PhoneEmptyState(
            title = stringResource(R.string.tally_multiview_empty_title),
            subtitle = stringResource(R.string.tally_phone_sports_multiview_empty_sub),
            modifier = modifier,
        )
        return
    }
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    LazyColumn(
        contentPadding = PaddingValues(top = 16.dp, bottom = bottom + 16.dp),
        modifier = modifier,
    ) {
        item(key = "open") {
            PhoneButton(
                label = stringResource(R.string.tally_open_multiview),
                primary = true,
                onClick = onOpen,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PhoneDimens.margin),
            )
            Spacer(Modifier.height(12.dp))
        }
        items(channelIds, key = { it }) { channelId ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .drawBehind {
                            val stroke = PhoneDimens.hairline.toPx()
                            drawLine(
                                color = TallyColors.rule,
                                start = Offset(0f, size.height - stroke / 2f),
                                end = Offset(size.width, size.height - stroke / 2f),
                                strokeWidth = stroke,
                            )
                        }.padding(start = PhoneDimens.margin, end = 4.dp),
            ) {
                Text(
                    text = channels.firstOrNull { it.id == channelId }?.name ?: channelId,
                    style = PhoneType.headline,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .height(PhoneDimens.touchTarget)
                            .phoneClickable { onRemove(channelId) }
                            .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tally_phone_sports_remove).tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
