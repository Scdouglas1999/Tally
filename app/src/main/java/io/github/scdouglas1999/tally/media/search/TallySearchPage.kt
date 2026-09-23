package io.github.scdouglas1999.tally.media.search

import android.Manifest
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceInputState
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.livetv.ProgramDialog
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.onMain
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.ui.search.SearchTypeOptionsDialog
import com.github.damontecres.wholphin.ui.search.SearchViewModel
import com.github.damontecres.wholphin.ui.search.SearchViewOptionsDialog
import com.github.damontecres.wholphin.ui.titleStringRes
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.github.scdouglas1999.tally.media.kit.CardDetailText
import io.github.scdouglas1999.tally.media.kit.CardFrame
import io.github.scdouglas1999.tally.media.kit.CardTitleText
import io.github.scdouglas1999.tally.media.kit.FocusEdge
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.LandscapeWidth
import io.github.scdouglas1999.tally.media.kit.MediaRow
import io.github.scdouglas1999.tally.media.kit.PersonCard
import io.github.scdouglas1999.tally.media.kit.PosterCard
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.kit.TallyIconButton
import io.github.scdouglas1999.tally.media.kit.posterDetail
import io.github.scdouglas1999.tally.media.kit.rememberFocusEdgeSpec
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.pages.joinMeta
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

// Row indexes of upstream's search page, kept so the view model's saved position means the same.
private const val SEARCH_ROW = 0
private const val TAB_ROW = SEARCH_ROW + 1
private const val SEERR_ROW = TAB_ROW + 1
private const val COMBINED_ROW = SEERR_ROW
private const val RESULTS_START = SEERR_ROW + 1

/**
 * The Tally search page: upstream's [SearchViewModel], its debounce, keyboard, voice, view options,
 * type filter and program dialog, drawn as a Tally search field over one [MediaRow] per type.
 */
