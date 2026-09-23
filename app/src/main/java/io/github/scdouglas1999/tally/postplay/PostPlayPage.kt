package io.github.scdouglas1999.tally.postplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.formatRuntime
import io.github.scdouglas1999.tally.postplay.phone.PhonePostPlay
import io.github.scdouglas1999.tally.ui.components.LabelBar
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.LocalTallyFormFactor
import io.github.scdouglas1999.tally.ui.formfactor.TallyFormFactor
import io.github.scdouglas1999.tally.ui.player.controls.phone.PhonePlayerWindow
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallySurface
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber

private val ColumnMaxWidth = 520.dp
private val LogoMaxWidth = 360.dp
private val LogoMaxHeight = 96.dp
private val PosterWidth = 132.dp
private val PosterHeight = 198.dp
private const val FADE_MS = 120

/**
 * Shown when a film ends with nothing queued after it. Backdrop of the film just watched,
 * watch-again / done, and a row of similar posters.
 */
@Composable
fun PostPlayPage(
    destination: Destination.TallyPostPlay,
    modifier: Modifier = Modifier,
    viewModel: PostPlayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // BACK does what Done does, so a post-play page that is the only page never leaves the app.
    BackHandler(onBack = viewModel::done)
    LaunchedEffect(destination.itemId) {
        Timber.i("Post-play page %s", destination.itemId)
        viewModel.load(destination.itemId)
    }
    if (LocalTallyFormFactor.current == TallyFormFactor.PHONE) {
        // The player is landscape: the post-play page that follows it stays so.
        PhonePlayerWindow()
        PhonePostPlay(
            film = state.film,
            similar = state.similar,
            onWatchAgain = viewModel::watchAgain,
            onDone = viewModel::done,
            onOpen = viewModel::open,
        )
        return
    }

    val watchAgainFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val firstPosterFocus = remember { FocusRequester() }

    // The poster that had focus last: coming back from its details page returns to it, not to the first one.
    val lastPosterFocus = remember { FocusRequester() }
    var posterIndex by rememberSaveable(destination.itemId) { mutableIntStateOf(0) }
    var initialFocusPlaced by remember(destination.itemId) { mutableStateOf(false) }
    val similar = state.similar
    val hasPosters = !similar.isNullOrEmpty()

    LaunchedEffect(state.film?.id, similar, initialFocusPlaced) {
        if (initialFocusPlaced || state.film == null) return@LaunchedEffect
        if (similar == null) {
            watchAgainFocus.tryRequestFocus("postplay-watch")
            return@LaunchedEffect
        }
        if (similar.isEmpty()) {
            watchAgainFocus.tryRequestFocus("postplay-watch")
            initialFocusPlaced = true
            return@LaunchedEffect
        }
        repeat(5) {
            if (lastPosterFocus.tryRequestFocus("postplay-poster")) {
                initialFocusPlaced = true
                return@LaunchedEffect
            }
            delay(40)
        }
    }

    // TallySurface already applies TallyScale. The page is the full 960×540dp TV canvas.
    TallySurface(modifier = modifier) {
        val film = state.film
        if (film != null) {
            FilmBackdrop(film)
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = TallyDimens.marginHorizontal,
                            top = TallyDimens.marginVertical + 24.dp,
                            end = TallyDimens.marginHorizontal,
                        ).widthIn(max = ColumnMaxWidth),
            ) {
                Text(
                    text = stringResource(R.string.tally_postplay_kicker).uppercase(),
                    style = TallyType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                LogoOrTitle(film)
                val meta = metaLine(film)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = meta,
                        style = TallyType.label,
                        color = TallyColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TallyButton(
                        label = stringResource(R.string.tally_postplay_watch_again),
                        onClick = viewModel::watchAgain,
                        primary = true,
                        modifier =
                            Modifier
                                .focusRequester(watchAgainFocus)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = if (hasPosters) firstPosterFocus else FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = doneFocus
                                    start = FocusRequester.Cancel
                                    end = doneFocus
                                },
                    )
                    TallyButton(
                        label = stringResource(R.string.tally_postplay_done),
                        onClick = viewModel::done,
                        modifier =
                            Modifier
                                .focusRequester(doneFocus)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = if (hasPosters) firstPosterFocus else FocusRequester.Cancel
                                    left = watchAgainFocus
                                    right = FocusRequester.Cancel
                                    start = watchAgainFocus
                                    end = FocusRequester.Cancel
                                },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = hasPosters,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            enter = fadeIn(tween(FADE_MS)),
            exit = fadeOut(tween(FADE_MS)),
        ) {
            SimilarRow(
                items = similar.orEmpty(),
                firstPosterFocus = firstPosterFocus,
                lastPosterFocus = lastPosterFocus,
                focusIndex = posterIndex,
                onFocusIndex = { posterIndex = it },
                upTarget = watchAgainFocus,
                onOpen = viewModel::open,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = TallyDimens.marginVertical),
            )
        }
    }
}

