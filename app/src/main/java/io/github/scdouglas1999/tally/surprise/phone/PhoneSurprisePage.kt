package io.github.scdouglas1999.tally.surprise.phone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.surprise.PosterImage
import io.github.scdouglas1999.tally.surprise.SurpriseKind
import io.github.scdouglas1999.tally.surprise.SurpriseState
import io.github.scdouglas1999.tally.surprise.SurpriseViewModel
import io.github.scdouglas1999.tally.surprise.metaLine
import io.github.scdouglas1999.tally.surprise.surpriseResumePercent
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.ImageType

/** The pick's poster takes this share of the page width. */
private const val POSTER_WIDTH_FRACTION = 0.55f
private const val POSTER_FADE_MS = 400

/** A swipe this far to the left on the pick shuffles again. */
private val SwipeToShuffle = 80.dp

/**
 * Surprise me on a phone, from the same [SurpriseViewModel] and the TV page's pick animation ([shownPick], [poster],
 * [slide], [textAlpha]: the channel scan, then the pick): a top bar; the filter chips as a sideways-scrolling row
 * (MOVIES | SHOWS, GENRE, UNDER 2h, KID-FRIENDLY, UNWATCHED, the match count at the end); the pick below it, its
 * poster 55% of the width and centered, TONIGHT'S PICK, logo or title, meta, genres, 4 lines of overview; PLAY
 * (accent, full width), then SHUFFLE AGAIN and DETAILS side by side. A swipe left on the pick shuffles again.
 */
