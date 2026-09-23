package io.github.scdouglas1999.tally.media.home.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.main.HomeViewModel
import com.github.damontecres.wholphin.ui.main.isContinueWatchingNextUp
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerViewModel
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.media.home.HomeItemCard
import io.github.scdouglas1999.tally.media.home.HomeMetaPart
import io.github.scdouglas1999.tally.media.home.homeMeta
import io.github.scdouglas1999.tally.media.home.phoneHomeCardHeight
import io.github.scdouglas1999.tally.media.home.phoneHomeImageHeight
import io.github.scdouglas1999.tally.media.home.rememberTallyRowSettled
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowMessage
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.together.ui.TogetherRow
import io.github.scdouglas1999.tally.ui.components.LampState
import io.github.scdouglas1999.tally.ui.components.TallyLamp
import io.github.scdouglas1999.tally.ui.components.gameStatusLabel
import io.github.scdouglas1999.tally.ui.components.hasNoResult
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.home.TallyHomeRow
import io.github.scdouglas1999.tally.ui.home.TallyHomeRowViewModel
import io.github.scdouglas1999.tally.ui.home.withoutPregameChannels
import io.github.scdouglas1999.tally.ui.household.HouseholdRow
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneStatusBarPadding
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType
import org.jellyfin.sdk.model.api.ImageType

/**
 * Home on a phone. The same [HomeViewModel] as the TV page (init on start, loading and error states, the same row
 * actions: open or play per the click-to-play setting, the item menu with its remove-from-rows entries, view all),
 * the same Tally row view model (its "watch live" filter and the settle wait before the rows are shown), drawn for a
 * hand: a top bar (lamp and TALLY, search, the user), a hero (the first card of the first row the page shows), then
 * the rows in the TV's order with phone cards, ending above the bottom bar.
 */
@Composable
fun PhoneHomePage(
    preferences: UserPreferences,
    modifier: Modifier,
    viewModel: HomeViewModel,
) {
    LifecycleStartEffect(Unit) {
        viewModel.init()
        onStopOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val tallyRow: TallyHomeRowViewModel = hiltViewModel()
    val pregame by tallyRow.pregameChannels.collectAsState()
    val tallyState by tallyRow.uiState.collectAsStateWithLifecycle()
    val settled = rememberTallyRowSettled(tallyRow)
    val dialogs = remember { ItemDialogsState() }
    val listState = rememberLazyListState()
    var heroShown by remember { mutableStateOf(false) }
    val barSolid by remember {
        derivedStateOf { !heroShown || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    Box(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        when (val loading = state.loadingState) {
            is LoadingState.Error -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_media_error_title),
                    subtitle = loading.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                    modifier = Modifier.phoneStatusBarPadding().padding(top = PhoneDimens.topBarHeight),
                )
            }

            LoadingState.Loading, LoadingState.Pending -> {
                PhoneLoading(Modifier.fillMaxSize())
            }

            LoadingState.Success -> {
                if (!settled) {
                    PhoneLoading(Modifier.fillMaxSize())
                } else {
                    PhoneHomeLoaded(
                        preferences = preferences,
                        homeRows = remember(state.homeRows, pregame) { state.homeRows.withoutPregameChannels(pregame) },
                        heroGame = tallyState.games.firstOrNull(),
                        hideScores = tallyState.hideScores,
                        tallyRow = tallyRow,
                        dialogs = dialogs,
                        viewModel = viewModel,
                        listState = listState,
                        onHero = { heroShown = it },
                    )
                }
            }
        }
        PhoneHomeTopBar(
            solid = barSolid,
            refreshing = state.refreshState == LoadingState.Loading || state.refreshState == LoadingState.Pending,
            viewModel = viewModel,
        )
    }
    ItemDialogsHost(
        state = dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = false,
        onConfirmDelete = {},
    )
}

/** The wordmark: Sans Bold 17sp, tracked like the launch card's. */
private val WordmarkStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        letterSpacing = 0.30.em,
    )

private val UserInitialStyle =
    TextStyle(
        fontFamily = TallyType.Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    )

