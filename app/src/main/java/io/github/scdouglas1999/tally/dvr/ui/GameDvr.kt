package io.github.scdouglas1999.tally.dvr.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.showToast
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.api.TallyTeam
import io.github.scdouglas1999.tally.dvr.DvrFormat
import io.github.scdouglas1999.tally.dvr.DvrNotice
import io.github.scdouglas1999.tally.dvr.DvrRule
import io.github.scdouglas1999.tally.dvr.DvrStorage
import io.github.scdouglas1999.tally.dvr.DvrViewModel
import io.github.scdouglas1999.tally.dvr.GameRecordingView
import io.github.scdouglas1999.tally.dvr.recordingView
import io.github.scdouglas1999.tally.dvr.spoilerGuarded
import io.github.scdouglas1999.tally.dvr.teamRuleFor
import kotlinx.coroutines.delay

/**
 * Everything the game menu (TV) and the game sheet (phone) show about recording one game: its job
 * ([recording]), whether this user may record ([canManage]; without it the actions are simply absent), the
 * storage estimate, and each team's rule. Null when the server does not record.
 */
class GameDvr(
    val game: TallyGame,
    val canManage: Boolean,
    val recording: GameRecordingView?,
    val estimate: DvrStorage?,
    val awayRule: DvrRule?,
    val homeRule: DvrRule?,
    private val viewModel: DvrViewModel,
) {
    /** RECORD is offered: an upcoming or live game without a job (or whose last job ended without a recording). */
    val canRecord: Boolean
        get() = canManage && (game.isUpcoming || game.isLive) && (recording == null || recording.allowsNewRecording)

    /** The estimate says it won't fit: RECORD is shown disabled with the server's reason. */
    val refusal: String? get() = estimate?.estimate?.takeIf { !it.fits }?.message

    /** The refusal as a line under RECORD, unless the failed job above already says the same. */
    val refusalLine: String? get() = refusal?.takeIf { it != recording?.reason }

    /** A finished game with a recording: its score stays hidden until asked for. */
    val guarded: Boolean get() = game.spoilerGuarded

    /** The finished recording can be played (it is in the library). */
    val watchableItemId: String? get() = recording?.itemId?.takeIf { recording.state == io.github.scdouglas1999.tally.dvr.DvrState.DONE }

    val startOverPath: String? get() = recording?.startOverPath?.takeIf { recording.isRecording }

    fun record() = viewModel.record(game)

    fun cancelOrStop() {
        recording?.jobId?.takeIf { it.isNotBlank() }?.let(viewModel::cancel)
    }

    fun watchFromStart(title: String) {
        val job = recording ?: return
        val path = startOverPath ?: return
        viewModel.watchFromStart(job.jobId, path, title)
    }

    fun watchRecording() {
        watchableItemId?.let(viewModel::playRecording)
    }

    fun ruleFor(team: TallyTeam): DvrRule? = if (team.id == game.away.id) awayRule else homeRule

    fun recordTeam(
        team: TallyTeam,
        keepLast: Int,
    ) = viewModel.recordTeam(game, team, keepLast)

    fun deleteRule(rule: DvrRule) = viewModel.deleteRule(rule.id)
}

/**
 * The [GameDvr] of [game] for a game menu or sheet: refreshes the recordings list when it opens, fetches the
 * estimate for a game that can still be recorded, and shows the server's refusals as toasts. Null when the server
 * has no DVR (or there is no game).
 */
@Composable
fun rememberGameDvr(game: TallyGame?): GameDvr? {
    val viewModel = hiltViewModel<DvrViewModel>()
    val enabled by viewModel.enabled.collectAsState()
    val list by viewModel.list.collectAsState()
    val estimates by viewModel.estimates.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val text =
                when (notice) {
                    is DvrNotice.Res -> context.getString(notice.id)
                    is DvrNotice.Text -> notice.text
                }
            showToast(context, text, Toast.LENGTH_LONG)
        }
    }
    val canEstimate = game != null && (game.isUpcoming || game.isLive)
    LaunchedEffect(enabled, game?.id) {
        if (!enabled || game == null) return@LaunchedEffect
        if (canEstimate) viewModel.loadEstimate(game.id)
        // While the menu is open its job moves on (scheduled, waiting, recording, the start-over playlist).
        while (true) {
            viewModel.refresh()
            delay(REFRESH_MS)
        }
    }
    if (!enabled || game == null) return null
    val current = list
    return GameDvr(
        game = game,
        canManage = current?.canManage == true,
        recording = recordingView(game, current),
        estimate = estimates[game.id],
        awayRule = current?.teamRuleFor(game, game.away),
        homeRule = current?.teamRuleFor(game, game.home),
        viewModel = viewModel,
    )
}

/** "~9 GB · 1.2 TB free on the server" (or only the free space while the estimate is not in). */
@Composable
fun estimateLine(estimate: DvrStorage?): String? {
    estimate ?: return null
    val free = estimate.freeBytes?.let { stringResource(R.string.tally_dvr_free_on_server, DvrFormat.size(it)) }
    val needs =
        estimate.estimate
            ?.bytes
            ?.takeIf { it > 0 }
            ?.let { "~" + DvrFormat.size(it) }
    return listOfNotNull(needs, free).joinToString(" · ").ifBlank { null }
}

/** "Away at Home" for titles, with the board's short names. */
@Composable
fun matchupTitle(game: TallyGame): String =
    stringResource(
        R.string.tally_actions_at,
        game.away.shortName.ifBlank { game.away.abbr.ifBlank { game.away.name } },
        game.home.shortName.ifBlank { game.home.abbr.ifBlank { game.home.name } },
    )

private const val REFRESH_MS = 5_000L
