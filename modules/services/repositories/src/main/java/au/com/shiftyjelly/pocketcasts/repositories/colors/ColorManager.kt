package au.com.shiftyjelly.pocketcasts.repositories.colors

import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.servers.cdn.ArtworkColors
import au.com.shiftyjelly.pocketcasts.utils.Optional
import io.reactivex.Single
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ColorManager @Inject constructor() {

    companion object {
        const val DEFAULT_BACKGROUND_COLOR = 0xFF3D3D3D.toInt()

        fun getBackgroundColor(podcast: Podcast?): Int = colorOrDefault(podcast?.backgroundColor, DEFAULT_BACKGROUND_COLOR)

        private fun colorOrDefault(color: Int?, defaultColor: Int): Int {
            return if (color == null || color == 0) {
                defaultColor
            } else {
                color
            }
        }
    }

    // PodHopper: podcast theme colors used to be fetched from the Pocket Casts static server,
    // which has no entry for feed podcasts. Never fetch; callers fall back to the default color
    // and to on-device palette extraction where that exists.

    @Suppress("UNUSED_PARAMETER")
    fun downloadColors(podcastUuid: String): Single<Optional<ArtworkColors>> {
        return Single.just(Optional.empty())
    }

    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun updateColors(podcasts: List<Podcast>) {
        // PodHopper: no-op, no remote color fetch.
    }
}
