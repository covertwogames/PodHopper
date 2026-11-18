package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.db.dao.PlaylistDao
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playlist.DefaultPlaylistsInitializer
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.UserEpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.searchhistory.SearchHistoryManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * PodHopper: removes everything an account left on this device. Used when a car or watch ends up
 * signed out, and when a phone was removed from the account's list of signed-in devices.
 *
 * What goes: the podcasts and their episodes (which carry the listening history), the Up Next
 * queue, filters, folders, search history, uploaded files, downloaded episode files, and this
 * device's sync bookkeeping. App settings stay. Nothing is removed from the account: everything
 * here is deleted at the database level, below the hooks that would report an unsubscribe or a
 * queue change to the server, and the device is signed out for the whole of it, so no sync can
 * run. The next sign-in starts from the account's copy as if the app were newly installed.
 *
 * The same steps, in the same order, as the app's original "sign out and clear data".
 */
@Singleton
class PodHopperLocalData @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val wipeGate: PodHopperWipeGate,
    private val playbackManager: Lazy<PlaybackManager>,
    private val podcastManager: Lazy<PodcastManager>,
    private val episodeManager: Lazy<EpisodeManager>,
    private val userEpisodeManager: Lazy<UserEpisodeManager>,
    private val folderManager: Lazy<FolderManager>,
    private val searchHistoryManager: Lazy<SearchHistoryManager>,
    private val playlistDao: Lazy<PlaylistDao>,
    private val playlistsInitializer: Lazy<DefaultPlaylistsInitializer>,
    private val fileStorage: Lazy<FileStorage>,
    private val positionSync: Lazy<PodHopperPositionSync>,
    private val subscriptionSync: Lazy<PodHopperSubscriptionSync>,
) {
    /**
     * Clears this device if it is signed out. Blocking, and can take a few seconds; call from a
     * background thread. Does nothing at all while signed in, so a library that belongs to a live
     * sign-in can never be removed by this. Safe to call repeatedly and to repeat after being
     * interrupted: the sync bookkeeping is reset first, so a device stopped halfway is simply
     * cleared again next time.
     *
     * [onlyIfSomethingStored] skips the deleting when the library is already empty (the usual
     * case on a car or watch that is merely sitting signed out).
     */
    fun clearIfSignedOut(reason: String, onlyIfSomethingStored: Boolean) {
        wipeGate.whileClearing {
            if (supabaseClient.isLoggedIn()) {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper clear skipped ($reason): signed in")
                return@whileClearing
            }
            // First, so a device interrupted partway never keeps a sync position for a library it
            // no longer has. Without this the next sign-in would fetch only recent changes.
            resetSyncBookkeeping()
            if (onlyIfSomethingStored && !hasAnythingStored()) {
                // Nothing in the library. Still sweep the download folders, in case an earlier
                // clearing was interrupted after the library went but before the files did.
                removeDownloadedFiles()
                return@whileClearing
            }
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper clearing this device's library: $reason")
            deleteEverything()
            resetSyncBookkeeping()
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper finished clearing this device's library")
        }
    }

    private fun hasAnythingStored(): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                podcastManager.get().countPodcasts() > 0 ||
                    episodeManager.get().countEpisodes() > 0 ||
                    !playbackManager.get().upNextQueue.isEmpty
            }
        } catch (e: Exception) {
            // If it cannot be checked, clear anyway. Clearing an empty library is harmless.
            true
        }
    }

    private fun resetSyncBookkeeping() {
        step("position sync state") { positionSync.get().clearLocalSyncState() }
        step("subscription sync state") { subscriptionSync.get().clearLocalSyncState() }
    }

    private fun deleteEverything() {
        // Stop playback before the episode it is playing is removed.
        step("playback") {
            val playback = playbackManager.get()
            playback.removeEpisode(
                episodeToRemove = playback.getCurrentEpisode(),
                source = SourceView.UNKNOWN,
                userInitiated = false,
            )
        }
        runBlocking(Dispatchers.IO) {
            step("up next") { playbackManager.get().upNextQueue.removeAllIncludingChanges() }
            step("filters") {
                playlistDao.get().deleteAllPlaylists()
                playlistsInitializer.get().initialize(force = true)
            }
            step("folders") { folderManager.get().deleteAll() }
            step("search history") { searchHistoryManager.get().clearAll() }
            step("podcasts") { podcastManager.get().deleteAllPodcasts() }
            step("files") {
                val files = userEpisodeManager.get()
                files.findUserEpisodes().forEach {
                    files.delete(episode = it, playbackManager = playbackManager.get())
                }
            }
            step("episodes") { episodeManager.get().deleteAll(SourceView.UNKNOWN) }
        }
        // The episode rows are gone, so whatever is left in the download folders belongs to nothing.
        removeDownloadedFiles()
    }

    private fun removeDownloadedFiles() {
        step("downloaded files") {
            val storage = fileStorage.get()
            emptyFolder(storage.getOrCreateEpisodesDir())
            emptyFolder(storage.getOrCreateEpisodesTempDir())
            emptyFolder(storage.getOrCreateEpisodesOldTempDir())
        }
    }

    private fun emptyFolder(folder: File?) {
        folder?.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Runs one part of the clearing. A part that fails is logged and the rest still runs. */
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper clear: $name failed: ${e.message}")
        }
    }
}
