package io.github.scdouglas1999.tally.ui.settings

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.components.ChosenTrackResult
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.MusicContextActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.QueueContextActions
import com.github.damontecres.wholphin.ui.components.resourceFor
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.SimpleMediaStream
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.util.supportedPlayableTypes
import com.github.damontecres.wholphin.util.supportedShufflableTypes
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/** Upstream's long-press guard for a menu opened by holding OK. */
private const val LONG_PRESS_WAIT_MS = 1000L

// ---------------------------------------------------------------------------------------------------------------
// Which actions an item's menu offers (upstream's buildContextMenuItems, as data)
// ---------------------------------------------------------------------------------------------------------------

/** The actions of an item's context menu, in upstream's order. */
internal enum class ItemAction {
    GO_TO,
    RESUME,
    PLAY_FROM_START,
    PLAY,
    SHUFFLE,
    CHOOSE_AUDIO,
    CHOOSE_SUBTITLES,
    CHOOSE_VERSION,
    ADD_TO_QUEUE,
    REMOVE_FROM_PLAYLIST,
    ADD_TO_PLAYLIST,
    DELETE,
    REMOVE_CONTINUE_WATCHING,
    REMOVE_NEXT_UP,
    MARK_WATCHED,
    MARK_UNWATCHED,
    FAVORITE,
    UNFAVORITE,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    GO_TO_SERIES,
    MEDIA_INFORMATION,
    CLEAR_TRACK_CHOICES,
    PLAY_WITH,
    SEND_MEDIA_INFO,
}

/** What decides an item's menu: the item's own state plus the flags the calling page passes. */
internal data class ItemMenuFacts(
    val type: BaseItemKind,
    val resumable: Boolean,
    val played: Boolean,
    val favorite: Boolean,
    val hasSeries: Boolean,
    val hasAlbum: Boolean,
    val hasArtist: Boolean,
    val sourceCount: Int,
    val audioCount: Int,
    val subtitleCount: Int,
    val showGoTo: Boolean,
    val showStreamChoices: Boolean,
    val canDelete: Boolean,
    val canRemoveContinueWatching: Boolean,
    val canRemoveNextUp: Boolean,
    val showRemoveFromPlaylist: Boolean,
    val canClearChosenStreams: Boolean,
)

internal fun itemMenuFacts(
    dto: BaseItemDto,
    sourceId: UUID?,
    showGoTo: Boolean,
    showStreamChoices: Boolean,
    canDelete: Boolean,
    canRemoveContinueWatching: Boolean,
    canRemoveNextUp: Boolean,
    showRemoveFromPlaylist: Boolean,
    canClearChosenStreams: Boolean,
): ItemMenuFacts {
    val sources = dto.mediaSources.orEmpty()
    val source =
        sourceId?.let { id -> sources.firstOrNull { it.id?.toUUIDOrNull() == id } } ?: sources.firstOrNull()
    val streams = source?.mediaStreams.orEmpty()
    return ItemMenuFacts(
        type = dto.type,
        resumable = (dto.userData?.playbackPositionTicks ?: 0L) > 0L,
        played = dto.userData?.played ?: false,
        favorite = dto.userData?.isFavorite ?: false,
        hasSeries = dto.seriesId != null,
        hasAlbum = dto.albumId != null,
        hasArtist = dto.artistItems?.firstOrNull() != null,
        sourceCount = sources.size,
        audioCount = streams.count { it.type == MediaStreamType.AUDIO },
        subtitleCount = streams.count { it.type == MediaStreamType.SUBTITLE },
        showGoTo = showGoTo,
        showStreamChoices = showStreamChoices,
        canDelete = canDelete,
        canRemoveContinueWatching = canRemoveContinueWatching,
        canRemoveNextUp = canRemoveNextUp,
        showRemoveFromPlaylist = showRemoveFromPlaylist,
        canClearChosenStreams = canClearChosenStreams,
    )
}

