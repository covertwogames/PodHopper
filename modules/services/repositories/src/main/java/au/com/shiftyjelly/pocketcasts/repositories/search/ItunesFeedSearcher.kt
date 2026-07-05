package au.com.shiftyjelly.pocketcasts.repositories.search

import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Searches the public iTunes podcast directory and returns real RSS feed URLs.
 *
 * This replaces the Pocket Casts search server for PodHopper. The feed URL is the
 * identity of a podcast in the client feed engine, so every result here carries the
 * feed URL that the subscribe action hands to the feed parser. No API key is required.
 */
@Singleton
class ItunesFeedSearcher @Inject constructor() {
    data class Result(
        val title: String,
        val author: String,
        val feedUrl: String,
        val imageUrl: String?,
    )

    private val httpClient = OkHttpClient()

    suspend fun search(term: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val request = Request.Builder()
                .url("https://itunes.apple.com/search?media=podcast&limit=25&term=$encoded")
                .header("User-Agent", "PodHopper")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use emptyList<Result>()
                }
                val json = JSONObject(response.body.string())
                val array = json.optJSONArray("results") ?: return@use emptyList<Result>()
                val results = mutableListOf<Result>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val feedUrl = item.optString("feedUrl", "")
                    if (feedUrl.isBlank()) {
                        continue
                    }
                    val image = item.optString("artworkUrl600", "").ifBlank { item.optString("artworkUrl100", "") }
                    results.add(
                        Result(
                            title = item.optString("collectionName", "Unknown"),
                            author = item.optString("artistName", ""),
                            feedUrl = feedUrl,
                            imageUrl = image.ifBlank { null },
                        ),
                    )
                }
                results
            }
        } catch (e: Exception) {
            Timber.e(e, "PodHopper iTunes search failed for term: $term")
            emptyList()
        }
    }

    /**
     * PodHopper: resolves an Apple Podcasts page link (podcasts.apple.com or itunes.apple.com) to
     * the podcast's real RSS feed url via the public iTunes lookup API, the same directory the
     * search above uses. Returns null when the url carries no iTunes id or the lookup returns no
     * feed url.
     */
    suspend fun resolveApplePodcastsUrl(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val itunesId = APPLE_PODCASTS_ID_PATTERN.find(pageUrl)?.groupValues?.get(1) ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("https://itunes.apple.com/lookup?id=$itunesId")
                .header("User-Agent", "PodHopper")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("PodHopper iTunes lookup HTTP ${response.code} for id $itunesId")
                    return@use null
                }
                val results = JSONObject(response.body.string()).optJSONArray("results") ?: return@use null
                val first = results.optJSONObject(0) ?: return@use null
                first.optString("feedUrl", "").ifBlank { null }
            }
        } catch (e: Exception) {
            Timber.e(e, "PodHopper iTunes lookup failed for url: $pageUrl")
            null
        }
    }

    companion object {
        // Matches the iTunes id path segment in Apple Podcasts links,
        // e.g. https://podcasts.apple.com/us/podcast/show-name/id123456789
        private val APPLE_PODCASTS_ID_PATTERN = Regex("/id(\\d+)")
    }
}
