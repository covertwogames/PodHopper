package au.com.shiftyjelly.pocketcasts.repositories.extensions

import au.com.shiftyjelly.pocketcasts.models.entity.Podcast

@Suppress("UNUSED_PARAMETER")
fun Podcast.getArtworkUrl(size: Int): String {
    // PodHopper: feed podcasts carry their own artwork url from the RSS feed on thumbnail_url.
    // The Pocket Casts image server has no entry for feed podcasts, so there is no fallback: a
    // podcast without feed artwork returns an empty string and callers show their placeholder.
    return thumbnailUrl.orEmpty()
}
