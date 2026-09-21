package com.github.damontecres.wholphin.jellytv

import com.github.damontecres.wholphin.jellytv.data.JellyTvRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

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
            return result is JellyTvRepository.Availability.Available
        }
    }
