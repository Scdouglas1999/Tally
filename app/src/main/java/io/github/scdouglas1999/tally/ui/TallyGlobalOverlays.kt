package io.github.scdouglas1999.tally.ui

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
import com.github.damontecres.wholphin.services.PlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.SleepTimerService
import io.github.scdouglas1999.tally.together.ui.TogetherDialog
import io.github.scdouglas1999.tally.together.ui.TogetherOverlay
import io.github.scdouglas1999.tally.ui.household.SendToDialog
import io.github.scdouglas1999.tally.ui.household.SentNoticeHost
import io.github.scdouglas1999.tally.ui.launch.TallyLaunch
import io.github.scdouglas1999.tally.ui.player.SleepTimerChip
import io.github.scdouglas1999.tally.ui.player.SleepTimerDialog
import io.github.scdouglas1999.tally.ui.player.TallyPlayerMenu
import io.github.scdouglas1999.tally.ui.theme.TallyDimens
import io.github.scdouglas1999.tally.ui.theme.TallyScale
import javax.inject.Inject

@HiltViewModel
class TallyGlobalOverlaysViewModel
    @Inject
    constructor(
        val sleepTimer: SleepTimerService,
        val playerFactory: PlayerFactory,
    ) : ViewModel()

/**
 * Overlays that must work above any screen: the sleep timer chip, Watch Together, the players' Tally menu
 * dialogs, the SENT / NOT SENT lower third after Send to another screen, and on a cold start the launch card
 * above all of them.
 */
@Composable
fun TallyGlobalOverlays(modifier: Modifier = Modifier) {
    val viewModel: TallyGlobalOverlaysViewModel = hiltViewModel()
    val remaining by viewModel.sleepTimer.remaining.collectAsStateWithLifecycle()
    val request by TallyPlayerMenu.request
    TallyScale {
        Box(modifier.fillMaxSize()) {
            remaining?.let {
                SleepTimerChip(remaining = it, modifier = Modifier.align(Alignment.TopStart).padding(48.dp, 27.dp))
            }
            when (request) {
                TallyPlayerMenu.Request.SLEEP_TIMER -> {
                    SleepTimerDialog(
                        remaining = remaining,
                        onStart = { viewModel.sleepTimer.start(it) },
                        onStartUntilEnd = { viewModel.sleepTimer.startUntilEnd() },
                        onCancel = { viewModel.sleepTimer.cancel() },
                        onDismiss = { TallyPlayerMenu.request.value = null },
                    )
                }

                TallyPlayerMenu.Request.SEND_TO -> {
                    val itemId = TallyPlayerMenu.nowPlayingItemId
                    if (itemId == null) {
                        TallyPlayerMenu.request.value = null
                    } else {
                        SendToDialog(
                            itemId = itemId,
                            positionMs = viewModel.playerFactory.currentPlayer?.currentPosition ?: 0L,
                            onDismiss = { TallyPlayerMenu.request.value = null },
                        )
                    }
                }

                TallyPlayerMenu.Request.QUALITY -> {
                    io.github.scdouglas1999.tally.quality.QualityDialog(
                        onDismiss = { TallyPlayerMenu.request.value = null },
                    )
                }

                TallyPlayerMenu.Request.TOGETHER -> {
                    TogetherDialog(
                        itemId = TallyPlayerMenu.nowPlayingItemId,
                        positionMs = viewModel.playerFactory.currentPlayer?.currentPosition ?: 0L,
                        onDismiss = { TallyPlayerMenu.request.value = null },
                    )
                }

                null -> {}
            }
            TogetherOverlay(Modifier.fillMaxSize())
            // The Send dialog draws the notice itself while it is open (above its scrim).
            if (request != TallyPlayerMenu.Request.SEND_TO) {
                SentNoticeHost(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = TallyDimens.marginHorizontal, vertical = TallyDimens.marginVertical),
                )
            }
            // Topmost: the launch card covers everything until the app is ready.
            TallyLaunch(Modifier.fillMaxSize())
        }
    }
}
