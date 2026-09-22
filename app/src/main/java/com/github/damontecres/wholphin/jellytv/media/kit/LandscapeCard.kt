package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import org.jellyfin.sdk.model.api.ImageType

/** 16:9 artwork. Label bar is an accent kicker over the title. */
@Composable
fun LandscapeCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    onPlay: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    progress: Float? = null,
    width: Dp = LandscapeWidth,
) {
    CardFrame(
        imageUrl = imageUrl,
        width = width,
        height = width * 9 / 16,
        contentDescription = title,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        onPlay = onPlay,
        onFocused = onFocused,
        progress = progress,
        label = {
            if (!kicker.isNullOrBlank()) {
                Text(
                    text = kicker.uppercase(),
                    style = CardKickerStyle,
                    color = JtvColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (title.isNotBlank()) CardTitleText(title)
        },
    )
}

/** Thumb, then backdrop, then primary. */
@Composable
fun rememberWideImageUrl(item: BaseItem): String? {
    val service = LocalImageUrlService.current
    return remember(item.id, item.data.imageTags, item.data.backdropImageTags) {
        val tags = item.data.imageTags.orEmpty()
        val type =
            when {
                ImageType.THUMB in tags -> ImageType.THUMB

                item.data.backdropImageTags
                    .orEmpty()
                    .isNotEmpty() -> ImageType.BACKDROP

                else -> ImageType.PRIMARY
            }
        service.getItemImageUrl(item, type)
    }
}

val LandscapeWidth = 232.dp
