package io.github.scdouglas1999.tally.media.drawer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.CollectionType
import kotlin.math.floor

/**
 * Indexes in [NavDrawerItem] lists must stay the original positions. [selectedIndex] and
 * [com.github.damontecres.wholphin.ui.nav.NavDrawerViewModel.onClickDrawerItem] both use them.
 * Live TV is dropped from what is drawn, not renumbered.
 */
internal fun indexedDrawerItems(
    items: List<NavDrawerItem>,
    hideLiveTv: Boolean,
): List<IndexedValue<NavDrawerItem>> =
    items.withIndex().filterNot { (_, item) ->
        hideLiveTv && item is ServerNavDrawerItem && item.type == CollectionType.LIVETV
    }

/**
 * The first group under Home (user's order, 2026-09-22): Movies, TV Shows, then Sports. Every movies- and
 * TV-type library belongs to it, in the server's order within each type.
 */
internal fun NavDrawerItem.tallyPrimaryRank(): Int? =
    when {
        this is ServerNavDrawerItem && type == CollectionType.MOVIES -> 0
        this is ServerNavDrawerItem && type == CollectionType.TVSHOWS -> 1
        this == NavDrawerItem.Sports -> 2
        else -> null
    }

internal fun NavDrawerItem.isTallyAppSection(): Boolean =
    when (this) {
        NavDrawerItem.Discover,
        NavDrawerItem.Favorites,
        NavDrawerItem.Sports,
        NavDrawerItem.TallySurprise,
        NavDrawerItem.TallyYear,
        -> true

        NavDrawerItem.More,
        is ServerNavDrawerItem,
        -> false
    }

internal fun tallyGlyph(item: NavDrawerItem): TallyGlyph =
    when (item) {
        NavDrawerItem.Favorites -> {
            TallyGlyph.Font(R.string.fa_heart)
        }

        NavDrawerItem.More -> {
            TallyGlyph.Font(R.string.fa_ellipsis)
        }

        NavDrawerItem.Discover -> {
            TallyGlyph.Font(R.string.fa_magnifying_glass_plus)
        }

        NavDrawerItem.Sports -> {
            // not fa_tv: TV Shows sits right above Sports and uses it
            TallyGlyph.Font(R.string.tally_drawer_fa_trophy)
        }

        NavDrawerItem.TallySurprise -> {
            TallyGlyph.Font(R.string.fa_dice)
        }

        NavDrawerItem.TallyYear -> {
            TallyGlyph.Font(R.string.tally_fa_calendar)
        }

        is ServerNavDrawerItem -> {
            when (item.type) {
                CollectionType.MOVIES -> TallyGlyph.Font(R.string.fa_film)
                CollectionType.TVSHOWS -> TallyGlyph.Font(R.string.fa_tv)
                CollectionType.HOMEVIDEOS -> TallyGlyph.Font(R.string.fa_video)
                CollectionType.LIVETV -> TallyGlyph.Image(R.drawable.gf_dvr)
                CollectionType.MUSIC -> TallyGlyph.Font(R.string.fa_music)
                CollectionType.BOXSETS -> TallyGlyph.Font(R.string.fa_open_folder)
                CollectionType.PLAYLISTS -> TallyGlyph.Font(R.string.fa_list_ul)
                else -> TallyGlyph.Font(R.string.fa_film)
            }
        }
    }

internal sealed interface TallyGlyph {
    data class Font(
        @param:StringRes val resId: Int,
    ) : TallyGlyph

    data class Image(
        @param:DrawableRes val resId: Int,
    ) : TallyGlyph
}

private val NowPlayingHeight = 52.dp

/** The rail's row pitch while few entries leave room, and the smallest it goes before the rail must scroll. */
private val MaxRowHeight = 40.dp
private val MinRowHeight = 30.dp

/** The user row keeps the 40dp square: the 32dp user tile sits inside it. */
private val ProfileRowHeight = 40.dp

/** The Settings row's gap to the screen's bottom edge (TallyNavDrawer). */
internal val SettingsBottomPad = 8.dp

/**
 * The row pitch of the drawer, provided by [TallyNavDrawer] from [drawerRowHeight]. Every entry row (and the collapsed
 * focus square) uses it, so the rail and the open drawer keep the same rows.
 */
internal val LocalDrawerRowHeight = staticCompositionLocalOf { MaxRowHeight }

/**
 * The largest row pitch (40dp at most, 30dp at least) at which the collapsed rail shows every entry at once in
 * [available] height: the header, Now Playing when it is up, [rows] entry rows (Settings included), the dividers and
 * the list's focus-border padding. The count of libraries decides how many rows there are, so the pitch is computed,
 * not fixed. Below 30dp the list scrolls instead.
 */
