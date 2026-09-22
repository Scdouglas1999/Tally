package com.github.damontecres.wholphin.jellytv.media.drawer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.tv.material3.DrawerState
import androidx.tv.material3.DrawerValue
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvColors
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.components.TimeDisplay
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.nav.CollapsedDrawerItemWidth
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.DestinationContent
import com.github.damontecres.wholphin.ui.nav.DrawerAnimationStiffness
import com.github.damontecres.wholphin.ui.nav.ExpandedDrawerItemWidth
import com.github.damontecres.wholphin.ui.nav.ModalNavigationDrawer
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.NavDrawerViewModel
import com.github.damontecres.wholphin.ui.nav.isOpen
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.launch

/** True when the Tally drawer replaces upstream's (the JELLYTV theme is selected). */
@Composable
fun tallyDrawerActive(): Boolean = LocalTheme.current == AppThemeColors.JELLYTV

/**
 * Same indexes [NavDrawerViewModel] writes. They are private on the upstream drawer, so they are repeated here.
 */
private const val HOME_INDEX = -1
private const val SEARCH_INDEX = -2
private const val NOW_PLAYING_INDEX = -3

/**
 * The Tally navigation drawer. Same [NavDrawerViewModel], items, actions and [ModalNavigationDrawer] as upstream,
 * drawn in the Tally language. Live TV is hidden while the Sports section is in the drawer; original indexes are kept.
 */
