package io.github.scdouglas1999.tally.media.kit.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.PersonCard
import io.github.scdouglas1999.tally.media.kit.PosterCard
import io.github.scdouglas1999.tally.media.kit.posterDetail
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneStatusBarPadding
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/** Height of a phone button (the minimum touch target). */
val PhoneButtonHeight = PhoneDimens.touchTarget

/** The visible square of a phone icon button; its touch target is [PhoneDimens.touchTarget]. */
val PhoneIconSquare = 40.dp

/** A square portrait on a phone row. */
val PhonePersonWidth = 104.dp

/**
 * A row header on a phone: the title in `PhoneType.labelLarge` uppercase with a muted count, and a trailing ALL
 * (`PhoneType.label`, 48dp tall target) where the TV row has "view all".
 */
@Composable
fun PhoneRowHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onAll: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = title.tallyUppercase(),
                style = PhoneType.labelLarge,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
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
        }
        if (onAll != null) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier =
                    Modifier
                        .height(PhoneDimens.touchTarget)
                        .phoneClickable(onClick = onAll)
                        .padding(start = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.tally_phone_browse_all).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A phone row: [PhoneRowHeader] over a horizontally scrolling row of cards, the page margin as the row's content
 * padding (the first card lines up with the page's text, the row scrolls to the screen's edge), [PhoneDimens.cardGap]
 * between cards.
 */
@Composable
fun <T> PhoneCardRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    count: Int? = items.size,
    onAll: (() -> Unit)? = null,
    key: (index: Int, item: T) -> Any = { index, _ -> index },
    card: @Composable (item: T, index: Int) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PhoneRowHeader(
            title = title,
            count = count,
            onAll = onAll,
            modifier = Modifier.padding(horizontal = PhoneDimens.margin),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            contentPadding = PaddingValues(horizontal = PhoneDimens.margin),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(items, key = key) { index, item -> card(item, index) }
        }
    }
}

/** A row that is loading (mono `LOADING…`, as tall as its cards) or failed (the message in `liveText`). */
@Composable
fun PhoneRowMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    failure: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin)) {
        PhoneRowHeader(title = title)
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .padding(top = 8.dp)
                    .then(if (height != null) Modifier.height(height) else Modifier),
        ) {
            Text(
                text = message.tallyUppercase(),
                style = PhoneType.label,
                color = if (failure) TallyColors.liveText else TallyColors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A page's loading state on a phone: a mono `LOADING…` in `muted`, centered. */
@Composable
fun PhoneLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.tally_media_loading).tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.muted,
        )
    }
}

/** An empty or error state on a phone: a title (`PhoneType.headline`) over a muted line. */
@Composable
fun PhoneEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.padding(horizontal = PhoneDimens.margin, vertical = 24.dp),
    ) {
        Text(
            text = title,
            style = PhoneType.headline,
            color = TallyColors.text,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = PhoneType.body,
                color = TallyColors.muted,
            )
        }
    }
}

/**
 * A phone button, 48dp tall and square: [primary] is the accent fill with `onAccent` text, otherwise a 1dp
 * `ruleStrong` outline. Mono `labelLarge` uppercase label centered, an optional Font Awesome [glyph] before it,
 * [bottom] drawn along the button's bottom edge (a progress line).
 */
@Composable
fun PhoneButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    glyph: String? = null,
    enabled: Boolean = true,
    progress: Float? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val content = if (primary) TallyColors.onAccent else TallyColors.text
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(PhoneButtonHeight)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .then(
                    if (primary) {
                        Modifier.background(TallyColors.accent)
                    } else {
                        Modifier.border(PhoneDimens.hairline, TallyColors.ruleStrong)
                    },
                ).drawBehind {
                    val fraction = progress?.coerceIn(0f, 1f) ?: return@drawBehind
                    val h = 2.dp.toPx()
                    drawRect(
                        color = TallyColors.onAccent.copy(alpha = 0.35f),
                        topLeft = Offset(0f, size.height - h),
                        size =
                            androidx.compose.ui.geometry
                                .Size(size.width, h),
                    )
                    drawRect(
                        color = TallyColors.onAccent,
                        topLeft = Offset(0f, size.height - h),
                        size =
                            androidx.compose.ui.geometry
                                .Size(size.width * fraction, h),
                    )
                }.then(if (enabled) Modifier.phoneClickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 15.sp,
                    color = content,
                    maxLines = 1,
                )
            }
            Text(
                text = label.tallyUppercase(),
                style = PhoneType.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke(this)
        }
    }
}

/**
 * A 40dp square glyph button with a 1dp `ruleStrong` frame, centered in a 48dp touch target. [label] is its
 * accessibility name. Not [enabled]: drawn at 40% and inert.
 */
@Composable
fun PhoneIconButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    framed: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(PhoneDimens.touchTarget)
                .semantics { contentDescription = label }
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .then(if (enabled) Modifier.phoneClickable(onClick = onClick) else Modifier),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(PhoneIconSquare)
                    .then(if (framed) Modifier.border(PhoneDimens.hairline, TallyColors.ruleStrong) else Modifier),
        ) {
            Text(
                text = glyph,
                fontFamily = FontAwesome,
                fontSize = 16.sp,
                color = TallyColors.text,
                maxLines = 1,
            )
        }
    }
}

/**
 * An icon-over-label action (the film page's row of actions on a phone): a 20dp glyph over a mono `label` caption,
 * at least 56dp tall, filling its share of the row. [marked] puts the 6dp accent square after the caption (on).
 */
