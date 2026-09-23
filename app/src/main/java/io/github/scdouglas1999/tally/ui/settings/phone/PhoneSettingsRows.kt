package io.github.scdouglas1999.tally.ui.settings.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlin.math.roundToLong

/** A settings row on a phone. */
internal val PhoneSettingsRowHeight = 56.dp

/** A long value ellipsizes here rather than squeezing the title. */
private val ValueMaxWidth = 150.dp

private val SliderHeight = 36.dp
private val SliderTrack = 2.dp
private val SliderThumb = 16.dp
private val MoveBarWidth = 4.dp

/**
 * A settings page's top bar on a phone: the page title and a back arrow (the system back, so it goes where Back
 * goes).
 */
@Composable
internal fun PhoneSettingsTopBar(
    title: String,
    modifier: Modifier = Modifier,
) {
    if (!LocalPhoneSettingsTitleShown.current) return
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    PhoneTopBar(
        title = title,
        onBack = dispatcher?.let { { it.onBackPressed() } },
        modifier = modifier,
    )
}

/** A group of settings on a phone: its name in `PhoneType.label`, `muted`, with an optional [count] after it. */
@Composable
internal fun PhoneSettingsGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    ) {
        Text(text = title.tallyUppercase(), style = PhoneType.label, color = TallyColors.muted, maxLines = 1)
        if (count != null) {
            Text(text = count.toString(), style = PhoneType.label, color = TallyColors.textSecondary, maxLines = 1)
        }
    }
}

/**
 * A settings row on a phone: 56dp, the title in `PhoneType.body` with the summary in `bodySmall` `muted` under it,
 * [trailing] at the right (the value in mono, a switch, a chevron). No frame; the whole row is the touch target.
 * [moving]: the row is being reordered (the accent bar at its left edge, as on the TV).
 */
@Composable
internal fun PhoneSettingsRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    moving: Boolean = false,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = PhoneSettingsRowHeight)
                .phoneTouch(
                    onClick = onClick,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    interactionSource = interactionSource,
                ).drawWithContent {
                    drawContent()
                    if (moving) drawRect(color = TallyColors.accent, size = Size(MoveBarWidth.toPx(), size.height))
                }.alpha(if (enabled) 1f else DISABLED)
                .padding(horizontal = PhoneRowInset, vertical = 8.dp),
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.widthIn(min = 24.dp)) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                    ProvideTextStyle(PhoneType.headline) { leading() }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = PhoneType.body,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = PhoneType.bodySmall,
                    color = TallyColors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (extra != null) {
                CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                    ProvideTextStyle(PhoneType.bodySmall.copy(color = TallyColors.muted)) { extra() }
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides TallyColors.textSecondary) {
            ProvideTextStyle(PhoneType.meta.copy(color = TallyColors.textSecondary)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    content = trailing,
                )
            }
        }
    }
}

/** How far a settings row's text sits in from the row's edges (the list around it pads another 16dp). */
internal val PhoneRowInset = 0.dp

private const val DISABLED = 0.4f

/** A row's value at the right, in mono. */
@Composable
internal fun PhoneValueText(text: String) {
    Text(
        text = text,
        style = PhoneType.meta,
        color = TallyColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = ValueMaxWidth),
    )
}

/**
 * A slider setting on a phone: the title with the value at the right, and under them a full-width track (2dp
 * `ruleStrong`, the part up to the value in accent) with a square accent thumb. Tap or drag anywhere on the track;
 * each step is saved as it is reached, as the TV's LEFT/RIGHT do.
 */
@Composable
internal fun PhoneSliderRow(
    title: String,
    valueText: String,
    value: Long,
    min: Long,
    max: Long,
    interval: Int,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var current by remember(value) { mutableLongStateOf(value) }
    val change by rememberUpdatedState(onChange)
    var width by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val thumb = with(density) { SliderThumb.toPx() }

    fun at(x: Float) {
        if (width <= 0L || max <= min) return
        val usable = (width - thumb).coerceAtLeast(1f)
        val fraction = ((x - thumb / 2f) / usable).coerceIn(0f, 1f)
        val steps = ((max - min).toFloat() / interval)
        val stepped = min + (fraction * steps).roundToLong() * interval
        val next = stepped.coerceIn(min, max)
        if (next != current) {
            current = next
            change(next)
        }
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = PhoneRowInset, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = PhoneType.body,
                    color = TallyColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (extra != null) {
                    CompositionLocalProvider(LocalContentColor provides TallyColors.muted) {
                        ProvideTextStyle(PhoneType.bodySmall.copy(color = TallyColors.muted)) { extra() }
                    }
                }
            }
            PhoneValueText(valueText)
        }
        val fraction = if (max > min) ((current - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SliderHeight)
                    .onSizeChanged { size: IntSize -> width = size.width.toLong() }
                    .pointerInput(min, max, interval) {
                        detectTapGestures { at(it.x) }
                    }.pointerInput(min, max, interval) {
                        detectHorizontalDragGestures { change, _ -> at(change.position.x) }
                    },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SliderThumb / 2)
                    .height(SliderTrack)
                    .background(TallyColors.ruleStrong),
            )
            val travel = with(density) { ((width - thumb).coerceAtLeast(0f) * fraction).toDp() }
            Box(
                Modifier
                    .padding(start = SliderThumb / 2)
                    .width(travel)
                    .height(SliderTrack)
                    .background(TallyColors.accent),
            )
            Box(
                Modifier
                    .offset(x = travel)
                    .size(SliderThumb)
                    .background(TallyColors.accent),
            )
        }
    }
}

/** Keeps a slot the width of a hidden button, so a row's buttons never shift. */
@Composable
internal fun PhoneButtonSlot() {
    Box(Modifier.width(PhoneDimens.touchTarget))
}

/**
 * The home settings list on a phone (seam in upstream's `HomeSettingsPage`): the whole width, above the gesture bar
 * (the page is full screen; the preview of the home page beside it is left out). Nothing on a TV.
 */
@Composable
fun phoneHomeSettingsPane(): Modifier =
    if (isPhone()) {
        Modifier.fillMaxWidth().navigationBarsPadding()
    } else {
        Modifier
    }

/** A full-screen settings page on a phone (seam in upstream's `UserProfilePreferencesPage`): the whole screen. */
fun phoneFullPage(modifier: Modifier): Modifier =
    modifier
        .fillMaxSize()
        .background(TallyColors.ground)
        .navigationBarsPadding()
