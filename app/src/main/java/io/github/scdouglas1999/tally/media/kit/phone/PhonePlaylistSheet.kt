package io.github.scdouglas1999.tally.media.kit.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.PlaylistInfo
import com.github.damontecres.wholphin.ui.detail.PlaylistLoadingState
import com.github.damontecres.wholphin.ui.preferences.StringInput
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.settings.TallyStringInputDialog
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneDialogRow
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetDivider
import io.github.scdouglas1999.tally.ui.settings.phone.PhoneSheetTitle
import io.github.scdouglas1999.tally.ui.settings.phone.phoneSheetListMaxHeight
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaType

/** Typing pauses this long before the playlists are searched again (upstream's dialog waits as long). */
private const val SEARCH_DELAY_MS = 750L

/**
 * Upstream's add-to-playlist dialog on a phone, as a sheet: [title], a search field, the playlists as rows (name,
 * kind under it, the count in mono at the right), and CREATE NEW PLAYLIST, which asks for the name in the text
 * entry sheet. Same state and actions as upstream's `PlaylistDialog`.
 */
@Composable
fun PhonePlaylistSheet(
    title: String,
    state: PlaylistLoadingState,
    onDismissRequest: () -> Unit,
    onClick: (PlaylistInfo) -> Unit,
    onSearch: (String) -> Unit,
    createEnabled: Boolean,
    onCreatePlaylist: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val previous = (state as? PlaylistLoadingState.Success)?.query ?: ""
        if (previous != query) {
            delay(SEARCH_DELAY_MS)
            onSearch(query)
        }
    }
    if (creating) {
        TallyStringInputDialog(
            input =
                StringInput(
                    title = stringResource(R.string.create_playlist),
                    value = null,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            autoCorrectEnabled = true,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    onSubmit = {},
                ),
            onSave = { name ->
                if (name.isNotBlank()) {
                    creating = false
                    onCreatePlaylist(name)
                }
            },
            onDismissRequest = { creating = false },
        )
        return
    }
    PhoneSheet(onDismiss = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            PhoneSheetTitle(title)
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = PhoneDimens.margin).padding(top = 12.dp, bottom = 4.dp),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = phoneSheetListMaxHeight())
                        .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    PlaylistLoadingState.Pending,
                    PlaylistLoadingState.Loading,
                    -> {
                        Message(stringResource(R.string.loading))
                    }

                    is PlaylistLoadingState.Error -> {
                        Message(state.localizedMessage, error = true)
                    }

                    is PlaylistLoadingState.Success -> {
                        state.items.forEach { playlist ->
                            PhoneDialogRow(
                                onClick = { onClick(playlist) },
                                headline = { Text(text = playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supporting = { Text(text = stringResource(playlist.kind())) },
                                trailing = {
                                    Text(
                                        text =
                                            pluralStringResource(R.plurals.items, playlist.count, playlist.count)
                                                .tallyUppercase(),
                                    )
                                },
                            )
                        }
                    }
                }
                if (createEnabled) {
                    PhoneSheetDivider()
                    PhoneDialogRow(
                        onClick = { creating = true },
                        headline = { Text(text = stringResource(R.string.create_playlist)) },
                        leading = { Text(text = "+", style = PhoneType.title) },
                    )
                }
            }
        }
    }
}

private fun PlaylistInfo.kind(): Int =
    when (mediaType) {
        MediaType.VIDEO -> R.string.video
        MediaType.AUDIO -> R.string.audio
        MediaType.PHOTO -> R.string.photos
        MediaType.UNKNOWN, MediaType.BOOK -> R.string.unknown
    }

@Composable
private fun Message(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        style = PhoneType.body,
        color = if (error) TallyColors.liveText else TallyColors.muted,
        modifier = Modifier.padding(horizontal = PhoneDimens.margin, vertical = 16.dp),
    )
}

/** The sheet's search field: 48dp, `ground`, 1dp `ruleStrong` (accent while typing), the muted mono hint. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PhoneType.headline.copy(color = TallyColors.text),
        cursorBrush = SolidColor(TallyColors.accent),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = PhoneDimens.touchTarget)
                        .background(TallyColors.ground)
                        .border(PhoneDimens.hairline, if (focused) TallyColors.accent else TallyColors.ruleStrong)
                        .padding(horizontal = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search).tallyUppercase(),
                        style = PhoneType.labelLarge,
                        color = TallyColors.muted,
                    )
                }
                inner()
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
