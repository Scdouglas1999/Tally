package io.github.scdouglas1999.tally.media.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import io.github.scdouglas1999.tally.media.kit.CardDetailStyle
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleStyle
import io.github.scdouglas1999.tally.media.kit.PosterWidth
import io.github.scdouglas1999.tally.media.kit.posterDetail
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.BaseItemKind

/** Height of the black label bar under a card's picture (see `CardFrame`). */
private val LabelBarHeight = 40.dp

/**
 * Picture height for a home row. Upstream's `heightDp` is authored for its own cards; its default
 * poster height ([Cards.HEIGHT_2X3_DP]) maps onto the kit's default poster (132 x 198) and every other
 * height scales by the same factor, so a customized row keeps its proportion to the default.
 */
internal fun homeImageHeight(viewOptions: HomeRowViewOptions): Dp =
    PosterWidth * 1.5f * viewOptions.heightDp.toFloat() / Cards.HEIGHT_2X3_DP.toFloat()

/**
 * Picture height of a home card on a phone: a poster ([PhoneDimens.posterWidth] wide) for tall and square pictures, a
 * landscape card ([PhoneDimens.landscapeCardWidth] wide) for wide ones, whatever height the row's options ask for.
 */
internal fun phoneHomeImageHeight(
    item: BaseItem?,
    viewOptions: HomeRowViewOptions,
): Dp {
    val ratio = ratioFor(item, viewOptions).ratio
    val width = if (ratio > PHONE_WIDE_RATIO) PhoneDimens.landscapeCardWidth else PhoneDimens.posterWidth
    return width / ratio
}

/** Full card height of a phone home row (picture plus label bar), for loading rows. */
internal fun phoneHomeCardHeight(
    viewOptions: HomeRowViewOptions,
    namesOnly: Boolean,
): Dp = phoneHomeImageHeight(null, viewOptions) + if (homeLabelShown(viewOptions, namesOnly)) LabelBarHeight else 0.dp

private const val PHONE_WIDE_RATIO = 1.2f

/** Whether a row's cards carry the label bar: the row's "show titles" option, always for genres and studios. */
internal fun homeLabelShown(
    viewOptions: HomeRowViewOptions,
    namesOnly: Boolean,
): Boolean = viewOptions.showTitles || namesOnly

/** Full card height of a row (picture plus label bar), used for loading rows so nothing jumps when cards arrive. */
internal fun homeCardHeight(
    viewOptions: HomeRowViewOptions,
    namesOnly: Boolean,
): Dp = homeImageHeight(viewOptions) + if (homeLabelShown(viewOptions, namesOnly)) LabelBarHeight else 0.dp

private fun BaseItem?.isEpisode() = this?.type == BaseItemKind.EPISODE

/** Genres and studios are only a name over a picture: their cards always show the name. */
internal fun BaseItem?.isNameCard() = this?.type == BaseItemKind.GENRE || this?.type == BaseItemKind.STUDIO

private fun ratioFor(
    item: BaseItem?,
    viewOptions: HomeRowViewOptions,
): AspectRatio = if (item.isEpisode()) viewOptions.episodeAspectRatio else viewOptions.aspectRatio

/** The kit crops pictures; a row set to fit (live TV logos) keeps fitting. Fill stays the kit's crop. */
private fun PrefContentScale.kitScale(): ContentScale =
    when (this) {
        PrefContentScale.FIT, PrefContentScale.UNRECOGNIZED -> ContentScale.Fit
        PrefContentScale.NONE -> ContentScale.None
        PrefContentScale.Fill_WIDTH -> ContentScale.FillWidth
        PrefContentScale.FILL_HEIGHT -> ContentScale.FillHeight
        PrefContentScale.CROP, PrefContentScale.FILL -> ContentScale.Crop
    }

/**
 * The card kicker that replaces upstream's episode badge: `S1 E3` for an episode and, in Continue
 * Watching / Next Up rows, the share watched: `S1 E3 · 42%` (episodes) or `42%` (films). Null when
 * there is nothing to say.
 */
@Composable
internal fun homeCardKicker(
    item: BaseItem?,
    watchingRow: Boolean,
): String? {
    if (item == null) return null
    val code =
        if (item.isEpisode()) {
            episodeCode(
                item.data.parentIndexNumber,
                item.indexNumber,
                item.data.indexNumberEnd,
                stringResource(R.string.tally_series_special),
            )
        } else {
            null
        }
    val position = item.data.userData?.playbackPositionTicks ?: 0L
    val percent =
        if (watchingRow && !item.played && position > 0L) {
            resumePercent(position, item.data.runTimeTicks ?: 0L).coerceIn(1, 99)
        } else {
            null
        }
    return when {
        code != null && percent != null -> stringResource(R.string.tally_home2_kicker_progress, code, percent)
        code != null -> code
        percent != null -> stringResource(R.string.tally_home2_percent, percent)
        else -> null
    }
}

