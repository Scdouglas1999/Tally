package io.github.scdouglas1999.tally.ui.setup

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.setup.UpdateViewModel
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.Version
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.scdouglas1999.tally.media.kit.TallyButton
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.theme.TallyColors
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyType
import kotlinx.coroutines.launch

private val ColumnWidth = 440.dp
private val ContentTop = 132.dp
private const val SCROLL_STEP = 100f

private val titleStyle =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    )

private val notesText =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = TallyColors.textSecondary,
    )

private val notesHeading =
    TextStyle(
        fontFamily = TallyType.Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        color = TallyColors.text,
    )

/**
 * The in-app update page in the Tally look (seam in upstream's `InstallUpdatePage`), on upstream's
 * [UpdateViewModel]: what is installed and what is offered, `DOWNLOAD & UPDATE` (focused on arrival: the page is
 * opened for people who did not ask for it), the release notes, and a progress bar while it downloads.
 */
@Composable
fun TallyUpdatePage(
    viewModel: UpdateViewModel,
    modifier: Modifier = Modifier,
) {
    OneTimeLaunchedEffect { viewModel.init() }
    val state by viewModel.state.collectAsState()
    var permissions by remember { mutableStateOf(viewModel.updater.hasPermissions()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) permissions = true
        }
    if (state.downloading) {
        BackHandler { viewModel.cancelDownload() }
    }

    TallySetupFrame(kicker = stringResource(R.string.tally_signin_kicker_update), modifier = modifier) {
        val release = state.release
        Row(
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = TallyDimens.marginHorizontal,
                        end = TallyDimens.marginHorizontal,
                        top = ContentTop,
                        bottom = TallyDimens.marginVertical,
                    ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(ColumnWidth),
            ) {
                when (val loading = state.loading) {
                    LoadingState.Pending, LoadingState.Loading -> {
                        Text(
                            text = stringResource(R.string.tally_signin_update_checking).tallyUppercase(),
                            style = TallyType.label,
                            color = TallyColors.muted,
                        )
                    }

                    is LoadingState.Error -> {
                        Text(
                            text = stringResource(R.string.tally_signin_update_failed),
                            style = titleStyle,
                            color = TallyColors.text,
                        )
                        SetupError(loading)
                        TallyButton(
                            label = stringResource(R.string.tally_signin_back),
                            onClick = { viewModel.navigationManager.goBack() },
                            modifier = initialFocus("tally-update-error"),
                        )
                    }

                    LoadingState.Success -> {
                        if (release == null) {
                            Text(
                                text = stringResource(R.string.tally_signin_update_none),
                                style = setupBody,
                                color = TallyColors.liveText,
                            )
                        } else {
                            Offer(
                                installed = shortVersion(viewModel.currentVersion),
                                offered = shortVersion(release.version),
                            )
                            Spacer(Modifier.height(8.dp))
                            if (state.downloading) {
                                val bytes by viewModel.bytesDownloaded.collectAsState(0L)
                                DownloadProgress(state.contentLength, bytes)
                                TallyButton(
                                    label = stringResource(R.string.tally_signin_cancel),
                                    onClick = { viewModel.cancelDownload() },
                                    modifier = initialFocus("tally-update-cancel"),
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TallyButton(
                                        label = stringResource(R.string.tally_signin_update_download),
                                        onClick = {
                                            if (!permissions) {
                                                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            } else {
                                                viewModel.installRelease(release)
                                            }
                                        },
                                        primary = true,
                                        modifier = initialFocus("jellytv-update"),
                                    )
                                    TallyButton(
                                        label = stringResource(R.string.tally_signin_cancel),
                                        onClick = { viewModel.navigationManager.goBack() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (release != null && state.loading == LoadingState.Success) {
                ReleaseNotes(release, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** `v1.0.8-130`: the release name without its commit hash (what the title shows). */
internal fun shortVersion(version: Version): String =
    buildString {
        append("v${version.major}.${version.minor}.${version.patch}")
        version.numCommits?.takeIf { it > 0 }?.let { append("-$it") }
    }

@Composable
private fun Offer(
    installed: String,
    offered: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.tally_signin_update_available, offered),
            style = titleStyle,
            color = TallyColors.text,
        )
        Text(
            text = stringResource(R.string.tally_signin_update_versions, installed, offered),
            style = TallyType.label.copy(letterSpacing = 1.sp),
            color = TallyColors.textSecondary,
            maxLines = 1,
        )
    }
}

/** A thin bar in the kit's style (accent on `ruleStrong`) and the bytes so far, while the new build downloads. */
@Composable
private fun DownloadProgress(
    contentLength: Long,
    bytes: Long,
) {
    val fraction = if (contentLength > 0) (bytes.toFloat() / contentLength).coerceIn(0f, 1f) else null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            // The label is uppercase; the sizes keep their unit's case (MiB, not MIB).
            text =
                stringResource(R.string.tally_signin_update_downloading).tallyUppercase().let { label ->
                    if (fraction != null) {
                        stringResource(R.string.tally_signin_update_progress, label, formatBytes(bytes), formatBytes(contentLength))
                    } else {
                        label
                    }
                },
            style = TallyType.label,
            color = TallyColors.textSecondary,
            maxLines = 1,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(TallyColors.ruleStrong),
        ) {
            if (fraction != null) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(TallyColors.accent),
                )
            }
        }
    }
}

/**
 * The release notes, scrollable: focus the panel and UP / DOWN scroll it (upstream's behavior). Its heading line
 * (the version) is left out: the version is already in the title.
 */
@Composable
private fun ReleaseNotes(
    release: Release,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val content = remember(release) { release.content.substringAfter('\n', release.content).trim() }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(24.dp),
        modifier =
            modifier
                .background(TallyColors.groundRaised)
                .border(
                    if (focused) TallyDimens.focusBorder else TallyDimens.hairline,
                    if (focused) TallyColors.accent else TallyColors.rule,
                ).onKeyEvent {
                    if (it.type == KeyEventType.KeyUp) return@onKeyEvent false
                    val delta =
                        when (it.key) {
                            Key.DirectionDown -> SCROLL_STEP
                            Key.DirectionUp -> -SCROLL_STEP
                            else -> return@onKeyEvent false
                        }
                    scope.launch(ExceptionHandler()) { listState.scrollBy(delta) }
                    true
                }.focusable(interactionSource = interactionSource),
    ) {
        item {
            Markdown(
                content = content,
                colors =
                    markdownColor(
                        text = TallyColors.textSecondary,
                        codeBackground = TallyColors.groundRaised,
                        inlineCodeBackground = TallyColors.groundRaised,
                        dividerColor = TallyColors.rule,
                    ),
                typography =
                    markdownTypography(
                        h1 = notesHeading,
                        h2 = notesHeading,
                        h3 = notesHeading,
                        text = notesText,
                        paragraph = notesText,
                        bullet = notesText,
                        list = notesText,
                        ordered = notesText,
                        code = notesText.copy(fontFamily = TallyType.Mono),
                        inlineCode = notesText.copy(fontFamily = TallyType.Mono),
                    ),
            )
        }
    }
}
