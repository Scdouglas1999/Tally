package io.github.scdouglas1999.tally.year

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.KeyHint
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay

/**
 * Full-screen recap of one year of watching. RIGHT / OK advances, LEFT goes back (and does nothing
 * on the first card), UP on the cover opens the year chips, BACK closes.
 */
@Composable
fun YearPage(
    destination: Destination.TallyYear,
    modifier: Modifier = Modifier,
    viewModel: YearViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(destination.year) { viewModel.load(destination.year) }
    BackHandler {
        if (pickerOpen) {
            pickerOpen = false
        } else {
            viewModel.close()
        }
    }
    TallySurface(modifier = modifier) {
        when (val current = state) {
            YearUiState.Loading -> {
                YearLoading()
            }

            is YearUiState.Failed -> {
                YearMessage(
                    title = stringResource(R.string.tally_year_error_title),
                    subtitle = current.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tally_year_error_generic),
                )
            }

            is YearUiState.Ready -> {
                if (current.stats.isEmpty) {
                    YearMessage(
                        title = stringResource(R.string.tally_year_empty_title),
                        subtitle = stringResource(R.string.tally_year_empty_sub),
                    )
                } else {
                    YearRecap(
                        ready = current,
                        pickerOpen = pickerOpen,
                        onPickerOpen = { pickerOpen = true },
                        onPickerClose = { pickerOpen = false },
                        onYear = { year ->
                            pickerOpen = false
                            viewModel.selectYear(year)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun YearLoading() {
    Column(Modifier.fillMaxSize()) {
        ProgressRow(count = 1, current = -1)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.tally_year_counting).uppercase(),
                style =
                    TextStyle(
                        fontFamily = TallyType.Mono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp,
                    ),
                color = TallyColors.muted,
            )
        }
    }
}

@Composable
private fun YearMessage(
    title: String,
    subtitle: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.fillMaxWidth(0.62f),
        )
    }
}

@Composable
private fun YearRecap(
    ready: YearUiState.Ready,
    pickerOpen: Boolean,
    onPickerOpen: () -> Unit,
    onPickerClose: () -> Unit,
    onYear: (Int) -> Unit,
) {
    val stats = ready.stats
    val cards = remember(stats) { visibleCards(stats) }
    var index by remember(stats.year) { mutableIntStateOf(0) }
    val card = cards[index.coerceIn(cards.indices)]
    val slidePx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val pageFocus = remember { FocusRequester() }
    val chipFocus = remember(ready.years) { List(ready.years.size) { FocusRequester() } }
    LaunchedEffect(pickerOpen, stats.year, ready.years) {
        val requester =
            if (pickerOpen) {
                val selected = ready.years.indexOf(stats.year).coerceAtLeast(0)
                chipFocus.getOrNull(selected)
            } else {
                pageFocus
            } ?: return@LaunchedEffect
        repeat(5) {
            if (requester.tryRequestFocus("jtv-year")) return@LaunchedEffect
            delay(50)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (pickerOpen) return@onPreviewKeyEvent false
                onDeckKey(
                    event = event,
                    index = index,
                    last = cards.lastIndex,
                    onIndex = { index = it },
                    onOpenYears = {
                        if (ready.years.isNotEmpty()) onPickerOpen()
                    },
                )
            }.focusRequester(pageFocus)
            .focusable(enabled = !pickerOpen),
    ) {
        Column(Modifier.fillMaxSize()) {
            ProgressRow(count = cards.size, current = index)
            Header(
                year = stats.year,
                index = index,
                count = cards.size,
                years = ready.years,
                pickerOpen = pickerOpen,
                chipFocus = chipFocus,
                onYear = onYear,
                onDismissPicker = onPickerClose,
            )
            AnimatedContent(
                targetState = index,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter =
                        fadeIn(tween(250)) +
                            slideInHorizontally(tween(250)) { if (forward) slidePx else -slidePx }
                    val exit =
                        fadeOut(tween(250)) +
                            slideOutHorizontally(tween(250)) { if (forward) -slidePx else slidePx }
                    enter togetherWith exit
                },
                contentAlignment = Alignment.TopStart,
            ) { page ->
                val shown = cards[page.coerceIn(cards.indices)]
                CardPage(
                    card = shown,
                    ready = ready,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (shown == YearCard.SHOWS) 0.dp else TallyDimens.marginHorizontal),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
        ) {
            if (card == YearCard.COVER) {
                Text(
                    text = stringResource(R.string.tally_year_footnote),
                    style = TallyType.hint,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            KeyHint(
                key = stringResource(R.string.tally_year_key_arrows),
                label = stringResource(R.string.tally_year_browse),
            )
            KeyHint(
                key = stringResource(R.string.tally_year_back),
                label = stringResource(R.string.tally_year_close),
            )
        }
    }
}

private fun onDeckKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    index: Int,
    last: Int,
    onIndex: (Int) -> Unit,
    onOpenYears: () -> Unit,
): Boolean {
    val key = event.key
    val handled =
        key == Key.DirectionLeft ||
            key == Key.DirectionRight ||
            key == Key.DirectionUp ||
            key == Key.DirectionDown ||
            key == Key.DirectionCenter ||
            key == Key.Enter ||
            key == Key.NumPadEnter
    if (!handled) return false
    if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) return true
    when (key) {
        Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            if (index < last) onIndex(index + 1)
        }

        Key.DirectionLeft -> {
            if (index > 0) onIndex(index - 1)
        }

        Key.DirectionUp -> {
            if (index == 0) onOpenYears()
        }
    }
    return true
}

