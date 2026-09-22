package com.github.damontecres.wholphin.jellytv.postplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/** The finished film, then "More like this". [similar] stays null until that request returns. */
data class PostPlayUiState(
    val film: BaseItemDto? = null,
    val similar: List<BaseItemDto>? = null,
)

/**
 * Loads the film that just ended and up to six similar titles (unwatched first, order otherwise
 * unchanged), and performs the page's navigation.
 */
@HiltViewModel
class PostPlayViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(PostPlayUiState())
        val state: StateFlow<PostPlayUiState> = _state.asStateFlow()

        private var loadedId: UUID? = null
        private var loadJob: Job? = null

        fun load(itemId: UUID) {
            if (loadedId == itemId) return
            loadedId = itemId
            loadJob?.cancel()
            _state.value = PostPlayUiState()
            Timber.i("Post-play load %s", itemId)
            loadJob =
                viewModelScope.launchIO {
                    val userId = serverRepository.currentUser?.id
                    try {
                        val film = api.userLibraryApi.getItem(itemId = itemId, userId = userId).content
                        Timber.i("Post-play film %s", film.name)
                        _state.update { it.copy(film = film) }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Post-play could not load %s", itemId)
                        return@launchIO
                    }
                    try {
                        val similar =
                            api.libraryApi
                                .getSimilarItems(
                                    itemId = itemId,
                                    userId = userId,
                                    limit = SIMILAR_FETCH,
                                    fields = listOf(ItemFields.GENRES),
                                ).content.items
                                .filter { it.id != itemId }
                                .sortedBy { it.userData?.played == true }
                                .take(SIMILAR_KEEP)
                        _state.update { it.copy(similar = similar) }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Post-play similar items failed for %s", itemId)
                        _state.update { it.copy(similar = emptyList()) }
                    }
                }
        }

        /** Play the finished film from the beginning. */
        fun watchAgain() {
            val film = _state.value.film ?: return
            navigationManager.navigateTo(Destination.Playback(itemId = film.id, positionMs = 0L))
        }

        /** Back to where the film was started; Home when the player was the only page (e.g. opened by a deep link). */
        fun done() {
            if (navigationManager.backStack.size > 1) navigationManager.goBack() else navigationManager.goToHome()
        }

        fun open(item: BaseItemDto) {
            navigationManager.navigateTo(Destination.MediaItem(itemId = item.id, type = item.type))
        }

        private companion object {
            const val SIMILAR_FETCH = 12
            const val SIMILAR_KEEP = 6
        }
    }
