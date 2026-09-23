package com.github.damontecres.wholphin.jellytv.surprise

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytv.ui.components.EmptyState
import com.github.damontecres.wholphin.jellytv.ui.components.IndicatorSquare
import com.github.damontecres.wholphin.jellytv.ui.components.JtvRow
import com.github.damontecres.wholphin.jellytv.ui.components.KeyHint
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvSurface
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber

/**
 * One random film or show. [JtvSurface] already applies [com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale].
 */
@Composable
fun SurprisePage(
    @Suppress("UNUSED_PARAMETER") preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: SurpriseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageUrls = LocalImageUrlService.current
    val playFocus = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    val genreChip = remember { FocusRequester() }
    val underChip = remember { FocusRequester() }
    val kidChip = remember { FocusRequester() }
    val unwatchedChip = remember { FocusRequester() }
    val tryAgain = remember { FocusRequester() }
    var keepChipFocus by remember { mutableStateOf(false) }
    var heldChip by remember { mutableStateOf(firstChip) }
    var spinning by remember { mutableStateOf(false) }
    var slide by remember { mutableStateOf(false) }
    var shownPick by remember { mutableStateOf<BaseItem?>(null) }
    var poster by remember { mutableStateOf<BaseItem?>(null) }
    val textAlpha = remember { Animatable(1f) }

    LaunchedEffect(state.shuffleId) {
        val pick = state.pick
        val reel = state.reel
        if (pick == null || reel.isEmpty()) {
            spinning = false
            slide = false
            shownPick = null
            poster = null
            textAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        if (reel.size == 1) {
            spinning = false
            slide = false
            shownPick = pick
            poster = pick
            textAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        spinning = true
        slide = true
        if (shownPick != null && textAlpha.value > 0f) {
            textAlpha.animateTo(0f, tween(TEXT_FADE_OUT_MS))
        } else {
            textAlpha.snapTo(0f)
        }
        shownPick = pick
        // A channel scan: hard cuts through candidates whose posters are already in memory, slowing
        // down, then the pick lands. Never waits for a poster; nothing to scan with animations off.
        val scanning = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
        val candidates = reel.filter { it.id != pick.id }
        if (scanning && candidates.size >= 2) {
            val loader = context.imageLoader
            var next = 0
            for (hold in SCAN_FRAME_MS) {
                val found =
                    nextScanCandidate(candidates, next) { item ->
                        imageUrls.getItemImageUrl(item, ImageType.PRIMARY)?.let { posterInMemory(loader, it) } == true
                    } ?: break
                poster = candidates[found]
                next = found + 1
                delay(hold.toLong())
            }
        }
        poster = pick
        spinning = false
        textAlpha.animateTo(1f, tween(TEXT_FADE_IN_MS))
    }

    // Preload the candidates' posters as soon as the result set arrives, for the channel scan.
    LaunchedEffect(state.reel) {
        preloadPosters(context, state.reel.mapNotNull { imageUrls.getItemImageUrl(it, ImageType.PRIMARY) })
    }

    LaunchedEffect(state.shuffleId) {
        if (state.shuffleId == 0) return@LaunchedEffect
        if (keepChipFocus) {
            heldChip.requestWhenReady("surprise-chip")
            return@LaunchedEffect
        }
        when {
            state.error != null -> tryAgain.requestWhenReady("surprise-retry")
            state.pick != null -> playFocus.requestWhenReady("surprise-play")
        }
    }

    val downTarget =
        when {
            state.error != null -> tryAgain
            state.pick != null -> playFocus
            else -> null
        }

    JtvSurface(modifier) {
        Box(Modifier.fillMaxSize()) {
            SurpriseBackdrop(
                item = shownPick,
                alpha = textAlpha.value,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(BACKDROP_WIDTH),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = JtvDimens.marginHorizontal),
            ) {
                Spacer(Modifier.height(JtvDimens.marginVertical))
                FilterBar(
                    state = state,
                    firstChip = firstChip,
                    genreChip = genreChip,
                    underChip = underChip,
                    kidChip = kidChip,
                    unwatchedChip = unwatchedChip,
                    downTarget = downTarget,
                    onKind = {
                        heldChip = firstChip
                        keepChipFocus = true
                        val next =
                            if (state.filters.kind == SurpriseKind.MOVIES) {
                                SurpriseKind.SHOWS
                            } else {
                                SurpriseKind.MOVIES
                            }
                        viewModel.setKind(next)
                    },
                    onGenre = {
                        heldChip = genreChip
                        keepChipFocus = true
                        viewModel.cycleGenre()
                    },
                    onUnderTwoHours = {
                        heldChip = underChip
                        keepChipFocus = true
                        viewModel.toggleUnderTwoHours()
                    },
                    onKidFriendly = {
                        heldChip = kidChip
                        keepChipFocus = true
                        viewModel.toggleKidFriendly()
                    },
                    onUnwatched = {
                        heldChip = unwatchedChip
                        keepChipFocus = true
                        viewModel.toggleUnwatchedOnly()
                    },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = JtvDimens.marginVertical),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    when {
                        state.error != null -> {
                            ErrorBlock(
                                message = state.error.orEmpty(),
                                tryAgain = tryAgain,
                                upTarget = firstChip,
                                onRetry = {
                                    keepChipFocus = false
                                    viewModel.shuffle()
                                },
                            )
                        }

                        !state.loading && state.pick == null -> {
                            EmptyState(
                                title = stringResource(R.string.jtv_surprise_empty_title),
                                subtitle = stringResource(R.string.jtv_surprise_empty_subtitle),
                                takeFocus = !keepChipFocus,
                                modifier =
                                    Modifier
                                        .width(EMPTY_WIDTH.dp)
                                        .height(EMPTY_HEIGHT.dp)
                                        .focusProperties { up = firstChip },
                            )
                        }

                        else -> {
                            PickBlock(
                                state = state,
                                shownPick = shownPick,
                                poster = poster,
                                slide = slide,
                                textAlpha = textAlpha.value,
                                playFocus = playFocus,
                                upTarget = firstChip,
                                onPlay = {
                                    if (!spinning && !state.loading) viewModel.play()
                                },
                                onShuffle = {
                                    keepChipFocus = false
                                    viewModel.shuffle()
                                },
                                onDetails = viewModel::openDetails,
                            )
                        }
                    }
                }
            }
            KeyHint(
                key = stringResource(R.string.jtv_surprise_key_up),
                label = stringResource(R.string.jtv_surprise_key_filters),
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            horizontal = JtvDimens.marginHorizontal,
                            vertical = JtvDimens.marginVertical,
                        ),
            )
        }
    }
}

