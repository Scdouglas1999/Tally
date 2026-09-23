package io.github.scdouglas1999.tally.ui.household

import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.scdouglas1999.tally.household.HouseholdRepository
import io.github.scdouglas1999.tally.household.HouseholdSession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Drives "Playing in the house" on the home screen. The poll lives as long as this view model
 * (the Home destination): the row itself is disposed whenever the home list scrolls it off, and
 * that must not cancel the fetch.
 */
@HiltViewModel
class HouseholdRowViewModel
    @Inject
    constructor(
        private val repository: HouseholdRepository,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        val sessions: StateFlow<List<HouseholdSession>> = repository.sessions

        init {
            repository.startPolling()
        }

        override fun onCleared() {
            repository.stopPolling()
            super.onCleared()
        }

        /** OK on a card: play the same item here, at the other device's position. */
        fun join(session: HouseholdSession) {
            val itemId = session.itemId ?: return
            navigationManager.navigateTo(
                Destination.Playback(
                    itemId = itemId,
                    positionMs = session.positionMs ?: 0L,
                ),
            )
        }
    }
