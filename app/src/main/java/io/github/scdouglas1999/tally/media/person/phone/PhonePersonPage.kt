package io.github.scdouglas1999.tally.media.person.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.detail.PersonViewModel
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.RowLoadingState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneButton
import io.github.scdouglas1999.tally.media.kit.phone.PhoneCardRow
import io.github.scdouglas1999.tally.media.kit.phone.PhoneEmptyState
import io.github.scdouglas1999.tally.media.kit.phone.PhoneItemCard
import io.github.scdouglas1999.tally.media.kit.phone.PhoneLoading
import io.github.scdouglas1999.tally.media.kit.phone.PhoneRowMessage
import io.github.scdouglas1999.tally.media.pages.monthFirstDate
import io.github.scdouglas1999.tally.media.person.TallyPersonRoleViewModel
import io.github.scdouglas1999.tally.media.person.creditKicker
import io.github.scdouglas1999.tally.media.person.roleLabel
import io.github.scdouglas1999.tally.media.series.serverDate
import io.github.scdouglas1999.tally.ui.components.IndicatorSquare
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.phone.PhoneTopBar
import io.github.scdouglas1999.tally.ui.phone.phoneClickable
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.ImageType
import java.time.LocalDate
import java.time.Period

/**
 * A person on a phone: the same [PersonViewModel] and role view model as the TV page (the person, their films, shows
 * and episodes, Discover, favorite). A top bar with back (the name joins it once scrolled); the header with the
 * portrait (2:3, 112dp) at the left and the usual credit, name and a mono line (born and age, or the years) beside
 * it; the biography under it, 4 lines, a tap expands it; FAVORITE; then the TV's rows.
 */