/** Upstream's menu, entry for entry and in the same order. */
internal fun itemMenuActions(facts: ItemMenuFacts): List<ItemAction> =
    buildList {
        val playable = facts.type in supportedPlayableTypes
        // Songs do not show Go to.
        if (facts.showGoTo && facts.type != BaseItemKind.AUDIO) add(ItemAction.GO_TO)
        if (playable) {
            if (facts.resumable) {
                add(ItemAction.RESUME)
                add(ItemAction.PLAY_FROM_START)
            } else {
                add(ItemAction.PLAY)
            }
        }
        if (facts.type in supportedShufflableTypes) add(ItemAction.SHUFFLE)
        if (facts.showStreamChoices && facts.sourceCount > 0) {
            if (facts.audioCount > 1) add(ItemAction.CHOOSE_AUDIO)
            if (facts.subtitleCount > 0) add(ItemAction.CHOOSE_SUBTITLES)
            if (facts.sourceCount > 1) add(ItemAction.CHOOSE_VERSION)
        }
        if (facts.type == BaseItemKind.MUSIC_ALBUM || facts.type == BaseItemKind.AUDIO) add(ItemAction.ADD_TO_QUEUE)
        if (facts.showRemoveFromPlaylist) add(ItemAction.REMOVE_FROM_PLAYLIST)
        add(ItemAction.ADD_TO_PLAYLIST)
        if (facts.canDelete) add(ItemAction.DELETE)
        if (facts.canRemoveContinueWatching && !facts.played && facts.resumable) {
            add(ItemAction.REMOVE_CONTINUE_WATCHING)
        }
        if (facts.canRemoveNextUp && facts.type == BaseItemKind.EPISODE && facts.hasSeries) {
            add(ItemAction.REMOVE_NEXT_UP)
        }
        add(if (facts.played) ItemAction.MARK_UNWATCHED else ItemAction.MARK_WATCHED)
        add(if (facts.favorite) ItemAction.UNFAVORITE else ItemAction.FAVORITE)
        if (facts.hasAlbum) add(ItemAction.GO_TO_ALBUM)
        if (facts.hasArtist) add(ItemAction.GO_TO_ARTIST)
        if (facts.hasSeries) add(ItemAction.GO_TO_SERIES)
        if (facts.sourceCount > 0) add(ItemAction.MEDIA_INFORMATION)
        if (facts.showStreamChoices && facts.canClearChosenStreams) add(ItemAction.CLEAR_TRACK_CHOICES)
        if (playable) add(ItemAction.PLAY_WITH)
        if (facts.sourceCount > 0) add(ItemAction.SEND_MEDIA_INFO)
    }

// ---------------------------------------------------------------------------------------------------------------
// The menu
// ---------------------------------------------------------------------------------------------------------------

/**
 * Tally [com.github.damontecres.wholphin.ui.components.ContextMenuDialog]: the panel family with the item's title
 * as the kicker and upstream's actions as rows, in upstream's order; Delete in red and confirmed first.
 */
@Composable
fun TallyContextMenuDialog(
    onDismissRequest: () -> Unit,
    contextMenu: ContextMenu,
    getMediaSource: ((dto: BaseItemDto, itemPlayback: ItemPlayback?) -> MediaSourceInfo?)?,
    preferredSubtitleLanguage: String?,
) {
    when (contextMenu) {
        is ContextMenu.ForBaseItem -> {
            ItemMenu(onDismissRequest, contextMenu, getMediaSource, preferredSubtitleLanguage)
        }

        is ContextMenu.ForPerson -> {
            PersonMenu(onDismissRequest, contextMenu, contextMenu.actions)
        }

        is ContextMenu.ForMusic -> {
            MusicMenu(onDismissRequest, contextMenu, contextMenu.actions)
        }

        is ContextMenu.ForQueue -> {
            QueueMenu(onDismissRequest, contextMenu, contextMenu.actions)
        }
    }
}