@Composable
private fun FilterBar(
    state: SurpriseState,
    firstChip: FocusRequester,
    genreChip: FocusRequester,
    underChip: FocusRequester,
    kidChip: FocusRequester,
    unwatchedChip: FocusRequester,
    downTarget: FocusRequester?,
    onKind: () -> Unit,
    onGenre: () -> Unit,
    onUnderTwoHours: () -> Unit,
    onKidFriendly: () -> Unit,
    onUnwatched: () -> Unit,
) {
    val filters = state.filters
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(CHIP_HEIGHT.dp)
                .padding(end = CLOCK_CLEARANCE.dp),
    ) {
        FilterChip(
            onClick = onKind,
            modifier =
                Modifier
                    .focusRequester(firstChip)
                    .focusDown(downTarget),
        ) {
            KindLabel(filters.kind)
        }
        FilterChip(
            onClick = onGenre,
            modifier =
                Modifier
                    .focusRequester(genreChip)
                    .focusDown(downTarget),
        ) {
            val name = filters.genre ?: stringResource(R.string.jtv_surprise_genre_any)
            Text(
                text = stringResource(R.string.jtv_surprise_genre, name).uppercase(),
                style = JtvType.label,
                color = JtvColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = GENRE_MAX.dp),
            )
        }
        if (filters.kind == SurpriseKind.MOVIES) {
            FilterChip(
                onClick = onUnderTwoHours,
                modifier =
                    Modifier
                        .focusRequester(underChip)
                        .focusDown(downTarget),
            ) {
                ToggleLabel(
                    text = stringResource(R.string.jtv_surprise_under_two_hours),
                    on = filters.underTwoHours,
                )
            }
        }
        FilterChip(
            onClick = onKidFriendly,
            modifier =
                Modifier
                    .focusRequester(kidChip)
                    .focusDown(downTarget),
        ) {
            ToggleLabel(
                text = stringResource(R.string.jtv_surprise_kid_friendly),
                on = filters.kidFriendly,
            )
        }
        FilterChip(
            onClick = onUnwatched,
            modifier =
                Modifier
                    .focusRequester(unwatchedChip)
                    .focusDown(downTarget),
        ) {
            ToggleLabel(
                text = stringResource(R.string.jtv_surprise_unwatched),
                on = filters.unwatchedOnly,
            )
        }
        Spacer(Modifier.weight(1f))
        val matches = state.matches
        if (matches != null) {
            Text(
                text =
                    pluralStringResource(R.plurals.jtv_surprise_matches, matches, matches)
                        .uppercase(),
                style = JtvType.label,
                color = JtvColors.muted,
                maxLines = 1,
                modifier =
                    Modifier
                        .background(JtvColors.ground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun KindLabel(kind: SurpriseKind) {
    val movies = kind == SurpriseKind.MOVIES
    Text(
        text = stringResource(R.string.jtv_surprise_movies).uppercase(),
        style = JtvType.label,
        color = if (movies) JtvColors.accent else JtvColors.muted,
        maxLines = 1,
    )
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .width(JtvDimens.hairline)
            .height(14.dp)
            .background(JtvColors.ruleStrong),
    )
    Text(
        text = stringResource(R.string.jtv_surprise_shows).uppercase(),
        style = JtvType.label,
        color = if (movies) JtvColors.muted else JtvColors.accent,
        maxLines = 1,
    )
}

@Composable
private fun ToggleLabel(
    text: String,
    on: Boolean,
) {
    IndicatorSquare(
        color = if (on) JtvColors.accent else JtvColors.ruleStrong,
        size = 8.dp,
    )
    Text(
        text = text.uppercase(),
        style = JtvType.label,
        color = JtvColors.text,
        maxLines = 1,
    )
}

@Composable
private fun FilterChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors = chipColors(primary = false),
        border = chipBorder(primary = false),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        interactionSource = interaction,
        modifier = modifier.height(CHIP_HEIGHT.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            content = content,
        )
    }
}

