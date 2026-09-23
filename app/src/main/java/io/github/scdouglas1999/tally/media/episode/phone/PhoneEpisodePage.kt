package io.github.scdouglas1999.tally.media.episode.phone

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.detail.episode.EpisodeViewModel
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.nav.Destination
import io.github.scdouglas1999.tally.media.episode.EpisodeExtras
import io.github.scdouglas1999.tally.media.episode.chapterImage
import io.github.scdouglas1999.tally.media.episode.directorLine
import io.github.scdouglas1999.tally.media.episode.episodeEnds
import io.github.scdouglas1999.tally.media.episode.episodeMeta
import io.github.scdouglas1999.tally.media.episode.seasonCardKicker
import io.github.scdouglas1999.tally.media.kit.ItemDialogsState
import io.github.scdouglas1999.tally.media.kit.LandscapeCard
import io.github.scdouglas1999.tally.media.kit.formatPosition
import io.github.scdouglas1999.tally.media.kit.rememberWideImageUrl
import io.github.scdouglas1999.tally.media.kit.resumePercent
import io.github.scdouglas1999.tally.media.kit.techBoxes
import io.github.scdouglas1999.tally.media.movie.phone.PhoneAbout
import io.github.scdouglas1999.tally.media.movie.phone.PhoneAction
import io.github.scdouglas1999.tally.media.movie.phone.PhoneDetailBackdrop
import io.github.scdouglas1999.tally.media.movie.phone.PhoneDetailHeading
import io.github.scdouglas1999.tally.media.movie.phone.PhoneItemActions
import io.github.scdouglas1999.tally.media.movie.phone.PhoneLandscapeWidth
import io.github.scdouglas1999.tally.media.movie.phone.PhoneMediaRow
import io.github.scdouglas1999.tally.media.movie.phone.PhoneStatusBarGround
import io.github.scdouglas1999.tally.media.movie.phone.phonePeopleRow
import io.github.scdouglas1999.tally.media.series.episodeCode
import io.github.scdouglas1999.tally.ui.phone.LocalPhoneContentPadding
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The episode page on a phone: the episode's still as the backdrop, `SEVERANCE · S1 · E2`, the title, meta, PLAY /
 * RESUME and the action buttons (WATCHED, FAVORITE, EPISODES, MORE), the overview, director and format chips, then the
 * TV page's rows (cast & crew, chapters, more from the season). Same view models, dialogs and menus as the TV page.
 */
