package au.com.shiftyjelly.pocketcasts.repositories.images

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.EpisodeFileMetadata
import au.com.shiftyjelly.pocketcasts.utils.extensions.dpToPx
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import coil3.transform.Transformation
import java.io.File
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast as PodcastEntity
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode as PodcastEpisodeEntity
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode as UserEpisodeEntity

data class PocketCastsImageRequestFactory(
    val context: Context,
    private val isDarkTheme: Boolean = false,
    private val cornerRadius: Int = 0,
    private val size: Int? = null,
    private val placeholderType: PlaceholderType = PlaceholderType.Large,
    private val transformations: List<Transformation> = emptyList(),
    private val showErrorPlaceholder: Boolean = true,
    private val crossfade: Boolean = true,
) {
    private val actualCornerRadius = cornerRadius.dpToPx(context)
    private val actualSize = size?.dpToPx(context)?.takeIf { it > 0 }

    fun smallSize() = copy(size = 128, crossfade = false)

    fun createForPodcast(
        podcastUuid: String?,
        onSuccess: () -> Unit = {},
    ) = create(RequestType.Podcast(podcastUuid), onSuccess)

    fun createForFileOrUrl(
        filePathOrUrl: String,
        onSuccess: () -> Unit = {},
    ) = create(RequestType.FileOrUrl(filePathOrUrl), onSuccess)

    fun create(
        podcast: PodcastEntity,
        onSuccess: () -> Unit = {},
    ): ImageRequest {
        // PodHopper: feed podcasts carry their own artwork url from the RSS feed. Use it directly
        // instead of the Pocket Casts image server, which has no entry for feed podcasts.
        val feedArtwork = podcast.thumbnailUrl
        return if (!feedArtwork.isNullOrBlank()) {
            create(RequestType.FileOrUrl(feedArtwork), onSuccess)
        } else {
            create(RequestType.Podcast(podcast.uuid), onSuccess)
        }
    }

    fun create(
        episode: BaseEpisode,
        useEpisodeArtwork: Boolean,
        onSuccess: () -> Unit = {},
    ) = when (episode) {
        is PodcastEpisodeEntity -> create(RequestType.PodcastEpisode(episode, useEpisodeArtwork), onSuccess)
        is UserEpisodeEntity -> create(RequestType.UserEpisode(episode, useEpisodeArtwork), onSuccess)
    }

    private fun create(
        type: RequestType,
        onSuccess: () -> Unit,
    ) = ImageRequest.Builder(context)
        .data(type.data(context))
        .let { if (placeholderType == PlaceholderType.None) it else it.placeholder(placeholderId) }
        .let { if (showErrorPlaceholder) it.error(if (isDarkTheme) IR.drawable.defaultartwork_dark else IR.drawable.defaultartwork) else it }
        .transformations(
            buildList {
                if (actualCornerRadius > 0) {
                    add(RoundedCornersTransformation(actualCornerRadius.toFloat()))
                }
                addAll(transformations)
            },
        )
        .let { if (actualSize != null) it.size(actualSize) else it }
        .crossfade(crossfade)
        .listener(type.listener(context, onSuccess))
        .build()

    @get:DrawableRes private val placeholderId
        get() = when (placeholderType) {
            PlaceholderType.None -> 0
            PlaceholderType.Small -> if (isDarkTheme) IR.drawable.defaultartwork_small_dark else IR.drawable.defaultartwork_small
            PlaceholderType.Large -> if (isDarkTheme) IR.drawable.defaultartwork_dark else IR.drawable.defaultartwork
        }

    private fun RequestType.data(context: Context) = when (this) {
        is RequestType.Podcast -> data(context)
        is RequestType.PodcastEpisode -> data(context)
        is RequestType.UserEpisode -> data()
        is RequestType.FileOrUrl -> filePathOrUrl
    }

    // PodHopper: a bare podcast uuid used to be turned into a Pocket Casts artwork CDN url, which
    // has no entry for feed podcasts. Feed artwork flows through create(podcast)/FileOrUrl with
    // the real feed image url; a uuid-only request falls straight to the placeholder drawable.
    private fun RequestType.Podcast.data(context: Context) = placeholderId

    // PodHopper: episode rows used to fall back to the Pocket Casts artwork CDN keyed by the
    // podcast uuid, which 404s for feed podcasts. Use the feed's episode image, then the artwork
    // embedded in the downloaded file, then the placeholder drawable; never a Pocket Casts url.
    private fun RequestType.PodcastEpisode.data(context: Context): Any = if (useEpisodeArtwork) {
        episode.imageUrl ?: EpisodeFileMetadata.artworkCacheFile(context, episode.uuid).takeIf(File::exists) ?: placeholderId
    } else {
        EpisodeFileMetadata.artworkCacheFile(context, episode.uuid).takeIf(File::exists) ?: placeholderId
    }

    private fun RequestType.UserEpisode.data(): Any = if (useEpisodeArtwork) {
        EpisodeFileMetadata.artworkCacheFile(context, episode.uuid).takeIf(File::exists) ?: episode.artworkUrl() ?: placeholderId
    } else {
        episode.artworkUrl() ?: placeholderId
    }

    // PodHopper: user episode tint placeholders used to be Pocket Casts static-server pngs. A
    // user episode without its own artwork now falls to the local placeholder drawable instead.
    private fun UserEpisodeEntity.artworkUrl(): String? {
        return if (tintColorIndex == 0 && artworkUrl != null) {
            artworkUrl
        } else {
            null
        }
    }

    private fun RequestType.listener(context: Context, onSuccess: () -> Unit): ImageRequest.Listener? = object : ImageRequest.Listener {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) = onSuccess()
    }

    enum class PlaceholderType {
        None,
        Small,
        Large,
    }
}

fun ImageRequest.loadInto(view: ImageView) = context.imageLoader.enqueue(newBuilder().target(view).build())

private sealed interface RequestType {
    data class Podcast(val podcastUuid: String?) : RequestType
    data class PodcastEpisode(val episode: PodcastEpisodeEntity, val useEpisodeArtwork: Boolean) : RequestType
    data class UserEpisode(val episode: UserEpisodeEntity, val useEpisodeArtwork: Boolean) : RequestType
    data class FileOrUrl(val filePathOrUrl: String) : RequestType
}
