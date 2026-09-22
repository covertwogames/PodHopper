package au.com.shiftyjelly.pocketcasts.wear.ui.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.models.entity.Folder
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderManager: FolderManager,
    private val podcastManager: PodcastManager,
) : ViewModel() {

    private val folderUuid: String = savedStateHandle[PodcastsScreen.ARGUMENT_FOLDER_UUID] ?: ""

    sealed class UiState {
        object Empty : UiState()
        object Loading : UiState()
        data class Loaded(
            val folder: Folder? = null,
            val items: List<FolderItem> = emptyList(),
        ) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    init {
        // PodHopper: reload whenever the subscribed podcasts or the folders change, so podcasts that
        // sync brings in appear while this screen is open instead of only on the next visit. Both
        // flows emit on start, which gives the first load.
        viewModelScope.launch(Dispatchers.IO) {
            combine(podcastManager.findSubscribedFlow(), folderManager.observeFolders()) { _, _ -> }
                .collectLatest { _uiState.value = load() }
        }
    }

    private suspend fun load(): UiState {
        val folder: Folder?
        val items: List<FolderItem>
        if (folderUuid.isEmpty()) {
            items = folderManager.getHomeFolder()
            folder = null
        } else {
            val podcasts = folderManager.findFolderPodcastsSorted(folderUuid)
            items = podcasts.map { FolderItem.Podcast(it) }
            folder = folderManager.findByUuid(folderUuid)
        }
        return if (items.isNotEmpty()) {
            UiState.Loaded(folder = folder, items = items)
        } else {
            UiState.Empty
        }
    }
}
