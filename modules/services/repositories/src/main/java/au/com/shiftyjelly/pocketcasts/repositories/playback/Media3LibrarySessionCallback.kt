package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.auto.PackageValidator
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.localization.R as LR
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * [MediaLibraryService.MediaLibrarySession.Callback] that provides browse tree and search
 * functionality using [BrowseTreeProvider] and delegates session-level operations
 * (onConnect, onCustomCommand, onAddMediaItems, onSetRating, onMediaButtonEvent) to
 * [Media3SessionCallback].
 */
@OptIn(UnstableApi::class)
internal class Media3LibrarySessionCallback(
    private val sessionCallback: Media3SessionCallback,
    private val browseTreeProvider: BrowseTreeProvider,
    private val playbackManager: PlaybackManager,
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val settings: Settings,
    private val packageValidator: PackageValidator?,
    private val scopeProvider: () -> CoroutineScope,
    private val contextProvider: () -> Context,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope: CoroutineScope get() = scopeProvider()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        if (packageValidator != null && !packageValidator.isKnownCaller(controller.packageName, controller.uid)) {
            // Unknown callers (e.g., Tasker, Automate) get transport controls only, no library
            // browsing or custom commands. This matches the legacy MediaBrowserServiceCompat
            // behavior where onGetRoot() returned null for unknown callers but transport
            // controls via MediaControllerCompat still worked independently.
            LogBuffer.i(
                LogBuffer.TAG_PLAYBACK,
                "Unknown caller connected with transport-only access: ${controller.packageName} uid=${controller.uid}",
            )
            return MediaSession.ConnectionResult.accept(SessionCommands.EMPTY, TRANSPORT_PLAYER_COMMANDS)
        }
        if (!controller.packageName.contains("au.com.shiftyjelly.pocketcasts")) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Client: ${controller.packageName} connected to media session")
            // PodHopper: an external controller attaching (the built-in car system or phone-projected
            // Android Auto) is the signal that something is about to display our now-playing state, so
            // reconcile it to the freshest across devices. Throttled and login-gated inside the sync,
            // and it never changes the episode while actively playing.
            playbackManager.reconcileNowPlaying()
            val context = contextProvider()
            if (Util.isAutomotive(context) && !settings.automotiveConnectedToMediaSession()) {
                scope.launch {
                    try {
                        delay(1000)
                        settings.setAutomotiveConnectedToMediaSession(true)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to set automotive connected flag")
                    }
                }
            }
        }
        return sessionCallback.onConnect(session, controller)
    }

    /**
     * PodHopper diagnostic: the moment the car's media app lets go. The app logs connections
     * already, so the record used to show only the car coming back, never it leaving, which made
     * the screen dropping to the car's home screen invisible in the app's own log.
     */
    override fun onDisconnected(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ) {
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Client: ${controller.packageName} disconnected from media session")
        sessionCallback.onDisconnected(session, controller)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        return sessionCallback.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        return sessionCallback.onAddMediaItems(mediaSession, controller, mediaItems)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating,
    ): ListenableFuture<SessionResult> {
        return sessionCallback.onSetRating(session, controller, rating)
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        return sessionCallback.onMediaButtonEvent(session, controllerInfo, intent)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        playbackHasBeenResumed: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val context = contextProvider()
        if (Util.isAutomotive(context)) {
            settings.setAutomotiveConnectedToMediaSession(true)
        }
        scope.launch {
            try {
                // PodHopper: adopt the most recently played episode from another device BEFORE
                // choosing what to resume, so the car loads the same episode you were on elsewhere
                // instead of its own last one. The background pull does this on the phone, but on the
                // car it loses the race to this one-shot resume; doing it here, before playback
                // starts, wins. Falls back to this device's current episode when there is nothing
                // newer, the setting is off, or it is offline.
                val adopted = playbackManager.adoptLatestSyncedEpisodeBeforeResume()
                val episode = adopted ?: playbackManager.getCurrentEpisode()
                if (episode != null) {
                    // PodHopper: before the car resumes on turn-on, pull this episode's latest
                    // cross-device position and apply it, so playback continues from where you left
                    // off on your phone rather than where this device last played. Bounded and
                    // offline-safe inside the sync; on timeout it falls back to the local position.
                    playbackManager.applyRemotePositionBeforeResume(episode)
                    val podcast = (episode as? PodcastEpisode)?.let {
                        podcastManager.findPodcastByUuid(it.podcastUuid)
                    }
                    val mediaItem = buildEpisodeMediaItem(episode, podcast)
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            listOf(mediaItem),
                            0,
                            episode.playedUpToMs.toLong(),
                        ),
                    )
                } else {
                    future.setException(UnsupportedOperationException("No episode to resume"))
                }
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Car requested the library root from ${browser.packageName} (recent=${params?.isRecent == true}, suggested=${params?.isSuggested == true})")
        val isRecent = params?.isRecent == true
        val isSuggested = params?.isSuggested == true
        val rootId = browseTreeProvider.getRootId(
            isRecent = isRecent,
            isSuggested = isSuggested,
            hasCurrentEpisode = playbackManager.getCurrentEpisode() != null,
        ) ?: return Futures.immediateFuture(
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
        )

        val rootItem = MediaItem.Builder()
            .setMediaId(rootId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

        val extras = Bundle().apply {
            putBoolean(MEDIA_SEARCH_SUPPORTED, true)
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        }
        val responseParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(extras)
            .build()

        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, responseParams))
    }

    // PodHopper: media3's default onSubscribe accepts a browse subscription only if onGetItem returns a
    // browsable item for the parent id. With no onGetItem override the default returns
    // ERROR_NOT_SUPPORTED, so the session rejects and removes every Android Auto browse subscription,
    // and notifyChildrenChanged then silently no-ops because it only delivers to controllers that are
    // still subscribed. That is why subscribing or unsubscribing never refreshed the car's podcast
    // list. Browse node ids (the roots, podcast ids, folders) never contain the AutoMediaId divider
    // "#"; only episode media ids do (they are built as "sourceId#episodeId"), so classify on that and
    // return a browsable item for browse nodes so the subscription is kept.
    /** PodHopper diagnostic: paired with the browse and disconnect logging above. */
    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val isEpisode = mediaId.contains("#")
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(!isEpisode)
                    .setIsPlayable(isEpisode)
                    .build(),
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(item, null))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        // PodHopper diagnostic: what the car is asking to show, and when. The car draws the UI, so
        // the app has no window to lose when the screen drops back to the car's home screen; but the
        // car has to ask for a node before it can display one, and a request for the root is that
        // navigation happening. Without this the only visible event was the car reconnecting
        // afterwards, which is the aftermath rather than the moment.
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Car browse request: $parentId (page $page) from ${browser.packageName}")
        // PodHopper: on the car only, if there is no signed-in PodHopper account, return an
        // authentication error for any browse request. media3 forwards this error (with the sign-in
        // button below) to the platform session the car reads, so the car shows a full-screen prompt
        // with a Sign In button. Tapping it (while parked) launches the pairing screen. After the
        // car is paired, the next children query succeeds and the prompt clears on its own. This is
        // gated on Util.isAutomotive, so the phone and phone-projected Android Auto never run it. The
        // error is returned here from onGetChildren rather than onGetLibraryRoot because root errors
        // are not forwarded to the legacy browser the car connects as.
        val context = contextProvider()
        if (Util.isAutomotive(context) && settings.podhopperRefreshToken.value.isEmpty()) {
            return Futures.immediateFuture(buildCarSignInRequiredResult(context))
        }
        // PodHopper: refresh cross-device positions when the car opens a browse page, so episode
        // lists reflect progress made on other devices. Throttled and login-gated inside the sync,
        // and it never adopts: navigating pages must not change what is playing.
        playbackManager.pullPositionsForBrowse()
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                val items = browseTreeProvider.loadChildren(parentId, contextProvider())
                val paginated = paginate(items, page, pageSize)
                future.set(LibraryResult.ofItemList(paginated, params))
            } catch (e: Exception) {
                Timber.e(e, "Failed to load children for: $parentId")
                future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                val items = browseTreeProvider.search(query, contextProvider())
                if (items == null) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                } else {
                    val paginated = paginate(items, page, pageSize)
                    future.set(LibraryResult.ofItemList(paginated, params))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get search results for: $query")
                future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            try {
                val results = browseTreeProvider.search(query, contextProvider())
                session.notifySearchResultChanged(browser, query, results?.size ?: 0, params)
            } catch (e: Exception) {
                Timber.e(e, "Search failed for query: $query")
            }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || page < 0) return items
        val start = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        val end = (start + pageSize).coerceAtMost(items.size)
        return items.subList(start, end)
    }

    /**
     * Builds the "sign in required" browse error for the car: an authentication error carrying a
     * label and a resolution PendingIntent. The car renders this as a full-screen prompt with a
     * Sign In button that launches the pairing screen (via an implicit action intent scoped to this
     * app, so this module does not need to depend on the automotive sign-in activity).
     */
    private fun buildCarSignInRequiredResult(context: Context): LibraryResult<ImmutableList<MediaItem>> {
        val signInIntent = Intent(CAR_SIGN_IN_ACTION).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            signInIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val errorExtras = Bundle().apply {
            putString(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL,
                context.getString(LR.string.podhopper_car_signin_button),
            )
            putParcelable(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
                pendingIntent,
            )
        }
        val errorParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(errorExtras)
            .build()
        val authError = SessionError(
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            context.getString(LR.string.podhopper_car_signin_message),
            Bundle.EMPTY,
        )
        return LibraryResult.ofError(authError, errorParams)
    }

    private companion object {
        const val CAR_SIGN_IN_ACTION = "au.com.shiftyjelly.pocketcasts.intents.PAIR_CAR"
    }
}
