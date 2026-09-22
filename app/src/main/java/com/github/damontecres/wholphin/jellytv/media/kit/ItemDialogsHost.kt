package com.github.damontecres.wholphin.jellytv.media.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.ui.components.ConfirmDialog
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.UUID

/**
 * What an item page asks [ItemDialogsHost] to show. Pages set these; they do not wire each dialog.
 */
@Stable
class ItemDialogsState {
    var contextMenu by mutableStateOf<ContextMenu?>(null)
    var overview by mutableStateOf<ItemDetailsDialogInfo?>(null)
    var playlistItemId by mutableStateOf<UUID?>(null)
    var deleteItem by mutableStateOf<BaseItem?>(null)

    /** A list dialog built by upstream (for example `buildDialogForSeason`). */
    var dialog by mutableStateOf<DialogParams?>(null)
}

/**
 * Context menu, add-to-playlist, full overview, an upstream list dialog, and delete confirmation.
 * Reuses the upstream dialogs until a later task restyles them.
 */
@Composable
fun ItemDialogsHost(
    state: ItemDialogsState,
    getMediaSource: (BaseItemDto, ItemPlayback?) -> MediaSourceInfo?,
    preferredSubtitleLanguage: String?,
    showFilePath: Boolean,
    onConfirmDelete: (BaseItem) -> Unit,
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val playlistState by playlistViewModel.playlistState.collectAsStateWithLifecycle()
    LaunchedEffect(state.playlistItemId) {
        if (state.playlistItemId != null) playlistViewModel.loadPlaylists()
    }
    state.contextMenu?.let { menu ->
        ContextMenuDialog(
            onDismissRequest = { state.contextMenu = null },
            getMediaSource = getMediaSource,
            contextMenu = menu,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
        )
    }
    state.overview?.let { info ->
        ItemDetailsDialog(
            info = info,
            showFilePath = showFilePath,
            onDismissRequest = { state.overview = null },
        )
    }
    state.playlistItemId?.let { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { state.playlistItemId = null },
            onClick = {
                playlistViewModel.addToPlaylist(it.id, itemId)
                state.playlistItemId = null
            },
            createEnabled = true,
            onCreatePlaylist = {
                playlistViewModel.createPlaylistAndAddItem(it, itemId)
                state.playlistItemId = null
            },
            onSearch = playlistViewModel::loadPlaylists,
            elevation = 3.dp,
        )
    }
    state.dialog?.let { params ->
        DialogPopup(
            showDialog = true,
            title = params.title,
            dialogItems = params.items,
            onDismissRequest = { state.dialog = null },
            dismissOnClick = true,
            waitToLoad = params.fromLongClick,
        )
    }
    state.deleteItem?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete_item),
            body = item.title ?: item.name,
            onCancel = { state.deleteItem = null },
            onConfirm = {
                state.deleteItem = null
                onConfirmDelete(item)
            },
        )
    }
}