/**
 * A library item on the home page, drawn with the kit's [CardFrame] and sized by the row's
 * [HomeRowViewOptions]: TALL is a poster, WIDE a landscape card, SQUARE a square poster; episodes use
 * the episode ratio and image type. With the label bar (show titles) a card reads like the kit's
 * cards: an accent kicker over the title when there is one, else the title over a muted detail.
 * Without it, the kicker moves onto the picture as a tag (where upstream put its `E3` badge).
 */
@Composable
fun HomeItemCard(
    item: BaseItem?,
    viewOptions: HomeRowViewOptions,
    watchingRow: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    imageHeight: Dp = homeImageHeight(viewOptions),
) {
    val width = imageHeight * ratioFor(item, viewOptions).ratio
    val imageType =
        if (item.isEpisode()) viewOptions.episodeImageType.imageType else viewOptions.imageType.imageType
    val contentScale =
        if (item.isEpisode()) viewOptions.episodeContentScale.kitScale() else viewOptions.contentScale.kitScale()
    val imageService = LocalImageUrlService.current
    val fillHeight = with(LocalDensity.current) { imageHeight.roundToPx() }
    val imageUrl =
        remember(item, imageType, fillHeight, viewOptions.useSeries) {
            item?.imageUrlOverride
                ?: imageService.getItemImageUrl(
                    item,
                    imageType,
                    fillHeight = fillHeight,
                    useSeriesForPrimary = viewOptions.useSeries,
                )
        }
    val showLabel = homeLabelShown(viewOptions, item.isNameCard())
    val kicker = homeCardKicker(item, watchingRow)
    val played = item?.played == true
    val unplayed = item?.data?.userData?.unplayedItemCount ?: 0
    val percent =
        resumePercent(
            item?.data?.userData?.playbackPositionTicks ?: 0L,
            item?.data?.runTimeTicks ?: 0L,
        )
    val progress = if (!played && percent in 1..99) percent / 100f else null
    val stateTag =
        when {
            played -> stringResource(R.string.tally_media_seen)
            unplayed > 0 && !item.isEpisode() -> stringResource(R.string.tally_media_new_count, unplayed)
            else -> null
        }
    val kickerTag = kicker != null && !showLabel
    val title =
        if (item.isNameCard()) {
            item?.name
        } else {
            item?.title ?: item?.name
        }.orEmpty()
    val detail = if (item != null && kicker == null) posterDetail(item) else null
    CardFrame(
        imageUrl = imageUrl,
        width = width,
        height = imageHeight,
        contentDescription = title,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        onPlay = onPlay,
        onFocused = onFocused,
        progress = progress,
        tag = if (kickerTag) kicker else stateTag,
        tagAccent = kickerTag || (!played && unplayed > 0),
        favorite = item?.favorite == true,
        contentScale = contentScale,
        label =
            if (showLabel) {
                {
                    if (kicker != null) {
                        Text(
                            text = kicker.uppercase(),
                            style = CardDetailStyle,
                            color = TallyColors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = CardTitleStyle,
                            color = TallyColors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (detail != null) {
                        Text(
                            text = detail.uppercase(),
                            style = CardDetailStyle,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                null
            },
    )
}

/**
 * Upstream's "view more" card at the end of a full row, as a Tally card of the row's card size:
 * `groundRaised`, a mono `VIEW ALL →`, focus like the cards.
 */
@Composable
fun HomeViewAllCard(
    lastItem: BaseItem?,
    viewOptions: HomeRowViewOptions,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val imageHeight = homeImageHeight(viewOptions)
    val width = imageHeight * ratioFor(lastItem, viewOptions).ratio
    val height = homeCardHeight(viewOptions, lastItem.isNameCard())
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.groundRaised,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong), shape = RectangleShape),
                focusedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(TallyDimens.focusBorder, TallyColors.accent), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier =
            modifier
                .size(width, height)
                .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tally_home2_view_all).uppercase(),
                    style = TallyType.label,
                    color = TallyColors.text,
                    maxLines = 1,
                )
                Text(
                    text = "→",
                    style = TallyType.label,
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A placeholder while a row loads: same height as its cards, a mono `LOADING…` in `muted`. */
@Composable
fun HomeRowMessage(
    text: String,
    height: Dp,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.height(height),
    ) {
        Text(
            text = if (mono) text.uppercase() else text,
            style = if (mono) TallyType.label else TallyType.body,
            color = TallyColors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