@Composable
fun TallyNavDrawer(
    destination: Destination,
    preferences: UserPreferences,
    user: JellyfinUser,
    server: JellyfinServer,
    drawerState: DrawerState,
    navDrawerListState: LazyListState,
    onClearBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: NavDrawerViewModel =
        hiltViewModel(
            LocalView.current.findViewTreeViewModelStoreOwner()!!,
            key = "${server.id}_${user.id}",
        )
    LaunchedEffect(Unit) { viewModel.updateSelectedIndex() }
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = (drawerState.currentValue == DrawerValue.Closed && destination is Destination.Home)) {
        drawerState.setValue(DrawerValue.Open)
        focusRequester.requestFocus()
    }
    val serviceState by viewModel.serviceState.collectAsState()
    val state by viewModel.state.collectAsState()
    val moreExpanded = state.moreExpanded
    val selectedIndex = state.selectedIndex

    BackHandler(enabled = moreExpanded && drawerState.currentValue == DrawerValue.Open) {
        viewModel.setShowMore(false)
    }

    val closedDrawerWidth = CollapsedDrawerItemWidth
    val openDrawerWidth = ExpandedDrawerItemWidth
    val offset by animateIntOffsetAsState(
        targetValue =
            IntOffset(
                x =
                    with(density) {
                        if (drawerState.isOpen) (openDrawerWidth - closedDrawerWidth).roundToPx() else 0
                    },
                y = 0,
            ),
        animationSpec =
            spring(
                stiffness = DrawerAnimationStiffness,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
    )
    val drawerWidth =
        with(density) {
            closedDrawerWidth + offset.x.toDp()
        }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            val isOpen = drawerValue.isOpen
            val searchFocusRequester = remember { FocusRequester() }
            val entry = remember { ListEntryTracker() }
            // the last row of the list: UP from the pinned Settings row goes there (upstream's Settings is the
            // list's own last item, so UP reaches the row above it)
            val lastRowRequester = remember { FocusRequester() }
            val hideLiveTv = NavDrawerItem.JellyTv in serviceState.items
            val visible = indexedDrawerItems(serviceState.items, hideLiveTv)
            val sections = visible.filter { it.value.isTallyAppSection() }
            val libraries = visible.filterNot { it.value.isTallyAppSection() }
            val moreVisible = indexedDrawerItems(serviceState.moreItems, hideLiveTv)
            val lastRowId =
                when {
                    moreVisible.isNotEmpty() && moreExpanded -> "more-" + moreVisible.last().value.id
                    moreVisible.isNotEmpty() -> "more"
                    libraries.isNotEmpty() -> libraries.last().value.id
                    sections.isNotEmpty() -> sections.last().value.id
                    else -> "home"
                }
            val lastRow = Modifier.focusRequester(lastRowRequester)
            val userImageUrl = remember(user) { viewModel.getUserImage(user) }
            val userName = user.name ?: user.id.toString()

            Box(
                modifier =
                    Modifier
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(JtvColors.ground)
                        .onFocusChanged { if (!it.hasFocus) entry.lastRegion = ListEntryTracker.NONE },
            ) {
                // rows stop short of the 1dp right edge, so the edge never covers a focus border
                Column(modifier = Modifier.fillMaxSize().padding(end = JtvDimens.hairline)) {
                    TallyDrawerHeader(
                        drawerOpen = isOpen,
                        userName = userName,
                        userId = user.id.toString(),
                        serverName = server.name ?: server.url,
                        imageUrl = userImageUrl,
                        onProfileClick = {
                            viewModel.navigateToSetup(SetupDestination.UserList(server))
                        },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) entry.lastRegion = ListEntryTracker.HEADER },
                    )
                    AnimatedVisibility(
                        visible = serviceState.nowPlayingEnabled,
                        enter = expandVertically(expandFrom = Alignment.Top),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top),
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) entry.lastRegion = ListEntryTracker.HEADER },
                    ) {
                        TallyEntry(
                            label = serviceState.nowPlayingTitle.orEmpty(),
                            glyph = TallyGlyph.Font(R.string.fa_play),
                            selected = selectedIndex == NOW_PLAYING_INDEX,
                            drawerOpen = isOpen,
                            kicker = stringResource(R.string.now_playing),
                            onClick = {
                                viewModel.setIndex(NOW_PLAYING_INDEX)
                                viewModel.navigationManager.navigateTo(Destination.NowPlaying)
                            },
                            focusRequester = focusRequester,
                        )
                    }
                    // the list and Settings share one focus group, so entry goes to the selected item (upstream's rule)
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .onFocusChanged {
                                    val entered = it.hasFocus && !entry.listHasFocus
                                    entry.listHasFocus = it.hasFocus
                                    if (entered) {
                                        if (!entry.viaOnEnter) {
                                            // Compose skipped onEnter (it does when the page being left has its own
                                            // focus exit handling): apply upstream's entry rule here instead
                                            val fromHeader = entry.lastRegion == ListEntryTracker.HEADER
                                            scope.launch {
                                                if (fromHeader) {
                                                    searchFocusRequester.tryRequestFocus()
                                                } else {
                                                    focusRequester.tryRequestFocus()
                                                }
                                            }
                                        }
                                        entry.viaOnEnter = false
                                        entry.lastRegion = ListEntryTracker.LIST
                                    }
                                }.focusGroup()
                                .focusProperties {
                                    onEnter = {
                                        entry.viaOnEnter = true
                                        if (entry.stepFromSettings) {
                                            // UP from Settings: land on the row above it, no redirect
                                        } else if (requestedFocusDirection == FocusDirection.Down) {
                                            searchFocusRequester.tryRequestFocus()
                                        } else {
                                            focusRequester.tryRequestFocus()
                                        }
                                    }
                                },
                    ) {
                        LazyColumn(
                            state = navDrawerListState,
                            // room for the focus border of the first and last rows (never clipped by the list)
                            contentPadding = PaddingValues(vertical = JtvDimens.focusBorder + 1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            item(key = "search") {
                                TallyEntry(
                                    label = stringResource(R.string.search),
                                    glyph = TallyGlyph.Font(R.string.jtv_drawer_fa_search),
                                    selected = selectedIndex == SEARCH_INDEX,
                                    drawerOpen = isOpen,
                                    onClick = {
                                        viewModel.setIndex(SEARCH_INDEX)
                                        viewModel.navigationManager.navigateToFromDrawer(Destination.Search())
                                    },
                                    focusRequester = focusRequester,
                                    modifier = Modifier.focusRequester(searchFocusRequester),
                                )
                            }
                            item(key = "home") {
                                TallyEntry(
                                    label = stringResource(R.string.home),
                                    glyph = TallyGlyph.Font(R.string.fa_house),
                                    selected = selectedIndex == HOME_INDEX,
                                    drawerOpen = isOpen,
                                    modifier = if (lastRowId == "home") lastRow else Modifier,
                                    onClick = {
                                        viewModel.setIndex(HOME_INDEX)
                                        if (destination is Destination.Home) {
                                            viewModel.navigationManager.reloadHome()
                                            onClearBackdrop.invoke()
                                        } else {
                                            viewModel.navigationManager.goToHome()
                                        }
                                    },
                                    focusRequester = focusRequester,
                                )
                            }
                            items(
                                items = sections,
                                key = { it.value.id },
                            ) { indexed ->
                                DrawerItemEntry(
                                    index = indexed.index,
                                    item = indexed.value,
                                    selectedIndex = selectedIndex,
                                    drawerOpen = isOpen,
                                    context = context,
                                    focusRequester = focusRequester,
                                    onClick = viewModel::onClickDrawerItem,
                                    modifier = if (lastRowId == indexed.value.id) lastRow else Modifier,
                                )
                            }
                            if (libraries.isNotEmpty() || moreVisible.isNotEmpty()) {
                                item(key = "libraries") {
                                    TallyDrawerDivider(
                                        title = stringResource(R.string.jtv_drawer_libraries),
                                        drawerOpen = isOpen,
                                    )
                                }
                            }
                            items(
                                items = libraries,
                                key = { it.value.id },
                            ) { indexed ->
                                DrawerItemEntry(
                                    index = indexed.index,
                                    item = indexed.value,
                                    selectedIndex = selectedIndex,
                                    drawerOpen = isOpen,
                                    context = context,
                                    focusRequester = focusRequester,
                                    onClick = viewModel::onClickDrawerItem,
                                    modifier = if (lastRowId == indexed.value.id) lastRow else Modifier,
                                )
                            }
                            if (moreVisible.isNotEmpty()) {
                                val moreIndex = serviceState.items.size
                                item(key = "more") {
                                    TallyEntry(
                                        label = NavDrawerItem.More.name(context),
                                        glyph = tallyGlyph(NavDrawerItem.More),
                                        // as upstream: More is never drawn as the current page, but it takes the
                                        // focus-entry requester when the selected index equals its own
                                        selected = false,
                                        focusTarget = selectedIndex == moreIndex,
                                        drawerOpen = isOpen,
                                        trailingGlyph =
                                            if (moreExpanded) {
                                                R.string.fa_caret_down
                                            } else {
                                                R.string.fa_caret_right
                                            },
                                        onClick = {
                                            viewModel.onClickDrawerItem(moreIndex, NavDrawerItem.More)
                                        },
                                        modifier = if (lastRowId == "more") lastRow else Modifier,
                                        focusRequester = focusRequester,
                                    )
                                }
                            }
                            if (moreExpanded) {
                                items(
                                    items = moreVisible,
                                    key = { "more-${it.value.id}" },
                                ) { indexed ->
                                    DrawerItemEntry(
                                        index = indexed.index + serviceState.items.size,
                                        item = indexed.value,
                                        selectedIndex = selectedIndex,
                                        drawerOpen = isOpen,
                                        context = context,
                                        focusRequester = focusRequester,
                                        onClick = viewModel::onClickDrawerItem,
                                        modifier = if (lastRowId == "more-" + indexed.value.id) lastRow else Modifier,
                                    )
                                }
                            }
                        }
                        // Settings: upstream's footer, pinned to the bottom so it is always visible
                        TallyDrawerDivider(title = null, drawerOpen = isOpen)
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            TallyEntry(
                                label = stringResource(R.string.settings),
                                glyph = TallyGlyph.Font(R.string.jtv_drawer_fa_settings),
                                selected = false,
                                drawerOpen = isOpen,
                                onClick = {
                                    viewModel.navigationManager.navigateTo(
                                        Destination.Settings(PreferenceScreenOption.BASIC),
                                    )
                                },
                                focusRequester = focusRequester,
                                modifier =
                                    Modifier.onPreviewKeyEvent {
                                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
                                            entry.stepFromSettings = true
                                            val moved = lastRowRequester.tryRequestFocus()
                                            entry.stepFromSettings = false
                                            moved
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(JtvDimens.hairline)
                            .background(if (isOpen) JtvColors.ruleStrong else JtvColors.rule),
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The offset and inset live on a wrapper, not on the page's own modifier: Tally pages apply their
            // modifier inside JtvScale, which would shrink a dp inset by the scale (the page then overlaps the rail).
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .offset { offset }
                        // no end padding (upstream has 16dp): Tally pages reach the right edge and keep their own margins
                        .padding(start = closedDrawerWidth + 8.dp)
                        // while the panel is out, nothing the page draws past its left edge may cover the panel
                        .ifElse(offset.x > 0, Modifier.clipToBounds()),
            ) {
                DestinationContent(
                    destination = destination,
                    preferences = preferences,
                    onClearBackdrop = onClearBackdrop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (preferences.appPreferences.interfacePreferences.showClock) {
                TimeDisplay()
            }
        }
    }
}

@Composable
private fun DrawerItemEntry(
    index: Int,
    item: NavDrawerItem,
    selectedIndex: Int,
    drawerOpen: Boolean,
    context: Context,
    focusRequester: FocusRequester,
    onClick: (Int, NavDrawerItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    TallyEntry(
        label = item.name(context),
        glyph = tallyGlyph(item),
        selected = selectedIndex == index,
        drawerOpen = drawerOpen,
        onClick = { onClick(index, item) },
        focusRequester = focusRequester,
        modifier = modifier,
    )
}

@Composable
private fun TallyEntry(
    label: String,
    glyph: TallyGlyph,
    selected: Boolean,
    drawerOpen: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    @StringRes trailingGlyph: Int? = null,
    focusTarget: Boolean = selected,
) {
    TallyDrawerRow(
        label = label,
        glyph = glyph,
        selected = selected,
        drawerOpen = drawerOpen,
        onClick = onClick,
        kicker = kicker,
        trailingGlyph = if (drawerOpen) trailingGlyph else null,
        modifier =
            modifier.ifElse(
                focusTarget,
                Modifier.focusRequester(focusRequester),
            ),
    )
}

/**
 * Plain (non-state) bookkeeping for how focus arrives in the drawer's list group, so the list can apply upstream's
 * entry rule (selected item, or Search when coming down from the header) even when Compose does not call `onEnter`.
 */
private class ListEntryTracker {
    var viaOnEnter = false
    var listHasFocus = false
    var lastRegion = NONE
    var stepFromSettings = false

    companion object {
        const val NONE = 0
        const val HEADER = 1
        const val LIST = 2
    }
}
