package au.com.shiftyjelly.pocketcasts.repositories.refresh

import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedParser
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedValidatorStore
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedRefreshManagerTest {

    private lateinit var feedParser: FeedParser
    private lateinit var validatorStore: FeedValidatorStore
    private lateinit var manager: FeedRefreshManager

    @Before
    fun setUp() {
        feedParser = mock()
        validatorStore = mock()
        manager = FeedRefreshManager(feedParser, validatorStore)
    }

    @Test
    fun `changed feed keeps only episodes that are not stored and records the full count`() {
        val podcast = podcast("p1", "https://example.com/p1.xml")
        val episodes = listOf(episode("e1"), episode("e2"), episode("e3"))
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p1.xml"), anyOrNull()))
            .thenReturn(updated(podcast, episodes, FeedParser.FeedValidators(etag = "tag", lastModified = null)))

        val response = manager.refreshPodcastsLocally(listOf(podcast)) { setOf("e1", "e3") }

        assertEquals(listOf("e2"), response.getUpdatesForPodcast("p1")?.map { it.uuid })
        assertEquals(3, response.getTotalEpisodeCount("p1"))
        assertEquals("tag" to null, response.getFeedValidators()["https://example.com/p1.xml"])
    }

    @Test
    fun `changed feed with every episode stored still reports the feed`() {
        val podcast = podcast("p1", "https://example.com/p1.xml")
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p1.xml"), anyOrNull()))
            .thenReturn(updated(podcast, listOf(episode("e1"), episode("e2")), validators = null))

        val response = manager.refreshPodcastsLocally(listOf(podcast)) { setOf("e1", "e2") }

        assertEquals(emptyList<PodcastEpisode>(), response.getUpdatesForPodcast("p1"))
        assertEquals(2, response.getTotalEpisodeCount("p1"))
    }

    @Test
    fun `changed feed with no episodes is not reported`() {
        val podcast = podcast("p1", "https://example.com/p1.xml")
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p1.xml"), anyOrNull()))
            .thenReturn(updated(podcast, emptyList(), FeedParser.FeedValidators(etag = "tag", lastModified = null)))

        val response = manager.refreshPodcastsLocally(listOf(podcast)) { emptySet() }

        assertTrue(response.getPodcastsWithUpdates().isEmpty())
        assertEquals("tag" to null, response.getFeedValidators()["https://example.com/p1.xml"])
    }

    @Test
    fun `unchanged and failed feeds are not reported`() {
        val unchanged = podcast("p1", "https://example.com/p1.xml")
        val failed = podcast("p2", "https://example.com/p2.xml")
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p1.xml"), anyOrNull()))
            .thenReturn(FeedParser.RefreshFetchResult.NotModified)
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p2.xml"), anyOrNull()))
            .thenReturn(FeedParser.RefreshFetchResult.Failure("HTTP 500"))

        val response = manager.refreshPodcastsLocally(listOf(unchanged, failed)) { emptySet() }

        assertTrue(response.getPodcastsWithUpdates().isEmpty())
        assertTrue(response.getFeedValidators().isEmpty())
    }

    @Test
    fun `a feed too large to read is skipped and the other feeds still refresh`() {
        val huge = podcast("p1", "https://example.com/huge.xml")
        val normal = podcast("p2", "https://example.com/p2.xml")
        whenever(feedParser.fetchForRefresh(eq("https://example.com/huge.xml"), anyOrNull()))
            .thenThrow(OutOfMemoryError("feed too large"))
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p2.xml"), anyOrNull()))
            .thenReturn(updated(normal, listOf(episode("e1")), validators = null))

        val response = manager.refreshPodcastsLocally(listOf(huge, normal)) { emptySet() }

        assertNull(response.getUpdatesForPodcast("p1"))
        assertEquals(listOf("e1"), response.getUpdatesForPodcast("p2")?.map { it.uuid })
    }

    @Test
    fun `stored episode lookup failure keeps every episode`() {
        val podcast = podcast("p1", "https://example.com/p1.xml")
        whenever(feedParser.fetchForRefresh(eq("https://example.com/p1.xml"), anyOrNull()))
            .thenReturn(updated(podcast, listOf(episode("e1"), episode("e2")), validators = null))

        val response = manager.refreshPodcastsLocally(listOf(podcast)) { throw IllegalStateException("database unavailable") }

        assertEquals(listOf("e1", "e2"), response.getUpdatesForPodcast("p1")?.map { it.uuid })
        assertEquals(2, response.getTotalEpisodeCount("p1"))
    }

    private fun podcast(uuid: String, feedUrl: String) = Podcast(uuid = uuid, podcastUrl = feedUrl)

    private fun episode(uuid: String) = PodcastEpisode(uuid = uuid, publishedDate = Date())

    private fun updated(
        podcast: Podcast,
        episodes: List<PodcastEpisode>,
        validators: FeedParser.FeedValidators?,
    ) = FeedParser.RefreshFetchResult.Updated(FeedParser.ParsedFeed(podcast, episodes), validators)
}
