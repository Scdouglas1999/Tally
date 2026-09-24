package io.github.scdouglas1999.tally.media.music.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import io.github.scdouglas1999.tally.downloads.ui.DownloadStatus
import io.github.scdouglas1999.tally.downloads.ui.DownloadSubject
import io.github.scdouglas1999.tally.downloads.ui.DownloadUi
import io.github.scdouglas1999.tally.downloads.ui.DownloadedSquare
import io.github.scdouglas1999.tally.downloads.ui.downloadEdge
import io.github.scdouglas1999.tally.downloads.ui.downloadMark
import io.github.scdouglas1999.tally.downloads.ui.rememberDownloadUi
import io.github.scdouglas1999.tally.downloads.ui.status
import io.github.scdouglas1999.tally.media.kit.CardDetailStyle
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleStyle
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButton
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneButtonKind
import io.github.scdouglas1999.tally.ui.settings.phone.phoneTouch
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens

/** The album / artist picture on a phone. */
internal val PhoneCoverSize = 240.dp

/** A track row on a phone. */
internal val PhoneTrackRowHeight = 56.dp

/** An album card in a phone row. */
private val PhoneAlbumCard = 140.dp

private const val DISABLED_ALPHA = 0.4f

/** The music pages' top bar on a phone: back and the page's [kicker] (`ALBUM`, `ARTIST`). */
@Composable
internal fun PhoneMusicTopBar(
    kicker: String,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    PhoneTopBar(
        kicker = kicker,
        onBack = dispatcher?.let { { it.onBackPressed() } },
        scrolled = scrolled,
        modifier = modifier,
        actions = actions,
    )
}

/** A square picture on `screen` in a 1dp `rule` frame (crop). */
@Composable
internal fun PhoneSquareCover(
    imageUrl: String?,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .background(TallyColors.screen)
                .border(PhoneDimens.hairline, TallyColors.rule),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
            )
        }
    }
}

/**
 * The top of the album, artist and song pages on a phone: the square picture (240dp) centered, the title
 * (`PhoneType.title`), [link] (the artist, tappable when [onLink] is set), the mono [meta] line, the genres, the
 * overview (4 lines; a tap shows all of it, another folds it again) and [actions].
 */