@Composable
fun PhonePersonPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier,
    viewModel: PersonViewModel,
    roleViewModel: TallyPersonRoleViewModel,
) {
    val state by viewModel.state.collectAsState()
    val role by roleViewModel.role.collectAsState()
    LaunchedEffect(destination.itemId) { roleViewModel.load(destination.itemId) }
    val listState = rememberLazyListState()
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val person = (state.person as? DataLoadingState.Success)?.data
    Column(modifier = modifier.fillMaxSize().background(TallyColors.ground)) {
        PhoneTopBar(
            title = if (listState.phoneScrolled) person?.name else null,
            onBack = { viewModel.navigationManager.goBack() },
            scrolled = listState.phoneScrolled,
        )
        when (val st = state.person) {
            is DataLoadingState.Error -> {
                PhoneEmptyState(
                    title = stringResource(R.string.tally_media_error_title),
                    subtitle = st.localizedMessage.ifBlank { stringResource(R.string.tally_media_error_body) },
                )
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                PhoneLoading(Modifier.fillMaxSize())
            }

            is DataLoadingState.Success -> {
                val p = st.data
                val name = p.name ?: p.id.toString()
                val maxItems = preferences.appPreferences.homePagePreferences.maxItemsPerRow
                val discovered = remember(state.discovered, maxItems) { state.discovered.take(maxItems) }
                val onClickItem = { item: BaseItem -> viewModel.navigationManager.navigateTo(item.destination()) }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottom + PhoneDimens.rowGap),
                    verticalArrangement = Arrangement.spacedBy(PhoneDimens.rowGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") {
                        PersonHeader(
                            person = p,
                            name = name,
                            roleText = roleLabel(role),
                            onFavorite = { viewModel.setFavorite(!p.favorite) },
                        )
                    }
                    listOf(
                        R.string.tally_pages_type_films to state.movies,
                        R.string.tally_pages_type_shows to state.series,
                        R.string.tally_pages_type_episodes to state.episodes,
                    ).forEach { (title, rowState) ->
                        item(key = "row-$title") {
                            CreditRow(title = stringResource(title), state = rowState, onClickItem = onClickItem)
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
                                onClickItem = { _, item -> viewModel.navigationManager.navigateTo(item.destination) },
                                onLongClickItem = { _, _ -> },
                                onCardFocus = {},
                                focusRequester = remember { FocusRequester() },
                                enableViewMore = state.discovered.size > discovered.size,
                                onClickViewMore = {
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
}

/** A row of one kind of credit; hidden when empty (as on the TV). No item menu and no play, as upstream's rows. */
@Composable
private fun CreditRow(
    title: String,
    state: RowLoadingState,
    onClickItem: (BaseItem) -> Unit,
) {
    when (state) {
        RowLoadingState.Pending, RowLoadingState.Loading -> {
            PhoneRowMessage(title = title, message = stringResource(R.string.tally_media_loading))
        }

        is RowLoadingState.Error -> {
            PhoneRowMessage(title = title, message = state.localizedMessage, failure = true)
        }

        is RowLoadingState.Success -> {
            if (state.items.isNotEmpty()) {
                PhoneCardRow(
                    title = title,
                    items = state.items,
                    key = { index, item -> "$index-${item?.id}" },
                ) { item, _ ->
                    PhoneItemCard(
                        item = item,
                        kicker = item?.let { creditKicker(it) },
                        onClick = { item?.let(onClickItem) },
                        onLongClick = {},
                    )
                }
            }
        }
    }
}

private val PortraitWidth = 112.dp

@Composable
private fun PersonHeader(
    person: BaseItem,
    name: String,
    roleText: String,
    onFavorite: () -> Unit,
) {
    val imageService = LocalImageUrlService.current
    val imageUrl = remember(person.id) { imageService.getItemImageUrl(itemId = person.id, imageType = ImageType.PRIMARY) }
    val born = person.data.premiereDate?.let { serverDate(it) }
    val died = person.data.endDate?.let { serverDate(it) }
    val lifeLine = phoneLifeLine(born, died)
    val place = person.data.productionLocations?.firstOrNull()
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneDimens.margin).padding(top = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(width = PortraitWidth, height = PortraitWidth * 3 / 2)
                        .background(TallyColors.screen)
                        .border(PhoneDimens.hairline, TallyColors.ruleStrong),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        onError = { logCoilError(imageUrl, it.result) },
                        modifier = Modifier.fillMaxSize().padding(PhoneDimens.hairline),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            ) {
                Text(
                    text = roleText.tallyUppercase(),
                    style = PhoneType.label,
                    color = TallyColors.muted,
                    maxLines = 1,
                )
                Text(
                    text = name,
                    style = PhoneType.title,
                    color = TallyColors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lifeLine != null) {
                    Text(
                        text = lifeLine.tallyUppercase(),
                        style = PhoneType.meta,
                        color = TallyColors.textSecondary,
                        maxLines = 2,
                    )
                }
                if (!place.isNullOrBlank()) {
                    Text(
                        text = place.tallyUppercase(),
                        style = PhoneType.meta,
                        color = TallyColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val bio = person.data.overview
        if (!bio.isNullOrBlank()) Biography(bio)
        PhoneButton(
            label =
                stringResource(if (person.favorite) R.string.tally_media_favorited else R.string.tally_media_favorite),
            glyph = stringResource(R.string.fa_heart),
            onClick = onFavorite,
            trailing =
                if (person.favorite) {
                    { IndicatorSquare(color = TallyColors.accent, size = 8.dp) }
                } else {
                    null
                },
        )
    }
}

/** `BORN AUG 20, 1974 · AGE 52`, or `1931–2016 · DIED JAN 5, 2016`. */
@Composable
private fun phoneLifeLine(
    born: LocalDate?,
    died: LocalDate?,
): String? {
    val bornText = born?.let { stringResource(R.string.tally_pages_born) + " " + monthFirstDate(it) }
    return when {
        died != null -> {
            listOfNotNull(
                born?.let { "${it.year}–${died.year}" },
                stringResource(R.string.tally_phone_browse_died, monthFirstDate(died)),
            ).joinToString(" · ")
        }

        born != null -> {
            val age = Period.between(born, LocalDate.now()).years
            listOfNotNull(bornText, stringResource(R.string.tally_phone_browse_age, age)).joinToString(" · ")
        }

        else -> {
            null
        }
    }
}

/** The biography, 4 lines; a tap shows it all (and folds it again). */
@Composable
private fun Biography(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = PhoneType.body,
        color = TallyColors.text,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (cut || expanded) Modifier.phoneClickable { expanded = !expanded } else Modifier),
    )
}
