package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/** 2:3 artwork. Default width 132 (height 198). [showLabel] hides the bar for dense grids. */
@Composable
fun PosterCard(
    item: BaseItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    showLabel: Boolean = true,
    width: Dp = PosterWidth,
) {
    val imageUrl = LocalImageUrlService.current.rememberImageUrl(item, ImageType.PRIMARY)
    val played = item.played
    val unplayed = item.data.userData?.unplayedItemCount ?: 0
    val percent =
        resumePercent(
            item.data.userData?.playbackPositionTicks ?: 0L,
            item.data.runTimeTicks ?: 0L,
        )
    val progress = if (!played && percent in 1..99) percent / 100f else null
    val tag =
        when {
            played -> stringResource(R.string.jtv_media_seen)
            unplayed > 0 -> stringResource(R.string.jtv_media_new_count, unplayed)
            else -> null
        }
    val detail = posterDetail(item)
    val title = item.name ?: ""
    CardFrame(
        imageUrl = imageUrl,
        width = width,
        height = width * 3 / 2,
        contentDescription = title,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        onPlay = onPlay,
        onFocused = onFocused,
        progress = progress,
        tag = tag,
        tagAccent = !played && unplayed > 0,
        favorite = item.favorite,
        label =
            if (showLabel) {
                {
                    CardTitleText(title)
                    if (detail != null) CardDetailText(detail)
                }
            } else {
                null
            },
    )
}

/** Year for films; "3 seasons" or "S2 · 8 left" for series and seasons. */
@Composable
fun posterDetail(item: BaseItem): String? {
    val unplayed = item.data.userData?.unplayedItemCount ?: 0
    val season = item.indexNumber ?: item.data.parentIndexNumber
    return when (item.type) {
        BaseItemKind.SERIES -> {
            val seasons = item.data.childCount
            when {
                unplayed > 0 && season != null -> {
                    stringResource(R.string.jtv_media_season_left, season, unplayed)
                }

                seasons != null && seasons > 0 -> {
                    pluralStringResource(R.plurals.jtv_media_seasons, seasons, seasons)
                }

                else -> {
                    item.data.productionYear?.toString()
                }
            }
        }

        BaseItemKind.SEASON -> {
            if (unplayed > 0 && season != null) {
                stringResource(R.string.jtv_media_season_left, season, unplayed)
            } else {
                item.data.productionYear?.toString()
            }
        }

        else -> {
            item.data.productionYear?.toString()
        }
    }
}

internal val PosterWidth = 132.dp