@Composable
fun TallySearchPage(
    initialQuery: String,
    userPreferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val state by viewModel.state.collectAsState()
    val programDialogState by viewModel.programDialogState.collectAsState()
    val prefs =
        viewModel.userPreferencesService.flow
            .collectAsState(userPreferences)
            .value.appPreferences.interfacePreferences.searchPreferences
    val combinedMode = prefs.combinedSearchResults
    val voiceSearchButtonVisible = prefs.showVoiceSearchButton

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val focusRequesters =
        remember(state.includedSearchableTypes.size) {
            List(RESULTS_START + state.includedSearchableTypes.size) { FocusRequester() }
        }
    val seerrActive by viewModel.seerrActive.collectAsState()
    var selectedTab by rememberSaveable(seerrActive, state.discoverEnabled) { mutableIntStateOf(0) }
    var showViewOptions by rememberSaveable { mutableStateOf(false) }
    var showFilterTypeDialog by rememberSaveable { mutableStateOf(false) }
    var searchClicked by rememberSaveable(query) { mutableStateOf(false) }
    var immediateSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var showProgramDialog by remember { mutableStateOf(false) }
    val position by viewModel.position.collectAsState()
    val dialogs = remember { ItemDialogsState() }

    fun setPosition(pos: RowColumn) {
        viewModel.position.value = pos
    }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { viewModel.voiceInputManager.stopListening() }
    }

    fun triggerImmediateSearch(searchQuery: String) {
        immediateSearchQuery = searchQuery
        searchClicked = true
        viewModel.search(searchQuery, combinedMode)
    }

    LaunchedEffect(query, combinedMode) {
        if (immediateSearchQuery == query) {
            immediateSearchQuery = null
        } else {
            delay(750.milliseconds)
            viewModel.search(query, combinedMode)
        }
    }

    val onClickItem = { _: Int, item: BaseItem ->
        if (item.type == BaseItemKind.TV_PROGRAM || item.type == BaseItemKind.PROGRAM ||
            item.type == BaseItemKind.LIVE_TV_PROGRAM
        ) {
            viewModel.fetchProgramForDialog(item.id)
            showProgramDialog = true
        } else {
            viewModel.navigationManager.navigateTo(item.destination())
        }
    }
    val onLongClickItem = { rowIndex: Int, index: Int, item: BaseItem ->
        setPosition(RowColumn(rowIndex, index))
        dialogs.contextMenu =
            providerContextMenu(
                provider = viewModel,
                position = index,
                item = item,
                preferences = userPreferences,
                dialogs = dialogs,
                onAddToQueue = playlistViewModel::addToQueue,
            )
    }
    val onPlayItem = { _: Int, item: BaseItem ->
        viewModel.navigationManager.navigateTo(Destination.Playback(item))
    }
    val onClickDiscover = { _: Int, item: DiscoverItem ->
        val dest =
            if (item.jellyfinItemId != null && item.type.baseItemKind != null) {
                Destination.MediaItem(itemId = item.jellyfinItemId, type = item.type.baseItemKind)
            } else {
                Destination.DiscoveredItem(item)
            }
        viewModel.navigationManager.navigateTo(dest)
    }

    val showTabs = seerrActive && state.discoverEnabled && query.isNotBlank() && combinedMode
    val isLibraryTab = selectedTab == 0
    LaunchedEffect(seerrActive, query) {
        if (!seerrActive || query.isBlank()) selectedTab = 0
    }

    // As upstream: after an explicit search (keyboard search key or voice), focus the first row with results.
    LaunchedEffect(searchClicked, state, combinedMode, selectedTab, seerrActive) {
        if (!searchClicked || position.row > TAB_ROW) return@LaunchedEffect
        withContext(WholphinDispatchers.IO) {
            val results =
                if (isLibraryTab) {
                    if (combinedMode) {
                        listOf(state.combinedResults)
                    } else {
                        state.includedSearchableTypes.map { state.results[it] }
                    }
                } else {
                    listOf(state.seerrResults)
                }
            val firstSuccess =
                results.indexOfFirst {
                    (it is SearchResult.Success && it.items.isNotEmpty()) ||
                        (it is SearchResult.SuccessSeerr && it.items.isNotEmpty())
                }
            if (firstSuccess >= 0 && results.subList(0, firstSuccess).none { it is SearchResult.Searching }) {
                val targetRow =
                    when {
                        !isLibraryTab -> SEERR_ROW
                        combinedMode -> COMBINED_ROW
                        else -> RESULTS_START + firstSuccess
                    }
                onMain { focusRequesters.getOrNull(targetRow)?.tryRequestFocus("tally-search-results") }
            }
        }
    }

    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Column(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .background(TallyColors.ground),
                ) {
                    var isSearchActive by remember { mutableStateOf(false) }
                    var isTextFieldFocused by remember { mutableStateOf(false) }
                    val textFieldFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        // As upstream (the search row restores to the field): the field first, else the saved row.
                        if (position.row == SEARCH_ROW) {
                            textFieldFocusRequester.tryRequestFocus("tally-search")
                        } else {
                            focusRequesters.getOrNull(position.row)?.tryRequestFocus("tally-search")
                        }
                    }
                    BackHandler(isTextFieldFocused) {
                        if (isSearchActive) {
                            isSearchActive = false
                            keyboardController?.hide()
                        } else {
                            focusManager.moveFocus(FocusDirection.Next)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = TallyDimens.marginHorizontal, end = ClockRoom)
                                .padding(top = TallyDimens.marginVertical)
                                .focusGroup()
                                .focusRestorer(textFieldFocusRequester)
                                .focusRequester(focusRequesters[SEARCH_ROW]),
                    ) {
                        if (voiceSearchButtonVisible) {
                            TallyVoiceButton(
                                voiceInputManager = viewModel.voiceInputManager,
                                onSpeechResult = { spokenText ->
                                    query = spokenText
                                    triggerImmediateSearch(spokenText)
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        TallySearchField(
                            value = query,
                            onValueChange = {
                                isSearchActive = true
                                query = it
                            },
                            onSearch = { triggerImmediateSearch(query) },
                            readOnly = !isSearchActive,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .focusRequester(textFieldFocusRequester)
                                    .onFocusChanged {
                                        isTextFieldFocused = it.isFocused
                                        if (!it.isFocused) isSearchActive = false
                                    }.onPreviewKeyEvent { event ->
                                        val activation = event.key == Key.DirectionCenter || event.key == Key.Enter
                                        // Typing = the on-screen keyboard is up. BACK closes the keyboard without
                                        // reaching the page, so the field stays editable after it: without this, LEFT
                                        // and RIGHT went on moving the cursor and never reached the voice and view
                                        // buttons.
                                        val typing = isSearchActive && imeShown(view)
                                        // Up and down always leave the field (a single line has nowhere to go, and while the
                                        // keyboard is up it gets the arrows, not the field); left and right only when not typing.
                                        val direction =
                                            focusDirectionFor(event.key)?.takeIf {
                                                !typing || it == FocusDirection.Up || it == FocusDirection.Down
                                            }
                                        when {
                                            event.type == KeyEventType.KeyUp && activation && !typing -> {
                                                isSearchActive = true
                                                keyboardController?.show()
                                                true
                                            }

                                            // A text field moves focus itself only for a D-pad device; a keyboard's
                                            // arrows would move the cursor instead.
                                            direction != null -> {
                                                if (event.type == KeyEventType.KeyDown) {
                                                    isSearchActive = false
                                                    focusManager.moveFocus(direction)
                                                }
                                                true
                                            }

                                            else -> {
                                                false
                                            }
                                        }
                                    },
                        )
                        Box(modifier = Modifier.padding(top = 4.dp)) {
                            IconSlot {
                                TallyIconButton(
                                    glyph = stringResource(R.string.fa_sliders),
                                    label = stringResource(R.string.tally_pages_view_options),
                                    onClick = { showViewOptions = true },
                                    modifier = it,
                                )
                            }
                        }
                    }
                    if (showTabs) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier
                                    .padding(horizontal = TallyDimens.marginHorizontal)
                                    .padding(top = 4.dp)
                                    .focusGroup()
                                    .onFocusChanged { if (it.hasFocus) setPosition(RowColumn(TAB_ROW, 0)) },
                        ) {
                            listOf(R.string.tally_pages_library, R.string.tally_pages_discover).forEachIndexed { index, label ->
                                TallyButton(
                                    label = stringResource(label),
                                    primary = index == selectedTab,
                                    onClick = {
                                        selectedTab = index
                                        setPosition(RowColumn(if (index == 0) COMBINED_ROW else SEERR_ROW, 0))
                                    },
                                )
                            }
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) {
                        when {
                            query.isBlank() -> {
                                SearchHint(Modifier.padding(horizontal = TallyDimens.marginHorizontal, vertical = 16.dp))
                            }

                            combinedMode -> {
                                CombinedResults(
                                    result = if (isLibraryTab) state.combinedResults else state.seerrResults,
                                    query = query,
                                    focusRequester = focusRequesters[if (isLibraryTab) COMBINED_ROW else SEERR_ROW],
                                    onClickItem = { index, item ->
                                        setPosition(RowColumn(COMBINED_ROW, index))
                                        onClickItem(index, item)
                                    },
                                    onLongClickItem = { index, item -> onLongClickItem(COMBINED_ROW, index, item) },
                                    onPlayItem = onPlayItem,
                                    onClickDiscover = onClickDiscover,
                                )
                            }

                            else -> {
                                RowResults(
                                    state = state,
                                    query = query,
                                    seerrShown = seerrActive && state.discoverEnabled,
                                    focusRequesters = focusRequesters,
                                    onClickItem = { rowIndex, index, item ->
                                        setPosition(RowColumn(rowIndex, index))
                                        onClickItem(index, item)
                                    },
                                    onLongClickItem = onLongClickItem,
                                    onPlayItem = onPlayItem,
                                    onClickDiscover = { index, item ->
                                        setPosition(RowColumn(SEERR_ROW, index))
                                        onClickDiscover(index, item)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ItemDialogsHost(
        state = dialogs,
        getMediaSource = { _, _ -> null },
        preferredSubtitleLanguage = null,
        showFilePath = viewModel.isAdministrator(),
        onConfirmDelete = { item -> viewModel.deleteItem(position.column, item) },
        playlistViewModel = playlistViewModel,
    )
    if (showViewOptions) {
        SearchViewOptionsDialog(
            combinedResults = combinedMode,
            onCombinedResultsChange = viewModel::setCombinedResults,
            voiceSearchButtonVisible = voiceSearchButtonVisible,
            onVoiceSearchButtonVisibleChange = viewModel::setVoiceSearchButtonVisible,
            onClickFilterTypes = { showFilterTypeDialog = true },
            onDismissRequest = { showViewOptions = false },
        )
    }
    if (showFilterTypeDialog) {
        SearchTypeOptionsDialog(
            onDismissRequest = { showFilterTypeDialog = false },
            searchableTypes = state.possibleSearchableTypes,
            excludedSearchableTypes = state.excludedSearchableTypes,
            discoverAvailable = seerrActive,
            discoverEnabled = state.discoverEnabled,
            onClick = viewModel::onClickExcludeSearchableType,
            onClickDiscover = viewModel::onClickExcludeDiscover,
        )
    }
    if (showProgramDialog) {
        val onDismissRequest = { showProgramDialog = false }
        ProgramDialog(
            state = programDialogState.loading,
            canRecord = true,
            onDismissRequest = onDismissRequest,
            onWatch = {
                onDismissRequest()
                val channelId = it.data.channelId
                if (channelId != null) {
                    viewModel.navigationManager.navigateTo(Destination.Playback(itemId = channelId, positionMs = 0L))
                } else {
                    Toast.makeText(context, "Program has no channel ID", Toast.LENGTH_LONG).show()
                }
            },
            onRecord = { program, series ->
                viewModel.record(programId = program.id, series = series)
                onDismissRequest()
            },
            onCancelRecord = { program, series ->
                viewModel.cancelRecording(
                    series = series,
                    timerId = if (series) program.data.seriesTimerId else program.data.timerId,
                )
                onDismissRequest()
            },
        )
    }
}

/** Whether the on-screen keyboard is up (the window's IME inset is visible). */
private fun imeShown(view: View): Boolean = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

private fun focusDirectionFor(key: Key): FocusDirection? =
    when (key) {
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }

/** Before any query: a mono muted line. Upstream has no suggestions or recent searches to show. */
@Composable
private fun SearchHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.tally_pages_search_hint).tallyUppercase(),
        style = TallyType.label,
        color = TallyColors.muted,
        modifier = modifier,
    )
}

/**
 * Square search box: `groundRaised`, 1dp `ruleStrong` (3dp accent while focused, inside its bounds),
 * a search glyph, a mono muted placeholder and the typed text in Sans 20sp.
 */
@Composable
private fun TallySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        singleLine = true,
        textStyle = SearchTextStyle.copy(color = TallyColors.text),
        cursorBrush = SolidColor(TallyColors.accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interactionSource,
        modifier = modifier.height(SearchFieldHeight),
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(TallyColors.groundRaised)
                        .drawBehind {
                            // Whole pixels, like the cards' frames, so the frame reads the same on every side.
                            val width = if (focused) TallyDimens.focusBorder else TallyDimens.hairline
                            val stroke = floor(width.toPx()).coerceAtLeast(1f)
                            drawRect(
                                color = if (focused) TallyColors.accent else TallyColors.ruleStrong,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke),
                            )
                        }.padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.tally_pages_fa_search),
                    fontFamily = FontAwesome,
                    fontSize = 16.sp,
                    color = if (focused) TallyColors.text else TallyColors.muted,
                )
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.weight(1f),
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tally_pages_search_placeholder).tallyUppercase(),
                            style = TallyType.label,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Plex Mono capitals sit below the middle of their line box: lift them so the
                            // ink has equal room above and below (measured on the 1080p emulator).
                            modifier = Modifier.offset(y = PlaceholderLift),
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowResults(
    state: com.github.damontecres.wholphin.ui.search.SearchState,
    query: String,
    seerrShown: Boolean,
    focusRequesters: List<FocusRequester>,
    onClickItem: (rowIndex: Int, index: Int, item: BaseItem) -> Unit,
    onLongClickItem: (rowIndex: Int, index: Int, item: BaseItem) -> Unit,
    onPlayItem: (Int, BaseItem) -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
) {
    val types = state.includedSearchableTypes
    val results = types.map { state.results.getOrDefault(it, SearchResult.Searching) }
    val nothingFound =
        results.isNotEmpty() &&
            results.all { it is SearchResult.Success && it.items.isEmpty() } &&
            (!seerrShown || (state.seerrResults as? SearchResult.SuccessSeerr)?.items?.isEmpty() != false)
    if (nothingFound) {
        EmptyState(
            title = stringResource(R.string.tally_pages_search_nothing, query),
            subtitle = stringResource(R.string.tally_pages_search_nothing_body),
            takeFocus = false,
            modifier =
                Modifier
                    .padding(horizontal = TallyDimens.marginHorizontal, vertical = 24.dp)
                    .fillMaxWidth()
                    .height(160.dp),
        )
        return
    }
    val listState = rememberLazyListState()
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPageScrollSpec()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = TallyDimens.marginVertical),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        // Rows scrolled up fade out under the search field instead of being cut, as on the
                        // library grid.
                        if (listState.canScrollBackward) {
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        0f to TallyColors.ground,
                                        1f to Color.Transparent,
                                        endY = ScrollFade.toPx(),
                                    ),
                                size = Size(size.width, ScrollFade.toPx()),
                            )
                        }
                    }.focusGroup(),
        ) {
            // Only rows with something to show: an empty row would still take the list's spacing.
            val shown =
                types.indices.filter { index ->
                    val result = results[index]
                    result !is SearchResult.NoQuery && !(result is SearchResult.Success && result.items.isEmpty())
                }
            items(shown, key = { index -> types[index].serialName }) { index ->
                val type = types[index]
                val rowIndex = RESULTS_START + index
                val requester = focusRequesters.getOrNull(rowIndex) ?: remember { FocusRequester() }
                when (val result = results[index]) {
                    SearchResult.NoQuery -> {}

                    SearchResult.Searching -> {
                        RowMessage(
                            title = stringResource(typeTitle(type)),
                            message = stringResource(R.string.tally_pages_searching),
                        )
                    }

                    is SearchResult.Error -> {
                        RowMessage(
                            title = stringResource(typeTitle(type)),
                            message =
                                result.ex.localizedMessage ?: stringResource(R.string.tally_pages_search_error),
                            failure = true,
                        )
                    }

                    is SearchResult.Success -> {
                        if (result.items.isNotEmpty()) {
                            PagesItemRow(
                                title = stringResource(typeTitle(type)),
                                items = result.items,
                                fallbackType = type,
                                modifier =
                                    Modifier
                                        .padding(horizontal = TallyDimens.marginHorizontal)
                                        .focusRequester(requester),
                                onClick = { i, item -> onClickItem(rowIndex, i, item) },
                                onLongClick = { i, item -> onLongClickItem(rowIndex, i, item) },
                                onPlay = onPlayItem,
                            )
                        }
                    }

                    is SearchResult.SuccessSeerr -> {}
                }
            }
            if (seerrShown) {
                item(key = "discover") {
                    val seerr = state.seerrResults
                    if (seerr is SearchResult.SuccessSeerr && seerr.items.isNotEmpty()) {
                        DiscoverRow(
                            row =
                                DiscoverRowData(
                                    ResStringProvider(R.string.discover),
                                    DataLoadingState.Success(seerr.items),
                                    type = DiscoverRequestType.UNKNOWN,
                                ),
                            onClickItem = onClickDiscover,
                            onLongClickItem = { _, _ -> },
                            onCardFocus = {},
                            focusRequester = focusRequesters[SEERR_ROW],
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (seerr is SearchResult.Searching) {
                        RowMessage(
                            title = stringResource(R.string.tally_pages_discover),
                            message = stringResource(R.string.tally_pages_searching),
                        )
                    }
                }
            }
        }
    }
}

