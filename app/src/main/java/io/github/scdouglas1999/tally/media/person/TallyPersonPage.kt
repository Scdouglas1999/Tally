package io.github.scdouglas1999.tally.media.person

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.PersonViewModel
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.RowLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.media.pages.joinMeta
import io.github.scdouglas1999.tally.media.pages.personLifeLine
import io.github.scdouglas1999.tally.media.pages.primaryRole
import io.github.scdouglas1999.tally.media.search.PagesItemRow
import io.github.scdouglas1999.tally.media.search.PagesLoading
import io.github.scdouglas1999.tally.media.search.rememberPageScrollSpec
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.media.series.serverDate
import io.github.scdouglas1999.tally.ui.components.EmptyState
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.RowHeader
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * The person's usual credit, for the page kicker (`ACTOR`, `DIRECTOR`). Upstream's person view model
 * loads no credits, so this reads the `People` of the items the person appears in.
 */
@HiltViewModel
class TallyPersonRoleViewModel
    @Inject
    constructor(
        private val api: ApiClient,
    ) : ViewModel() {
        private val _role = MutableStateFlow<PersonKind?>(null)
        val role: StateFlow<PersonKind?> = _role
        private var loadedFor: UUID? = null

        fun load(personId: UUID) {
            if (loadedFor == personId) return
            loadedFor = personId
            viewModelScope.launchIO {
                try {
                    val items =
                        api.itemsApi
                            .getItems(
                                GetItemsRequest(
                                    personIds = listOf(personId),
                                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                                    recursive = true,
                                    fields = listOf(ItemFields.PEOPLE),
                                    limit = ROLE_SAMPLE,
                                    enableTotalRecordCount = false,
                                ),
                            ).content.items
                    val credits =
                        items.flatMap { item ->
                            item.people
                                .orEmpty()
                                .filter { it.id == personId }
                                .map { it.type }
                        }
                    _role.value = primaryRole(credits)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not read the credits of %s", personId)
                }
            }
        }

        private companion object {
            const val ROLE_SAMPLE = 40
        }
    }

private const val POS_HEADER = 0
private const val POS_MOVIES = 1
private const val POS_SERIES = 2
private const val POS_EPISODES = 3

/**
 * The Tally person page: upstream's [PersonViewModel] (person, films, shows, episodes, Discover),
 * a detail header with the portrait in a square frame at the right, then one row per kind of credit.
 */
