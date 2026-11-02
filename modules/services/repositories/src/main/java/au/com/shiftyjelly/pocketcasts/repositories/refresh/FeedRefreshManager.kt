package au.com.shiftyjelly.pocketcasts.repositories.refresh

import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedParser
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedValidatorStore
import au.com.shiftyjelly.pocketcasts.repositories.podcast.NewestEpisodes
import au.com.shiftyjelly.pocketcasts.servers.RefreshResponse
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
 *
 * Every refresh first asks each host whether the feed changed since the last refresh, using the
 * version markers in [FeedValidatorStore], so an unchanged feed costs a 304 reply with no download.
 */
@Singleton
class FeedRefreshManager @Inject constructor(
    private val feedParser: FeedParser,
    private val validatorStore: FeedValidatorStore,
) {

    private data class FeedUpdate(
        val podcastUuid: String,
        val feedUrl: String,
        val outcome: FeedOutcome,
    )

    /**
     * PodHopper: what one feed's refresh produced. A changed feed keeps only its episodes that are not
     * already stored, plus the feed's total episode count, so a parsed feed's full episode list is
     * let go as soon as that feed finishes instead of being held until every feed is done.
     */
    private sealed interface FeedOutcome {
        data object NotModified : FeedOutcome

        data object Failed : FeedOutcome

        data class Updated(
            val newEpisodes: List<PodcastEpisode>,
            val totalEpisodeCount: Int,
            val validators: FeedParser.FeedValidators?,
        ) : FeedOutcome
    }

    /**
     * Refreshes every subscribed feed on the phone and car.
     *
     * [findStoredUuids] returns the uuids of the episodes already stored for a podcast. Each changed
     * feed keeps only the episodes not in that set, and records its total episode count alongside,
     * so the refresh pipeline makes exactly the decisions it made from the full list: its add step
     * only ever inserted episodes that were not already stored. This mirrors what the watch's
     * [refreshPodcastsStreaming] already does, without the watch's newest-episodes cap.
     */
    fun refreshPodcastsLocally(
        podcasts: List<Podcast>,
        findStoredUuids: suspend (podcastUuid: String) -> Set<String>,
    ): RefreshResponse {
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
                        FeedUpdate(
                            podcastUuid = podcast.uuid,
                            feedUrl = feedUrl,
                            outcome = refreshFeed(podcast.uuid, feedUrl, findStoredUuids),
                        )
                    }
                }
            }.awaitAll()
        }
        var unchanged = 0
        for (update in updates) {
            if (update == null) {
                continue
            }
            when (val outcome = update.outcome) {
                is FeedOutcome.NotModified -> unchanged++

                is FeedOutcome.Failed -> {
                    LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Refresh - skipped ${update.podcastUuid}, feed unavailable")
                }

                is FeedOutcome.Updated -> {
                    if (outcome.totalEpisodeCount > 0) {
                        response.addUpdate(update.podcastUuid, outcome.newEpisodes)
                        response.setTotalEpisodeCount(update.podcastUuid, outcome.totalEpisodeCount)
                    }
                    outcome.validators?.let { response.setFeedValidators(update.feedUrl, it.etag, it.lastModified) }
                }
            }
        }
        if (unchanged > 0) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Refresh - $unchanged of ${podcasts.size} feeds unchanged since the last refresh")
        }
        return response
    }

    private suspend fun refreshFeed(
        podcastUuid: String,
        feedUrl: String,
        findStoredUuids: suspend (podcastUuid: String) -> Set<String>,
    ): FeedOutcome {
        return try {
            when (val result = feedParser.fetchForRefresh(feedUrl, validatorStore.get(feedUrl))) {
                is FeedParser.RefreshFetchResult.NotModified -> FeedOutcome.NotModified

                is FeedParser.RefreshFetchResult.Failure -> FeedOutcome.Failed

                is FeedParser.RefreshFetchResult.Updated -> {
                    val episodes = result.feed.episodes
                    val stored = if (episodes.isEmpty()) emptySet() else storedUuidsOrNone(podcastUuid, findStoredUuids)
                    FeedOutcome.Updated(
                        newEpisodes = episodes.filterNot { it.uuid in stored },
                        totalEpisodeCount = episodes.size,
                        validators = result.validators,
                    )
                }
            }
        } catch (e: OutOfMemoryError) {
            // A single enormous feed can be too big to read. Skip it for this refresh rather than
            // letting the error take the whole app down; the rest of the feeds still refresh.
            LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "Refresh - skipped $podcastUuid, feed too large to read")
            FeedOutcome.Failed
        }
    }

    /**
     * The uuids already stored for [podcastUuid]. If the lookup fails, nothing is filtered out, which
     * is the old behaviour: the refresh's add step still skips every episode that is already stored.
     */
    private suspend fun storedUuidsOrNone(
        podcastUuid: String,
        findStoredUuids: suspend (podcastUuid: String) -> Set<String>,
    ): Set<String> {
        return try {
            findStoredUuids(podcastUuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "Refresh - could not read stored episodes for $podcastUuid")
            emptySet()
        }
    }

    /**
     * PodHopper watch: the same refresh as [refreshPodcastsLocally], sized for a watch, which keeps only
     * each podcast's newest [FeedParser.WATCH_EPISODE_CAP] episodes and has a small per-app memory
     * limit. Feeds are read one at a time and a batch of episodes at a time, and only episodes
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
        var unchanged = 0
        for (podcast in podcasts) {
            val feedUrl = podcast.podcastUrl
            if (feedUrl.isNullOrBlank()) {
                continue
            }
            // The watch keeps only each podcast's newest FeedParser.WATCH_EPISODE_CAP episodes.
            //
            // With version markers (the last refresh of this feed was complete), only episodes newer
            // than the podcast's latest stored episode count as new, and reading stops once
            // STORED_IN_A_ROW_TO_STOP episodes in a row are already stored: in a newest-first feed
            // everything after them is older still.
            //
            // Without markers (the first refresh, or after a resubscribe) the refresh restores the
            // feed's newest episodes that are missing, reading only the first WATCH_EPISODE_CAP of a
            // newest-first feed.
            //
            // If the dates ever run oldest to newest, the order cannot be relied on and the whole
            // feed is read, holding only the newest few in memory. Stopping only happens after a full
            // batch, so the episode count recorded below is at least WATCH_EPISODE_CAP (10 or more),
            // which leaves the refresh's decisions from it unchanged.
            val validators = validatorStore.get(feedUrl)
            val hasMarkers = validators != null
            val latestStored = podcast.latestEpisodeDate
            val kept = NewestEpisodes(FeedParser.WATCH_EPISODE_CAP)
            var storedInARow = 0
            var itemsRead = 0
            var newestFirst = true
            var previousDate: Date? = null
            val result = feedParser.stream(
                feedUrl = feedUrl,
                batchSize = FeedParser.WATCH_EPISODE_CAP,
                validators = validators,
                continueReading = {
                    when {
                        !newestFirst -> true
                        hasMarkers -> storedInARow < STORED_IN_A_ROW_TO_STOP
                        else -> itemsRead < FeedParser.WATCH_EPISODE_CAP
                    }
                },
            ) { _, episodes ->
                val stored = findStoredUuids(episodes.map { it.uuid })
                for (episode in episodes) {
                    itemsRead++
                    val previous = previousDate
                    if (previous != null && episode.publishedDate.after(previous)) {
                        newestFirst = false
                    }
                    previousDate = episode.publishedDate
                    val isStored = episode.uuid in stored
                    storedInARow = if (isStored) storedInARow + 1 else 0
                    if (!hasMarkers) {
                        kept.add(episode)
                    } else if (!isStored && (latestStored == null || episode.publishedDate.after(latestStored))) {
                        kept.add(episode)
                    }
                }
            }
            when (result) {
                is FeedParser.StreamResult.Success -> {
                    if (result.episodeCount > 0) {
                        val newest = kept.toList()
                        val newEpisodes = if (hasMarkers) {
                            newest
                        } else {
                            val storedNow = findStoredUuids(newest.map { it.uuid })
                            newest.filterNot { it.uuid in storedNow }
                        }
                        response.addUpdate(podcast.uuid, newEpisodes)
                        response.setTotalEpisodeCount(podcast.uuid, result.episodeCount)
                    }
                    result.validators?.let { response.setFeedValidators(feedUrl, it.etag, it.lastModified) }
                }

                is FeedParser.StreamResult.NotModified -> unchanged++

                is FeedParser.StreamResult.Failure -> {
                    LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Refresh - skipped ${podcast.uuid}, feed unavailable")
                }
            }
        }
        if (unchanged > 0) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Refresh - $unchanged of ${podcasts.size} feeds unchanged since the last refresh")
        }
        return response
    }

    /**
     * PodHopper: records the version markers of every feed [response] downloaded. Call only after the
     * refresh has stored the response's new episodes, so a refresh cut short never makes the next one
     * skip episodes it did not save.
     */
    fun commitFeedValidators(response: RefreshResponse) {
        validatorStore.putAll(
            response.getFeedValidators().mapValues { (_, markers) ->
                FeedParser.FeedValidators(etag = markers.first, lastModified = markers.second)
            },
        )
    }

    companion object {
        // PodHopper: each feed being read holds its whole download and parsed episode list in memory,
        // so this bounds the peak. Three keeps network waits overlapping while leaving enough headroom
        // for very large feeds, such as right after a new user imports a big podcast list.
        private const val MAX_CONCURRENT_FEED_REFRESHES = 3
        private const val STORED_IN_A_ROW_TO_STOP = 5
    }
}