/** A menu panel; a menu opened by a long press ignores the release of the held OK key (upstream's guard). */
@Composable
private fun MenuPanel(
    title: String,
    entries: List<PanelEntry>,
    fromLongClick: Boolean,
    onDismissRequest: () -> Unit,
) {
    var waiting by remember { mutableStateOf(fromLongClick) }
    LaunchedEffect(fromLongClick) {
        if (fromLongClick) delay(LONG_PRESS_WAIT_MS)
        waiting = false
    }
    TallyPanelWindow(onDismissRequest = onDismissRequest) {
        TallyListPanel(
            title = title,
            entries = entries,
            onBack = onDismissRequest,
            waiting = waiting,
            onWaitReleased = { waiting = false },
            initialIndex = 0,
        )
    }
}

private sealed interface SubMenu {
    data class Streams(
        val type: MediaStreamType,
        val source: MediaSourceInfo,
    ) : SubMenu

    data object Version : SubMenu

    data object PlayWith : SubMenu

    data object Delete : SubMenu
}

@Composable
private fun ItemMenu(
    onDismissRequest: () -> Unit,
    menu: ContextMenu.ForBaseItem,
    getMediaSource: ((dto: BaseItemDto, itemPlayback: ItemPlayback?) -> MediaSourceInfo?)?,
    preferredSubtitleLanguage: String?,
) {
    val item = menu.item
    val chosen = menu.chosenStreams
    val actions = menu.actions
    val resources = LocalResources.current
    var subMenu by remember { mutableStateOf<SubMenu?>(null) }
    val itemActions =
        remember(item, chosen, menu) {
            itemMenuActions(
                itemMenuFacts(
                    dto = item.data,
                    sourceId = chosen?.source?.id?.toUUIDOrNull(),
                    showGoTo = menu.showGoTo,
                    showStreamChoices = menu.showStreamChoices,
                    canDelete = menu.canDelete,
                    canRemoveContinueWatching = menu.canRemoveContinueWatching,
                    canRemoveNextUp = menu.canRemoveNextUp,
                    showRemoveFromPlaylist = menu.showRemoveFromPlaylist,
                    canClearChosenStreams = chosen.let { it?.itemPlayback != null || it?.plc != null },
                ),
            )
        }

    fun openStreams(type: MediaStreamType) {
        getMediaSource?.invoke(item.data, chosen?.itemPlayback)?.let { subMenu = SubMenu.Streams(type, it) }
    }

    val entries =
        itemActions.map { action ->
            val label = itemActionLabel(resources, action)
            val stays = action.keepsMenuOpen()
            panelItem(
                label = label,
                destructive = action == ItemAction.DELETE,
                onClick = {
                    if (!stays) onDismissRequest()
                    when (action) {
                        ItemAction.GO_TO -> {
                            actions.onClickGoTo(item)
                        }

                        ItemAction.RESUME -> {
                            actions.navigateTo(Destination.Playback(item.id, item.playbackPosition.inWholeMilliseconds))
                        }

                        ItemAction.PLAY_FROM_START, ItemAction.PLAY -> {
                            actions.navigateTo(Destination.Playback(item.id, 0L))
                        }

                        ItemAction.SHUFFLE -> {
                            actions.navigateTo(Destination.PlaybackList(itemId = item.id, shuffle = true))
                        }

                        ItemAction.CHOOSE_AUDIO -> {
                            openStreams(MediaStreamType.AUDIO)
                        }

                        ItemAction.CHOOSE_SUBTITLES -> {
                            openStreams(MediaStreamType.SUBTITLE)
                        }

                        ItemAction.CHOOSE_VERSION -> {
                            subMenu = SubMenu.Version
                        }

                        ItemAction.ADD_TO_QUEUE -> {
                            actions.onClickAddToQueue(item)
                        }

                        ItemAction.REMOVE_FROM_PLAYLIST -> {
                            actions.onRemoveFromPlaylist(menu.index, item.id)
                        }

                        ItemAction.ADD_TO_PLAYLIST -> {
                            actions.onClickAddPlaylist(item.id)
                        }

                        ItemAction.DELETE -> {
                            subMenu = SubMenu.Delete
                        }

                        ItemAction.REMOVE_CONTINUE_WATCHING -> {
                            actions.onClickWatch(item.id, false)
                        }

                        ItemAction.REMOVE_NEXT_UP -> {
                            actions.onClickRemoveFromNextUp(item)
                        }

                        ItemAction.MARK_WATCHED, ItemAction.MARK_UNWATCHED -> {
                            actions.onClickWatch(item.id, !item.played)
                        }

                        ItemAction.FAVORITE, ItemAction.UNFAVORITE -> {
                            actions.onClickFavorite(item.id, !item.favorite)
                        }

                        ItemAction.GO_TO_ALBUM -> {
                            item.data.albumId?.let {
                                actions.navigateTo(Destination.MediaItem(it, BaseItemKind.MUSIC_ALBUM, null))
                            }
                        }

                        ItemAction.GO_TO_ARTIST -> {
                            item.data.artistItems?.firstOrNull()?.id?.let {
                                actions.navigateTo(Destination.MediaItem(it, BaseItemKind.MUSIC_ARTIST, null))
                            }
                        }

                        ItemAction.GO_TO_SERIES -> {
                            item.data.seriesId?.let {
                                actions.navigateTo(Destination.MediaItem(it, BaseItemKind.SERIES, null))
                            }
                        }

                        ItemAction.MEDIA_INFORMATION -> {
                            actions.onShowOverview(item)
                        }

                        ItemAction.CLEAR_TRACK_CHOICES -> {
                            actions.onClearChosenStreams(chosen)
                        }

                        ItemAction.PLAY_WITH -> {
                            subMenu = SubMenu.PlayWith
                        }

                        ItemAction.SEND_MEDIA_INFO -> {
                            actions.onSendMediaInfo(item.id)
                        }
                    }
                },
            )
        }
    MenuPanel(
        title = item.title ?: "",
        entries = entries,
        fromLongClick = menu.fromLongClick,
        onDismissRequest = onDismissRequest,
    )

    when (val sub = subMenu) {
        is SubMenu.Streams -> {
            StreamsMenu(
                resources = resources,
                item = item,
                type = sub.type,
                source = sub.source,
                chosen = chosen,
                preferredSubtitleLanguage = preferredSubtitleLanguage,
                actions = actions,
                onClose = { subMenu = null },
                onDismissRequest = onDismissRequest,
            )
        }

        SubMenu.Version -> {
            VersionMenu(
                resources = resources,
                item = item,
                chosenSourceId = chosen?.source?.id?.toUUIDOrNull(),
                actions = actions,
                onClose = { subMenu = null },
                onDismissRequest = onDismissRequest,
            )
        }

        SubMenu.PlayWith -> {
            PlayWithMenu(
                resources = resources,
                item = item,
                actions = actions,
                onClose = { subMenu = null },
                onDismissRequest = onDismissRequest,
            )
        }

        SubMenu.Delete -> {
            TallyConfirmDeleteDialog(
                itemTitle = item.title ?: "",
                onCancel = { subMenu = null },
                onConfirm = {
                    actions.onDeleteItem(item)
                    onDismissRequest()
                },
            )
        }

        null -> {}
    }
}

