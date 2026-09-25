package au.com.shiftyjelly.pocketcasts.repositories.shownotes

import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.servers.shownotes.ShowNotesState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ShowNotesManager @Inject constructor(
    private val episodeManager: EpisodeManager,
) {
    // PodHopper: show notes used to come from the Pocket Casts show-notes server, keyed by
    // their episode ids. Feed episodes are not in that database, so serve the notes the RSS
    // feed already provides (stored locally as the episode description) instead of fetching.
    fun loadShowNotesFlow(podcastUuid: String, episodeUuid: String): Flow<ShowNotesState> = flow {
        emit(ShowNotesState.Loading)
        emit(loadShowNotes(podcastUuid = podcastUuid, episodeUuid = episodeUuid))
    }

    suspend fun loadShowNotes(podcastUuid: String, episodeUuid: String): ShowNotesState {
        val notes = episodeManager.findByUuid(episodeUuid)?.episodeDescription
        return if (!notes.isNullOrBlank()) {
            ShowNotesState.Loaded(notes)
        } else {
            ShowNotesState.NotFound
        }
    }
}
