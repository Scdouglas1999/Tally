package com.github.damontecres.wholphin.jellytv.together.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.jellytv.together.TogetherGroupSummary
import com.github.damontecres.wholphin.jellytv.together.TogetherService
import com.github.damontecres.wholphin.jellytv.together.TogetherState
import com.github.damontecres.wholphin.jellytv.ui.player.JellyTvPlayerMenu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/** One card in the home row. [isMine] = this TV is in that watch party. */
data class TogetherPartyCard(
    val id: UUID,
    val name: String,
    val participants: List<String>,
    val isMine: Boolean,
)

/**
 * Watch parties on this server for the home row. [poll] runs while the row is composed; the list is merged
 * with the engine's own state so the party this TV is in shows (marked) even before the next poll.
 */
@HiltViewModel
class TogetherRowViewModel
    @Inject
    constructor(
        private val together: TogetherService,
    ) : ViewModel() {
        private val groups = MutableStateFlow<List<TogetherGroupSummary>>(emptyList())

        val parties: StateFlow<List<TogetherPartyCard>> =
            combine(groups, together.state) { listed, state -> partyCards(listed, state) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Fetch now, then every [POLL_MS] until the caller's scope ends (the row leaves composition). */
        suspend fun poll() {
            while (true) {
                refresh()
                delay(POLL_MS)
            }
        }

        suspend fun refresh() {
            groups.value = together.groups()
        }

        /** OK on a card: join it, or for this TV's own party open the Together dialog (leave / close). */
        fun open(card: TogetherPartyCard) {
            if (card.isMine) {
                JellyTvPlayerMenu.request.value = JellyTvPlayerMenu.Request.TOGETHER
                return
            }
            viewModelScope.launch {
                Timber.i("Watch together: joining %s from the home row", card.id)
                together.join(card.id)
            }
        }

        private companion object {
            const val POLL_MS = 15_000L
        }
    }

/** The server's list, with this TV's own party marked (and added when the list does not have it yet). */
internal fun partyCards(
    listed: List<TogetherGroupSummary>,
    state: TogetherState,
): List<TogetherPartyCard> {
    val mine = (state as? TogetherState.InGroup)?.group
    val cards =
        listed.map { group ->
            if (group.id == mine?.id) {
                TogetherPartyCard(mine.id, mine.name, mine.participants, isMine = true)
            } else {
                TogetherPartyCard(group.id, group.name, group.participants, isMine = false)
            }
        }
    return if (mine != null && cards.none { it.isMine }) {
        listOf(TogetherPartyCard(mine.id, mine.name, mine.participants, isMine = true)) + cards
    } else {
        cards
    }
}