/** Actions that open a second panel (or a confirmation) instead of closing the menu. */
private fun ItemAction.keepsMenuOpen(): Boolean =
    when (this) {
        ItemAction.CHOOSE_AUDIO,
        ItemAction.CHOOSE_SUBTITLES,
        ItemAction.CHOOSE_VERSION,
        ItemAction.DELETE,
        ItemAction.PLAY_WITH,
        -> true

        else -> false
    }

private fun itemActionLabel(
    resources: Resources,
    action: ItemAction,
): String =
    when (action) {
        ItemAction.GO_TO -> resources.getString(R.string.tally_menu_go_to)
        ItemAction.RESUME -> resources.getString(R.string.resume)
        ItemAction.PLAY_FROM_START -> resources.getString(R.string.tally_menu_play_from_start)
        ItemAction.PLAY -> resources.getString(R.string.play)
        ItemAction.SHUFFLE -> resources.getString(R.string.shuffle)
        ItemAction.CHOOSE_AUDIO -> chooseLabel(resources, MediaStreamType.AUDIO)
        ItemAction.CHOOSE_SUBTITLES -> chooseLabel(resources, MediaStreamType.SUBTITLE)
        ItemAction.CHOOSE_VERSION -> resources.getString(R.string.choose_stream, resources.getString(R.string.version))
        ItemAction.ADD_TO_QUEUE -> resources.getString(R.string.add_to_queue)
        ItemAction.REMOVE_FROM_PLAYLIST -> resources.getString(R.string.remove_from_playlist)
        ItemAction.ADD_TO_PLAYLIST -> resources.getString(R.string.add_to_playlist)
        ItemAction.DELETE -> resources.getString(R.string.delete)
        ItemAction.REMOVE_CONTINUE_WATCHING -> resources.getString(R.string.remove_continue_watching)
        ItemAction.REMOVE_NEXT_UP -> resources.getString(R.string.remove_next_up)
        ItemAction.MARK_WATCHED -> resources.getString(R.string.tally_menu_mark_watched)
        ItemAction.MARK_UNWATCHED -> resources.getString(R.string.tally_menu_mark_unwatched)
        ItemAction.FAVORITE -> resources.getString(R.string.add_favorite)
        ItemAction.UNFAVORITE -> resources.getString(R.string.remove_favorite)
        ItemAction.GO_TO_ALBUM -> resources.getString(R.string.go_to_album)
        ItemAction.GO_TO_ARTIST -> resources.getString(R.string.go_to_artist)
        ItemAction.GO_TO_SERIES -> resources.getString(R.string.go_to_series)
        ItemAction.MEDIA_INFORMATION -> resources.getString(R.string.tally_menu_media_information)
        ItemAction.CLEAR_TRACK_CHOICES -> resources.getString(R.string.clear_track_choices)
        ItemAction.PLAY_WITH -> resources.getString(R.string.play_with)
        ItemAction.SEND_MEDIA_INFO -> resources.getString(R.string.send_media_info_log_to_server)
    }

