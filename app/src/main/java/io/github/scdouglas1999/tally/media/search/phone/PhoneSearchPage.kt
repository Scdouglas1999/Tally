package io.github.scdouglas1999.tally.media.search.phone

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceInputState
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.livetv.ProgramDialog
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.ui.search.SearchState
import com.github.damontecres.wholphin.ui.search.SearchViewModel
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import io.github.scdouglas1999.tally.media.kit.ItemDialogsHost
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneIconButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneItemCard
import io.github.scdouglas1999.tally.media.kit.phone.PhoneMediaGrid
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowHeader
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowMessage
import io.github.scdouglas1999.tally.media.kit.phone.fullWidthItem
import io.github.scdouglas1999.tally.media.kit.phone.phoneGridColumns
import io.github.scdouglas1999.tally.media.search.defaultKicker
import io.github.scdouglas1999.tally.media.search.providerContextMenu
import io.github.scdouglas1999.tally.media.search.typeTitle
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.formfactor.tallyFocusVisible
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneStatusBarPadding
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.time.Duration.Companion.milliseconds

/**
 * Search on a phone: the same [SearchViewModel] as the TV page (its debounce, the search key and voice searching at
 * once, combined or per-type results, the type filter and view options, Discover, the program dialog, the item menu),
 * with the field pinned at the top under the status bar (48dp, square, a 1dp `ruleStrong` frame, the keyboard opening
 * on arrival, a clear button inside it; voice and view options beside it), the results below as the TV's rows (or its
 * combined grid) with phone cards.
 */
