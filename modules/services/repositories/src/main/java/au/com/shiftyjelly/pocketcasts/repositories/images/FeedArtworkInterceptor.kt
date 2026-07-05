package au.com.shiftyjelly.pocketcasts.repositories.images

import au.com.shiftyjelly.pocketcasts.models.db.dao.PodcastDao
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import javax.inject.Inject

/**
 * Resolves podcast artwork to the feed's own image when one is stored locally.
 *
 * Surfaces that only have a podcast uuid to hand (the podcast page header, playlist artwork, the
 * compose PodcastImage component) and the podcast-artwork fallback for episodes (players, Up Next,
 * episode rows) address artwork with the podhopper-artwork marker uri built by
 * [podcastArtworkUri]. Every artwork request flows through this one interceptor, so it is the
 * single place that swaps the marker for the real feed image url the feed parser saved on the
 * podcast. This fixes artwork on all surfaces at once: library, podcast page, player, episode
 * screens, widgets, notifications and Auto. A podcast with no stored feed artwork falls through
 * unresolved, and the request's error drawable shows.
 *
 * The legacy Pocket Casts id-based url shape is still recognized as a protective net, so any
 * stray CDN url from an old code path resolves locally instead of ever reaching a Pocket Casts
 * server. Search results are unaffected (they pass direct iTunes artwork urls).
 */
class FeedArtworkInterceptor @Inject constructor(
    private val podcastDao: PodcastDao,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data as? String
        val uuid = data?.let { podcastUuidFromMarkerUri(it) ?: podcastUuidFromArtworkUrl(it) }
        if (uuid != null) {
            val feedArtwork = podcastDao.findThumbnailUrlByUuid(uuid)
            if (!feedArtwork.isNullOrBlank()) {
                val request = chain.request.newBuilder().data(feedArtwork).build()
                return chain.withRequest(request).proceed()
            }
        }
        return chain.proceed()
    }

    private fun podcastUuidFromMarkerUri(uri: String): String? {
        if (!uri.startsWith(PODCAST_ARTWORK_URI_PREFIX)) {
            return null
        }
        return uri.removePrefix(PODCAST_ARTWORK_URI_PREFIX).takeIf { it.isNotBlank() }
    }

    private fun podcastUuidFromArtworkUrl(url: String): String? {
        // Pocket Casts podcast artwork url shape: .../discover/images/webp/<size>/<uuid>.webp
        if (!url.contains("/discover/images/webp/")) {
            return null
        }
        val fileName = url.substringAfterLast('/')
        if (!fileName.endsWith(".webp")) {
            return null
        }
        return fileName.removeSuffix(".webp").takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PODCAST_ARTWORK_URI_PREFIX = "podhopper-artwork://podcast/"

        /**
         * The marker uri carrying a podcast uuid to this interceptor. The scheme has no fetcher,
         * so an unresolved marker fails cleanly to the request's error drawable rather than ever
         * touching the network.
         */
        fun podcastArtworkUri(podcastUuid: String): String = PODCAST_ARTWORK_URI_PREFIX + podcastUuid
    }
}