@Composable
fun PhoneToolButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    marked: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier =
            modifier
                .heightIn(min = 60.dp)
                .phoneClickable(onClick = onClick)
                .padding(vertical = 8.dp),
    ) {
        Text(
            text = glyph,
            fontFamily = FontAwesome,
            fontSize = 18.sp,
            color = if (marked) TallyColors.accent else TallyColors.text,
            maxLines = 1,
        )
        Text(
            text = label.tallyUppercase(),
            style = PhoneType.label,
            color = TallyColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * An outline chip (sort, filter, view): 40dp high (the icon squares' height) in a 48dp target, 1dp `ruleStrong`, mono `label`; [value] follows
 * the label in `text` (`SORT · NAME ↑`). [active] marks a chip whose setting is on with an accent square.
 */
@Composable
fun PhoneChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    active: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(PhoneDimens.touchTarget)
                .phoneClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .height(PhoneIconSquare)
                    .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                    .padding(horizontal = 12.dp),
        ) {
            if (active) IndicatorSquare(color = TallyColors.accent, size = 6.dp)
            Text(
                text = label.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.text,
                maxLines = 1,
            )
        }
    }
}

/**
 * One item on a phone, with the kit card for its type at phone size: episodes, channels and programs as landscape
 * cards, people square portraits, music and photos square, everything else a poster. [width] overrides the card
 * width (a grid cell). A null [item] (a page still loading) is an empty frame.
 */
@Composable
fun PhoneItemCard(
    item: BaseItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackType: BaseItemKind? = null,
    kicker: String? = null,
    width: Dp? = null,
) {
    val type = item?.type ?: fallbackType
    val imageService = LocalImageUrlService.current
    when {
        type == BaseItemKind.PERSON -> {
            val url = remember(item) { imageService.getItemImageUrl(item, ImageType.PRIMARY) }
            PersonCard(
                name = item?.name ?: "",
                role = null,
                imageUrl = url,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
                width = width ?: PhonePersonWidth,
            )
        }

        width == null && type in PhoneWideTypes -> {
            val url = item?.let { rememberWideImageUrl(it) }
            val percent =
                resumePercent(
                    item?.data?.userData?.playbackPositionTicks ?: 0L,
                    item?.data?.runTimeTicks ?: 0L,
                )
            LandscapeCard(
                title = item?.name ?: "",
                kicker = kicker,
                imageUrl = url,
                progress = if (item?.played != true && percent in 1..99) percent / 100f else null,
                favorite = item?.favorite == true,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
                width = PhoneDimens.landscapeCardWidth,
                downloadId = item?.id,
            )
        }

        type in PhoneSquareTypes || item == null -> {
            val url = remember(item) { imageService.getItemImageUrl(item, ImageType.PRIMARY) }
            val w = width ?: PhoneDimens.posterWidth
            CardFrame(
                imageUrl = url,
                width = w,
                height = if (type in PhoneSquareTypes) w else w * 3 / 2,
                contentDescription = item?.name,
                onClick = onClick,
                onLongClick = onLongClick,
                favorite = item?.favorite == true,
                modifier = modifier,
                downloadId = item?.id,
                label = {
                    Text(
                        text = item?.name ?: "",
                        style = io.github.scdouglas1999.tally.media.kit.CardTitleStyle,
                        color = TallyColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail =
                        item?.let {
                            if (it.type == BaseItemKind.AUDIO || it.type == BaseItemKind.MUSIC_ALBUM) {
                                it.data.albumArtist ?: it.data.productionYear?.toString()
                            } else {
                                posterDetail(it)
                            }
                        }
                    if (!detail.isNullOrBlank()) {
                        Text(
                            text = detail.tallyUppercase(),
                            style = io.github.scdouglas1999.tally.media.kit.CardDetailStyle,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }

        else -> {
            PosterCard(
                item = item!!,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
                width = width ?: PhoneDimens.posterWidth,
            )
        }
    }
}

/** A thin `rule` line across the page. */
@Composable
fun PhoneRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(PhoneDimens.hairline)
            .background(TallyColors.rule),
    )
}

/** Full-size box on `ground`, the root of a phone page. */
@Composable
fun PhonePage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(TallyColors.ground)) { content() }
}

private val PhoneWideTypes =
    setOf(
        BaseItemKind.EPISODE,
        BaseItemKind.TV_CHANNEL,
        BaseItemKind.LIVE_TV_PROGRAM,
        BaseItemKind.TV_PROGRAM,
        BaseItemKind.PROGRAM,
    )

private val PhoneSquareTypes =
    setOf(
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.MUSIC_ARTIST,
        BaseItemKind.AUDIO,
        BaseItemKind.PLAYLIST,
        BaseItemKind.PHOTO,
        BaseItemKind.PHOTO_ALBUM,
    )

private const val DISABLED_ALPHA = 0.4f

/**
 * A back button over a picture (a page whose image runs under the status bar): a 48dp target under the status bar at
 * the top-left, the arrow on a small dark square so it reads on any image.
 */
@Composable
fun PhoneBackOverImage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.tally_phone_back)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .phoneStatusBarPadding()
                .padding(start = 4.dp, top = 4.dp)
                .size(PhoneDimens.touchTarget)
                .semantics { contentDescription = label }
                .phoneClickable(onClick = onBack),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(TallyColors.labelBar.copy(alpha = 0.6f)),
        ) {
            Text(
                text = stringResource(R.string.tally_phone_fa_arrow_left),
                fontFamily = FontAwesome,
                fontSize = 17.sp,
                color = TallyColors.text,
            )
        }
    }
}