@Composable
internal fun PhoneSurpriseContent(
    state: SurpriseState,
    shownPick: BaseItem?,
    poster: BaseItem?,
    slide: Boolean,
    textAlpha: Float,
    spinning: Boolean,
    viewModel: SurpriseViewModel,
    modifier: Modifier = Modifier,
) {
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val filters = state.filters
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(title = stringResource(R.string.tally_surprise_name))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = PhoneDimens.margin),
        ) {
            SurpriseChip(
                onClick = {
                    viewModel.setKind(if (filters.kind == SurpriseKind.MOVIES) SurpriseKind.SHOWS else SurpriseKind.MOVIES)
                },
            ) {
                val movies = filters.kind == SurpriseKind.MOVIES
                ChipText(stringResource(R.string.tally_surprise_movies), if (movies) TallyColors.accent else TallyColors.muted)
                Box(Modifier.width(PhoneDimens.hairline).height(12.dp).background(TallyColors.ruleStrong))
                ChipText(stringResource(R.string.tally_surprise_shows), if (movies) TallyColors.muted else TallyColors.accent)
            }
            SurpriseChip(onClick = viewModel::cycleGenre) {
                val name = filters.genre ?: stringResource(R.string.tally_surprise_genre_any)
                ChipText(stringResource(R.string.tally_surprise_genre, name), TallyColors.text)
            }
            if (filters.kind == SurpriseKind.MOVIES) {
                ToggleChip(stringResource(R.string.tally_surprise_under_two_hours), filters.underTwoHours, viewModel::toggleUnderTwoHours)
            }
            ToggleChip(stringResource(R.string.tally_surprise_kid_friendly), filters.kidFriendly, viewModel::toggleKidFriendly)
            ToggleChip(stringResource(R.string.tally_surprise_unwatched), filters.unwatchedOnly, viewModel::toggleUnwatchedOnly)
            val matches = state.matches
            if (matches != null) {
                Text(
                    text = pluralStringResource(R.plurals.tally_surprise_matches, matches, matches).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.error != null -> {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        PhoneEmptyState(
                            title = stringResource(R.string.tally_surprise_error_title),
                            subtitle = state.error.orEmpty().ifBlank { stringResource(R.string.tally_surprise_error_generic) },
                        )
                        PhoneButton(
                            label = stringResource(R.string.tally_surprise_try_again),
                            primary = true,
                            onClick = viewModel::shuffle,
                            modifier = Modifier.padding(horizontal = PhoneDimens.margin),
                        )
                    }
                }

                !state.loading && state.pick == null -> {
                    PhoneEmptyState(
                        title = stringResource(R.string.tally_surprise_empty_title),
                        subtitle = stringResource(R.string.tally_surprise_empty_subtitle),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                else -> {
                    PickColumn(
                        state = state,
                        shownPick = shownPick,
                        poster = poster,
                        slide = slide,
                        textAlpha = textAlpha,
                        onPlay = { if (!spinning && !state.loading) viewModel.play() },
                        onShuffle = viewModel::shuffle,
                        onDetails = viewModel::openDetails,
                        modifier = Modifier.padding(bottom = bottom),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickColumn(
    state: SurpriseState,
    shownPick: BaseItem?,
    poster: BaseItem?,
    slide: Boolean,
    textAlpha: Float,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swipePx = with(LocalDensity.current) { SwipeToShuffle.toPx() }
    val currentShuffle by rememberUpdatedState(onShuffle)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (dragged < -swipePx) currentShuffle() },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragged += amount
                        },
                    )
                }.verticalScroll(rememberScrollState())
                .padding(horizontal = PhoneDimens.margin)
                .padding(top = 20.dp, bottom = PhoneDimens.rowGap),
    ) {
        Box(
            Modifier
                .fillMaxWidth(POSTER_WIDTH_FRACTION)
                .aspectRatio(2f / 3f)
                .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                .clipToBounds()
                .background(TallyColors.screen),
        ) {
            AnimatedContent(
                targetState = poster,
                contentKey = { it?.id },
                transitionSpec = {
                    // The channel scan and its landing are hard cuts; a single result fades in (as on the TV).
                    if (slide) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(POSTER_FADE_MS)) togetherWith fadeOut(tween(POSTER_FADE_MS)))
                            .using(SizeTransform(clip = true))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { item ->
                PosterImage(item, Modifier.fillMaxSize())
            }
        }
        val pick = shownPick
        if (pick != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).alpha(textAlpha),
            ) {
                Text(
                    text = stringResource(R.string.tally_surprise_kicker).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.accent,
                    maxLines = 1,
                )
                PickTitle(pick)
                val meta = metaLine(pick, state.filters.kind)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = PhoneType.meta,
                        color = TallyColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val genres =
                    pick.data.genres
                        .orEmpty()
                        .filter { it.isNotBlank() }
                        .take(3)
                if (genres.isNotEmpty()) {
                    Text(
                        text = genres.joinToString(" / "),
                        style = PhoneType.bodySmall,
                        color = TallyColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val overview =
                    pick.data.overview
                        ?.trim()
                        .orEmpty()
                if (overview.isNotEmpty()) {
                    Text(
                        text = overview,
                        style = PhoneType.body,
                        color = TallyColors.textSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val percent = surpriseResumePercent(pick)
                Spacer(Modifier.height(8.dp))
                PhoneButton(
                    label =
                        if (percent != null) {
                            stringResource(R.string.tally_surprise_resume_percent, percent)
                        } else {
                            stringResource(R.string.tally_surprise_play)
                        },
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    progress = percent?.let { it / 100f },
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap)) {
                    PhoneButton(
                        label = stringResource(R.string.tally_surprise_shuffle),
                        glyph = stringResource(R.string.fa_shuffle),
                        onClick = onShuffle,
                        modifier = Modifier.weight(1f),
                    )
                    PhoneButton(
                        label = stringResource(R.string.tally_surprise_details),
                        onClick = onDetails,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The pick's logo (max 220x64dp) or its title in `PhoneType.display`. */
@Composable
private fun PickTitle(item: BaseItem) {
    val urls = LocalImageUrlService.current
    var failed by remember(item.id) { mutableStateOf(false) }
    val logo =
        if (failed || ImageType.LOGO !in item.data.imageTags.orEmpty()) null else urls.getItemImageUrl(item, ImageType.LOGO)
    if (logo != null) {
        coil3.compose.AsyncImage(
            model = logo,
            contentDescription = item.title ?: item.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.heightIn(max = 64.dp).widthIn(max = 220.dp),
        )
    } else {
        Text(
            text = item.title ?: item.name.orEmpty(),
            style = PhoneType.display,
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A filter chip: 40dp in a 48dp target, 1dp `ruleStrong`, mono label. */
@Composable
private fun SurpriseChip(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(PhoneDimens.touchTarget).phoneClickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .height(40.dp)
                    .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                    .padding(horizontal = 12.dp),
            content = content,
        )
    }
}

/** A toggle chip: an indicator square (accent when on) before the label, as on the TV. */
@Composable
private fun ToggleChip(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    SurpriseChip(onClick = onClick) {
        IndicatorSquare(color = if (on) TallyColors.accent else TallyColors.ruleStrong, size = 6.dp)
        ChipText(text, TallyColors.text)
    }
}

@Composable
private fun ChipText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text.tallyUppercase(),
        style = PhoneType.label,
        color = color,
        maxLines = 1,
    )
}
