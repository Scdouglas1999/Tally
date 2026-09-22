package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlayerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Sleep timer for both players. When it fires: pause whatever is playing, leave the player (go back), and
 * clear itself. [remaining] is null when no timer is set. STUB: remembers a request, never fires.
 */
@Singleton
class SleepTimerService
    @Inject
    constructor(
        private val playerFactory: PlayerFactory,
        private val navigationManager: NavigationManager,
    ) {
        private val _remaining = MutableStateFlow<Duration?>(null)
        val remaining: StateFlow<Duration?> = _remaining.asStateFlow()

        /** Stop after [duration]. */
        fun start(duration: Duration) {
            _remaining.value = duration
        }

        /** Stop when the current item ends. */
        fun startUntilEnd() {
            _remaining.value = Duration.ZERO
        }

        fun cancel() {
            _remaining.value = null
        }
    }