internal fun drawerRowHeight(
    available: Dp,
    rows: Int,
    libraryDivider: Boolean,
    sectionsDivider: Boolean,
    nowPlaying: Boolean,
): Dp {
    if (rows <= 0) return MaxRowHeight
    var fixed = HeaderInset + WordmarkHeight + HeaderGap + ProfileRowHeight + HeaderGap
    if (nowPlaying) fixed += NowPlayingHeight
    if (libraryDivider) fixed += DividerPad + TallyDimens.hairline + KickerBand
    if (sectionsDivider) fixed += DividerPad * 2 + TallyDimens.hairline
    // the rule above Settings and Settings' bottom gap
    fixed += DividerPad * 2 + TallyDimens.hairline + SettingsBottomPad
    // the list's content padding (room for the focus border of its first and last rows)
    fixed += (TallyDimens.focusBorder + 1.dp) * 2
    val pitch = floor(((available - fixed) / rows).value).dp
    return pitch.coerceIn(MinRowHeight, MaxRowHeight)
}

private val GlyphSlot = 24.dp
private val UserTile = 32.dp
private val RowInset = 12.dp
private val HeaderInset = 16.dp

/** The user tile's left edge in the expanded header; puts the name on the same line as the row labels (62dp). */
private val UserRowInset = 20.dp
private val WordmarkHeight = 16.dp
private val HeaderGap = 8.dp
private val KickerBand = 20.dp
private val DividerPad = 6.dp

private val Kicker =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp,
    )

private val RowLabel =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    )

private val ProfileName =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 18.sp,
    )

private val ProfileServer =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )

private val UserInitial =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    )

/**
 * The top of the drawer: the wordmark (expanded only; its band is kept while collapsed so nothing below moves when
 * the drawer opens) and the focusable user row, which opens the user list.
 */