@Composable
internal fun PhoneMusicHeader(
    title: String,
    imageUrl: String?,
    link: String?,
    onLink: (() -> Unit)?,
    meta: String,
    genres: List<String>,
    overview: String?,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 8.dp, bottom = 20.dp),
    ) {
        PhoneSquareCover(imageUrl = imageUrl, contentDescription = title, size = PhoneCoverSize)
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = PhoneType.title,
            color = TallyColors.text,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!link.isNullOrBlank()) {
            Text(
                text = link,
                style = PhoneType.headline,
                color = TallyColors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .then(if (onLink != null) Modifier.phoneTouch(onClick = onLink) else Modifier)
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        if (meta.isNotBlank()) {
            Text(
                text = meta.tallyUppercase(),
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (genres.isNotEmpty()) {
            Text(
                text = genres.joinToString(" / "),
                style = PhoneType.bodySmall,
                color = TallyColors.muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        actions()
        if (!overview.isNullOrBlank()) {
            var expanded by remember(overview) { mutableStateOf(false) }
            var cut by remember(overview) { mutableStateOf(false) }
            Text(
                text = overview,
                style = PhoneType.body,
                color = TallyColors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .then(if (cut || expanded) Modifier.phoneTouch(onClick = { expanded = !expanded }) else Modifier),
            )
        }
    }
}

/**
 * The action row of the music pages on a phone: PLAY (accent) and SHUFFLE side by side (PLAY alone when there is no
 * [onShuffle]), then INSTANT MIX, FAVORITE (accent when on), DOWNLOAD (when [download] is set; SHUFFLE is then a
 * glyph square too, for room) and MORE as 48dp icon squares.
 */
@Composable
internal fun PhoneMusicActions(
    favorite: Boolean,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onInstantMix: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    download: DownloadSubject? = null,
) {
    val downloads = if (download != null) rememberDownloadUi() else null
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = PhoneDimens.buttonMaxWidth).fillMaxWidth(),
    ) {
        PhoneButton(
            label = stringResource(R.string.tally_music_play),
            onClick = onPlay,
            kind = PhoneButtonKind.PRIMARY,
            modifier = Modifier.weight(1f),
        )
        if (onShuffle != null && downloads == null) {
            PhoneButton(
                label = stringResource(R.string.tally_music_shuffle),
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
            )
        } else if (onShuffle != null) {
            // with DOWNLOAD in the row there is no room for a SHUFFLE label: the glyph square, named for accessibility
            PhoneIconSquare(
                glyph = stringResource(R.string.fa_shuffle),
                label = stringResource(R.string.tally_music_shuffle),
                onClick = onShuffle,
            )
        }
        PhoneIconSquare(
            glyph = stringResource(R.string.fa_compass),
            label = stringResource(R.string.tally_music_instant_mix),
            onClick = onInstantMix,
        )
        PhoneIconSquare(
            glyph = stringResource(R.string.fa_heart),
            label = stringResource(if (favorite) R.string.tally_music_favorited else R.string.tally_music_favorite),
            onClick = onFavorite,
            color = if (favorite) TallyColors.accent else TallyColors.text,
        )
        if (downloads != null && download != null) PhoneDownloadSquare(downloads, download)
        PhoneIconSquare(
            glyph = stringResource(R.string.fa_ellipsis),
            label = stringResource(R.string.tally_music_more),
            onClick = onMore,
        )
    }
}

/**
 * DOWNLOAD on the music pages: the download glyph square; while downloading the percentage beside it (PAUSED when
 * paused), a check once downloaded. Tap and long-press as [DownloadUi].
 */
@Composable
internal fun PhoneDownloadSquare(
    downloads: DownloadUi,
    subject: DownloadSubject,
) {
    val status = downloads.status(subject)
    PhoneIconSquare(
        glyph = stringResource(if (status is DownloadStatus.Done) R.string.fa_check else R.string.fa_download),
        label =
            stringResource(
                if (status is DownloadStatus.Done) R.string.tally_dlui_downloaded else R.string.tally_dlui_download,
            ),
        onClick = { downloads.onTap(subject, status) },
        onLongClick = { downloads.onLongPress(subject) },
        tag =
            (status as? DownloadStatus.Active)?.let {
                when {
                    it.failed -> stringResource(R.string.tally_dlui_failed)
                    it.paused -> stringResource(R.string.tally_dlui_paused)
                    it.queued -> stringResource(R.string.tally_dlui_queued)
                    else -> stringResource(R.string.tally_dlui_percent, it.percent)
                }
            },
        color = if ((status as? DownloadStatus.Active)?.failed == true) TallyColors.liveText else TallyColors.text,
    )
}

/** A 48dp square icon button with a 1dp `ruleStrong` frame; [label] is its accessibility name. */
@Composable
internal fun PhoneIconSquare(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = TallyColors.text,
    enabled: Boolean = true,
    size: Dp = PhoneDimens.touchTarget,
    frame: Color = TallyColors.ruleStrong,
    frameWidth: Dp = PhoneDimens.hairline,
    glyphSize: Int = 17,
    tag: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = size)
                .widthIn(min = size)
                .height(size)
                .border(frameWidth, frame)
                .semantics { contentDescription = label }
                .phoneTouch(onClick = onClick, enabled = enabled, onLongClick = onLongClick)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = if (tag != null) 10.dp else 0.dp),
        ) {
            Text(text = glyph, fontFamily = FontAwesome, fontSize = glyphSize.sp, color = color, maxLines = 1)
            if (tag != null) {
                Text(text = tag.tallyUppercase(), style = PhoneType.label, color = color, maxLines = 1)
            }
        }
    }
}

/**
 * A music rundown line on a phone: 56dp, the mono number (the playing track's lamp lit in its place, a `muted`
 * square before a queued one), the title in `PhoneType.body` with the artist under it when there is one, the
 * duration in mono at the right. Tap plays, long-press opens the menu.
 */