/** Upstream's combined mode: one grid of every result (or of the Discover results on that tab). */
@Composable
private fun CombinedResults(
    result: SearchResult,
    query: String,
    focusRequester: FocusRequester,
    onClickItem: (Int, BaseItem) -> Unit,
    onLongClickItem: (Int, BaseItem) -> Unit,
    onPlayItem: (Int, BaseItem) -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
) {
    val margin = Modifier.padding(horizontal = TallyDimens.marginHorizontal, vertical = 16.dp)
    when (result) {
        SearchResult.NoQuery -> {}

        SearchResult.Searching -> {
            RowMessage(
                title = stringResource(R.string.tally_pages_results),
                message = stringResource(R.string.tally_pages_searching),
                modifier = margin,
            )
        }

        is SearchResult.Error -> {
            RowMessage(
                title = stringResource(R.string.tally_pages_results),
                message = result.ex.localizedMessage ?: stringResource(R.string.tally_pages_search_error),
                failure = true,
                modifier = margin,
            )
        }

        is SearchResult.Success -> {
            if (result.items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.tally_pages_search_nothing, query),
                    subtitle = stringResource(R.string.tally_pages_search_nothing_body),
                    takeFocus = false,
                    modifier = margin.fillMaxWidth().height(160.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    RowHeader(
                        title = stringResource(R.string.tally_pages_results),
                        count = result.items.size,
                        modifier = Modifier.padding(horizontal = TallyDimens.marginHorizontal),
                    )
                    PagesItemGrid(
                        items = result.items,
                        focusRequester = focusRequester,
                        onClick = onClickItem,
                        onLongClick = onLongClickItem,
                        onPlay = onPlayItem,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        is SearchResult.SuccessSeerr -> {
            if (result.items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.tally_pages_search_nothing, query),
                    subtitle = stringResource(R.string.tally_pages_search_nothing_body),
                    takeFocus = false,
                    modifier = margin.fillMaxWidth().height(160.dp),
                )
            } else {
                DiscoverRow(
                    row =
                        DiscoverRowData(
                            ResStringProvider(R.string.discover),
                            DataLoadingState.Success(result.items),
                            type = DiscoverRequestType.UNKNOWN,
                        ),
                    onClickItem = onClickDiscover,
                    onLongClickItem = { _, _ -> },
                    onCardFocus = {},
                    focusRequester = focusRequester,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }
}

/** A row header with a mono message under it, for a row still searching or one that failed. */
@Composable
private fun RowMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier.padding(horizontal = TallyDimens.marginHorizontal),
    failure: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        RowHeader(title = title)
        Text(
            text = message.tallyUppercase(),
            style = TallyType.label,
            color = if (failure) TallyColors.liveText else TallyColors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Shared by the Tally search, person and favorites pages.
// ---------------------------------------------------------------------------------------------

/** Row title for a type: `MOVIES`, `SHOWS`, `EPISODES`, `PEOPLE`, `COLLECTIONS`…; upstream's title otherwise. */
@StringRes
internal fun typeTitle(type: BaseItemKind): Int =
    when (type) {
        BaseItemKind.MOVIE -> R.string.tally_pages_type_movies
        BaseItemKind.SERIES -> R.string.tally_pages_type_shows
        BaseItemKind.EPISODE -> R.string.tally_pages_type_episodes
        BaseItemKind.PERSON -> R.string.tally_pages_type_people
        BaseItemKind.BOX_SET -> R.string.tally_pages_type_collections
        BaseItemKind.PLAYLIST -> R.string.tally_pages_type_playlists
        BaseItemKind.VIDEO -> R.string.tally_pages_type_videos
        else -> type.titleStringRes
    }

/**
 * Context menu for [item] through [ItemDialogsHost], with the same actions upstream's
 * `ContextMenuUtils` wires for a [ContextMenuProvider] page.
 */
internal fun providerContextMenu(
    provider: ContextMenuProvider,
    position: Int,
    item: BaseItem,
    preferences: UserPreferences,
    dialogs: ItemDialogsState,
    onAddToQueue: (BaseItem) -> Unit,
): ContextMenu =
    ContextMenu.ForBaseItem(
        fromLongClick = true,
        item = item,
        chosenStreams = null,
        showGoTo = true,
        showStreamChoices = false,
        canDelete = provider.canDelete(item, preferences.appPreferences),
        canRemoveContinueWatching = false,
        canRemoveNextUp = false,
        actions =
            ContextMenuActions(
                navigateTo = provider::navigateTo,
                onClickWatch = { itemId, watched -> provider.setWatched(position, itemId, watched) },
                onClickFavorite = { itemId, favorite -> provider.setFavorite(position, itemId, favorite) },
                onClickAddPlaylist = { dialogs.playlistItemId = it },
                onSendMediaInfo = provider::sendReportFor,
                onDeleteItem = { provider.deleteItem(position, it) },
                onShowOverview = { dialogs.overview = ItemDetailsDialogInfo(it) },
                onChooseVersion = { _, _ -> },
                onChooseTracks = { _ -> },
                onClearChosenStreams = { },
                onClickAddToQueue = onAddToQueue,
            ),
    )

/**
 * Scroll a page's list only as far as needed to show the focused row, as the film page does
 * (the TV default parks it a third of the way down), and keep [bottomRoom] (px) under it so the last row rests
 * inside the safe area instead of on the screen's bottom edge.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class PageScrollSpec(
    private val bottomRoom: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val trailing = offset + size
        val limit = containerSize - bottomRoom
        return when {
            offset >= 0f && trailing <= limit -> 0f
            size <= limit && trailing > limit -> trailing - limit
            offset >= 0f && trailing <= containerSize -> 0f
            size <= containerSize && trailing > containerSize -> trailing - containerSize
            else -> offset
        }
    }
}

/** Height of the fade at the top of the scrolled results. */
private val ScrollFade = 32.dp

/** [PageScrollSpec] with the page's bottom safe margin. */
@Composable
internal fun rememberPageScrollSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) { PageScrollSpec(with(density) { TallyDimens.marginVertical.toPx() }) }
}

/** A [MediaRow] of [PagesItemCard]s. Keys are position + id, so a repeated id never collides. */
@Composable
internal fun PagesItemRow(
    title: String,
    items: List<BaseItem?>,
    fallbackType: BaseItemKind?,
    onClick: (Int, BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    onPlay: (Int, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = items.size,
    onFocusItem: (Int, BaseItem?) -> Unit = { _, _ -> },
    kicker: @Composable (BaseItem) -> String? = { defaultKicker(it) },
) {
    MediaRow(
        title = title,
        items = items,
        count = count,
        key = { index, item -> "$index-${item?.id}" },
        modifier = modifier,
        card = { item, index, cardModifier, onFocused ->
            PagesItemCard(
                item = item,
                fallbackType = fallbackType,
                kicker = item?.let { kicker(it) },
                onClick = { item?.let { onClick(index, it) } },
                onLongClick = { item?.let { onLongClick(index, it) } },
                onPlay = { item?.let { if (it.type.playable) onPlay(index, it) } },
                onFocused = {
                    onFocusItem(index, item)
                    onFocused()
                },
                modifier = cardModifier,
            )
        },
    )
}

/** `BREAKING BAD · S1 E3` for an episode card; nothing for the others (their year is on the label). */
@Composable
internal fun defaultKicker(item: BaseItem): String? =
    if (item.type == BaseItemKind.EPISODE) {
        joinMeta(
            item.data.seriesName,
            episodeCode(
                item.data.parentIndexNumber,
                item.indexNumber,
                item.data.indexNumberEnd,
                stringResource(R.string.tally_series_special),
            ),
        ).ifBlank { null }
    } else {
        null
    }

/**
 * One result, drawn with the kit card for its type: episodes, channels and programs as
 * [LandscapeCard]s, people as [PersonCard]s, music and photos square, everything else a [PosterCard].
 * A null [item] (a page still loading) is an empty frame of the same size.
 */
@Composable
internal fun PagesItemCard(
    item: BaseItem?,
    fallbackType: BaseItemKind?,
    kicker: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    gridSized: Boolean = false,
) {
    val type = item?.type ?: fallbackType
    val imageService = LocalImageUrlService.current
    when {
        type == BaseItemKind.PERSON -> {
            val url = remember(item) { imageService.getItemImageUrl(item, ImageType.PRIMARY) }
            PersonCard(
                name = item?.name ?: "",
                role = null,
                imageUrl = url,
                onClick = onClick,
                onLongClick = onLongClick,
                onFocused = onFocused,
                modifier = modifier,
                width = if (gridSized) GridCardWidth else PersonRowWidth,
            )
        }

        !gridSized && type in WideTypes -> {
            val url = item?.let { rememberWideImageUrl(it) }
            val percent =
                resumePercent(
                    item?.data?.userData?.playbackPositionTicks ?: 0L,
                    item?.data?.runTimeTicks ?: 0L,
                )
            LandscapeCard(
                title = item?.name ?: "",
                kicker = kicker,
                imageUrl = url,
                progress = if (item?.played != true && percent in 1..99) percent / 100f else null,
                favorite = item?.favorite == true,
                onClick = onClick,
                onLongClick = onLongClick,
                onPlay = onPlay,
                onFocused = onFocused,
                modifier = modifier,
                width = LandscapeWidth,
            )
        }

        type in SquareTypes || item == null -> {
            val url = remember(item) { imageService.getItemImageUrl(item, ImageType.PRIMARY) }
            val width = if (gridSized || (item == null && type !in SquareTypes)) GridCardWidth else SquareWidth
            val height = if (item == null && type !in SquareTypes) width * 3 / 2 else width
            CardFrame(
                imageUrl = url,
                width = width,
                height = height,
                contentDescription = item?.name,
                onClick = onClick,
                onLongClick = onLongClick,
                onPlay = onPlay,
                onFocused = onFocused,
                favorite = item?.favorite == true,
                modifier = modifier,
                label = {
                    CardTitleText(item?.name ?: "")
                    val detail = item?.let { squareDetail(it) }
                    if (!detail.isNullOrBlank()) CardDetailText(detail)
                },
            )
        }

        else -> {
            PosterCard(
                item = item!!,
                onClick = onClick,
                onLongClick = onLongClick,
                onPlay = onPlay,
                onFocused = onFocused,
                modifier = modifier,
                width = GridCardWidth,
            )
        }
    }
}

@Composable
private fun squareDetail(item: BaseItem): String? =
    when (item.type) {
        BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> item.data.albumArtist ?: item.data.productionYear?.toString()
        else -> posterDetail(item)
    }

/**
 * Upstream's combined grid in the Tally look: poster-sized cards in a focus-safe grid (padding of
 * [FocusEdge] so no focus border is cut by the grid's clip).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PagesItemGrid(
    items: List<BaseItem?>,
    focusRequester: FocusRequester,
    onClick: (Int, BaseItem) -> Unit,
    onLongClick: (Int, BaseItem) -> Unit,
    onPlay: (Int, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    onFocusItem: (Int, BaseItem?) -> Unit = { _, _ -> },
    cardWidth: Dp = GridCardWidth,
    card: (@Composable (index: Int, item: BaseItem?, cardModifier: Modifier, onFocused: () -> Unit) -> Unit)? = null,
) {
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberFocusEdgeSpec()) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(cardWidth + FocusEdge * 2),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding =
                PaddingValues(
                    start = TallyDimens.marginHorizontal - FocusEdge,
                    end = TallyDimens.marginHorizontal - FocusEdge,
                    top = FocusEdge + 8.dp,
                    bottom = TallyDimens.marginVertical,
                ),
            modifier =
                modifier
                    .focusRequester(focusRequester)
                    .focusGroup()
                    .focusRestorer(first),
        ) {
            itemsIndexed(items, key = { index, item -> "$index-${item?.id}" }) { index, item ->
                val cardModifier =
                    Modifier
                        .padding(horizontal = FocusEdge)
                        .then(if (index == focusedIndex) Modifier.focusRequester(first) else Modifier)
                val onFocused = {
                    focusedIndex = index
                    onFocusItem(index, item)
                }
                if (card != null) {
                    card(index, item, cardModifier, onFocused)
                } else {
                    PagesItemCard(
                        item = item,
                        fallbackType = null,
                        kicker = item?.let { defaultKicker(it) },
                        gridSized = true,
                        onClick = { item?.let { onClick(index, it) } },
                        onLongClick = { item?.let { onLongClick(index, it) } },
                        onPlay = { item?.let { if (it.type.playable) onPlay(index, it) } },
                        onFocused = onFocused,
                        modifier = cardModifier,
                    )
                }
            }
        }
    }
}

/**
 * Voice search as the kit's square icon button. A click does what upstream's round [VoiceSearchButton] does: listen
 * (asking for the microphone first), or stop while listening. Upstream's button still runs, hidden and out of the
 * focus order, because its listening overlay and its hand-off of the result live in it and are not reusable alone.
 */
@Composable
private fun TallyVoiceButton(
    voiceInputManager: VoiceInputManager,
    onSpeechResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!voiceInputManager.isAvailable) return
    val state by voiceInputManager.state.collectAsState()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voiceInputManager.onPermissionGranted() else voiceInputManager.onPermissionDenied()
        }
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .size(0.dp)
                    .clipToBounds()
                    .alpha(0f),
        ) {
            VoiceSearchButton(
                onSpeechResult = onSpeechResult,
                voiceInputManager = voiceInputManager,
                modifier = Modifier.focusProperties { canFocus = false },
            )
        }
        IconSlot {
            TallyIconButton(
                glyph = stringResource(R.string.fa_microphone),
                label = stringResource(R.string.voice_search),
                onClick = {
                    when (state) {
                        VoiceInputState.Starting, VoiceInputState.Listening -> {
                            voiceInputManager.stopListening()
                        }

                        else -> {
                            if (voiceInputManager.hasPermission) {
                                voiceInputManager.startListening()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                },
                modifier = it,
            )
        }
    }
}

/**
 * A 40dp slot for an icon button whose caption (shown while focused) may be wider than the button: the
 * caption spills over the slot instead of widening it, so the buttons never shift when focus moves.
 */
@Composable
internal fun IconSlot(content: @Composable (Modifier) -> Unit) {
    Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.width(40.dp)) {
        content(Modifier.wrapContentWidth(unbounded = true))
    }
}

/** A mono `LOADING…` that holds focus while a page loads (focus with nowhere to go falls to the drawer). */
@Composable
internal fun PagesLoading(
    tag: String,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.tryRequestFocus(tag) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.tally_media_loading).tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
            modifier =
                Modifier
                    .focusRequester(requester)
                    .focusable(),
        )
    }
}

private val WideTypes =
    setOf(
        BaseItemKind.EPISODE,
        BaseItemKind.TV_CHANNEL,
        BaseItemKind.LIVE_TV_PROGRAM,
        BaseItemKind.TV_PROGRAM,
        BaseItemKind.PROGRAM,
    )

private val SquareTypes =
    setOf(
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.MUSIC_ARTIST,
        BaseItemKind.AUDIO,
        BaseItemKind.PLAYLIST,
        BaseItemKind.PHOTO,
        BaseItemKind.PHOTO_ALBUM,
    )

internal val GridCardWidth = 132.dp
private val SquareWidth = 132.dp
private val PersonRowWidth = 104.dp
private val GridGap = 16.dp - FocusEdge * 2
private val SearchFieldHeight = 48.dp
private val PlaceholderLift = (-2).dp

/** The search row ends short of the app clock drawn at the top right. */
private val ClockRoom = 160.dp

private val SearchTextStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    )
