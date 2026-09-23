package io.github.scdouglas1999.tally.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The channels queued for multiview (max [MAX]). A process-wide singleton because three screens feed it:
 * the games board (hold OK), the channels grid, and the in-player switcher.
 */
@Singleton
class TallyMultiviewState
    @Inject
    constructor() {
        private val _channelIds = MutableStateFlow<List<String>>(emptyList())
        val channelIds: StateFlow<List<String>> = _channelIds.asStateFlow()

        enum class AddResult { ADDED, ALREADY_PRESENT, FULL }

        fun add(channelId: String): AddResult {
            var result = AddResult.ADDED
            _channelIds.update { current ->
                when {
                    channelId in current -> {
                        result = AddResult.ALREADY_PRESENT
                        current
                    }

                    current.size >= MAX -> {
                        result = AddResult.FULL
                        current
                    }

                    else -> {
                        current + channelId
                    }
                }
            }
            return result
        }

        fun remove(channelId: String) = _channelIds.update { it - channelId }

        fun replace(
            index: Int,
            channelId: String,
        ) = _channelIds.update { current ->
            if (index !in current.indices || channelId in current) current else current.toMutableList().also { it[index] = channelId }
        }

        fun clear() = _channelIds.update { emptyList() }

        companion object {
            const val MAX = 4
        }
    }
