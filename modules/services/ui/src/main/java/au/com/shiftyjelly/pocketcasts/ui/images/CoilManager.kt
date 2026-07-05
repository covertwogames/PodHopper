package au.com.shiftyjelly.pocketcasts.ui.images

import coil3.ImageLoader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoilManager @Inject constructor(val imageLoader: ImageLoader) {

    fun clearCache(artworkUrl: String?) {
        // PodHopper: cache entries used to be evicted by rebuilding the Pocket Casts CDN urls for
        // a podcast uuid. Artwork is keyed by the feed's own image url now, so evict that instead.
        if (!artworkUrl.isNullOrBlank()) {
            imageLoader.diskCache?.remove(artworkUrl)
        }
        // clear the whole image memory cache, as clearing individual images didn't work
        imageLoader.memoryCache?.clear()
    }

    fun clearAll() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }
}