@Composable
private fun Header(
    year: Int,
    index: Int,
    count: Int,
    years: List<Int>,
    pickerOpen: Boolean,
    chipFocus: List<FocusRequester>,
    onYear: (Int) -> Unit,
    onDismissPicker: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = 12.dp),
    ) {
        if (pickerOpen) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            ) {
                years.forEachIndexed { i, option ->
                    YearChip(
                        year = option,
                        onClick = {
                            onDismissPicker()
                            onYear(option)
                        },
                        modifier =
                            Modifier
                                .focusRequester(chipFocus[i])
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    if (i == 0) left = FocusRequester.Cancel
                                    if (i == years.lastIndex) right = FocusRequester.Cancel
                                },
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.tally_year_name, year).uppercase(),
                style = TallyType.label,
                color = TallyColors.accent,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.tally_year_index, index + 1, count),
            style = TallyType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgressRow(
    count: Int,
    current: Int,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = TallyDimens.marginVertical),
    ) {
        repeat(count.coerceAtLeast(1)) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(if (i <= current) TallyColors.text else TallyColors.ruleStrong),
            )
        }
    }
}

@Composable
private fun CardPage(
    card: YearCard,
    ready: YearUiState.Ready,
    modifier: Modifier = Modifier,
) {
    val stats = ready.stats
    if (card == YearCard.SHOWS) {
        // The backdrop fills the card; the text centers itself like the other cards.
        YearShows(stats, modifier)
        return
    }
    // Every other card is only as tall as its content, centered in the same band above the key hints, so the
    // cards share one center line and one height of page.
    Box(modifier.padding(bottom = aboveHints), contentAlignment = Alignment.CenterStart) {
        when (card) {
            YearCard.COVER -> {
                YearCover(stats, ready.serverName)
            }

            YearCard.FILMS -> {
                YearFilms(stats)
            }

            YearCard.GENRES -> {
                YearGenres(stats)
            }

            YearCard.MONTHS -> {
                YearMonths(stats)
            }

            YearCard.DECADE -> {
                YearDecade(stats)
            }

            YearCard.SUMMARY -> {
                YearSummary(
                    stats = stats,
                    firstPlayed = ready.firstPlayed,
                    latestPlayed = ready.latestPlayed,
                )
            }

            YearCard.SHOWS -> {}
        }
    }
}
