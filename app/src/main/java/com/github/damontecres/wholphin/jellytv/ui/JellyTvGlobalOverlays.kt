package com.github.damontecres.wholphin.jellytv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.damontecres.wholphin.jellytv.SleepTimerService
import com.github.damontecres.wholphin.jellytv.together.ui.TogetherDialog
import com.github.damontecres.wholphin.jellytv.together.ui.TogetherOverlay
import com.github.damontecres.wholphin.jellytv.ui.household.SendToDialog
import com.github.damontecres.wholphin.jellytv.ui.household.SentNoticeHost
import com.github.damontecres.wholphin.jellytv.ui.launch.TallyLaunch
import com.github.damontecres.wholphin.jellytv.ui.player.JellyTvPlayerMenu
import com.github.damontecres.wholphin.jellytv.ui.player.SleepTimerChip
import com.github.damontecres.wholphin.jellytv.ui.player.SleepTimerDialog
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvDimens
import com.github.damontecres.wholphin.jellytv.ui.theme.JtvScale
import com.github.damontecres.wholphin.services.PlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class JellyTvGlobalOverlaysViewModel
    @Inject
    constructor(
        val sleepTimer: SleepTimerService,
        val playerFactory: PlayerFactory,
    ) : ViewModel()

/**
 * Overlays that must work above any screen: the sleep timer chip, Watch Together, the players' JellyTV menu
 * dialogs, the SENT / NOT SENT lower third after Send to another screen, and on a cold start the launch card
 * above all of them.
 */
@Composable
fun JellyTvGlobalOverlays(modifier: Modifier = Modifier) {
    val viewModel: JellyTvGlobalOverlaysViewModel = hiltViewModel()
    val remaining by viewModel.sleepTimer.remaining.collectAsStateWithLifecycle()
    val request by JellyTvPlayerMenu.request
    JtvScale {
        Box(modifier.fillMaxSize()) {
            remaining?.let {
                SleepTimerChip(remaining = it, modifier = Modifier.align(Alignment.TopStart).padding(48.dp, 27.dp))
            }
            when (request) {
                JellyTvPlayerMenu.Request.SLEEP_TIMER -> {
                    SleepTimerDialog(
                        remaining = remaining,
                        onStart = { viewModel.sleepTimer.start(it) },
                        onStartUntilEnd = { viewModel.sleepTimer.startUntilEnd() },
                        onCancel = { viewModel.sleepTimer.cancel() },
                        onDismiss = { JellyTvPlayerMenu.request.value = null },
                    )
                }

                JellyTvPlayerMenu.Request.SEND_TO -> {
                    val itemId = JellyTvPlayerMenu.nowPlayingItemId
                    if (itemId == null) {
                        JellyTvPlayerMenu.request.value = null
                    } else {
                        SendToDialog(
                            itemId = itemId,
                            positionMs = viewModel.playerFactory.currentPlayer?.currentPosition ?: 0L,
                            onDismiss = { JellyTvPlayerMenu.request.value = null },
                        )
                    }
                }

                JellyTvPlayerMenu.Request.QUALITY -> {
                    com.github.damontecres.wholphin.jellytv.quality.QualityDialog(
                        onDismiss = { JellyTvPlayerMenu.request.value = null },
                    )
                }

                JellyTvPlayerMenu.Request.TOGETHER -> {
                    TogetherDialog(
                        itemId = JellyTvPlayerMenu.nowPlayingItemId,
                        positionMs = viewModel.playerFactory.currentPlayer?.currentPosition ?: 0L,
                        onDismiss = { JellyTvPlayerMenu.request.value = null },
                    )
                }

                null -> {}
            }
            TogetherOverlay(Modifier.fillMaxSize())
            // The Send dialog draws the notice itself while it is open (above its scrim).
            if (request != JellyTvPlayerMenu.Request.SEND_TO) {
                SentNoticeHost(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = JtvDimens.marginHorizontal, vertical = JtvDimens.marginVertical),
                )
            }
            // Topmost: the launch card covers everything until the app is ready.
            TallyLaunch(Modifier.fillMaxSize())
        }
    }
}