@Composable
internal fun TallyDrawerHeader(
    drawerOpen: Boolean,
    userName: String,
    userId: String,
    serverName: String,
    imageUrl: String?,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = HeaderInset, bottom = HeaderGap),
        verticalArrangement = Arrangement.spacedBy(HeaderGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(WordmarkHeight).padding(start = UserRowInset),
        ) {
            if (drawerOpen) {
                IndicatorSquare(color = TallyColors.accent)
                Text(
                    text = stringResource(R.string.tally_drawer_wordmark),
                    style = TallyType.labelLarge,
                    color = TallyColors.text,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(ProfileRowHeight),
            contentAlignment = Alignment.Center,
        ) {
            TallyFocusSurface(
                onClick = onProfileClick,
                modifier = if (drawerOpen) Modifier.fillMaxSize() else Modifier.size(ProfileRowHeight),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (drawerOpen) {
                                    Modifier.padding(start = UserRowInset, end = HeaderInset)
                                } else {
                                    Modifier.padding(horizontal = 4.dp)
                                },
                            ),
                ) {
                    TallyUserTile(
                        name = userName,
                        userId = userId,
                        imageUrl = imageUrl,
                    )
                    if (drawerOpen) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = userName,
                                style = ProfileName,
                                color = TallyColors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = serverName,
                                style = ProfileServer,
                                color = TallyColors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A 1dp `rule` line; with a [title], a mono kicker under it (shown only while expanded, its band always kept). */
@Composable
internal fun TallyDrawerDivider(
    title: String?,
    drawerOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = DividerPad, bottom = if (title == null) DividerPad else 0.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TallyDimens.hairline)
                    .background(TallyColors.rule),
        )
        if (title != null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(KickerBand),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (drawerOpen) {
                    Text(
                        text = title.uppercase(),
                        modifier = Modifier.padding(start = RowInset),
                        style = Kicker,
                        color = TallyColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One drawer entry. Collapsed: the glyph centered in a square focus box as tall as the row, with the tally light (3x20dp accent bar
 * flush against the rail's left edge) beside the current page. Expanded: a full-width row ([LocalDrawerRowHeight]), indicator slot,
 * glyph, label. With a [kicker] (Now Playing) the row is 52dp in both states and the label sits under the kicker.
 * [modifier] goes to the focusable square, [outerModifier] to the whole row (the tally light included).
 */
@Composable
internal fun TallyDrawerRow(
    label: String,
    glyph: TallyGlyph,
    selected: Boolean,
    drawerOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    @StringRes trailingGlyph: Int? = null,
    outerModifier: Modifier = Modifier,
) {
    val rowHeight = LocalDrawerRowHeight.current
    val height = if (kicker != null) NowPlayingHeight else rowHeight
    val glyphColor = if (selected) TallyColors.text else TallyColors.muted
    Box(
        modifier = outerModifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        if (!drawerOpen && selected) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(TallyDimens.focusBorder)
                        .height(20.dp)
                        .background(TallyColors.accent),
            )
        }
        TallyFocusSurface(
            onClick = onClick,
            modifier =
                modifier
                    .align(Alignment.Center)
                    .then(if (drawerOpen) Modifier.fillMaxSize() else Modifier.size(rowHeight)),
        ) {
            if (!drawerOpen) {
                TallyGlyphIcon(glyph = glyph, size = 20.sp, color = glyphColor)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = RowInset),
                ) {
                    Box(modifier = Modifier.size(8.dp)) {
                        if (selected) IndicatorSquare(color = TallyColors.accent)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.size(GlyphSlot), contentAlignment = Alignment.Center) {
                        TallyGlyphIcon(glyph = glyph, size = 18.sp, color = glyphColor)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        if (kicker != null) {
                            Text(
                                text = kicker.uppercase(),
                                style = Kicker,
                                color = TallyColors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (label.isNotBlank()) {
                            Text(
                                text = label,
                                style = RowLabel,
                                color = if (selected || kicker != null) TallyColors.text else TallyColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (trailingGlyph != null) {
                        Spacer(Modifier.width(8.dp))
                        TallyGlyphIcon(
                            glyph = TallyGlyph.Font(trailingGlyph),
                            size = 14.sp,
                            color = TallyColors.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TallyUserTile(
    name: String,
    userId: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val initial =
        remember(name, userId) {
            val source = name.ifBlank { userId }
            source.firstOrNull()?.uppercase() ?: "?"
        }
    Box(
        modifier =
            modifier
                .size(UserTile)
                .clipToBounds()
                .background(TallyColors.groundRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank() && !failed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = initial,
                style = UserInitial,
                color = TallyColors.text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TallyGlyphIcon(
    glyph: TallyGlyph,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (glyph) {
        is TallyGlyph.Font -> {
            Text(
                text = stringResource(glyph.resId),
                modifier = modifier,
                fontFamily = FontAwesome,
                fontSize = size,
                lineHeight = size,
                color = color,
                textAlign = TextAlign.Center,
            )
        }

        is TallyGlyph.Image -> {
            Icon(
                painter = painterResource(glyph.resId),
                contentDescription = null,
                tint = color,
                modifier = modifier.size(size.value.dp),
            )
        }
    }
}

/**
 * The Tally focus on a square, unscaled tv Surface: `groundRaised` fill and a 3dp accent border drawn INSIDE the
 * bounds (tv-material3 draws a Surface border across the edge, which the drawer panel clips on its sides). The
 * content is centered: a fixed-size tv Surface otherwise lays its content out top-left.
 */
@Composable
private fun TallyFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border = ClickableSurfaceDefaults.border(Border.None, Border.None, Border.None),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        if (focused) {
                            val w = TallyDimens.focusBorder.toPx()
                            drawRect(
                                color = TallyColors.accent,
                                topLeft = Offset(w / 2f, w / 2f),
                                size = Size(size.width - w, size.height - w),
                                style = Stroke(width = w),
                            )
                        }
                    },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** The drawer list's own placement, for [wholeInRail]. Plain bookkeeping: read only while drawing. */
internal class RailViewport {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Draws a list row only while it is whole inside the drawer list: a row the list has scrolled part-way out is not
 * drawn at all (glyph, label and the current-page light), so the rail never shows half an entry. Moving focus onto
 * such a row scrolls it into view first, and it is drawn again. [state] is read while drawing so every scroll or
 * resize of the list redraws the rows.
 */
internal fun Modifier.wholeInRail(
    rail: RailViewport,
    state: LazyListState,
): Modifier =
    composed {
        val own = remember { RailViewport() }
        onPlaced { own.coordinates = it }
            .drawWithContent {
                // subscribe to the list's scroll and size
                state.firstVisibleItemScrollOffset
                state.layoutInfo.viewportEndOffset
                val list = rail.coordinates
                val row = own.coordinates
                if (list == null || row == null || !list.isAttached || !row.isAttached) {
                    drawContent()
                    return@drawWithContent
                }
                val bounds = list.localBoundingBoxOf(row, clipBounds = false)
                val slack = 0.5f
                if (bounds.top >= -slack && bounds.bottom <= list.size.height + slack) drawContent()
            }
    }
