package io.github.scdouglas1999.tally.media.movie.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.logCoilError
import io.github.scdouglas1999.tally.media.kit.DetailMetaPart
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneStatusBarPadding
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/*
 * The phone detail pages' shared parts (film, episode and show pages): the 16:9 backdrop with the back arrow, the
 * heading (kicker, logo or title, meta, genres, tagline), the full-width primary button, the icon-over-label action
 * buttons, the expandable overview, the format chips and the horizontal rows.
 */

/** Opacity of the dark square behind the back arrow on the backdrop. */
private const val BACK_SQUARE_ALPHA = 0.6f

/**
 * The page's picture, full width at 16:9, running up under the status bar, fading into `ground` at its foot. The
 * back arrow (a 48dp target) sits on a small dark square at its top-left, below the status bar.
 */
@Composable
fun PhoneDetailBackdrop(
    imageUrl: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(TallyColors.screen),
    ) {
        var failed by remember(imageUrl) { mutableStateOf(false) }
        if (imageUrl != null && !failed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = {
                    logCoilError(imageUrl, it.result)
                    failed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Image scrim: the picture fades into the page at its foot.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Transparent,
                                1f to TallyColors.ground,
                            ),
                    )
                },
        )
        val backLabel = stringResource(R.string.tally_phone_back)
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .phoneStatusBarPadding()
                    .padding(start = 4.dp, top = 4.dp)
                    .size(PhoneDimens.touchTarget)
                    .semantics { contentDescription = backLabel }
                    .phoneClickable(onClick = onBack),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = BACK_SQUARE_ALPHA)),
            ) {
                Text(
                    text = stringResource(R.string.tally_phone_fa_arrow_left),
                    fontFamily = FontAwesome,
                    fontSize = 18.sp,
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A `ground` strip the height of the status bar, drawn over the top once the page has scrolled. */
@Composable
fun PhoneStatusBarGround(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier
            .fillMaxWidth()
            .background(TallyColors.ground)
            .phoneStatusBarPadding(),
    )
}

/**
 * Kicker, logo (max 240x72dp) or title, the mono meta line (wrapping onto a second line if needed, the rating in its
 * box), the end time, genres and the tagline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneDetailHeading(
    kicker: String,
    title: String,
    logoUrl: String?,
    meta: List<DetailMetaPart>,
    modifier: Modifier = Modifier,
    kickerColor: Color = TallyColors.muted,
    ends: String? = null,
    genres: List<String> = emptyList(),
    tagline: String? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin),
    ) {
        Text(
            text = kicker.tallyUppercase(),
            style = PhoneType.label,
            color = kickerColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PhoneLogoOrTitle(title = title, logoUrl = logoUrl)
        if (meta.isNotEmpty()) PhoneMetaLine(meta)
        if (!ends.isNullOrBlank()) {
            Text(
                text = ends.tallyUppercase(),
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                maxLines = 1,
            )
        }
        if (genres.isNotEmpty()) {
            Text(
                text = genres.joinToString(" / "),
                style = PhoneType.bodySmall,
                color = TallyColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!tagline.isNullOrBlank()) {
            Text(
                text = tagline,
                style = PhoneType.body,
                color = TallyColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PhoneLogoOrTitle(
    title: String,
    logoUrl: String?,
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !failed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = {
                logCoilError(logoUrl, it.result)
                failed = true
            },
            modifier =
                Modifier
                    .widthIn(max = 240.dp)
                    .heightIn(max = 72.dp),
        )
    } else {
        Text(
            text = title,
            style = PhoneType.display,
            color = TallyColors.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `2021 · [PG-13] · 2h 35m · ★ 7.8 · RT 83%`, wrapping between parts. [boxColor] frames the boxed part (the official
 * rating): `ruleStrong` on the ground; a page that sets the line over a picture passes a lighter one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneMetaLine(
    parts: List<DetailMetaPart>,
    modifier: Modifier = Modifier,
    boxColor: Color = TallyColors.ruleStrong,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        parts.forEachIndexed { index, part ->
            // A part and the dot after it stay together, so a wrapped line never starts with a dot.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (part) {
                    is DetailMetaPart.Plain -> {
                        Text(
                            text = part.text.tallyUppercase(),
                            style = PhoneType.labelLarge,
                            color = TallyColors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    is DetailMetaPart.Boxed -> {
                        Text(
                            text = part.text.tallyUppercase(),
                            style = PhoneType.labelLarge,
                            color = TallyColors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier =
                                Modifier
                                    .border(PhoneDimens.hairline, boxColor)
                                    .padding(start = 5.dp, end = 5.dp, top = 1.dp, bottom = 1.dp),
                        )
                    }
                }
                if (index < parts.lastIndex) {
                    Text(
                        text = "·",
                        style = PhoneType.labelLarge,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The page's main action, full width: accent fill, mono label with the play glyph. [progress] (0..1) draws a 2dp
 * line along its bottom (a partly watched item).
 */
@Composable
fun PhonePrimaryButton(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .height(PhoneDimens.touchTarget)
                .background(TallyColors.accent)
                .drawBehind {
                    val fraction = progress?.coerceIn(0f, 1f) ?: return@drawBehind
                    val line = 2.dp.toPx()
                    drawRect(
                        color = TallyColors.onAccent.copy(alpha = 0.25f),
                        topLeft = Offset(0f, size.height - line),
                        size =
                            androidx.compose.ui.geometry
                                .Size(size.width, line),
                    )
                    drawRect(
                        color = TallyColors.onAccent,
                        topLeft = Offset(0f, size.height - line),
                        size =
                            androidx.compose.ui.geometry
                                .Size(size.width * fraction, line),
                    )
                }.phoneClickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = glyph,
                fontFamily = FontAwesome,
                fontSize = 15.sp,
                color = TallyColors.onAccent,
                maxLines = 1,
            )
            Text(
                text = label.tallyUppercase(),
                style = PhoneType.labelLarge,
                color = TallyColors.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One icon-over-label button of a detail page's action row. [active] colors the glyph accent (a favorite).
 * [onLongClick] is the long-press (DOWNLOAD opens its quality sheet). [progress] (0..1) replaces the glyph by a thin
 * progress bar (a download under way); [failed] draws the label red.
 */
data class PhoneAction(
    val key: String,
    val glyph: String,
    val label: String,
    val onClick: () -> Unit,
    val active: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val progress: Float? = null,
    val failed: Boolean = false,
)

/** The action buttons in one row of equal cells, each at least 48dp tall, 1dp `ruleStrong` frames. */
@Composable
fun PhoneActionRow(
    actions: List<PhoneAction>,
    modifier: Modifier = Modifier,
) {
    // Five cells (TRAILER · WATCHED · FAVORITE · DOWNLOAD · MORE) only fit a phone's width with a slightly tighter
    // label: 10sp, less tracking, closer cells, so "DOWNLOADED" and "FAVORITED" are not cut.
    val crowded = actions.size >= CROWDED_ACTIONS
    val labelStyle = if (crowded) PhoneType.label.copy(fontSize = 10.sp, letterSpacing = 0.04.em) else PhoneType.label
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (crowded) 6.dp else 8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                modifier =
                    Modifier
                        .weight(1f)
                        .height(PhoneActionHeight)
                        .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                        .phoneClickable(onLongClick = action.onLongClick, onClick = action.onClick),
            ) {
                val progress = action.progress
                if (progress != null) {
                    ActionProgress(action.glyph, progress)
                } else {
                    Text(
                        text = action.glyph,
                        fontFamily = FontAwesome,
                        fontSize = 18.sp,
                        color = if (action.active) TallyColors.accent else TallyColors.text,
                        maxLines = 1,
                    )
                }
                Text(
                    text = action.label.tallyUppercase(),
                    style = labelStyle,
                    color = if (action.failed) TallyColors.liveText else TallyColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = if (crowded) 2.dp else 4.dp),
                )
            }
        }
    }
}