@Composable
fun PhoneSearchPage(
    initialQuery: String,
    userPreferences: UserPreferences,
    modifier: Modifier,
    viewModel: SearchViewModel,
    playlistViewModel: AddPlaylistViewModel,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state by viewModel.state.collectAsState()
    val programDialogState by viewModel.programDialogState.collectAsState()
    val prefs =
        viewModel.userPreferencesService.flow
            .collectAsState(userPreferences)
            .value.appPreferences.interfacePreferences.searchPreferences
    val combinedMode = prefs.combinedSearchResults
    val voiceSearchButtonVisible = prefs.showVoiceSearchButton
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val seerrActive by viewModel.seerrActive.collectAsState()
    var selectedTab by rememberSaveable(seerrActive, state.discoverEnabled) { mutableIntStateOf(0) }
    var showViewOptions by rememberSaveable { mutableStateOf(false) }
    var showFilterTypeDialog by rememberSaveable { mutableStateOf(false) }
    var immediateSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var showProgramDialog by remember { mutableStateOf(false) }
    val position by viewModel.position.collectAsState()
    val dialogs = remember { ItemDialogsState() }
    // The keyboard opens when the page is first opened, not when coming back to it from a result.
    var arrived by rememberSaveable { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { viewModel.voiceInputManager.stopListening() }
    }

    fun triggerImmediateSearch(searchQuery: String) {
        immediateSearchQuery = searchQuery
        viewModel.search(searchQuery, combinedMode)
        keyboard?.hide()
    }

    LaunchedEffect(query, combinedMode) {
        if (immediateSearchQuery == query) {
            immediateSearchQuery = null
        } else {
            delay(750.milliseconds)
            viewModel.search(query, combinedMode)
        }
    }
    LaunchedEffect(Unit) {
        if (!arrived) {
            arrived = true
            repeat(10) {
                try {
                    fieldFocus.requestFocus()
                    keyboard?.show()
                    return@LaunchedEffect
                } catch (_: IllegalStateException) {
                    delay(50)
                }
            }
        }
    }

    val setPosition = { pos: RowColumn -> viewModel.position.value = pos }
    val onClickItem = { item: BaseItem ->
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
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()

    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .phoneStatusBarPadding()
                    .padding(start = PhoneDimens.margin, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            PhoneSearchField(
                value = query,
                onValueChange = { query = it },
                onSearch = { triggerImmediateSearch(query) },
                onClear = {
                    query = ""
                    fieldFocus.requestFocus()
                    keyboard?.show()
                },
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
            )
            if (voiceSearchButtonVisible) {
                PhoneVoiceButton(
                    voiceInputManager = viewModel.voiceInputManager,
                    onSpeechResult = { spoken ->
                        query = spoken
                        triggerImmediateSearch(spoken)
                    },
                )
            }
            PhoneIconButton(
                glyph = stringResource(R.string.fa_sliders),
                label = stringResource(R.string.tally_pages_view_options),
                onClick = { showViewOptions = true },
                framed = false,
            )
        }
        if (showTabs) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PhoneDimens.cardGap),
                modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(bottom = 8.dp),
            ) {
                listOf(R.string.tally_pages_library, R.string.tally_pages_discover).forEachIndexed { index, label ->
                    PhoneButton(
                        label = stringResource(label),
                        primary = index == selectedTab,
                        onClick = { selectedTab = index },
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                query.isBlank() -> {
                    Text(
                        text = stringResource(R.string.tally_phone_browse_search_hint).tallyUppercase(),
                        style = PhoneType.label,
                        color = TallyColors.muted,
                        modifier = Modifier.padding(horizontal = PhoneDimens.margin, vertical = 16.dp),
                    )
                }

                combinedMode -> {
                    PhoneCombinedResults(
                        result = if (isLibraryTab) state.combinedResults else state.seerrResults,
                        query = query,
                        bottom = bottom,
                        onClickItem = { index, item ->
                            setPosition(RowColumn(COMBINED_ROW, index))
                            onClickItem(item)
                        },
                        onLongClickItem = { index, item -> onLongClickItem(COMBINED_ROW, index, item) },
                        onClickDiscover = onClickDiscover,
                    )
                }

                else -> {
                    PhoneRowResults(
                        state = state,
                        query = query,
                        seerrShown = seerrActive && state.discoverEnabled,
                        bottom = bottom,
                        onClickItem = { rowIndex, index, item ->
                            setPosition(RowColumn(rowIndex, index))
                            onClickItem(item)
                        },
                        onLongClickItem = onLongClickItem,
                        onClickDiscover = onClickDiscover,
                    )
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
        PhoneSearchViewOptionsSheet(
            combinedResults = combinedMode,
            onCombinedResultsChange = viewModel::setCombinedResults,
            voiceSearchButtonVisible = voiceSearchButtonVisible,
            onVoiceSearchButtonVisibleChange = viewModel::setVoiceSearchButtonVisible,
            // One sheet at a time on a phone: the types replace the view options.
            onClickFilterTypes = {
                showViewOptions = false
                showFilterTypeDialog = true
            },
            onDismissRequest = { showViewOptions = false },
        )
    }
    if (showFilterTypeDialog) {
        PhoneSearchTypesSheet(
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

// Row indexes of upstream's search page, kept so the view model's saved position means the same.
private const val SEERR_ROW = 2
private const val COMBINED_ROW = SEERR_ROW
private const val RESULTS_START = SEERR_ROW + 1

private val FieldTextStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
    )

/**
 * The search field: 48dp, square, `groundRaised` with a 1dp `ruleStrong` frame (an accent frame only while a keyboard
 * or D-pad is in use), a search glyph, a mono placeholder, the text in Sans 17sp; a clear (×) button at its right
 * end while it has text.
 */
@Composable
private fun PhoneSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = tallyFocusVisible()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = FieldTextStyle.copy(color = TallyColors.text),
        cursorBrush = SolidColor(TallyColors.accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier =
            modifier
                .height(PhoneDimens.touchTarget)
                .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(TallyColors.groundRaised)
                        .border(
                            if (focused && showFocus) PhoneDimens.focusBorder else PhoneDimens.hairline,
                            if (focused && showFocus) TallyColors.accent else TallyColors.ruleStrong,
                        ).padding(start = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.tally_pages_fa_search),
                    fontFamily = FontAwesome,
                    fontSize = 15.sp,
                    color = TallyColors.muted,
                )
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).clipToBounds(),
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tally_pages_search_placeholder).tallyUppercase(),
                            style = PhoneType.label,
                            color = TallyColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    val clear = stringResource(R.string.tally_phone_browse_clear)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(PhoneDimens.touchTarget)
                                .semantics { contentDescription = clear }
                                .focusProperties { canFocus = false }
                                .phoneClickable(onClick = onClear),
                    ) {
                        Text(
                            text = stringResource(R.string.fa_xmark),
                            fontFamily = FontAwesome,
                            fontSize = 16.sp,
                            color = TallyColors.textSecondary,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Voice search at phone size: listens (asking for the microphone first) or stops while listening, as upstream's
 * button does. Upstream's button still runs, hidden and out of the focus order, because its listening overlay and its
 * hand-off of the result live in it.
 */
@Composable
private fun PhoneVoiceButton(
    voiceInputManager: VoiceInputManager,
    onSpeechResult: (String) -> Unit,
) {
    if (!voiceInputManager.isAvailable) return
    val state by voiceInputManager.state.collectAsState()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voiceInputManager.onPermissionGranted() else voiceInputManager.onPermissionDenied()
        }
    Box {
        Box(modifier = Modifier.size(0.dp).clipToBounds().alpha(0f)) {
            VoiceSearchButton(
                onSpeechResult = onSpeechResult,
                voiceInputManager = voiceInputManager,
                modifier = Modifier.focusProperties { canFocus = false },
            )
        }
        PhoneIconButton(
            glyph = stringResource(R.string.fa_microphone),
            label = stringResource(R.string.voice_search),
            framed = false,
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
        )
    }
}

/** The TV's per-type rows (films, shows, episodes, people, collections…) with phone cards; Discover last. */
@Composable
private fun PhoneRowResults(
    state: SearchState,
    query: String,
    seerrShown: Boolean,
    bottom: androidx.compose.ui.unit.Dp,
    onClickItem: (rowIndex: Int, index: Int, item: BaseItem) -> Unit,
    onLongClickItem: (rowIndex: Int, index: Int, item: BaseItem) -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
) {
    val types = state.includedSearchableTypes
    val results = types.map { state.results.getOrDefault(it, SearchResult.Searching) }
    val nothingFound =
        results.isNotEmpty() &&
            results.all { it is SearchResult.Success && it.items.isEmpty() } &&
            (!seerrShown || (state.seerrResults as? SearchResult.SuccessSeerr)?.items?.isEmpty() != false)
    if (nothingFound) {
        PhoneEmptyState(
            title = stringResource(R.string.tally_pages_search_nothing, query),
            subtitle = stringResource(R.string.tally_pages_search_nothing_body),
        )
        return
    }
    val shown =
        types.indices.filter { index ->
            val result = results[index]
            result !is SearchResult.NoQuery && !(result is SearchResult.Success && result.items.isEmpty())
        }
    LazyColumn(
        contentPadding = PaddingValues(top = 8.dp, bottom = bottom + PhoneDimens.rowGap),
        verticalArrangement = Arrangement.spacedBy(PhoneDimens.rowGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shown, key = { index -> types[index].serialName }) { index ->
            val type = types[index]
            val rowIndex = RESULTS_START + index
            when (val result = results[index]) {
                SearchResult.Searching -> {
                    PhoneRowMessage(
                        title = stringResource(typeTitle(type)),
                        message = stringResource(R.string.tally_pages_searching),
                    )
                }

                is SearchResult.Error -> {
                    PhoneRowMessage(
                        title = stringResource(typeTitle(type)),
                        message = result.ex.localizedMessage ?: stringResource(R.string.tally_pages_search_error),
                        failure = true,
                    )
                }

                is SearchResult.Success -> {
                    PhoneCardRow(
                        title = stringResource(typeTitle(type)),
                        items = result.items,
                        key = { i, item -> "$i-${item?.id}" },
                    ) { item, i ->
                        PhoneItemCard(
                            item = item,
                            fallbackType = type,
                            kicker = item?.let { defaultKicker(it) },
                            onClick = { item?.let { onClickItem(rowIndex, i, it) } },
                            onLongClick = { item?.let { onLongClickItem(rowIndex, i, it) } },
                        )
                    }
                }

                else -> {}
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
                        focusRequester = remember { FocusRequester() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (seerr is SearchResult.Searching) {
                    PhoneRowMessage(
                        title = stringResource(R.string.tally_pages_discover),
                        message = stringResource(R.string.tally_pages_searching),
                    )
                }
            }
        }
    }
}

/** Upstream's combined mode: one grid of every result (or the Discover results on that tab). */
@Composable
private fun PhoneCombinedResults(
    result: SearchResult,
    query: String,
    bottom: androidx.compose.ui.unit.Dp,
    onClickItem: (Int, BaseItem) -> Unit,
    onLongClickItem: (Int, BaseItem) -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
) {
    when (result) {
        SearchResult.NoQuery -> {}

        SearchResult.Searching -> {
            PhoneRowMessage(
                title = stringResource(R.string.tally_pages_results),
                message = stringResource(R.string.tally_pages_searching),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        is SearchResult.Error -> {
            PhoneRowMessage(
                title = stringResource(R.string.tally_pages_results),
                message = result.ex.localizedMessage ?: stringResource(R.string.tally_pages_search_error),
                failure = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        is SearchResult.Success -> {
            if (result.items.isEmpty()) {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_pages_search_nothing, query),
                    subtitle = stringResource(R.string.tally_pages_search_nothing_body),
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    PhoneMediaGrid(
                        items = result.items,
                        columns = phoneGridColumns(2f / 3f, maxWidth),
                        topPadding = 8.dp,
                        bottomPadding = bottom + PhoneDimens.rowGap,
                        key = { index, item -> "$index-${item?.id}" },
                        header = {
                            fullWidthItem("header") {
                                PhoneRowHeader(title = stringResource(R.string.tally_pages_results), count = result.items.size)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) { item, index, width ->
                        PhoneItemCard(
                            item = item,
                            onClick = { item?.let { onClickItem(index, it) } },
                            onLongClick = { item?.let { onLongClickItem(index, it) } },
                            width = width,
                        )
                    }
                }
            }
        }

        is SearchResult.SuccessSeerr -> {
            if (result.items.isEmpty()) {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_pages_search_nothing, query),
                    subtitle = stringResource(R.string.tally_pages_search_nothing_body),
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
                    focusRequester = remember { FocusRequester() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
