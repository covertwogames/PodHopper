package au.com.shiftyjelly.pocketcasts.repositories.ratings

import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastRatings
import au.com.shiftyjelly.pocketcasts.models.entity.UserPodcastRating
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncManager
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

class RatingsManagerImpl @Inject constructor(
    private val syncManager: SyncManager,
    appDatabase: AppDatabase,
) : RatingsManager,
    CoroutineScope {
    private val podcastRatingsDao = appDatabase.podcastRatingsDao()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    override fun podcastRatings(podcastUuid: String) = podcastRatingsDao.podcastRatingsFlow(podcastUuid)
        .map { it.firstOrNull() ?: noRatings(podcastUuid) }

    override suspend fun refreshPodcastRatings(podcastUuid: String, useCache: Boolean) {
        // PodHopper: ratings used to be fetched from the Pocket Casts cache server whenever a
        // podcast page opened. Feed podcasts have no entry there, so never fetch; the ratings flow
        // falls back to the zero-rating placeholder from the local database.
    }

    override suspend fun submitPodcastRating(rating: UserPodcastRating): PodcastRatingResult = try {
        syncManager.addPodcastRating(rating.podcastUuid, rating.rating)
        podcastRatingsDao.insertOrReplaceUserRatings(listOf(rating))
        PodcastRatingResult.Success(rating.rating.toDouble())
    } catch (e: Exception) {
        PodcastRatingResult.Error(e)
    }

    override suspend fun getPodcastRating(podcastUuid: String): PodcastRatingResult = try {
        val rate = syncManager.getPodcastRating(podcastUuid)
        PodcastRatingResult.Success(rate.podcastRating.toDouble())
    } catch (e: HttpException) {
        if (e.code() == 404) {
            PodcastRatingResult.NotFound
        } else {
            PodcastRatingResult.Error(e)
        }
    } catch (e: Exception) {
        PodcastRatingResult.Error(e)
    }

    override suspend fun updateUserRatings(ratings: List<UserPodcastRating>) {
        podcastRatingsDao.updateUserRatings(ratings)
    }

    companion object {
        private fun noRatings(podcastUuid: String) = PodcastRatings(
            podcastUuid = podcastUuid,
            average = 0.0,
            total = 0,
        )
    }
}

sealed class PodcastRatingResult {
    data class Success(val rating: Double) : PodcastRatingResult()
    data class Error(val exception: Exception) : PodcastRatingResult()
    data object NotFound : PodcastRatingResult()
}