private val PhoneActionHeight = 64.dp

/** From this many buttons the row tightens its labels. */
private const val CROWDED_ACTIONS = 5

/**
 * A thin progress bar in the glyph's place: 28x3dp, accent on `ruleStrong`, centered on an invisible copy of the
 * glyph so the label under it stays where the other buttons' labels are.
 */
@Composable
private fun ActionProgress(
    glyph: String,
    progress: Float,
) {
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = glyph,
            fontFamily = FontAwesome,
            fontSize = 18.sp,
            color = Color.Transparent,
            maxLines = 1,
        )
        Box(
            Modifier
                .size(width = 28.dp, height = 3.dp)
                .background(TallyColors.ruleStrong),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(TallyColors.accent),
            )
        }
    }
}

/** The overview, 4 lines; a tap shows the rest (and a second tap folds it again). */
@Composable
fun PhoneOverview(
    text: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var truncated by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = PhoneType.body,
        color = TallyColors.text,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (truncated || expanded) {
                        Modifier.phoneClickable { expanded = !expanded }
                    } else {
                        Modifier
                    },
                ),
    )
}

/** The format chips, as on the TV: `1080p`, `H264`, `EN · AAC 5.1`, `CC · EN ES`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneTechChips(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
                modifier =
                    Modifier
                        .border(PhoneDimens.hairline, TallyColors.rule)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** A mono row header (`CAST & CREW 37`) on the page margin. */
@Composable
fun PhoneRowHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = PhoneDimens.margin),
    ) {
        Text(
            text = title.tallyUppercase(),
            style = PhoneType.labelLarge,
            color = TallyColors.text,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = PhoneType.labelLarge,
                color = TallyColors.muted,
                maxLines = 1,
            )
        }
    }
}

/** [PhoneRowHeader] over a horizontal row of cards that starts on the page margin. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> PhoneMediaRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    key: (index: Int, item: T) -> Any = { index, _ -> index },
    count: Int? = items.size,
    card: @Composable (item: T, index: Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = PhoneDimens.rowGap),
    ) {
        PhoneRowHeader(title = title, count = count)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            contentPadding = PaddingValues(horizontal = PhoneDimens.margin),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(items, key = key) { index, item -> card(item, index) }
        }
    }
}

/** A small caption line (`Directed by …`) in `muted`. */
@Composable
fun PhoneCaption(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = PhoneType.bodySmall,
        color = TallyColors.muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Person cards on a phone. */
val PhonePersonWidth: Dp = 88.dp

/** Chapter / extra / episode cards on a phone. */
val PhoneLandscapeWidth: Dp = 200.dp

/** A full-height centered mono label (loading, empty lists). */
@Composable
fun PhoneCenteredLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        Text(
            text = text.tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.muted,
            maxLines = 1,
        )
    }
}
