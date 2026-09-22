package au.com.shiftyjelly.pocketcasts.repositories.refresh

import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedParser
import au.com.shiftyjelly.pocketcasts.servers.RefreshResponse
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * PodHopper client-side refresh.
 *
 * Re-parses each subscribed podcast's RSS feed on-device (no Pocket Casts server) and returns the
 * parsed episodes in the same [RefreshResponse] shape the existing refresh pipeline already
 * consumes. The pipeline's add step then inserts only episodes that are not already stored, matched
 * by the deterministic episode uuid, so existing, played, and in-progress episodes are left
 * untouched and nothing is duplicated.
 *
 * Each feed is parsed independently: a single feed that is unreachable or malformed is skipped and
 * logged, and the rest of the refresh continues.
 */
@Singleton
class FeedRefreshManager @Inject constructor(
    private val feedParser: FeedParser,
) {

    fun refreshPodcastsLocally(podcasts: List<Podcast>): RefreshResponse {
        val response = RefreshResponse()
        val semaphore = Semaphore(MAX_CONCURRENT_FEED_REFRESHES)
        // Parse feeds in parallel with a bounded number running at once, so the network waits overlap
        // instead of stacking up one podcast at a time. Each result is collected first, then added to
        // the shared response sequentially below to keep that step single-threaded.
        val updates = runBlocking {
            podcasts.map { podcast ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val feedUrl = podcast.podcastUrl
                        if (feedUrl.isNullOrBlank()) {
                            return@withPermit null
                        }
                        val parsed = feedParser.parse(feedUrl)
                        if (parsed == null) {
                            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Refresh - skipped ${podcast.uuid}, feed unavailable")
                            return@withPermit null
                        }
                        if (parsed.episodes.isEmpty()) {
                            null
                        } else {
                            podcast.uuid to parsed.episodes
                        }
                    }
                }
            }.awaitAll()
        }
        for (update in updates) {
            if (update != null) {
                response.addUpdate(update.first, update.second)
            }
        }
        return response
    }

    /**
     * PodHopper watch: the same refresh as [refreshPodcastsLocally], sized for a watch's small per-app
     * memory limit. Feeds are read one at a time and a batch of episodes at a time, and only episodes
     * [findStoredUuids] reports as not already stored are kept, so memory holds one feed's new
     * episodes rather than every episode of every feed at once. Each feed's total episode count is
     * recorded alongside, so the refresh pipeline makes exactly the decisions it makes from a full
     * list. A feed that cannot be read is skipped and logged, as in [refreshPodcastsLocally].
     */
    fun refreshPodcastsStreaming(
        podcasts: List<Podcast>,
        findStoredUuids: (List<String>) -> Set<String>,
    ): RefreshResponse {
        val response = RefreshResponse()
        for (podcast in podcasts) {
            val feedUrl = podcast.podcastUrl
            if (feedUrl.isNullOrBlank()) {
                continue
            }
            val newEpisodes = ArrayList<PodcastEpisode>()
            val result = feedParser.stream(feedUrl, FeedParser.STREAM_BATCH_SIZE) { _, episodes ->
                val stored = findStoredUuids(episodes.map { it.uuid })
                episodes.filterNotTo(newEpisodes) { it.uuid in stored }
            }
            when (result) {
                is FeedParser.StreamResult.Success -> if (result.episodeCount > 0) {
                    response.addUpdate(podcast.uuid, newEpisodes)
                    response.setTotalEpisodeCount(podcast.uuid, result.episodeCount)
                }

                is FeedParser.StreamResult.Failure -> {
                    LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Refresh - skipped ${podcast.uuid}, feed unavailable")
                }
            }
        }
        return response
    }

    companion object {
        private const val MAX_CONCURRENT_FEED_REFRESHES = 6
    }
}
