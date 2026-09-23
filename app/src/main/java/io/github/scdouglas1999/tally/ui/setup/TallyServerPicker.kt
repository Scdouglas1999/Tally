package io.github.scdouglas1999.tally.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.setup.ServerConnectionStatus
import com.github.damontecres.wholphin.ui.setup.ServerVersionSupported
import com.github.damontecres.wholphin.ui.setup.SwitchServerViewModel
import com.github.damontecres.wholphin.ui.setup.serverErrorMessage
import com.github.damontecres.wholphin.util.LoadingState
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import timber.log.Timber

private enum class ServerStep { List, Add, Address }

private val TileGap = 8.dp

/** Room for a tile's 3dp focus border inside the LazyRow's clip. */
private val RowPad = TallyDimens.focusBorder + 1.dp

/**
 * Select Server in the Tally look (seam in upstream's `SwitchServerContent`): the saved servers as square tiles
 * and a `+` tile, then Add Server (network discovery, `FOUND ON YOUR NETWORK`) and address entry, all on
 * upstream's [SwitchServerViewModel]. The first tile has focus on arrival.
 */
@Composable
fun TallyServerPicker(
    viewModel: SwitchServerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.init() }
    var step by remember { mutableStateOf(ServerStep.List) }

    when (step) {
        ServerStep.List -> {
            TallySetupFrame(kicker = stringResource(R.string.tally_signin_kicker_server), modifier = modifier) {
                if (state.loading == LoadingState.Success) {
                    ServerList(
                        viewModel = viewModel,
                        onAddServer = { step = ServerStep.Add },
                    )
                } else if (state.loading is LoadingState.Error) {
                    SetupError(state.loading, Modifier.align(Alignment.Center).padding(TallyDimens.marginHorizontal))
                }
            }
        }

        ServerStep.Add, ServerStep.Address -> {
            val close = {
                viewModel.clearAddServerState()
                step = ServerStep.List
            }
            BackHandler(onBack = close)
            TallySetupFrame(kicker = stringResource(R.string.tally_signin_kicker_add_server), modifier = modifier) {
                AddServer(
                    viewModel = viewModel,
                    enterAddress = step == ServerStep.Address,
                    onEnterAddress = { step = ServerStep.Address },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun ServerList(
    viewModel: SwitchServerViewModel,
    onAddServer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current
    var showDeleteDialog by remember { mutableStateOf<JellyfinServer?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val firstFocus = remember { FocusRequester() }
    var rowHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.servers.isEmpty()) {
        requestUntilFocused(firstFocus, { rowHasFocus }, focusManager, "tally-server-first")
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(TileGap),
                contentPadding = PaddingValues(horizontal = TallyDimens.marginHorizontal, vertical = RowPad),
                modifier =
                    Modifier
                        .wrapContentWidth()
                        .onFocusChanged { rowHasFocus = it.hasFocus },
            ) {
                itemsIndexed(state.servers, key = { _, it -> it.server.id }) { index, server ->
                    val status = server.status
                    val problem =
                        status is ServerConnectionStatus.Error ||
                            server.versionSupported == ServerVersionSupported.NOT_SUPPORTED
                    SetupTile(
                        name = server.server.name?.ifBlank { null } ?: server.server.url,
                        detail = server.server.url,
                        onClick = {
                            when (status) {
                                is ServerConnectionStatus.Success -> {
                                    viewModel.switchServer(server.server)
                                }

                                ServerConnectionStatus.Pending -> {}

                                is ServerConnectionStatus.Error -> {
                                    viewModel.testServer(server.server)
                                }
                            }
                        },
                        onLongClick = { showDeleteDialog = server.server },
                        onFocused = { focusedIndex = index },
                        tileModifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    ) {
                        TileFace(
                            initialOf = server.server.name ?: server.server.url.replace(Regex("^https?://"), ""),
                            initialColor =
                                when {
                                    problem -> TallyColors.liveText
                                    status == ServerConnectionStatus.Pending -> TallyColors.muted
                                    else -> TallyColors.text
                                },
                        )
                    }
                }
                item(key = "add") {
                    SetupTile(
                        name = stringResource(R.string.tally_signin_add_server),
                        muted = true,
                        onClick = onAddServer,
                        onFocused = { focusedIndex = -1 },
                        tileModifier =
                            Modifier
                                .testTag("add_server")
                                .then(if (state.servers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                    ) {
                        PlusFace()
                    }
                }
            }
        }

        val errorMessage =
            remember(resources, focusedIndex, state.servers) {
                serverErrorMessage(state.servers.getOrNull(focusedIndex), resources)
            }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = setupBody,
                color = TallyColors.liveText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
            )
        }
    }

    showDeleteDialog?.let { server ->
        DialogPopup(
            showDialog = true,
            title = server.name ?: server.url,
            dialogItems =
                listOf(
                    DialogItem(
                        stringResource(R.string.switch_servers),
                        R.string.fa_arrow_left_arrow_right,
                    ) {
                        viewModel.switchServer(server)
                        showDeleteDialog = null
                    },
                    DialogItem(
                        stringResource(R.string.delete),
                        Icons.Default.Delete,
                        Color.Red.copy(alpha = .8f),
                    ) {
                        viewModel.removeServer(server)
                        showDeleteDialog = null
                    },
                ),
            onDismissRequest = { showDeleteDialog = null },
            dismissOnClick = true,
            waitToLoad = true,
            properties = DialogProperties(),
            elevation = 5.dp,
        )
    }
}

/**
 * Add Server: servers found by upstream's discovery (after the local network permission where Android asks for it),
 * then the way to type an address; or, with [enterAddress], the address form.
 */
@Composable
private fun AddServer(
    viewModel: SwitchServerViewModel,
    enterAddress: Boolean,
    onEnterAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.discoverServers() else onEnterAddress()
        }
    LaunchedEffect(Unit) {
        viewModel.clearAddServerState()
        if (!enterAddress) {
            if (viewModel.hasPermission) {
                viewModel.discoverServers()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else {
                Timber.w("No ACCESS_LOCAL_NETWORK permission, but API %s", Build.VERSION.SDK_INT)
            }
        }
    }

    if (enterAddress) {
        TallyAddressEntry(
            state = state.addServerState,
            onSubmit = { viewModel.addServer(it, showToast = false) },
            onUrlChanged = { viewModel.clearAddServerState() },
            modifier = modifier,
        )
        return
    }

    val focusManager = LocalFocusManager.current
    val firstFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var tileFocused by remember { mutableStateOf(false) }
    val found = state.discoveredServers
    // The button first; the first server found, as soon as there is one (as upstream's dialog does).
    LaunchedEffect(found.isNotEmpty()) {
        if (found.isNotEmpty()) {
            requestUntilFocused(firstFocus, { tileFocused }, focusManager, "tally-add-server-found")
        } else {
            requestUntilFocused(buttonFocus, { hasFocus }, focusManager, "tally-add-server")
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().onFocusChanged { hasFocus = it.hasFocus },
    ) {
        Text(
            text = stringResource(R.string.tally_signin_found_on_network).tallyUppercase(),
            style = TallyType.label,
            color = TallyColors.muted,
        )
        if (found.isEmpty()) {
            Box(Modifier.height(SetupTileSize + 60.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.tally_signin_searching).tallyUppercase(),
                    style = TallyType.label,
                    color = TallyColors.textSecondary,
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(TileGap),
                    contentPadding = PaddingValues(horizontal = TallyDimens.marginHorizontal, vertical = RowPad),
                    modifier = Modifier.wrapContentWidth(),
                ) {
                    itemsIndexed(found, key = { _, it -> it.url }) { index, server ->
                        SetupTile(
                            name = server.name?.ifBlank { null } ?: server.url,
                            detail = server.url,
                            onClick = { viewModel.addServer(server.url, showToast = true) },
                            tileModifier =
                                if (index == 0) {
                                    Modifier.focusRequester(firstFocus).onFocusChanged { tileFocused = it.hasFocus }
                                } else {
                                    Modifier
                                },
                        ) {
                            TileFace(initialOf = server.name ?: server.url.replace(Regex("^https?://"), ""))
                        }
                    }
                }
            }
        }
        TallyButton(
            label = stringResource(R.string.tally_signin_enter_address),
            onClick = onEnterAddress,
            modifier = Modifier.padding(top = 24.dp).focusRequester(buttonFocus),
        )
    }
}
