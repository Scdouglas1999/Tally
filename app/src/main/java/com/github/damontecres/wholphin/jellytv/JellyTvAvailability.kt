package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the connected server has the JellyTV plugin. The nav drawer asks every time it is rebuilt
 * (sign-in, user or server switch), so the answer always belongs to the current server.
 */
@Singleton
class JellyTvAvailability
    @Inject
    constructor(
        private val repository: JellyTvRepository,
    ) {
        /** Last known answer, without asking the server. */
        val available: Boolean
            get() = repository.availability.value is JellyTvRepository.Availability.Available

        /** Asks the server. Never throws: any failure means "not available". */
        suspend fun check(): Boolean {
            val result = repository.probe()
            Timber.i("JellyTV availability: %s", result)
            val available = result is JellyTvRepository.Availability.Available
            // Warm the board now, so the home screen's JellyTV row has its cards on its very first frame
            // instead of racing the library rows for the initial focus.
            if (available) repository.refreshNow()
            return available
        }
    }