private fun chooseLabel(
    resources: Resources,
    type: MediaStreamType,
): String = resources.getString(R.string.choose_stream, resources.getString(resourceFor(type)))

/** Upstream's audio / subtitle chooser: None and Only forced first for subtitles, the current track marked. */
@Composable
private fun StreamsMenu(
    resources: Resources,
    item: BaseItem,
    type: MediaStreamType,
    source: MediaSourceInfo,
    chosen: ChosenStreams?,
    preferredSubtitleLanguage: String?,
    actions: ContextMenuActions,
    onClose: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val currentIndex =
        if (type == MediaStreamType.AUDIO) chosen?.audioStream?.index else chosen?.subtitleStream?.index

    fun choose(trackIndex: Int) {
        onClose()
        actions.onChooseTracks(ChosenTrackResult(item, type, trackIndex, chosen?.itemPlayback))
        onDismissRequest()
    }
    val streams = source.mediaStreams.orEmpty().filter { it.type == type }
    val entries =
        buildList {
            if (type == MediaStreamType.SUBTITLE) {
                add(
                    panelItem(
                        label = resources.getString(R.string.none),
                        marked = currentIndex == null,
                        onClick = { choose(TrackIndex.DISABLED) },
                    ),
                )
                add(
                    panelItem(
                        label = resources.getString(R.string.only_forced_subtitles),
                        marked = currentIndex == TrackIndex.ONLY_FORCED,
                        onClick = { choose(TrackIndex.ONLY_FORCED) },
                    ),
                )
                if (streams.isNotEmpty()) add(PanelEntry.Divider)
            }
            val ordered =
                if (type == MediaStreamType.SUBTITLE && preferredSubtitleLanguage.isNotNullOrBlank()) {
                    streams.sortedByDescending { it.language != null && it.language == preferredSubtitleLanguage }
                } else {
                    streams
                }
            ordered.forEach { stream ->
                val simple = SimpleMediaStream.from(resources, stream, true)
                add(
                    panelItem(
                        label = simple.streamTitle ?: simple.displayTitle,
                        supporting = if (simple.streamTitle != null) simple.displayTitle else null,
                        marked = currentIndex == stream.index,
                        onClick = { choose(stream.index) },
                    ),
                )
            }
        }
    TallyPanelWindow(onDismissRequest = onClose) {
        TallyListPanel(title = chooseLabel(resources, type), entries = entries, onBack = onClose)
    }
}