/**
 * Home's top bar: the lit lamp and TALLY at the left; search and the user's initial square (user switching, as the
 * More sheet's user row) at the right. Over the hero it is transparent on an image scrim; once the page scrolls
 * (or without a hero) it is `ground` with its 1dp `rule` under it.
 */
@Composable
private fun PhoneHomeTopBar(
    solid: Boolean,
    refreshing: Boolean,
    viewModel: HomeViewModel,
) {
    val current by viewModel.serverRepository.current.collectAsState()
    val server = current?.server
    val user = current?.user
    val owner = LocalView.current.findViewTreeViewModelStoreOwner()
    val drawer: NavDrawerViewModel? =
        if (server != null && user != null && owner != null) {
            hiltViewModel(owner, key = "${server.id}_${user.id}")
        } else {
            null
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (solid) {
                        Modifier.background(TallyColors.ground)
                    } else {
                        Modifier.drawBehind {
                            drawRect(
                                Brush.verticalGradient(
                                    0f to TallyColors.ground.copy(alpha = 0.85f),
                                    0.6f to TallyColors.ground.copy(alpha = 0.5f),
                                    1f to Color.Transparent,
                                ),
                            )
                        }
                    },
                ).phoneStatusBarPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PhoneDimens.topBarHeight)
                    .drawWithContent {
                        drawContent()
                        if (solid) {
                            val stroke = PhoneDimens.hairline.toPx()
                            drawLine(
                                color = TallyColors.rule,
                                start = Offset(0f, size.height - stroke / 2f),
                                end = Offset(size.width, size.height - stroke / 2f),
                                strokeWidth = stroke,
                            )
                        }
                    }.padding(start = PhoneDimens.margin, end = 4.dp),
        ) {
            TallyLamp(state = LampState.Lit, size = 10.dp, glow = false)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.tally_lamp_wordmark),
                style = WordmarkStyle,
                color = TallyColors.text,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (refreshing) {
                Text(
                    text = stringResource(R.string.tally_media_loading).tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            val searchLabel = stringResource(R.string.search)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(PhoneDimens.touchTarget)
                        .semantics { contentDescription = searchLabel }
                        .phoneClickable {
                            viewModel.navigationManager.navigateTo(Destination.Search())
                            drawer?.updateSelectedIndex()
                        },
            ) {
                Text(
                    text = stringResource(R.string.tally_pages_fa_search),
                    fontFamily = com.github.damontecres.wholphin.ui.FontAwesome,
                    fontSize = 19.sp,
                    color = TallyColors.text,
                )
            }
            if (user != null && server != null) {
                val name = user.name ?: user.id.toString()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(PhoneDimens.touchTarget)
                            .semantics { contentDescription = name }
                            .phoneClickable { drawer?.navigateToSetup(SetupDestination.UserList(server)) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp).background(TallyColors.groundRaised),
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            style = UserInitialStyle,
                            color = TallyColors.text,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneHomeLoaded(
    preferences: UserPreferences,
    homeRows: List<HomeRowLoadingState>,
    heroGame: TallyGame?,
    hideScores: Boolean,
    tallyRow: TallyHomeRowViewModel,
    dialogs: ItemDialogsState,
    viewModel: HomeViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onHero: (Boolean) -> Unit,
) {
    var position by remember { mutableStateOf(RowColumn(-1, -1)) }
    val currentRows by rememberUpdatedState(homeRows)
    val homePrefs = preferences.appPreferences.homePagePreferences
    val onClickItem = { clicked: RowColumn, item: BaseItem ->
        position = clicked
        if (homePrefs.clickToPlay && currentRows.getOrNull(clicked.row)?.isContinueWatchingNextUp == true) {
            viewModel.navigationManager.navigateTo(Destination.Playback(item))
        } else {
            viewModel.navigationManager.navigateTo(item.destination())
        }
    }
    val onLongClickItem = { clicked: RowColumn, item: BaseItem ->
        position = clicked
        val row = currentRows.getOrNull(clicked.row) as? HomeRowLoadingState.Success
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = item,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = viewModel.canDelete(item, preferences.appPreferences),
                canRemoveContinueWatching =
                    row?.rowType is HomeRowConfig.ContinueWatching || row?.rowType is HomeRowConfig.ContinueWatchingCombined,
                canRemoveNextUp = row?.rowType is HomeRowConfig.NextUp || row?.rowType is HomeRowConfig.ContinueWatchingCombined,
                actions =
                    ContextMenuActions(
                        navigateTo = viewModel.navigationManager::navigateTo,
                        onClickWatch = viewModel::setWatched,
                        onClickFavorite = viewModel::setFavorite,
                        onClickAddPlaylist = { itemId -> dialogs.playlistItemId = itemId },
                        onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                        onDeleteItem = { viewModel.deleteItem(position, it) },
                        onChooseVersion = { _, _ -> },
                        onChooseTracks = { },
                        onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
                        onClearChosenStreams = {},
                        onClickRemoveFromNextUp = viewModel::removeFromNextUp,
                    ),
            )
    }
    val onPlay = { item: BaseItem -> viewModel.navigationManager.navigateTo(Destination.Playback(item)) }

    // The hero: the first card of the first row the page shows (a game, else the first library row's first card).
    val heroRow =
        if (heroGame == null) {
            homeRows.withIndex().firstOrNull { (_, row) -> row is HomeRowLoadingState.Success && row.items.isNotEmpty() }
        } else {
            null
        }
    val heroItem = (heroRow?.value as? HomeRowLoadingState.Success)?.items?.firstOrNull()
    val hasHero = heroGame != null || heroItem != null
    androidx.compose.runtime.LaunchedEffect(hasHero) { onHero(hasHero) }
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = bottom + PhoneDimens.rowGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero") {
            when {
                heroGame != null -> {
                    // TALLY phone-sports: GAME opens the game sheet (the Sports section's), WATCH stays a direct watch.
                    var gameSheet by remember { mutableStateOf(false) }
                    GameHero(
                        game = heroGame,
                        hideScores = hideScores,
                        artUrl = remember(heroGame.id, heroGame.backdropPath) { tallyRow.artUrl(heroGame) },
                        onWatch = { tallyRow.watch(heroGame) },
                        onGame = { gameSheet = true },
                        modifier = Modifier.padding(bottom = PhoneDimens.rowGap),
                    )
                    if (gameSheet) {
                        val favoriteTeams =
                            tallyRow.uiState
                                .collectAsStateWithLifecycle()
                                .value.favoriteTeams
                        io.github.scdouglas1999.tally.ui.components.GameActionsDialog(
                            game = heroGame,
                            actions =
                                io.github.scdouglas1999.tally.ui.components.gameActions(
                                    game = heroGame,
                                    favoriteTeams = favoriteTeams,
                                    hideScores = hideScores,
                                    onWatch = tallyRow::watch,
                                    onAddToMultiview = { game -> game.watch?.channelId?.let(tallyRow::addToMultiview) },
                                    onWatchInCorner = null,
                                    onToggleFollow = tallyRow::toggleFollow,
                                    onToggleHideScores = tallyRow::toggleHideScores,
                                ),
                            onDismiss = { gameSheet = false },
                        )
                    }
                }

                heroItem != null && heroRow != null -> {
                    ItemHero(
                        item = heroItem,
                        kicker = heroRow.value.title.getString(),
                        showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                        onPlay = { onPlay(heroItem) },
                        onDetails = {
                            position = RowColumn(heroRow.index, 0)
                            viewModel.navigationManager.navigateTo(heroItem.destination())
                        },
                        modifier = Modifier.padding(bottom = PhoneDimens.rowGap),
                    )
                }

                else -> {
                    Spacer(
                        Modifier
                            .phoneStatusBarPadding()
                            .height(PhoneDimens.topBarHeight + 16.dp),
                    )
                }
            }
        }
        item(key = "jellytv") {
            Box(Modifier.gapBelowWhenShown(PhoneDimens.rowGap)) { TallyHomeRow() }
        }
        item(key = "household") {
            Box(Modifier.gapBelowWhenShown(PhoneDimens.rowGap)) { HouseholdRow() }
        }
        item(key = "together") {
            Box(Modifier.gapBelowWhenShown(PhoneDimens.rowGap)) { TogetherRow() }
        }
        itemsIndexed(homeRows) { rowIndex, row ->
            val rowModifier = Modifier.padding(bottom = PhoneDimens.rowGap)
            when (row) {
                is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                    PhoneRowMessage(
                        title = row.title.getString(),
                        message = stringResource(R.string.tally_media_loading),
                        height = phoneHomeCardHeight(HomeRowViewOptions(), namesOnly = false),
                        modifier = rowModifier,
                    )
                }

                is HomeRowLoadingState.Error -> {
                    PhoneRowMessage(
                        title = row.title.getString(),
                        message = row.localizedMessage,
                        failure = true,
                        modifier = rowModifier,
                    )
                }

                is HomeRowLoadingState.Success -> {
                    if (row.items.isNotEmpty()) {
                        val watching = row.isContinueWatchingNextUp
                        PhoneCardRow(
                            title = row.title.getString(),
                            items = row.items,
                            count = row.items.size,
                            onAll =
                                if (row.showViewMore && row.rowType != null) {
                                    {
                                        viewModel.navigationManager.navigateTo(
                                            Destination.MoreHomeRow(row.title, row.rowType!!, row.items.size),
                                        )
                                    }
                                } else {
                                    null
                                },
                            key = { index, item -> "$index-${item?.id}" },
                            modifier = rowModifier,
                        ) { item, index ->
                            HomeItemCard(
                                item = item,
                                viewOptions = row.viewOptions,
                                watchingRow = watching,
                                onClick = { if (item != null) onClickItem(RowColumn(rowIndex, index), item) },
                                onLongClick = { if (item != null) onLongClickItem(RowColumn(rowIndex, index), item) },
                                onPlay = if (item != null && item.type.playable) ({ onPlay(item) }) else null,
                                imageHeight = phoneHomeImageHeight(item, row.viewOptions),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Adds [gap] under the content only when the content has height (the Tally rows are empty without data). */
private fun Modifier.gapBelowWhenShown(gap: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val extra = if (placeable.height > 0) gap.roundToPx() else 0
        layout(placeable.width, placeable.height + extra) { placeable.place(0, 0) }
    }

/** Picture height of the hero (full width 16:9); the text starts this far above its bottom, on the scrim. */
private val HeroTextOverlap = 56.dp
private val LogoMaxWidth = 220.dp
private val LogoMaxHeight = 64.dp

/** The hero's picture: full width, 16:9, under the status bar, with a scrim into `ground` at its bottom. */
@Composable
internal fun PhoneHeroPicture(
    url: String?,
    description: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(TallyColors.screen)
                .clipToBounds(),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                onError = { logCoilError(url, it.result) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to TallyColors.ground,
                        ),
                    )
                },
        )
    }
}

/** The hero's text over the bottom of its picture: [content] starts [HeroTextOverlap] above the picture's end. */
@Composable
internal fun PhoneHeroFrame(
    url: String?,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PhoneHeroPicture(url = url, description = description)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val overlap = HeroTextOverlap.roundToPx()
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) {
                            placeable.place(0, -overlap)
                        }
                    }.padding(horizontal = PhoneDimens.margin),
        ) {
            content()
        }
    }
}

