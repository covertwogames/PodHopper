package au.com.shiftyjelly.pocketcasts.servers

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode

class RefreshResponse {

    private val updates = HashMap<String, List<PodcastEpisode>>()

    // PodHopper watch: the watch's streamed refresh passes only the episodes not already stored, so
    // it records each feed's total episode count here. Nothing else sets it, so everywhere else the
    // refresh pipeline reads the count from the full episode list, exactly as before.
    private val totalEpisodeCounts = HashMap<String, Int>()

    // PodHopper: each downloaded feed's version markers (ETag, Last-Modified), keyed by feed URL.
    // The refresh records them only after it has stored that feed's new episodes.
    private val feedValidators = HashMap<String, Pair<String?, String?>>()

    fun getPodcastsWithUpdates(): Set<String> {
        return updates.keys
    }

    fun getUpdatesForPodcast(podcastUuid: String): List<PodcastEpisode>? {
        return updates[podcastUuid]
    }

    fun addUpdate(podcastUuid: String, episodeIds: List<PodcastEpisode>) {
        updates[podcastUuid] = episodeIds
    }

    fun getTotalEpisodeCount(podcastUuid: String): Int? {
        return totalEpisodeCounts[podcastUuid]
    }

    fun setTotalEpisodeCount(podcastUuid: String, count: Int) {
        totalEpisodeCounts[podcastUuid] = count
    }

    fun getFeedValidators(): Map<String, Pair<String?, String?>> {
        return feedValidators
    }

    fun setFeedValidators(feedUrl: String, etag: String?, lastModified: String?) {
        feedValidators[feedUrl] = etag to lastModified
    }

    fun merge(other: RefreshResponse): RefreshResponse {
        val newResponse = RefreshResponse()
        newResponse.updates += this.updates
        newResponse.updates += other.updates
        newResponse.totalEpisodeCounts += this.totalEpisodeCounts
        newResponse.totalEpisodeCounts += other.totalEpisodeCounts
        newResponse.feedValidators += this.feedValidators
        newResponse.feedValidators += other.feedValidators
        return newResponse
    }

    override fun equals(other: Any?) = other is RefreshResponse && other.updates == updates

    override fun hashCode() = updates.hashCode()

    override fun toString() = "RefreshResponse(updates=$updates)"
}