@Composable
internal fun FilmBackdrop(film: BaseItemDto) {
    val images = LocalImageUrlService.current
    val backdropUrl =
        remember(film.id) {
            if (film.backdropImageTags.isNullOrEmpty()) {
                null
            } else {
                images.getItemImageUrl(
                    itemId = film.id,
                    imageType = ImageType.BACKDROP,
                    fillWidth = 1920,
                    fillHeight = 1080,
                )
            }
        }
    var failed by remember(film.id) { mutableStateOf(false) }
    if (backdropUrl == null || failed) return
    AsyncImage(
        model = backdropUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center,
        onError = {
            logCoilError(backdropUrl, it.result)
            failed = true
        },
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(TallyColors.ground.copy(alpha = 0.78f)),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.42f to Color.Transparent,
                    1f to TallyColors.ground,
                ),
            ),
    )
}

@Composable
private fun LogoOrTitle(film: BaseItemDto) {
    val images = LocalImageUrlService.current
    val logoUrl =
        remember(film.id) {
            if (ImageType.LOGO in film.imageTags.orEmpty()) {
                images.getItemImageUrl(
                    itemId = film.id,
                    imageType = ImageType.LOGO,
                    maxWidth = 720,
                    maxHeight = 192,
                )
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
            modifier =
                Modifier
                    .widthIn(max = LogoMaxWidth)
                    .heightIn(max = LogoMaxHeight),
        )
    } else {
        Text(
            text = film.name.orEmpty(),
            style =
                TextStyle(
                    fontFamily = TallyType.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    lineHeight = 48.sp,
                ),
            color = TallyColors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun metaLine(film: BaseItemDto): String =
    remember(film.id) {
        buildList {
            film.productionYear?.let { add(it.toString()) }
            film.officialRating?.takeIf { it.isNotBlank() }?.let(::add)
            film.runTimeTicks
                ?.takeIf { it > 0L }
                ?.let { add(formatRuntime(it)) }
        }.joinToString(" · ").tallyUppercase()
    }

@Composable
private fun SimilarRow(
    items: List<BaseItemDto>,
    firstPosterFocus: FocusRequester,
    lastPosterFocus: FocusRequester,
    focusIndex: Int,
    onFocusIndex: (Int) -> Unit,
    upTarget: FocusRequester,
    onOpen: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    // [lastPosterFocus] sits on the poster that had focus last (the first one to begin with).
    val target = focusIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val images = LocalImageUrlService.current
    val listState = rememberLazyListState()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        RowHeader(
            title = stringResource(R.string.tally_postplay_more_like_this),
            modifier = Modifier.padding(horizontal = TallyDimens.marginHorizontal),
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(TallyDimens.cardGap),
            contentPadding =
                PaddingValues(
                    horizontal = TallyDimens.marginHorizontal,
                    vertical = 6.dp,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusProperties {
                        up = upTarget
                        down = FocusRequester.Cancel
                    },
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                val imageUrl =
                    images.getItemImageUrl(
                        itemId = item.id,
                        imageType = ImageType.PRIMARY,
                        fillWidth = 400,
                        fillHeight = 600,
                    )
                SimilarPoster(
                    title = item.name.orEmpty(),
                    imageUrl = imageUrl,
                    onClick = { onOpen(item) },
                    modifier =
                        Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstPosterFocus) else Modifier)
                            .then(if (index == target) Modifier.focusRequester(lastPosterFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocusIndex(index) }
                            .focusProperties {
                                up = upTarget
                                if (index == 0) {
                                    left = FocusRequester.Cancel
                                    start = FocusRequester.Cancel
                                }
                                if (index == items.lastIndex) {
                                    right = FocusRequester.Cancel
                                    end = FocusRequester.Cancel
                                }
                            },
                )
            }
        }
    }
}

@Composable
private fun SimilarPoster(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(1f, 1f, 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = TallyColors.ground,
                contentColor = TallyColors.text,
                focusedContainerColor = TallyColors.groundRaised,
                focusedContentColor = TallyColors.text,
                pressedContainerColor = TallyColors.groundRaised,
                pressedContentColor = TallyColors.text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(TallyDimens.hairline, TallyColors.ruleStrong),
                        shape = RectangleShape,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
                pressedBorder =
                    Border(
                        border = BorderStroke(TallyDimens.focusBorder, TallyColors.accent),
                        shape = RectangleShape,
                    ),
            ),
        glow = ClickableSurfaceDefaults.glow(Glow.None, Glow.None, Glow.None),
        modifier = modifier.width(PosterWidth),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(PosterHeight)
                        .background(TallyColors.screen),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            LabelBar(text = title, live = false)
        }
    }
}
