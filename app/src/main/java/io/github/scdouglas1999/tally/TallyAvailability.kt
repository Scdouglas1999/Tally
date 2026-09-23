package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.data.TallyRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the connected server has the Tally plugin. The nav drawer asks every time it is rebuilt
 * (sign-in, user or server switch), so the answer always belongs to the current server.
 */
@Singleton
class TallyAvailability
    @Inject
    constructor(
        private val repository: TallyRepository,
    ) {
        /** Last known answer, without asking the server. */
        val available: Boolean
            get() = repository.availability.value is TallyRepository.Availability.Available

        /** Asks the server. Never throws: any failure means "not available". */
        suspend fun check(): Boolean {
            val result = repository.probe()
            Timber.i("Tally availability: %s", result)
            val available = result is TallyRepository.Availability.Available
            // Warm the board now, so the home screen's Tally row has its cards on its very first frame
            // instead of racing the library rows for the initial focus.
            if (available) repository.refreshNow()
            return available
        }
    }
