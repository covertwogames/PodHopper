package au.com.shiftyjelly.pocketcasts.podcasts.helper.search

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.PodcastScreenSearchClearedEvent
import com.automattic.eventhorizon.PodcastScreenSearchPerformedEvent
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class EpisodeSearchHandler @Inject constructor(
    settings: Settings,
    private val episodeManager: EpisodeManager,
    private val eventHorizon: EventHorizon,
) : SearchHandler<BaseEpisode>() {
    private val searchDebounce = settings.getEpisodeSearchDebounceMs()

    override fun getSearchResultsObservable(podcastUuid: String): Observable<SearchResult> = searchQueryRelay.debounce {
        // Only debounce when search has a value otherwise it slows down loading the pages
        if (it.isEmpty()) {
            Observable.empty()
        } else {
            Observable.timer(searchDebounce, TimeUnit.MILLISECONDS)
        }
    }.switchMapSingle { searchTerm ->
        if (searchTerm.length > 1) {
            // PodHopper: episode search within a podcast used to be sent to the Pocket Casts
            // cache server. Every episode of the podcast is already in the local database, so
            // match titles locally instead. The podcast uuid is app generated (hex), so it is
            // safe to place in the where clause; the user's search term never touches SQL.
            Single
                .fromCallable {
                    val episodes = episodeManager.findEpisodesWhereBlocking(
                        queryAfterWhere = "podcast_id = '$podcastUuid'",
                        forSubscribedPodcastsOnly = false,
                    )
                    val matchingUuids = episodes
                        .filter { episode -> episode.title.contains(searchTerm, ignoreCase = true) }
                        .map { episode -> episode.uuid }
                    SearchResult(searchTerm, matchingUuids)
                }
                .subscribeOn(Schedulers.io())
                .onErrorReturnItem(noSearchResult)
        } else {
            Single.just(noSearchResult)
        }
    }.distinctUntilChanged()

    override fun trackSearchIfNeeded(oldValue: String, newValue: String) {
        val event = if (oldValue.isEmpty() && newValue.isNotEmpty()) {
            PodcastScreenSearchPerformedEvent
        } else if (oldValue.isNotEmpty() && newValue.isEmpty()) {
            PodcastScreenSearchClearedEvent
        } else {
            null
        }
        event?.let(eventHorizon::track)
    }
}