@Composable
private fun PickBlock(
    state: SurpriseState,
    shownPick: BaseItem?,
    poster: BaseItem?,
    slide: Boolean,
    textAlpha: Float,
    playFocus: FocusRequester,
    upTarget: FocusRequester,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDetails: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PosterSlot(
            poster = poster,
            slide = slide,
        )
        Spacer(Modifier.width(24.dp))
        Column {
            AnimatedContent(
                targetState = shownPick,
                contentKey = { it?.id },
                transitionSpec = {
                    if (slide) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        fadeIn(tween(BACKDROP_FADE_MS)) togetherWith fadeOut(tween(BACKDROP_FADE_MS))
                    }
                },
                modifier =
                    Modifier
                        .alpha(textAlpha)
                        .widthIn(max = TEXT_MAX.dp),
            ) { item ->
                if (item == null) {
                    Spacer(Modifier.height(1.dp))
                } else {
                    PickText(item, state.filters.kind)
                }
            }
            val pick = state.pick
            if (pick != null) {
                Spacer(Modifier.height(16.dp))
                ActionRow(
                    pick = pick,
                    playFocus = playFocus,
                    upTarget = upTarget,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onDetails = onDetails,
                )
            }
        }
    }
}

@Composable
private fun PosterSlot(
    poster: BaseItem?,
    slide: Boolean,
) {
    Box(
        Modifier
            .size(POSTER_W.dp, POSTER_H.dp)
            .border(JtvDimens.hairline, JtvColors.ruleStrong)
            .clipToBounds()
            .background(JtvColors.ground),
    ) {
        AnimatedContent(
            targetState = poster,
            contentKey = { it?.id },
            transitionSpec = {
                // The channel scan and its landing are hard cuts; a single result fades in.
                if (slide) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(BACKDROP_FADE_MS)) togetherWith fadeOut(tween(BACKDROP_FADE_MS)))
                        .using(SizeTransform(clip = true))
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            PosterImage(item, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PosterImage(
    item: BaseItem?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val urls = LocalImageUrlService.current
    val url = item?.let { urls.getItemImageUrl(it, ImageType.PRIMARY) }
    if (item == null || url == null) {
        Box(modifier.background(JtvColors.ground))
        return
    }
    AsyncPoster(context, url, item.title ?: item.name, modifier)
}

@Composable
private fun AsyncPoster(
    context: Context,
    url: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    coil3.compose.AsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(url)
                .memoryCacheKey(url)
                .size(Size.ORIGINAL)
                .build(),
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun PickText(
    item: BaseItem,
    kind: SurpriseKind,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.jtv_surprise_kicker).uppercase(),
            style = JtvType.label,
            color = JtvColors.accent,
            maxLines = 1,
        )
        TitleOrLogo(item)
        val meta = metaLine(item, kind)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = JtvType.label,
                color = JtvColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val genres =
            item.data.genres
                .orEmpty()
                .filter { it.isNotBlank() }
                .take(3)
        if (genres.isNotEmpty()) {
            Text(
                text = genres.joinToString(" / "),
                style = JtvType.body,
                color = JtvColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val overview =
            item.data.overview
                ?.trim()
                .orEmpty()
        if (overview.isNotEmpty()) {
            Text(
                text = overview,
                style = JtvType.body,
                color = JtvColors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val percent = resumePercent(item)
        if (percent != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .width(PROGRESS_WIDTH.dp)
                        .height(4.dp)
                        .background(JtvColors.ruleStrong),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(percent / 100f)
                            .background(JtvColors.accent),
                    )
                }
                Text(
                    text = stringResource(R.string.jtv_surprise_resume_percent, percent).uppercase(),
                    style = JtvType.label,
                    color = JtvColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TitleOrLogo(item: BaseItem) {
    val urls = LocalImageUrlService.current
    var failed by remember(item.id) { mutableStateOf(false) }
    val logo =
        if (failed || ImageType.LOGO !in item.data.imageTags.orEmpty()) {
            null
        } else {
            urls.getItemImageUrl(item, ImageType.LOGO)
        }
    if (logo != null) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .widthIn(max = LOGO_MAX_W.dp)
                    .height(LOGO_MAX_H.dp),
        ) {
            coil3.compose.AsyncImage(
                model = logo,
                contentDescription = item.title ?: item.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { failed = true },
                modifier =
                    Modifier
                        .heightIn(max = LOGO_MAX_H.dp)
                        .widthIn(max = LOGO_MAX_W.dp),
            )
        }
    } else {
        Text(
            text = item.title ?: item.name.orEmpty(),
            color = JtvColors.text,
            style =
                TextStyle(
                    fontFamily = JtvType.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun metaLine(
    item: BaseItem,
    kind: SurpriseKind,
): String {
    val parts = mutableListOf<String>()
    item.data.productionYear?.let { parts += it.toString() }
    item.data.officialRating
        ?.takeIf { it.isNotBlank() }
        ?.let { parts += it }
    if (kind == SurpriseKind.SHOWS) {
        item.data.childCount?.takeIf { it > 0 }?.let { count ->
            parts += pluralStringResource(R.plurals.jtv_surprise_seasons, count, count).uppercase()
        }
    } else {
        item.data.runTimeTicks?.takeIf { it > 0L }?.let { ticks ->
            parts += runtimeLabel(ticks)
        }
    }
    item.data.communityRating?.let { rating ->
        parts += stringResource(R.string.jtv_surprise_rating, rating)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun runtimeLabel(ticks: Long): String {
    val minutesTotal = (ticks / TICKS_PER_SECOND / 60L).toInt()
    val hours = minutesTotal / 60
    val minutes = minutesTotal % 60
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.jtv_surprise_runtime_hm, hours, minutes)
        hours > 0 -> stringResource(R.string.jtv_surprise_runtime_h, hours)
        else -> stringResource(R.string.jtv_surprise_runtime_m, minutes)
    }
}

@Composable
private fun ActionRow(
    pick: BaseItem,
    playFocus: FocusRequester,
    upTarget: FocusRequester,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDetails: () -> Unit,
) {
    val resume = resumePercent(pick) != null
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SurpriseButton(
            label =
                stringResource(
                    if (resume) R.string.jtv_surprise_resume else R.string.jtv_surprise_play,
                ).uppercase(),
            onClick = onPlay,
            primary = true,
            modifier =
                Modifier
                    .focusRequester(playFocus)
                    .focusProperties { up = upTarget },
        )
        SurpriseButton(
            label = stringResource(R.string.jtv_surprise_shuffle).uppercase(),
            onClick = onShuffle,
            modifier = Modifier.focusProperties { up = upTarget },
        )
        SurpriseButton(
            label = stringResource(R.string.jtv_surprise_details).uppercase(),
            onClick = onDetails,
            modifier = Modifier.focusProperties { up = upTarget },
        )
    }
}

@Composable
private fun SurpriseButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors = chipColors(primary),
        border = chipBorder(primary),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .widthIn(min = BUTTON_MIN.dp)
                    .height(BUTTON_HEIGHT.dp)
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = label,
                style = JtvType.body,
                color = if (primary) JtvColors.onAccent else JtvColors.text,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    tryAgain: FocusRequester,
    upTarget: FocusRequester,
    onRetry: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.width(EMPTY_WIDTH.dp),
    ) {
        EmptyState(
            title = stringResource(R.string.jtv_surprise_error_title),
            subtitle = message.ifBlank { stringResource(R.string.jtv_surprise_error_generic) },
            takeFocus = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(EMPTY_HEIGHT.dp),
        )
        JtvRow(
            label = stringResource(R.string.jtv_surprise_try_again).uppercase(),
            onClick = onRetry,
            primary = true,
            modifier =
                Modifier
                    .focusRequester(tryAgain)
                    .focusProperties { up = upTarget },
        )
    }
}

@Composable
private fun SurpriseBackdrop(
    item: BaseItem?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val urls = LocalImageUrlService.current
    val url =
        remember(item?.id) {
            item?.let { urls.getItemImageUrl(it, ImageType.BACKDROP) }
        }
    Box(modifier.alpha(alpha)) {
        Crossfade(
            targetState = url,
            animationSpec = tween(BACKDROP_FADE_MS),
            modifier = Modifier.fillMaxSize(),
        ) { target ->
            if (target != null) {
                coil3.compose.AsyncImage(
                    model = target,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (url != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0f to JtvColors.ground,
                                    0.45f to Color.Transparent,
                                ),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.55f to Color.Transparent,
                                    1f to JtvColors.ground,
                                ),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun chipColors(primary: Boolean) =
    ClickableSurfaceDefaults.colors(
        containerColor = if (primary) JtvColors.accent else JtvColors.ground,
        contentColor = if (primary) JtvColors.onAccent else JtvColors.text,
        focusedContainerColor = if (primary) JtvColors.accent else JtvColors.groundRaised,
        focusedContentColor = if (primary) JtvColors.onAccent else JtvColors.text,
        pressedContainerColor = if (primary) JtvColors.accent else JtvColors.groundRaised,
        pressedContentColor = if (primary) JtvColors.onAccent else JtvColors.text,
    )

@Composable
private fun chipBorder(primary: Boolean) =
    ClickableSurfaceDefaults.border(
        border =
            Border(
                border =
                    BorderStroke(
                        JtvDimens.hairline,
                        if (primary) JtvColors.accent else JtvColors.ruleStrong,
                    ),
                shape = RectangleShape,
            ),
        focusedBorder =
            Border(
                border =
                    BorderStroke(
                        JtvDimens.focusBorder,
                        if (primary) JtvColors.text else JtvColors.accent,
                    ),
                shape = RectangleShape,
            ),
        pressedBorder =
            Border(
                border =
                    BorderStroke(
                        JtvDimens.focusBorder,
                        if (primary) JtvColors.text else JtvColors.accent,
                    ),
                shape = RectangleShape,
            ),
    )

private fun Modifier.focusDown(target: FocusRequester?): Modifier = if (target == null) this else focusProperties { down = target }

private suspend fun FocusRequester.requestWhenReady(tag: String) {
    repeat(5) {
        if (tryRequestFocus(tag)) return
        delay(40)
    }
}

private suspend fun preloadPosters(
    context: Context,
    urls: List<String>,
) {
    if (urls.isEmpty()) return
    try {
        withTimeoutOrNull(PRELOAD_MS) {
            coroutineScope {
                urls
                    .map { url ->
                        async {
                            try {
                                context.imageLoader.execute(
                                    ImageRequest
                                        .Builder(context)
                                        .data(url)
                                        .memoryCacheKey(url)
                                        .size(Size.ORIGINAL)
                                        .build(),
                                )
                            } catch (canceled: CancellationException) {
                                throw canceled
                            } catch (error: Exception) {
                                Timber.w(error, "Surprise poster preload failed")
                            }
                        }
                    }.awaitAll()
            }
        }
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (error: Exception) {
        Timber.w(error, "Surprise poster preload failed")
    }
}

/** Hold times of the channel scan's frames, slowing down; at most this many candidates are shown. */
internal val SCAN_FRAME_MS = listOf(60, 70, 90, 120, 160)

/**
 * Index of the first candidate at or after [from] whose poster is ready ([ready]), or null when
 * none is: a candidate that is not ready is skipped, never waited for.
 */
internal fun <T> nextScanCandidate(
    candidates: List<T>,
    from: Int,
    ready: (T) -> Boolean,
): Int? {
    for (index in from.coerceAtLeast(0) until candidates.size) {
        if (ready(candidates[index])) return index
    }
    return null
}

/** True when [url]'s poster is decoded in memory (requests here key the memory cache by URL). */
private fun posterInMemory(
    loader: ImageLoader,
    url: String,
): Boolean = loader.memoryCache?.get(MemoryCache.Key(url)) != null

private fun resumePercent(item: BaseItem): Int? {
    if (item.type != BaseItemKind.MOVIE || item.resumeMs <= 0L) return null
    val runtime = item.data.runTimeTicks
    val ticks = item.data.userData?.playbackPositionTicks ?: return null
    if (runtime != null && runtime > 0L && ticks >= runtime) return null
    val reported = item.data.userData?.playedPercentage
    val percent =
        when {
            reported != null && reported > 0.0 -> reported
            runtime != null && runtime > 0L -> ticks * 100.0 / runtime.toDouble()
            else -> return null
        }
    if (percent >= 99.5) return null
    return percent.toInt().coerceIn(1, 99)
}

private const val BACKDROP_WIDTH = 0.64f
private const val BACKDROP_FADE_MS = 400
private const val TEXT_FADE_OUT_MS = 120
private const val TEXT_FADE_IN_MS = 300
private const val PRELOAD_MS = 800L
private const val TICKS_PER_SECOND = 10_000_000L
private const val POSTER_W = 168
private const val POSTER_H = 252
private const val TEXT_MAX = 420
private const val LOGO_MAX_W = 360
private const val LOGO_MAX_H = 96
private const val PROGRESS_WIDTH = 200
private const val CHIP_HEIGHT = 36
private const val BUTTON_MIN = 160
private const val BUTTON_HEIGHT = 52
private const val GENRE_MAX = 280
private const val EMPTY_WIDTH = 560
private const val EMPTY_HEIGHT = 200

/** Upstream draws its clock in this corner; keep the match count to the left of it. */
private const val CLOCK_CLEARANCE = 88