@Composable
fun TallyPersonPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PersonViewModel =
        hiltViewModel<PersonViewModel, PersonViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    roleViewModel: TallyPersonRoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val role by roleViewModel.role.collectAsState()
    LaunchedEffect(destination.itemId) { roleViewModel.load(destination.itemId) }
    var overview by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    TallyScale {
        CompositionLocalProvider(LocalContentColor provides TallyColors.text) {
            ProvideTextStyle(TallyType.body) {
                Box(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
                    when (val st = state.person) {
                        is DataLoadingState.Error -> {
                            EmptyState(
                                title = stringResource(R.string.tally_media_error_title),
                                subtitle =
                                    st.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                                modifier = Modifier.fillMaxSize().padding(TallyDimens.marginHorizontal),
                            )
                        }

                        DataLoadingState.Loading,
                        DataLoadingState.Pending,
                        -> {
                            PagesLoading("tally-person-loading", Modifier.fillMaxSize())
                        }

                        is DataLoadingState.Success -> {
                            val person = st.data
                            val name = person.name ?: person.id.toString()
                            // As upstream: a long filmography is cut at the home rows' limit.
                            val maxItems = preferences.appPreferences.homePagePreferences.maxItemsPerRow
                            val discovered = remember(state.discovered, maxItems) { state.discovered.take(maxItems) }
                            PersonLoaded(
                                person = person,
                                name = name,
                                role = role,
                                movies = state.movies,
                                series = state.series,
                                episodes = state.episodes,
                                discovered = discovered,
                                enableViewMoreDiscover = state.discovered.size > discovered.size,
                                onClickItem = { item -> viewModel.navigationManager.navigateTo(item.destination()) },
                                onOverview = {
                                    overview =
                                        ItemDetailsDialogInfo(
                                            title = name,
                                            overview = person.data.overview,
                                            genres = listOf(),
                                            files = listOf(),
                                        )
                                },
                                onFavorite = { viewModel.setFavorite(!person.favorite) },
                                onClickDiscover = { item: DiscoverItem ->
                                    viewModel.navigationManager.navigateTo(item.destination)
                                },
                                onClickViewMoreDiscover = {
                                    state.discoverPerson?.let {
                                        viewModel.navigationManager.navigateTo(Destination.DiscoveredItem(it, maxItems))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    overview?.let { info ->
        ItemDetailsDialog(info = info, showFilePath = false, onDismissRequest = { overview = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonLoaded(
    person: BaseItem,
    name: String,
    role: PersonKind?,
    movies: RowLoadingState,
    series: RowLoadingState,
    episodes: RowLoadingState,
    discovered: List<DiscoverItem>,
    enableViewMoreDiscover: Boolean,
    onClickItem: (BaseItem) -> Unit,
    onOverview: () -> Unit,
    onFavorite: () -> Unit,
    onClickDiscover: (DiscoverItem) -> Unit,
    onClickViewMoreDiscover: () -> Unit,
) {
    var position by com.github.damontecres.wholphin.ui
        .rememberInt(POS_HEADER)
    val favoriteFocus = remember { FocusRequester() }
    val rowFocus = remember { List(4) { FocusRequester() } }
    val discoverFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val target = if (position > POS_HEADER) rowFocus[position] else favoriteFocus
        if (!target.tryRequestFocus("tally-person")) favoriteFocus.tryRequestFocus("tally-person")
    }
    val bringHeader = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPageScrollSpec()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = TallyDimens.marginVertical),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                PersonHeader(
                    person = person,
                    name = name,
                    role = role,
                    favoriteFocus = favoriteFocus,
                    onOverview = onOverview,
                    onFavorite = onFavorite,
                    onFocused = {
                        position = POS_HEADER
                        scope.launch(ExceptionHandler()) { bringHeader.bringIntoView() }
                    },
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringHeader),
                )
            }
            val rows =
                listOf(
                    Triple(POS_MOVIES, R.string.tally_pages_type_films, movies),
                    Triple(POS_SERIES, R.string.tally_pages_type_shows, series),
                    Triple(POS_EPISODES, R.string.tally_pages_type_episodes, episodes),
                )
            rows.forEach { (pos, title, rowState) ->
                item(key = "row-$pos") {
                    CreditRow(
                        title = stringResource(title),
                        state = rowState,
                        modifier = Modifier.focusRequester(rowFocus[pos]),
                        onFocused = { position = pos },
                        onClickItem = { item ->
                            position = pos
                            onClickItem(item)
                        },
                    )
                }
            }
            if (discovered.isNotEmpty()) {
                item(key = "discover") {
                    DiscoverRow(
                        row =
                            DiscoverRowData(
                                ResStringProvider(R.string.discover),
                                DataLoadingState.Success(discovered),
                                DiscoverRequestType.UNKNOWN,
                            ),
                        onClickItem = { _, item -> onClickDiscover(item) },
                        onLongClickItem = { _, _ -> },
                        onCardFocus = {},
                        focusRequester = discoverFocus,
                        enableViewMore = enableViewMoreDiscover,
                        onClickViewMore = onClickViewMoreDiscover,
                    )
                }
            }
        }
    }
}

/** A row of one kind of credit, newest first as upstream loads it; hidden when empty (as upstream). */
@Composable
private fun CreditRow(
    title: String,
    state: RowLoadingState,
    onFocused: () -> Unit,
    onClickItem: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val margin = Modifier.padding(horizontal = TallyDimens.marginHorizontal)
    when (state) {
        RowLoadingState.Pending,
        RowLoadingState.Loading,
        -> {
            RowNote(title, stringResource(R.string.tally_media_loading), failure = false, modifier = margin)
        }

        is RowLoadingState.Error -> {
            RowNote(title, state.localizedMessage, failure = true, modifier = margin)
        }

        is RowLoadingState.Success -> {
            if (state.items.isNotEmpty()) {
                PagesItemRow(
                    title = title,
                    items = state.items,
                    fallbackType = null,
                    modifier = margin.then(modifier),
                    onFocusItem = { _, _ -> onFocused() },
                    onClick = { _, item -> onClickItem(item) },
                    // Upstream's person rows have no long-press menu and no play key.
                    onLongClick = { _, _ -> },
                    onPlay = { _, _ -> },
                    kicker = { item -> creditKicker(item) },
                )
            }
        }
    }
}

/** Episodes carry their year, show and number (`2008 · BREAKING BAD · S1 E3`); films and shows show the year on the label. */
@Composable
private fun creditKicker(item: BaseItem): String? =
    if (item.type == BaseItemKind.EPISODE) {
        joinMeta(
            item.data.productionYear?.toString() ?: item.data.premiereDate
                ?.year
                ?.toString(),
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

@Composable
private fun RowNote(
    title: String,
    message: String,
    failure: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
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

/**
 * Kicker (the usual credit), name, a mono life line, a 4-line biography (OK opens all of it when it is
 * cut) and the favorite button; the portrait at the right in a square frame.
 */
@Composable
private fun PersonHeader(
    person: BaseItem,
    name: String,
    role: PersonKind?,
    favoriteFocus: FocusRequester,
    onOverview: () -> Unit,
    onFavorite: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageService = LocalImageUrlService.current
    val imageUrl = remember(person.id) { imageService.getItemImageUrl(itemId = person.id, imageType = ImageType.PRIMARY) }
    val lifeLine =
        personLifeLine(
            // The server stores these as midnight UTC; the SDK moves them into the local zone.
            born = person.data.premiereDate?.let { serverDate(it) },
            died = person.data.endDate?.let { serverDate(it) },
            place = person.data.productionLocations?.firstOrNull(),
            bornLabel = stringResource(R.string.tally_pages_born),
        )
    Row(
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        modifier =
            modifier
                .padding(horizontal = TallyDimens.marginHorizontal)
                .padding(top = TallyDimens.marginVertical),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).widthIn(max = 560.dp),
        ) {
            Text(
                text = roleLabel(role).tallyUppercase(),
                style = TallyType.label,
                color = TallyColors.muted,
                maxLines = 1,
            )
            Text(
                text = name,
                style = NameStyle,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (lifeLine != null) {
                Text(
                    text = lifeLine.tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val bio = person.data.overview
            if (!bio.isNullOrBlank()) {
                Biography(text = bio, onClick = onOverview, onFocused = onFocused)
            }
            Box(modifier = Modifier.padding(top = 16.dp)) {
                TallyButton(
                    label =
                        stringResource(
                            if (person.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite,
                        ),
                    glyph = stringResource(R.string.fa_heart),
                    onClick = onFavorite,
                    onFocused = onFocused,
                    modifier = Modifier.focusRequester(favoriteFocus),
                    trailing =
                        if (person.favorite) {
                            { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                        } else {
                            null
                        },
                )
            }
        }
        Portrait(
            imageUrl = imageUrl,
            name = name,
            modifier = Modifier.padding(top = PortraitTop),
        )
    }
}

/** The biography, 4 lines. Focusable (3dp accent frame) only when it is cut, and OK then opens it all. */
@Composable
private fun Biography(
    text: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var truncated by remember(text) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    Text(
        text = text,
        style = TallyType.body,
        color = TallyColors.text,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { truncated = it.hasVisualOverflow },
        modifier =
            Modifier
                .drawBehind {
                    if (focused) {
                        // The frame stands off the text so the words never touch it; the text stays on the
                        // column's left edge.
                        val stroke = kotlin.math.floor(TallyDimens.focusBorder.toPx())
                        val out = BioFrameOutset.toPx()
                        drawRect(
                            color = TallyColors.accent,
                            topLeft = Offset(-out + stroke / 2f, stroke / 2f),
                            size = Size(size.width + 2 * out - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                }.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = truncated,
                    onClick = onClick,
                ).padding(vertical = 8.dp),
    )
}

@Composable
private fun Portrait(
    imageUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(PortraitSize)
                .background(TallyColors.screen)
                .border(TallyDimens.hairline, TallyColors.ruleStrong),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { logCoilError(imageUrl, it.result) },
                modifier = Modifier.fillMaxSize().padding(TallyDimens.hairline),
            )
        }
    }
}

@Composable
private fun roleLabel(role: PersonKind?): String =
    stringResource(
        when (role) {
            PersonKind.ACTOR -> R.string.tally_pages_role_actor
            PersonKind.DIRECTOR -> R.string.tally_pages_role_director
            PersonKind.WRITER -> R.string.tally_pages_role_writer
            PersonKind.PRODUCER -> R.string.tally_pages_role_producer
            PersonKind.COMPOSER -> R.string.tally_pages_role_composer
            PersonKind.GUEST_STAR -> R.string.tally_pages_role_guest_star
            else -> R.string.tally_pages_person
        },
    )

private val PortraitSize = 208.dp
private val BioFrameOutset = 10.dp

/** The portrait starts below the app clock at the top right. */
private val PortraitTop = 40.dp

private val NameStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    )