@Composable
internal fun PhoneTrackRow(
    number: String,
    title: String,
    artist: String?,
    duration: String,
    playing: Boolean,
    queued: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadId: java.util.UUID? = null,
) {
    val download = downloadMark(downloadId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(PhoneTrackRowHeight)
                .phoneTouch(onClick = onClick, onLongClick = onLongClick)
                .downloadEdge(download)
                .padding(horizontal = PhoneDimens.margin),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            modifier = Modifier.width(32.dp),
        ) {
            if (playing) {
                TallyLamp(state = LampState.Lit, size = 10.dp, glow = false)
            } else {
                if (queued) IndicatorSquare(color = TallyColors.muted, size = 5.dp)
                Text(text = number, style = PhoneType.meta, color = TallyColors.muted, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PhoneType.body,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!artist.isNullOrBlank()) {
                Text(
                    text = artist,
                    style = PhoneType.bodySmall,
                    color = TallyColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (download?.done == true) {
            Spacer(Modifier.width(12.dp))
            DownloadedSquare()
        }
        if (duration.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(text = duration, style = PhoneType.meta, color = TallyColors.textSecondary, maxLines = 1, softWrap = false)
        }
    }
}

/** A rundown heading on a phone (`TRACKS 5`, `DISC 2`, `TOP SONGS 10`): mono `label`, `muted`, with the count. */
@Composable
internal fun PhoneRundownHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(TallyColors.ground)
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 12.dp, bottom = 6.dp),
    ) {
        Text(text = title.tallyUppercase(), style = PhoneType.label, color = TallyColors.muted, maxLines = 1)
        if (count != null) {
            Text(text = count.toString(), style = PhoneType.label, color = TallyColors.textSecondary, maxLines = 1)
        }
    }
}

/**
 * A row of square album cards on a phone (`ALBUMS`, `APPEARS ON`, `MORE LIKE THIS`): the heading, then 140dp kit cards
 * (the cover over a black label bar with the title and the year), scrolling sideways. Tap opens, long-press opens the menu.
 */
@Composable
internal fun PhoneAlbumRow(
    title: String,
    items: List<BaseItem?>,
    onClick: (BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        PhoneRundownHeader(title = title, count = items.size)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            contentPadding = PaddingValues(horizontal = PhoneDimens.margin),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            itemsIndexed(items, key = { index, item -> "$index-${item?.id}" }) { index, item ->
                val imageUrl = LocalImageUrlService.current.rememberImageUrl(item)
                val width = if (wide) PhoneDimens.landscapeCardWidth * 0.8f else PhoneAlbumCard
                val height = if (wide) width * 9f / 16f else PhoneAlbumCard
                // the kit's card: the cover over a black label bar with the title and the year, as the other cards
                CardFrame(
                    imageUrl = imageUrl,
                    width = width,
                    height = height,
                    contentDescription = item?.name,
                    onClick = { item?.let(onClick) },
                    onLongClick = { item?.let { onLongClick(index, it) } },
                    favorite = item?.favorite == true,
                    downloadId = item?.id,
                    label = {
                        Text(
                            text = item?.name ?: "",
                            style = CardTitleStyle,
                            color = TallyColors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val year = item?.data?.productionYear ?: item?.data?.premiereDate?.year
                        if (year != null) {
                            Text(
                                text = year.toString(),
                                style = CardDetailStyle,
                                color = TallyColors.muted,
                                maxLines = 1,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** Whether the phone music page's list has scrolled (its top bar then shows its rule). */
private val LocalMusicPageScrolled = compositionLocalOf<MutableState<Boolean>?> { null }

/**
 * The album, artist and song pages on a phone: [PhoneMusicTopBar] with the page's [kicker], then the page on `ground`
 * (the app's backdrop does not show through on a phone).
 */
@Composable
internal fun PhoneMusicPageFrame(
    kicker: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val scrolled = remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneMusicTopBar(kicker = kicker, scrolled = scrolled.value)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CompositionLocalProvider(LocalMusicPageScrolled provides scrolled) {
                ProvideTextStyle(PhoneType.body) { content() }
            }
        }
    }
}

/** Tells [PhoneMusicPageFrame] whether [listState] has scrolled away from its top. */
@Composable
internal fun ReportMusicScroll(listState: LazyListState) {
    val target = LocalMusicPageScrolled.current ?: return
    val scrolled by remember(listState) { derivedStateOf { listState.phoneScrolled } }
    LaunchedEffect(scrolled) { target.value = scrolled }
}

/** The bottom of a music page's list: above the bottom bar (and the mini player) on a phone, the TV margin on a TV. */
@Composable
internal fun musicListBottom(): Dp =
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        LocalPhoneContentPadding.current.calculateBottomPadding() + 24.dp
    } else {
        TallyDimens.marginVertical
    }