/** Upstream's version chooser: name, video and bitrate under it, runtime at the right, current one marked. */
@Composable
private fun VersionMenu(
    resources: Resources,
    item: BaseItem,
    chosenSourceId: UUID?,
    actions: ContextMenuActions,
    onClose: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sources =
        item.data.mediaSources
            .orEmpty()
            .filter { it.id.isNotNullOrBlank() }
    val entries =
        sources.map { source ->
            val uuid = source.id?.toUUIDOrNull()
            val video = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
            val detail =
                buildList {
                    video?.displayTitle?.let(::add)
                    source.bitrate?.let(::formatBitrate)?.let(::add)
                }.joinToString(", ")
            panelItem(
                label = source.name ?: source.path ?: source.id ?: "",
                supporting = detail.ifEmpty { null },
                trailing =
                    source.runTimeTicks
                        ?.ticks
                        ?.roundMinutes
                        ?.toString(),
                marked = uuid != null && uuid == chosenSourceId,
                onClick = {
                    onClose()
                    actions.onChooseVersion(item, source)
                    onDismissRequest()
                },
            )
        }
    TallyPanelWindow(onDismissRequest = onClose) {
        TallyListPanel(
            title = resources.getString(R.string.choose_stream, resources.getString(R.string.version)),
            entries = entries,
            onBack = onClose,
        )
    }
}

/** Upstream's "Play with": each player backend, then forced transcoding. */
@Composable
private fun PlayWithMenu(
    resources: Resources,
    item: BaseItem,
    actions: ContextMenuActions,
    onClose: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    fun play(
        transcode: Boolean,
        backend: PlayerBackend?,
    ) {
        onClose()
        onDismissRequest()
        actions.navigateTo(
            Destination.Playback(
                itemId = item.id,
                positionMs = item.resumeMs,
                forceTranscoding = transcode,
                backend = backend,
            ),
        )
    }
    val backends =
        PlayerBackend.entries
            .filterNot { it == PlayerBackend.UNRECOGNIZED }
            .zip(resources.getStringArray(R.array.player_backend_options))
            .filterNot { it.first == PlayerBackend.PREFER_MPV }
    val entries =
        backends.map { (backend, title) -> panelItem(label = title, onClick = { play(false, backend) }) } +
            panelItem(label = resources.getString(R.string.transcoding), onClick = { play(true, null) })
    TallyPanelWindow(onDismissRequest = onClose) {
        TallyListPanel(title = stringResource(R.string.play_with), entries = entries, onBack = onClose, initialIndex = 0)
    }
}

@Composable
private fun PersonMenu(
    onDismissRequest: () -> Unit,
    menu: ContextMenu.ForPerson,
    actions: PersonContextActions,
) {
    val person = menu.person
    val entries =
        listOf(
            panelItem(
                label = stringResource(R.string.tally_menu_go_to),
                onClick = {
                    onDismissRequest()
                    actions.navigateTo(Destination.MediaItem(person.id, BaseItemKind.PERSON, null))
                },
            ),
            panelItem(
                label = stringResource(if (person.favorite) R.string.remove_favorite else R.string.add_favorite),
                onClick = {
                    onDismissRequest()
                    actions.onClickFavorite(person.id, !person.favorite)
                },
            ),
        )
    MenuPanel(
        title = person.name ?: "",
        entries = entries,
        fromLongClick = menu.fromLongClick,
        onDismissRequest = onDismissRequest,
    )
}

