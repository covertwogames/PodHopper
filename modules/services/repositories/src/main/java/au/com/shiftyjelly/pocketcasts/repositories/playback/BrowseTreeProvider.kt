package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE
import androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.FolderItem
import au.com.shiftyjelly.pocketcasts.models.to.ImprovedSearchResultItem
import au.com.shiftyjelly.pocketcasts.models.to.PlaylistEpisode
import au.com.shiftyjelly.pocketcasts.models.type.PodcastsSortType
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.AutoPlaySource
import au.com.shiftyjelly.pocketcasts.repositories.search.ImprovedSearchManager
import au.com.shiftyjelly.pocketcasts.repositories.search.ItunesTopListLoader
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperSubscriptionSync
import au.com.shiftyjelly.pocketcasts.repositories.playback.auto.AutoConverter
import au.com.shiftyjelly.pocketcasts.repositories.playback.auto.AutoConverter.convertFolderToMediaItem
import au.com.shiftyjelly.pocketcasts.repositories.playback.auto.AutoConverter.convertPodcastToMediaItem
import au.com.shiftyjelly.pocketcasts.repositories.playlist.Playlist
import au.com.shiftyjelly.pocketcasts.repositories.playlist.PlaylistManager
import au.com.shiftyjelly.pocketcasts.repositories.playlist.PlaylistPreview
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedParser
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.UserEpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.Util
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.rx2.awaitSingleOrNull
import java.util.Locale
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

private const val DOWNLOADS_ROOT = "__DOWNLOADS__"
private const val FILES_ROOT = "__FILES__"
private const val EPISODE_LIMIT = 100
private const val DISCOVER_DISPLAY_LIMIT = 12
private const val NUM_SUGGESTED_ITEMS = 8

internal const val FILTERS_ROOT = "__FILTERS__"
internal const val DISCOVER_ROOT = "__DISCOVER__"
internal const val PROFILE_ROOT = "__PROFILE__"
internal const val PROFILE_FILES = "__PROFILE_FILES__"
internal const val PROFILE_STARRED = "__PROFILE_STARRED__"
internal const val PROFILE_LISTENING_HISTORY = "__LISTENING_HISTORY__"

