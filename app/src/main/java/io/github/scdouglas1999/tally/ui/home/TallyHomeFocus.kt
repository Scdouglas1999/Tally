package io.github.scdouglas1999.tally.ui.home

import android.os.SystemClock
import androidx.compose.ui.focus.FocusRequester
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Lets the Tally row claim the home screen's initial focus. Upstream focuses the first library row and scrolls
 * it to the top, which pushes a row above it out of sight (and out of composition); when games are on, they are
 * what this app is for, so the row takes focus once its data arrives, provided that happens within a moment of
 * the page opening. Later arrivals never steal focus from someone already browsing.
 *
 * The row sits above the lazy list as a fixed band, so it is always composed: when its cards appear it calls
 * [claim]; upstream's own step asks [awaitPendingClaim] before it focuses a library row.
 */
object TallyHomeFocus {
    @Volatile
    private var pageOpenedAt = 0L

    /** Set by the row's view model once the server is known to have the plugin: a row is coming, worth a wait. */
    @Volatile
    var rowExpected = false

    /** True once the row has taken the initial focus for this page opening; upstream then leaves focus alone. */
    @Volatile
    var claimed = false
        private set

    /**
     * Called by the home page while it is choosing its initial focus. Upstream re-runs that step as each library
     * row loads, so this only starts a new window when the previous one has lapsed.
     */
    fun pageOpened() {
        if (windowOpen()) return
        pageOpenedAt = SystemClock.elapsedRealtime()
        claimed = false
    }

    private fun windowOpen() = pageOpenedAt != 0L && SystemClock.elapsedRealtime() - pageOpenedAt <= CLAIM_WINDOW_MS

    /** Called by the composed row; focuses its first card if the window is still open. Retries across a few
     * frames because the card composes before its focus node is attached. */
    suspend fun claim(firstCard: FocusRequester): Boolean {
        if (claimed || !windowOpen()) return false
        repeat(CLAIM_TRIES) {
            if (firstCard.tryRequestFocus("jellytv-home")) {
                claimed = true
                Timber.d("Tally home row claimed the initial focus")
                return true
            }
            delay(FRAME_MS)
        }
        Timber.d("Tally home row could not take focus")
        return false
    }

    /** For upstream's initial-focus step: while the window is open, give the row a moment to claim before deciding. */
    suspend fun awaitPendingClaim(): Boolean {
        repeat(AWAIT_STEPS) {
            if (claimed) return true
            if (!rowExpected || !windowOpen()) return false
            delay(FRAME_MS)
        }
        return claimed
    }

    private const val CLAIM_WINDOW_MS = 4_000L
    private const val CLAIM_TRIES = 12
    private const val FRAME_MS = 32L
    private const val AWAIT_STEPS = 80
}
