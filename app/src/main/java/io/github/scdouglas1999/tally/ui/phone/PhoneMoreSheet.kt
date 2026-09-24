package io.github.scdouglas1999.tally.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import io.github.scdouglas1999.tally.downloads.ui.downloadsSummary
import io.github.scdouglas1999.tally.media.drawer.TallyGlyph
import io.github.scdouglas1999.tally.media.drawer.tallyGlyph
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType

/** A More row: 56dp, glyph and name. */
private val RowHeight = 56.dp
private val UserTile = 32.dp

private val UserInitial =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    )

/**
 * The More sheet: the signed-in user (opens user switching, as the drawer's profile row does), Now Playing while
 * music plays, Search, the libraries that are not on the bar, the drawer's app sections (Surprise me, Favorites,
 * Discover) and Settings, in the drawer's order. The [selectedIndex] entry (the drawer's current page) carries the
 * drawer's tally light. Every row calls back with what the drawer's entry does.
 */
@Composable
internal fun PhoneMoreSheet(
    nav: PhoneNavModel,
    selectedIndex: Int,
    onSettings: Boolean,
    userName: String,
    userId: String,
    serverName: String,
    userImageUrl: String?,
    nowPlayingTitle: String?,
    onDownloads: Boolean,
    onDownloadsClick: () -> Unit,
    onDismiss: () -> Unit,
    onProfile: () -> Unit,
    onNowPlaying: () -> Unit,
    onSearch: () -> Unit,
    onItem: (Int, NavDrawerItem) -> Unit,
    onSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    PhoneSheet(onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "user") {
                PhoneUserRow(
                    name = userName,
                    userId = userId,
                    server = serverName,
                    imageUrl = userImageUrl,
                    onClick = onProfile,
                )
            }
            item(key = "user-rule") { SheetRule() }
            if (nowPlayingTitle != null) {
                item(key = "now-playing") {
                    PhoneSheetRow(
                        label = nowPlayingTitle,
                        kicker = stringResource(R.string.now_playing),
                        glyph = TallyGlyph.Font(R.string.fa_play),
                        current = selectedIndex == NOW_PLAYING_INDEX,
                        onClick = onNowPlaying,
                    )
                }
            }
            item(key = "search") {
                PhoneSheetRow(
                    label = stringResource(R.string.search),
                    glyph = TallyGlyph.Font(R.string.tally_drawer_fa_search),
                    current = selectedIndex == SEARCH_INDEX,
                    onClick = onSearch,
                )
            }
            nav.moreLibraries.forEach { indexed ->
                item(key = "lib-" + indexed.value.id) {
                    PhoneSheetRow(
                        label = indexed.value.name(context),
                        glyph = tallyGlyph(indexed.value),
                        current = !onSettings && selectedIndex == indexed.index,
                        onClick = { onItem(indexed.index, indexed.value) },
                    )
                }
            }
            nav.moreSections.forEach { indexed ->
                item(key = "section-" + indexed.value.id) {
                    PhoneSheetRow(
                        label = indexed.value.name(context),
                        glyph = tallyGlyph(indexed.value),
                        current = !onSettings && selectedIndex == indexed.index,
                        onClick = { onItem(indexed.index, indexed.value) },
                    )
                }
            }
            item(key = "downloads") {
                PhoneSheetRow(
                    label = stringResource(R.string.tally_dl_page_title),
                    glyph = TallyGlyph.Font(R.string.fa_download),
                    current = onDownloads,
                    detail = downloadsSummary(),
                    onClick = onDownloadsClick,
                )
            }
            item(key = "settings-rule") { SheetRule() }
            item(key = "settings") {
                PhoneSheetRow(
                    label = stringResource(R.string.settings),
                    glyph = TallyGlyph.Font(R.string.tally_drawer_fa_settings),
                    current = onSettings,
                    onClick = onSettingsClick,
                )
            }
        }
    }
}

@Composable
private fun SheetRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(PhoneDimens.hairline)
            .background(TallyColors.rule),
    )
}

/**
 * A 56dp sheet row: glyph (20dp, `muted`) and name (`PhoneType.headline`, `text`); an optional mono kicker over the
 * name. The [current] page has the drawer's tally light (a 3x20dp accent bar at the left edge) and a `text` glyph.
 */
@Composable
internal fun PhoneSheetRow(
    label: String,
    glyph: TallyGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    current: Boolean = false,
    detail: String? = null,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .fillMaxWidth()
                .height(RowHeight)
                .phoneClickable(onClick = onClick),
    ) {
        if (current) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 20.dp)
                    .background(TallyColors.accent),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = PhoneDimens.margin),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(UserTile)) {
                PhoneGlyph(glyph = glyph, size = 20.dp, color = if (current) TallyColors.text else TallyColors.muted)
            }
            Column(modifier = Modifier.weight(1f)) {
                if (kicker != null) {
                    Text(
                        text = kicker.tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.accent,
                        maxLines = 1,
                    )
                }
                Text(
                    text = label,
                    style = PhoneType.headline,
                    color = TallyColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detail != null) {
                Text(
                    text = detail.tallyUppercase(),
                    style = PhoneType.meta,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The signed-in user: the drawer's square user tile (picture, or the initial), name and server. */
@Composable
private fun PhoneUserRow(
    name: String,
    userId: String,
    server: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .phoneClickable(onClick = onClick)
                .padding(horizontal = PhoneDimens.margin),
    ) {
        var failed by remember(imageUrl) { mutableStateOf(false) }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(UserTile)
                    .clipToBounds()
                    .background(TallyColors.ground),
        ) {
            if (!imageUrl.isNullOrBlank() && !failed) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.ifBlank { userId }.firstOrNull()?.uppercase() ?: "?",
                    style = UserInitial,
                    color = TallyColors.text,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = PhoneType.headline,
                color = TallyColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server,
                style = PhoneType.meta,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