@Composable
fun PhoneEpisodeLoaded(
    episode: BaseItem,
    extras: EpisodeExtras,
    chosenStreams: ChosenStreams?,
    canDelete: Boolean,
    dialogs: ItemDialogsState,
    contextActions: ContextMenuActions,
    viewModel: EpisodeViewModel,
) {
    val episodeNow by rememberUpdatedState(episode)
    val streamsNow by rememberUpdatedState(chosenStreams)
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val people = if (extras.itemId == episode.id) extras.people else emptyList()
    val chapters = remember(episode.id, episode.data.chapters) { Chapter.fromDto(episode.data) }
    val currentIndex = extras.seasonEpisodes.indexOfFirst { it.id == episode.id }
    val moreFromSeason = if (currentIndex >= 0) extras.seasonEpisodes.drop(currentIndex + 1) else emptyList()

    fun openEpisodeMenu(fromLongClick: Boolean) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
                item = episodeNow,
                chosenStreams = streamsNow,
                showGoTo = false,
                showStreamChoices = true,
                canDelete = canDelete,
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
            )
    }

    fun openItemMenu(item: BaseItem) {
        dialogs.contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = item,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = false,
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
            )
    }

    val special = stringResource(R.string.tally_series_special)
    val season = episode.data.parentIndexNumber
    val number = episode.indexNumber
    val code =
        if (season != null && season > 0 && number != null && episode.data.indexNumberEnd == null) {
            "S$season · E$number"
        } else {
            episodeCode(season, number, episode.data.indexNumberEnd, special)
        }
    val seasonLabel =
        when (season) {
            0 -> stringResource(R.string.tally_series_specials)
            null -> episode.data.seasonName ?: ""
            else -> stringResource(R.string.tally_series_season_n, season)
        }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val bottom = LocalPhoneContentPadding.current.calculateBottomPadding()
    val stillUrl = rememberWideImageUrl(episode)

    Box(modifier = Modifier.fillMaxSize().background(TallyColors.ground)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottom + 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "backdrop") {
                PhoneDetailBackdrop(imageUrl = stillUrl, onBack = { backDispatcher?.onBackPressed() })
            }
            item(key = "heading") {
                PhoneDetailHeading(
                    kicker = listOfNotNull(episode.data.seriesName, code).joinToString(" · "),
                    kickerColor = TallyColors.accent,
                    title = episode.name ?: "",
                    logoUrl = null,
                    meta = episodeMeta(episode),
                    ends = episodeEnds(episode),
                )
            }
            item(key = "actions") {
                val seriesId = episode.data.seriesId
                val seasonId = episode.data.seasonId
                PhoneItemActions(
                    item = episode,
                    extra = emptyList(),
                    trailing =
                        if (seriesId != null && seasonId != null) {
                            listOf(
                                PhoneAction(
                                    key = "episodes",
                                    glyph = stringResource(R.string.fa_list_ul),
                                    label = stringResource(R.string.tally_signin_episodes),
                                    onClick = {
                                        viewModel.navigateTo(
                                            Destination.SeriesOverview(
                                                seriesId,
                                                BaseItemKind.SERIES,
                                                SeasonEpisodeIds(
                                                    seasonId,
                                                    episode.data.parentIndexNumber,
                                                    episode.id,
                                                    episode.indexNumber,
                                                ),
                                            ),
                                        )
                                    },
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    onPlay = { position ->
                        viewModel.navigateTo(Destination.Playback(episode.id, position.inWholeMilliseconds))
                    },
                    onWatch = { viewModel.setWatched(episode.id, !episode.played) },
                    onFavorite = { viewModel.setFavorite(episode.id, !episode.favorite) },
                    onMore = { openEpisodeMenu(fromLongClick = false) },
                )
            }
            item(key = "about") {
                PhoneAbout(
                    overview = episode.data.overview,
                    caption = directorLine(episode),
                    tech =
                        techBoxes(
                            chosenStreams?.source ?: episode.data.mediaSources?.firstOrNull(),
                            chosenStreams?.videoStream,
                            chosenStreams?.audioStream,
                        ),
                )
            }
            phonePeopleRow(
                people = people,
                onOpen = { viewModel.navigateTo(Destination.MediaItem(it.id, BaseItemKind.PERSON)) },
                onMenu = { person ->
                    dialogs.contextMenu =
                        ContextMenu.ForPerson(
                            fromLongClick = true,
                            person = person,
                            actions =
                                PersonContextActions(
                                    navigateTo = viewModel::navigateTo,
                                    onClickFavorite = viewModel::setFavorite,
                                ),
                        )
                },
            )
            if (chapters.isNotEmpty()) {
                item(key = "chapters") {
                    PhoneMediaRow(
                        title = stringResource(R.string.tally_media_chapters),
                        items = chapters,
                        key = { _, chapter -> chapter.index },
                    ) { chapter, _ ->
                        val play = {
                            viewModel.navigateTo(Destination.Playback(episode.id, chapter.position.inWholeMilliseconds))
                        }
                        LandscapeCard(
                            title = chapter.name ?: "",
                            kicker =
                                stringResource(
                                    R.string.tally_media_chapter,
                                    chapter.index + 1,
                                    formatPosition(chapter.position.inWholeMilliseconds * 10_000L),
                                ),
                            imageUrl = chapterImage(chapter),
                            onClick = play,
                            onLongClick = { openEpisodeMenu(fromLongClick = true) },
                            onPlay = play,
                            width = PhoneLandscapeWidth,
                        )
                    }
                }
            }
            if (moreFromSeason.isNotEmpty()) {
                item(key = "season") {
                    PhoneMediaRow(
                        title = stringResource(R.string.tally_series_more_from, seasonLabel),
                        items = moreFromSeason,
                        key = { _, item -> item.id },
                    ) { item, _ ->
                        val percent =
                            resumePercent(
                                item.data.userData?.playbackPositionTicks ?: 0L,
                                item.data.runTimeTicks ?: 0L,
                            )
                        LandscapeCard(
                            title = item.name ?: "",
                            kicker = seasonCardKicker(item),
                            imageUrl = rememberWideImageUrl(item),
                            progress = if (!item.played && percent in 1..99) percent / 100f else null,
                            favorite = item.favorite,
                            onClick = { viewModel.navigateTo(Destination.MediaItem(item)) },
                            onLongClick = { openItemMenu(item) },
                            onPlay = { viewModel.navigateTo(Destination.Playback(item)) },
                            width = PhoneLandscapeWidth,
                        )
                    }
                }
            }
        }
        PhoneStatusBarGround(visible = scrolled)
    }
}
