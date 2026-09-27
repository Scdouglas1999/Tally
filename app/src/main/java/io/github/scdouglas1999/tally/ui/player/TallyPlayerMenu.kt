package io.github.scdouglas1999.tally.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import io.github.scdouglas1999.tally.api.TallyFeed
import java.util.UUID

/**
 * Requests from the players' settings menu to the Tally overlay host (which lives above every screen):
 * upstream's dialog only knows how to name an entry, the Tally overlays do the rest.
 */
object TallyPlayerMenu {
    enum class Request { SLEEP_TIMER, SEND_TO, TOGETHER, QUALITY }

    val request = mutableStateOf<Request?>(null)

    /** The request behind a settings-menu entry, or null for upstream's own entries. */
    fun requestFor(type: PlaybackDialogType): Request? =
        when (type) {
            PlaybackDialogType.TALLY_SLEEP_TIMER -> Request.SLEEP_TIMER
            PlaybackDialogType.TALLY_SEND_TO -> Request.SEND_TO
            PlaybackDialogType.TALLY_TOGETHER -> Request.TOGETHER
            PlaybackDialogType.TALLY_QUALITY -> Request.QUALITY
            else -> null
        }

    /**
     * The Commentary page's choices while the Tally player shows a game with more than one commentary (published by
     * [PublishCommentary]); null otherwise, and the settings list then has no Commentary row.
     */
    val commentary = mutableStateOf<CommentaryMenu?>(null)

    /** The item the upstream player is on, published by a seam in PlaybackViewModel. */
    @Volatile
    var nowPlayingItemId: UUID? = null
}

/** What the settings panel's Commentary page offers: [choice]'s feeds; [choose] plays one in place. */
class CommentaryMenu(
    val choice: Commentary,
    val choose: (TallyFeed) -> Unit,
)

/**
 * Publishes the player's Commentary choice to the settings panel while this player page is up, and takes it back when
 * the page goes (a switch to the other commentary replaces the page, and the new one publishes its own).
 */
@Composable
fun PublishCommentary(viewModel: TallyPlayerViewModel) {
    val choice by viewModel.commentary.collectAsState()
    DisposableEffect(choice) {
        val menu = choice?.let { CommentaryMenu(it, viewModel::switchCommentary) }
        TallyPlayerMenu.commentary.value = menu
        onDispose {
            if (TallyPlayerMenu.commentary.value === menu) TallyPlayerMenu.commentary.value = null
        }
    }
}