@Composable
internal fun PhoneHeroKicker(text: String) {
    Text(
        text = text.tallyUppercase(),
        style = PhoneType.label,
        color = TallyColors.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PhoneHeroTitle(title: String) {
    Text(
        text = title,
        style = PhoneType.display,
        color = TallyColors.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Hero for a library item: kicker (the row's title), logo or title, the TV header's meta line, PLAY / RESUME and DETAILS. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemHero(
    item: BaseItem,
    kicker: String,
    showLogo: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageService = LocalImageUrlService.current
    val wide = rememberWideImageUrl(item)
    val backdrop = remember(item.id) { imageService.getItemImageUrl(item, ImageType.BACKDROP) } ?: wide
    val title = item.title ?: item.name ?: ""
    PhoneHeroFrame(url = backdrop, description = title, modifier = modifier) {
        PhoneHeroKicker(kicker)
        val logoUrl = rememberLogoUrl(item)
        var logoFailed by remember(logoUrl) { mutableStateOf(false) }
        if (showLogo && logoUrl != null && !logoFailed) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = {
                    logCoilError(logoUrl, it.result)
                    logoFailed = true
                },
                modifier = Modifier.widthIn(max = LogoMaxWidth).heightIn(max = LogoMaxHeight),
            )
        } else {
            PhoneHeroTitle(title)
        }
        PhoneMetaLine(homeMeta(item))
        Spacer(Modifier.height(4.dp))
        val position = item.data.userData?.playbackPositionTicks ?: 0L
        val percent = resumePercent(position, item.data.runTimeTicks ?: 0L)
        val resumable = !item.played && position > 0L && percent in 1..99
        Row(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (item.type.playable) {
                PhoneButton(
                    label =
                        if (resumable) {
                            stringResource(R.string.tally_phone_browse_resume_percent, percent)
                        } else {
                            stringResource(R.string.tally_media_play)
                        },
                    glyph = stringResource(R.string.fa_play),
                    primary = true,
                    progress = if (resumable) percent / 100f else null,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                )
                PhoneButton(
                    label = stringResource(R.string.tally_phone_browse_details),
                    onClick = onDetails,
                )
            } else {
                PhoneButton(
                    label = stringResource(R.string.tally_phone_browse_details),
                    primary = true,
                    onClick = onDetails,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Hero for a game: kicker `MLB · BOT 7TH`, the matchup, the TV game header's mono line (the score, or the broadcasts
 * before the start), then WATCH (a direct watch) and GAME (the game sheet, as a tap on a game card opens).
 */
@Composable
private fun GameHero(
    game: TallyGame,
    hideScores: Boolean,
    artUrl: String?,
    onWatch: () -> Unit,
    onGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.tally_actions_at, game.away.heroName(), game.home.heroName())
    PhoneHeroFrame(url = artUrl, description = title, modifier = modifier) {
        PhoneHeroKicker(listOf(game.league, gameStatusLabel(game)).filter { it.isNotBlank() }.joinToString(" · "))
        PhoneHeroTitle(title)
        val showScore = !hideScores && !game.isUpcoming && !game.hasNoResult
        val meta =
            if (showScore) {
                "${game.away.abbr} ${game.away.score ?: 0} · ${game.home.abbr} ${game.home.score ?: 0}"
            } else {
                game.broadcasts.joinToString(" · ")
            }
        if (meta.isNotBlank()) {
            Text(
                text = meta.tallyUppercase(),
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PhoneButton(
                label =
                    if (game.watch != null) {
                        stringResource(R.string.tally_phone_browse_watch)
                    } else {
                        stringResource(R.string.tally_phone_browse_not_on_channels)
                    },
                glyph = if (game.watch != null) stringResource(R.string.fa_play) else null,
                primary = game.watch != null,
                enabled = game.watch != null,
                onClick = onWatch,
                modifier = Modifier.weight(1f),
            )
            PhoneButton(
                label = stringResource(R.string.tally_phone_sports_game),
                onClick = onGame,
            )
        }
    }
}

private fun io.github.scdouglas1999.tally.api.TallyTeam.heroName(): String = shortName.ifBlank { abbr.ifBlank { name } }

/** The TV header's meta line at phone size: mono `meta` parts in `textSecondary`, the rating boxed; wraps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneMetaLine(
    parts: List<HomeMetaPart>,
    modifier: Modifier = Modifier,
) {
    if (parts.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(text = "·", style = PhoneType.meta, color = TallyColors.textSecondary, maxLines = 1)
            }
            Text(
                text = part.text,
                style = PhoneType.meta,
                color = TallyColors.textSecondary,
                maxLines = 1,
                modifier =
                    if (part.boxed) {
                        Modifier
                            .border(PhoneDimens.hairline, TallyColors.ruleStrong)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    } else {
                        Modifier
                    },
            )
        }
    }
}
