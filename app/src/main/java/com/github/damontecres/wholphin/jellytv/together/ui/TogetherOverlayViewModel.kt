package com.github.damontecres.wholphin.jellytv.together.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.jellytv.together.TogetherNotice
import com.github.damontecres.wholphin.jellytv.together.TogetherService
import com.github.damontecres.wholphin.jellytv.together.TogetherState
import com.github.damontecres.wholphin.services.UserPreferencesService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The Watch Together engine's flows for the party chip and its notices, plus the few preferences they follow. */
@HiltViewModel
class TogetherOverlayViewModel
    @Inject
    constructor(
        together: TogetherService,
        serverRepository: ServerRepository,
        userPreferencesService: UserPreferencesService,
    ) : ViewModel() {
        val state: StateFlow<TogetherState> = together.state
        val notices: SharedFlow<TogetherNotice> = together.notices

        /** This TV's Jellyfin user name: its own Joined/Left notices are not shown. */
        val userName: StateFlow<String?> =
            serverRepository.currentUserDtoFlow
                .map { it?.name }
                .stateIn(viewModelScope, SharingStarted.Eagerly, serverRepository.currentUserDto?.name)

        /** Upstream draws a clock in the top-right corner when this is on; the chip then sits below it. */
        val showClock: StateFlow<Boolean> =
            userPreferencesService.flow
                .map { it.appPreferences.interfacePreferences.showClock }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        /** How long upstream's player controls stay up after the last key. */
        val controlsTimeoutMs: StateFlow<Long> =
            userPreferencesService.flow
                .map { it.appPreferences.playbackPreferences.controllerTimeoutMs }
                .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_CONTROLS_TIMEOUT_MS)

        private companion object {
            const val DEFAULT_CONTROLS_TIMEOUT_MS = 5_000L
        }
    }
