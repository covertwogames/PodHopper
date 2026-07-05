package au.com.shiftyjelly.pocketcasts.repositories.extensions

import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode

@Suppress("UNUSED_PARAMETER")
fun UserEpisode.getUrlForArtwork(themeIsDark: Boolean = false, thumbnail: Boolean = false): String {
    // PodHopper: the tint placeholder art used to come from the Pocket Casts static server. A user
    // episode without its own artwork returns an empty string and callers show their placeholder.
    if (tintColorIndex == 0 && artworkUrl != null) {
        artworkUrl?.let { return@getUrlForArtwork it }
    }
    return ""
}