@Composable
private fun MusicMenu(
    onDismissRequest: () -> Unit,
    menu: ContextMenu.ForMusic,
    actions: MusicContextActions,
) {
    val item = menu.item
    val index = menu.index
    var confirmDelete by remember { mutableStateOf(false) }

    fun then(block: () -> Unit): () -> Unit =
        {
            onDismissRequest()
            block()
        }
    val entries =
        buildList {
            add(panelItem(stringResource(R.string.play), then { actions.onClickPlay(index, item) }))
            add(panelItem(stringResource(R.string.play_next), then { actions.onClickPlayNext(index, item) }))
            if (menu.canRemoveFromQueue) {
                add(
                    panelItem(
                        stringResource(R.string.remove_from_queue),
                        then { actions.onClickRemoveFromQueue(index, item) },
                    ),
                )
            } else {
                add(panelItem(stringResource(R.string.add_to_queue), then { actions.onClickAddToQueue(item) }))
            }
            if (menu.showRemoveFromPlaylist) {
                add(
                    panelItem(
                        stringResource(R.string.remove_from_playlist),
                        then { actions.onRemoveFromPlaylist(index, item.id) },
                    ),
                )
            }
            add(panelItem(stringResource(R.string.add_to_playlist), then { actions.onClickAddPlaylist(item.id) }))
            if (menu.canDelete) {
                add(panelItem(stringResource(R.string.delete), { confirmDelete = true }, destructive = true))
            }
            add(
                panelItem(
                    stringResource(if (item.favorite) R.string.remove_favorite else R.string.add_favorite),
                    then { actions.onClickFavorite(item.id, !item.favorite) },
                ),
            )
            val albumId = item.data.albumId
            if (item.type == BaseItemKind.AUDIO && albumId != null) {
                add(panelItem(stringResource(R.string.go_to_album), then { actions.onClickGoToAlbum(albumId) }))
            }
            val artistId =
                item.data.artistItems
                    ?.firstOrNull()
                    ?.id
            if ((item.type == BaseItemKind.AUDIO || item.type == BaseItemKind.MUSIC_ALBUM) && artistId != null) {
                add(panelItem(stringResource(R.string.go_to_artist), then { actions.onClickGoToArtist(artistId) }))
            }
        }
    MenuPanel(
        title = item.title ?: "",
        entries = entries,
        fromLongClick = menu.fromLongClick,
        onDismissRequest = onDismissRequest,
    )
    if (confirmDelete) {
        TallyConfirmDeleteDialog(
            itemTitle = item.title ?: "",
            onCancel = { confirmDelete = false },
            onConfirm = {
                actions.onDeleteItem(item)
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun QueueMenu(
    onDismissRequest: () -> Unit,
    menu: ContextMenu.ForQueue,
    actions: QueueContextActions,
) {
    val item = menu.item
    val index = menu.index

    fun then(block: () -> Unit): () -> Unit =
        {
            onDismissRequest()
            block()
        }
    val entries =
        buildList {
            add(panelItem(stringResource(R.string.play), then { actions.onClickPlay(index, item) }))
            add(panelItem(stringResource(R.string.play_next), then { actions.onClickPlayNext(index, item) }))
            add(
                panelItem(
                    stringResource(R.string.remove_from_queue),
                    then { actions.onClickRemoveFromQueue(index, item) },
                ),
            )
            item.albumId?.let { albumId ->
                add(panelItem(stringResource(R.string.go_to_album), then { actions.onClickGoToAlbum(albumId) }))
            }
            item.artistId?.let { artistId ->
                add(panelItem(stringResource(R.string.go_to_artist), then { actions.onClickGoToArtist(artistId) }))
            }
        }
    MenuPanel(
        title = item.title ?: "",
        entries = entries,
        fromLongClick = menu.fromLongClick,
        onDismissRequest = onDismissRequest,
    )
}