@Singleton
class BrowseTreeProvider @Inject constructor(
    private val podcastManager: PodcastManager,
    private val episodeManager: EpisodeManager,
    private val folderManager: FolderManager,
    private val userEpisodeManager: UserEpisodeManager,
    private val playlistManager: PlaylistManager,
    private val upNextQueue: UpNextQueue,
    private val settings: Settings,
    private val improvedSearchManager: ImprovedSearchManager,
    private val itunesTopListLoader: ItunesTopListLoader,
    private val podHopperSubscriptionSync: PodHopperSubscriptionSync,
    private val feedParser: FeedParser,
) {

    // PodHopper: maps an Android Auto search result's media id (the feed's own uuid) to its rss feed
    // url, so that tapping an unsubscribed result can pull and parse the feed on-device. Lives on this
    // singleton for the life of the process, which spans the search-then-tap sequence.
    private val searchFeedUrls = ConcurrentHashMap<String, String>()
    private val searchImageUrls = ConcurrentHashMap<String, String>()

    fun getRootId(isRecent: Boolean, isSuggested: Boolean, hasCurrentEpisode: Boolean): String? {
        return when {
            isRecent -> {
                Timber.d("Browser root hint for recent items")
                if (hasCurrentEpisode) RECENT_ROOT else null
            }

            isSuggested -> {
                Timber.d("Browser root hint for suggested items")
                SUGGESTED_ROOT
            }

            else -> MEDIA_ID_ROOT
        }
    }

    suspend fun loadChildren(parentId: String, context: Context): List<MediaItem> {
        Timber.d("On load children: $parentId")
        // PodHopper: on the car, opening any browse node is a cue to check for subscription changes
        // made on other devices, so the Podcasts list feels current the moment it is opened. Mirrors
        // the phone's navigation poll. Throttled to once a second and a no-op while signed out, so it
        // is cheap. Gated on automotive, so phone-projected Android Auto never enters this branch.
        if (Util.isAutomotive(context)) {
            podHopperSubscriptionSync.pollSubscriptions()
        }
        return when (parentId) {
            RECENT_ROOT -> loadRecentChildren(context)

            SUGGESTED_ROOT -> loadSuggestedChildren(context)

            MEDIA_ID_ROOT -> if (Util.isAutomotive(context)) {
                loadAutomotiveRootChildren(context)
            } else {
                loadRootChildren(context)
            }

            UP_NEXT_ROOT -> loadUpNextChildren(context)

            PODCASTS_ROOT -> loadPodcastsChildren(context)

            FILES_ROOT -> loadFilesChildren(context)

            FILTERS_ROOT -> loadFiltersRoot(context)

            DISCOVER_ROOT -> loadDiscoverRoot(context)

            PROFILE_ROOT -> loadProfileRoot(context)

            PROFILE_FILES -> loadFilesChildren(context)

            PROFILE_STARRED -> loadStarredChildren(context)

            PROFILE_LISTENING_HISTORY -> loadListeningHistoryChildren(context)

            else -> {
                if (parentId.startsWith(FOLDER_ROOT_PREFIX)) {
                    loadFolderPodcastsChildren(folderUuid = parentId.substring(FOLDER_ROOT_PREFIX.length), context = context)
                } else {
                    loadEpisodeChildren(parentId, context)
                }
            }
        }
    }

    internal suspend fun loadRecentChildren(context: Context): List<MediaItem> {
        Timber.d("Loading recent children")
        val episodes = listOfNotNull(upNextQueue.currentEpisode)
        return convertEpisodesToMediaItems(episodes, context)
    }

    internal suspend fun loadUpNextChildren(context: Context): List<MediaItem> {
        Timber.d("Loading Up Next children")
        val episodes = mutableListOf<BaseEpisode>()
        upNextQueue.currentEpisode?.let { episodes.add(it) }
        episodes.addAll(upNextQueue.queueEpisodes)
        return convertEpisodesToMediaItems(episodes, context)
    }

    internal suspend fun loadSuggestedChildren(context: Context): List<MediaItem> {
        Timber.d("Loading suggested children")
        val episodes = mutableListOf<BaseEpisode>()
        val currentEpisode = upNextQueue.currentEpisode
        if (currentEpisode != null) {
            episodes.add(currentEpisode)
        }
        episodes.addAll(upNextQueue.queueEpisodes.take(NUM_SUGGESTED_ITEMS - 1))
        if (episodes.size < NUM_SUGGESTED_ITEMS) {
            val showPlayed = settings.autoShowPlayed.value
            val topPlaylist = getPlaylistPreviews().firstOrNull()
            if (topPlaylist != null) {
                val filterEpisodes = getPlaylistEpisodes(
                    uuid = topPlaylist.uuid,
                    filterEpisode = { playlistType, episode ->
                        when (playlistType) {
                            Playlist.Type.Manual -> showPlayed || !(episode.isFinished || episode.isArchived)
                            Playlist.Type.Smart -> true
                        }
                    },
                ).orEmpty()
                for (filterEpisode in filterEpisodes) {
                    if (episodes.size >= NUM_SUGGESTED_ITEMS) {
                        break
                    }
                    if (episodes.none { it.uuid == filterEpisode.uuid }) {
                        episodes.add(filterEpisode)
                    }
                }
            }
        }
        if (episodes.size < NUM_SUGGESTED_ITEMS) {
            val latestEpisode = episodeManager.findLatestEpisodeToPlayBlocking()
            if (latestEpisode != null && episodes.none { it.uuid == latestEpisode.uuid }) {
                episodes.add(latestEpisode)
            }
        }
        return convertEpisodesToMediaItems(episodes, context)
    }

    internal suspend fun loadRootChildren(context: Context): List<MediaItem> {
        val rootItems = ArrayList<MediaItem>()

        val podcastsMetadata = MediaMetadata.Builder()
            .setTitle(context.getString(LR.string.podcasts))
            .setArtworkUri(AutoConverter.getPodcastsBitmapUri(context))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        val podcastItem = MediaItem.Builder()
            .setMediaId(PODCASTS_ROOT)
            .setMediaMetadata(podcastsMetadata)
            .build()
        rootItems.add(podcastItem)

        for (playlist in getPlaylistPreviews()) {
            if (playlist.title.equals("video", ignoreCase = true)) continue

            val playlistItem = AutoConverter.convertPlaylistToMediaItem(context, playlist)
            rootItems.add(playlistItem)
        }

        val downloadsMetadata = MediaMetadata.Builder()
            .setTitle(context.getString(LR.string.downloads))
            .setArtworkUri(AutoConverter.getDownloadsBitmapUri(context))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        val downloadsItem = MediaItem.Builder()
            .setMediaId(DOWNLOADS_ROOT)
            .setMediaMetadata(downloadsMetadata)
            .build()
        rootItems.add(downloadsItem)

        // PodHopper: Up Next was missing from the Android Auto menu too, for the same reason as
        // the car: the node existed but nothing offered it.
        val upNextMetadata = MediaMetadata.Builder()
            .setTitle(context.getString(LR.string.up_next))
            .setArtworkUri(AutoConverter.getBitmapUri(drawable = IR.drawable.ic_upnext, context = context))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        val upNextItem = MediaItem.Builder()
            .setMediaId(UP_NEXT_ROOT)
            .setMediaMetadata(upNextMetadata)
            .build()
        rootItems.add(upNextItem)

        val filesMetadata = MediaMetadata.Builder()
            .setTitle(context.getString(LR.string.profile_navigation_files))
            .setArtworkUri(AutoConverter.getFilesBitmapUri(context))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        val filesItem = MediaItem.Builder()
            .setMediaId(FILES_ROOT)
            .setMediaMetadata(filesMetadata)
            .build()
        rootItems.add(filesItem)

        return rootItems
    }

    internal suspend fun loadPodcastsChildren(context: Context): List<MediaItem> {
        return if (settings.cachedSubscription.value != null) {
            folderManager.getHomeFolder().mapNotNull { item ->
                when (item) {
                    is FolderItem.Folder -> convertFolderToMediaItem(context, item.folder)

                    is FolderItem.Podcast -> convertPodcastToMediaItem(
                        podcast = item.podcast,
                        context = context,
                        useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                    )
                }
            }
        } else {
            podcastManager.findSubscribedSorted().mapNotNull { podcast ->
                convertPodcastToMediaItem(
                    podcast = podcast,
                    context = context,
                    useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                )
            }
        }
    }

    internal suspend fun loadFolderPodcastsChildren(folderUuid: String, context: Context): List<MediaItem> {
        return if (settings.cachedSubscription.value != null) {
            folderManager.findFolderPodcastsSorted(folderUuid).mapNotNull { podcast ->
                convertPodcastToMediaItem(
                    podcast = podcast,
                    context = context,
                    useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                )
            }
        } else {
            emptyList()
        }
    }

    internal suspend fun loadEpisodeChildren(parentId: String, context: Context): List<MediaItem> {
        val episodeItems = mutableListOf<MediaItem>()
        val autoPlaySource: AutoPlaySource

        val showPlayed = settings.autoShowPlayed.value

        val episodesWithSource = if (DOWNLOADS_ROOT == parentId) {
            autoPlaySource = AutoPlaySource.Predefined.Downloads
            episodeManager.findDownloadedEpisodesRxFlowable().blockingFirst() to ""
        } else {
            autoPlaySource = AutoPlaySource.fromId(parentId)
            val episodes = getPlaylistEpisodes(
                uuid = parentId,
                filterEpisode = { playlistType, episode ->
                    when (playlistType) {
                        Playlist.Type.Manual -> showPlayed || !(episode.isFinished || episode.isArchived)
                        Playlist.Type.Smart -> true
                    }
                },
            )
            if (episodes != null) {
                episodes to parentId
            } else {
                null
            }
        }
        if (episodesWithSource != null) {
            val (episodeList, sourceId) = episodesWithSource
            val topEpisodes = episodeList.take(EPISODE_LIMIT)
            if (topEpisodes.isNotEmpty()) {
                for (episode in topEpisodes) {
                    podcastManager.findPodcastByUuid(episode.podcastUuid)?.let { parentPodcast ->
                        episodeItems.add(
                            AutoConverter.convertEpisodeToMediaItem(
                                context,
                                episode,
                                parentPodcast,
                                sourceId = sourceId,
                                useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                            ),
                        )
                    }
                }
            }
        } else {
            // PodHopper: mirror the phone's onSubscribeToPodcast. On the phone, tapping an iTunes search
            // result subscribes through the feed engine unconditionally, whether or not the show already
            // exists locally. The Android Auto browse must do the same. We key off the feed url stored for
            // this result at search time: if we have one, subscribe before loading instead of gating on
            // "not already in the database". subscribeToFeedUrl is idempotent (it re-subscribes an existing
            // show, or parses and inserts a new one), so the tapped show always lands in the podcasts list
            // and syncs, rather than only previewing when it is already present unsubscribed.
            val feedUrl = searchFeedUrls[parentId]
            val podcastFound = if (feedUrl != null) {
                podcastManager.subscribeToFeedUrl(feedUrl)
                val subscribed = podcastManager.findPodcastByUuid(parentId)
                // PodHopper: pin the car to the iTunes cover proven to render through the Auto content
                // provider; the feed's own image url does not survive that pipeline for some hosts. Same
                // cover image, so the phone is unchanged. Then forget this result: dropping it from the
                // search maps is what stops a later passive browse from silently re-subscribing the show
                // and fighting an unsubscribe made on the phone. A fresh search re-arms it.
                if (subscribed != null) {
                    val itunesArt = searchImageUrls[parentId]
                    if (!itunesArt.isNullOrBlank() && subscribed.thumbnailUrl != itunesArt) {
                        subscribed.thumbnailUrl = itunesArt
                        podcastManager.updatePodcast(subscribed)
                    }
                }
                searchFeedUrls.remove(parentId)
                searchImageUrls.remove(parentId)
                subscribed
            } else {
                podcastManager.findPodcastByUuid(parentId)
                    ?: podcastManager.findOrDownloadPodcastRxSingle(parentId).toMaybe().onErrorComplete().awaitSingleOrNull()
            }
            podcastFound?.let { podcast ->
                val episodes = episodeManager
                    .findEpisodesByPodcastOrderedBlocking(podcast)
                    .filterNot { !showPlayed && (it.isFinished || it.isArchived) }
                    .take(EPISODE_LIMIT)
                    .toMutableList()
                if (!podcast.isSubscribed) {
                    episodes.sortBy { it.episodeType !is PodcastEpisode.EpisodeType.Trailer } // Bring trailers to the top
                }
                episodes.forEach { episode ->
                    episodeItems.add(
                        AutoConverter.convertEpisodeToMediaItem(
                            context,
                            episode,
                            podcast,
                            groupTrailers = !podcast.isSubscribed,
                            useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                        ),
                    )
                }
            }
        }

        setAutoPlaySource(autoPlaySource)

        return episodeItems
    }

    internal suspend fun loadFilesChildren(context: Context): List<MediaItem> {
        setAutoPlaySource(AutoPlaySource.Predefined.Files)
        return userEpisodeManager.findUserEpisodes().map {
            AutoConverter.convertEpisodeToMediaItem(
                context,
                it,
                Podcast.userPodcast,
                useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
            )
        }
    }

    internal suspend fun loadStarredChildren(context: Context): List<MediaItem> {
        setAutoPlaySource(AutoPlaySource.Predefined.Starred)
        return episodeManager.findStarredEpisodes().take(EPISODE_LIMIT).mapNotNull { episode ->
            podcastManager.findPodcastByUuidBlocking(episode.podcastUuid)?.let { podcast ->
                AutoConverter.convertEpisodeToMediaItem(
                    context = context,
                    episode = episode,
                    parentPodcast = podcast,
                    useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                )
            }
        }
    }

    internal suspend fun loadListeningHistoryChildren(context: Context): List<MediaItem> {
        val episodes = episodeManager.findPlaybackHistoryEpisodes().take(EPISODE_LIMIT)
        episodes.firstOrNull()?.let { setAutoPlaySource(AutoPlaySource.fromId(it.podcastUuid)) }
        return episodes.mapNotNull { episode ->
            podcastManager.findPodcastByUuidBlocking(episode.podcastUuid)?.let { podcast ->
                AutoConverter.convertEpisodeToMediaItem(
                    context = context,
                    episode = episode,
                    parentPodcast = podcast,
                    useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                )
            }
        }
    }

    private fun loadAutomotiveRootChildren(context: Context): List<MediaItem> {
        val extrasContentAsList = Bundle().apply {
            putInt(DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }

        val podcastsItem = buildListMediaItem(context, id = PODCASTS_ROOT, title = LR.string.podcasts, drawable = IR.drawable.auto_tab_podcasts)
        val filtersItem = buildListMediaItem(
            context,
            id = FILTERS_ROOT,
            title = LR.string.playlists,
            drawable = IR.drawable.auto_tab_playlists,
            extras = extrasContentAsList,
        )
        val discoverItem = buildListMediaItem(context, id = DISCOVER_ROOT, title = LR.string.discover, drawable = IR.drawable.auto_tab_discover)
        val profileItem = buildListMediaItem(context, id = PROFILE_ROOT, title = LR.string.profile, drawable = IR.drawable.auto_tab_profile, extras = extrasContentAsList)

        // PodHopper: Up Next was unreachable in the car. The browse node and its list builder
        // already existed and were handled, but no root menu ever offered the entry, so there was
        // no way to see the queue or what would play next. It sits after Podcasts because the queue
        // is what a driver checks most often after their shows.
        val upNextItem = buildListMediaItem(context, id = UP_NEXT_ROOT, title = LR.string.up_next, drawable = IR.drawable.ic_upnext, extras = extrasContentAsList)

        // PodHopper: always land on Podcasts first. Upstream showed Discover first when no
        // subscriptions were present, but right after car sign-in the subscription sync has not
        // finished yet, so countSubscribed() is momentarily 0 and the car would open on Discover.
        // Podcasts is the expected home tab, so it always leads.
        return listOf(podcastsItem, upNextItem, filtersItem, discoverItem, profileItem)
    }

    private suspend fun loadFiltersRoot(context: Context): List<MediaItem> {
        return getPlaylistPreviews().mapNotNull {
            Timber.d("Filters ${it.title}")
            try {
                AutoConverter.convertPlaylistToMediaItem(context, it)
            } catch (e: Exception) {
                Timber.e(e, "Filter ${it.title} load failed")
                null
            }
        }
    }

    /**
     * PodHopper: the car Discover tile, sourced from the iTunes top-podcasts chart instead of the
     * Pocket Casts catalogue. This reuses the exact same loader (and blocklist) the phone Discover
     * page uses, so the car list behaves identically: a fixed buffer is fetched, blocked shows are
     * removed, the rest is shuffled, and the requested number is returned.
     *
     * Each chart entry only carries an iTunes id, so its real rss feed url is resolved with a lookup
     * call. We resolve those in parallel, then key each tile by the feed's own uuid (the id it will
     * have once subscribed) and remember its feed url and iTunes cover in the same maps a search
     * result uses. That makes a tapped Discover tile travel the identical subscribe-on-tap path as a
     * tapped search result, with no change to loadEpisodeChildren. Entries whose feed cannot be
     * resolved are dropped. Reachable on the car only (the phone Android Auto root has no Discover
     * node), so the phone is unaffected.
     */
    private suspend fun loadDiscoverRoot(context: Context): List<MediaItem> {
        Timber.d("Loading iTunes discover root")
        val topPodcasts = try {
            itunesTopListLoader.loadTopList(deviceCountry(), DISCOVER_DISPLAY_LIMIT)
        } catch (e: Exception) {
            Timber.e(e, "Error loading iTunes discover")
            return emptyList()
        }
        if (topPodcasts.isEmpty()) {
            return emptyList()
        }

        val resolved = coroutineScope {
            topPodcasts.map { top ->
                async {
                    val feedUrl = itunesTopListLoader.resolveFeedUrl(top.lookupUrl)
                    if (feedUrl.isNullOrBlank()) null else top to feedUrl
                }
            }.awaitAll()
        }.filterNotNull()

        return resolved.mapNotNull { (top, feedUrl) ->
            val resultUuid = feedParser.podcastUuidForFeed(feedUrl)
            searchFeedUrls[resultUuid] = feedUrl
            top.imageUrl?.let { searchImageUrls[resultUuid] = it }
            val podcast = Podcast(
                uuid = resultUuid,
                title = top.title,
                author = top.author,
                podcastUrl = feedUrl,
                thumbnailUrl = top.imageUrl,
            )
            convertPodcastToMediaItem(
                context = context,
                podcast = podcast,
                useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                artworkUrlOverride = podcast.thumbnailUrl,
            )
        }
    }

    /** Two letter ISO country for the iTunes chart, matching the phone Discover page. */
    private fun deviceCountry(): String {
        val country = Locale.getDefault().country
        return country.ifBlank { "US" }
    }

    private fun loadProfileRoot(context: Context): List<MediaItem> {
        return buildList {
            val isPaidUser = settings.cachedSubscription.value != null
            if (isPaidUser) {
                add(buildListMediaItem(context, id = PROFILE_FILES, title = LR.string.profile_navigation_files, drawable = IR.drawable.automotive_files))
            }
            add(buildListMediaItem(context, id = PROFILE_STARRED, title = LR.string.profile_navigation_starred, drawable = IR.drawable.automotive_filter_star))
            add(buildListMediaItem(context, id = PROFILE_LISTENING_HISTORY, title = LR.string.profile_navigation_listening_history, drawable = IR.drawable.automotive_listening_history))
        }
    }

    private fun buildListMediaItem(context: Context, id: String, @StringRes title: Int, @DrawableRes drawable: Int, extras: Bundle? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(title))
            .setArtworkUri(AutoConverter.getBitmapUri(drawable = drawable, context))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .apply { if (extras != null) setExtras(extras) }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Search for local and remote podcasts.
     * Returning an empty list displays "No media available for browsing here"
     * Returning null displays "Something went wrong". There is no way to display our own error message.
     */
    suspend fun search(query: String, context: Context): List<MediaItem>? {
        val termCleaned = query.trim()
        val localPodcasts = podcastManager.findSubscribedNoOrder()
            .filter { it.title.contains(termCleaned, ignoreCase = true) || it.author.contains(termCleaned, ignoreCase = true) }
            .sortedBy { PodcastsSortType.cleanStringForSort(it.title) }
        val serverPodcasts = try {
            if (termCleaned.length <= 1) {
                emptyList()
            } else {
                // PodHopper: search the iTunes directory for new podcasts instead of the Pocket Casts
                // search server, mirroring the in-app search path. Each result maps to a feed-backed
                // podcast carrying its own rss feed url. We key the media item by the feed's own uuid
                // (the same id it will have once subscribed) and remember its feed url so a tap can
                // pull the feed on-device. The iTunes image url rides on thumbnailUrl so the browse
                // row shows real artwork instead of a placeholder.
                improvedSearchManager.combinedSearch(termCleaned)
                    .filterIsInstance<ImprovedSearchResultItem.PodcastItem>()
                    .map { item ->
                        val feedUrl = item.feedUrl
                        val resultUuid = if (feedUrl != null) feedParser.podcastUuidForFeed(feedUrl) else item.uuid
                        if (feedUrl != null) {
                            searchFeedUrls[resultUuid] = feedUrl
                            // PodHopper: remember the iTunes cover too. It is the artwork url proven to render
                            // through the Auto content provider (it is what shows on the search row), so on
                            // subscribe we pin the show to it instead of the feed's own image url, which does not
                            // survive that content-provider pipeline for some hosts.
                            item.imageUrl?.let { searchImageUrls[resultUuid] = it }
                        }
                        Podcast(
                            uuid = resultUuid,
                            title = item.title,
                            author = item.author,
                            podcastUrl = feedUrl,
                            thumbnailUrl = item.imageUrl,
                        )
                    }
            }
        } catch (ex: Exception) {
            Timber.e(ex)
            if (localPodcasts.isEmpty()) {
                return null
            }
            emptyList()
        }
        val podcasts = (localPodcasts + serverPodcasts).distinctBy { it.uuid }
        return podcasts.mapNotNull { podcast ->
            convertPodcastToMediaItem(
                context = context,
                podcast = podcast,
                useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                artworkUrlOverride = if (searchFeedUrls.containsKey(podcast.uuid)) podcast.thumbnailUrl else null,
            )
        }
    }

    @VisibleForTesting
    internal suspend fun getPlaylistPreviews(): List<PlaylistPreview> {
        return playlistManager.playlistPreviewsFlow().first()
    }

    @VisibleForTesting
    internal suspend fun getPlaylistEpisodes(
        uuid: String,
        filterEpisode: (Playlist.Type, PodcastEpisode) -> Boolean,
    ): List<PodcastEpisode>? {
        val playlist = playlistManager.smartPlaylistFlow(uuid).first() ?: playlistManager.manualPlaylistFlow(uuid).first()
        return playlist
            ?.episodes
            ?.mapNotNull(PlaylistEpisode::toPodcastEpisode)
            ?.filter { filterEpisode(playlist.type, it) }
    }

    private suspend fun convertEpisodesToMediaItems(
        episodes: List<BaseEpisode>,
        context: Context,
    ): List<MediaItem> {
        return episodes.mapNotNull { episode ->
            val podcast = if (episode is PodcastEpisode) podcastManager.findPodcastByUuid(episode.podcastUuid) else Podcast.userPodcast
            if (podcast == null) {
                null
            } else {
                AutoConverter.convertEpisodeToMediaItem(
                    context = context,
                    episode = episode,
                    parentPodcast = podcast,
                    useEpisodeArtwork = settings.artworkConfiguration.value.useEpisodeArtwork,
                )
            }
        }
    }

    private fun setAutoPlaySource(autoPlaySource: AutoPlaySource) {
        settings.trackingAutoPlaySource.set(autoPlaySource, updateModifiedAt = false)
    }
}
