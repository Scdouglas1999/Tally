package io.github.scdouglas1999.tally.postplay.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.logCoilError
import io.github.scdouglas1999.tally.postplay.FilmBackdrop
import io.github.scdouglas1999.tally.postplay.metaLine
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

private val SimilarWidth = 96.dp

/**
 * The post-play page on a phone, in landscape like the player it follows: the film's backdrop dimmed behind, its
 * poster at the left; at the right YOU JUST WATCHED, the logo or title, the meta line, WATCH AGAIN and DONE, and the
 * "more like this" posters under them. Same view model and actions as the TV page.
 */
@Composable
fun PhonePostPlay(
    film: BaseItemDto?,
    similar: List<BaseItemDto>?,
    onWatchAgain: () -> Unit,
    onDone: () -> Unit,
    onOpen: (BaseItemDto) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(TallyColors.ground)) {
        if (film == null) return@Box
        FilmBackdrop(film)
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            val images = LocalImageUrlService.current
            val posterUrl = remember(film.id) { images.getItemImageUrl(itemId = film.id, imageType = ImageType.PRIMARY) }
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                        .background(TallyColors.screen)
                        .border(PhoneDimens.hairline, TallyColors.ruleStrong),
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = film.name,
                    contentScale = ContentScale.Crop,
                    onError = { logCoilError(posterUrl, it.result) },
                    modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
                )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.tally_postplay_kicker).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                LogoOrTitle(film)
                val meta = metaLine(film)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = meta, style = PhoneType.meta, color = TallyColors.textSecondary, maxLines = 1)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PostPlayButton(
                        label = stringResource(R.string.tally_postplay_watch_again),
                        primary = true,
                        onClick = onWatchAgain,
                    )
                    PostPlayButton(
                        label = stringResource(R.string.tally_postplay_done),
                        primary = false,
                        onClick = onDone,
                    )
                }
                if (!similar.isNullOrEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.tally_postplay_more_like_this).tallyUppercase(),
                        style = PhoneType.labelLarge,
                        color = TallyColors.text,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
                        contentPadding = PaddingValues(end = 8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(similar, key = { it.id }) { item -> SimilarPoster(item, onClick = { onOpen(item) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogoOrTitle(film: BaseItemDto) {
    val images = LocalImageUrlService.current
    val logoUrl =
        remember(film.id) {
            if (ImageType.LOGO in film.imageTags.orEmpty()) {
                images.getItemImageUrl(itemId = film.id, imageType = ImageType.LOGO, maxWidth = 480, maxHeight = 144)
            } else {
                null
            }
        }
    var failed by remember(film.id) { mutableStateOf(false) }
    if (logoUrl != null && !failed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = film.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = {
                logCoilError(logoUrl, it.result)
                failed = true
            },
            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 56.dp),
        )
    } else {
        Text(
            text = film.name.orEmpty(),
            style = PhoneType.title,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** WATCH AGAIN (accent fill) / DONE (1dp `ruleStrong` frame), 48dp tall, mono. */
@Composable
private fun PostPlayButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .height(PhoneDimens.touchTarget)
                .background(if (primary) TallyColors.accent else TallyColors.ground.copy(alpha = 0.6f))
                .then(if (primary) Modifier else Modifier.border(PhoneDimens.hairline, TallyColors.ruleStrong))
                .phoneClickable(onClick = onClick)
                .padding(horizontal = 20.dp),
    ) {
        Text(
            text = label.tallyUppercase(),
            style = PhoneType.labelLarge,
            color = if (primary) TallyColors.onAccent else TallyColors.text,
            maxLines = 1,
        )
    }
}

/** A small poster with its title (Sans, as the kit cards) in a black label bar. */
@Composable
private fun SimilarPoster(
    item: BaseItemDto,
    onClick: () -> Unit,
) {
    val images = LocalImageUrlService.current
    val url = remember(item.id) { images.getItemImageUrl(itemId = item.id, imageType = ImageType.PRIMARY, fillWidth = 240) }
    Column(
        modifier =
            Modifier
                .width(SimilarWidth)
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .phoneClickable(onClick = onClick),
    ) {
        AsyncImage(
            model = url,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            onError = { logCoilError(url, it.result) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(TallyColors.screen),
        )
        // The kit cards' title (Sans, not the mono label: a mono title was cut after a few letters).
        Text(
            text = item.name.orEmpty(),
            style =
                io.github.scdouglas1999.tally.media.kit.CardTitleStyle.copy(
                    hyphens = androidx.compose.ui.text.style.Hyphens.Auto,
                ),
            color = TallyColors.text,
            // Two lines: at this width one line keeps only a few letters of most titles.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(TallyColors.labelBar)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
