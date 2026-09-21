package com.github.damontecres.wholphin.jellytv

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the connected server has the JellyTV plugin (driven by jellytv code elsewhere)
 */
@Singleton
class JellyTvAvailability
    @Inject
    constructor() {
        @Volatile
        var available: Boolean = false
    }
