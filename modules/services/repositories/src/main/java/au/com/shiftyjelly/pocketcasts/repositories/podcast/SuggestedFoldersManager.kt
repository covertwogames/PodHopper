package au.com.shiftyjelly.pocketcasts.repositories.podcast

import androidx.room.withTransaction
import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.entity.Folder
import au.com.shiftyjelly.pocketcasts.models.entity.SuggestedFolder
import au.com.shiftyjelly.pocketcasts.models.type.PodcastsSortType
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.utils.UUIDProvider
import jakarta.inject.Inject
import java.time.Clock
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SuggestedFoldersManager @Inject constructor(
    private val database: AppDatabase,
    private val settings: Settings,
    private val clock: Clock,
    private val uuidProvider: UUIDProvider,
) {
    private val suggestedFoldersDao = database.suggestedFoldersDao()
    private val podcastDao = database.podcastDao()
    private val folderDao = database.folderDao()

    fun observeSuggestedFolders() = suggestedFoldersDao.findAll()

    suspend fun refreshSuggestedFolders() {
        // PodHopper: folder suggestions used to be fetched from the Pocket Casts cache server by
        // posting the user's full list of subscribed podcast uuids. Never fetch, and clear any
        // suggestions a previous build may have cached so no stale server suggestion can surface.
        suggestedFoldersDao.deleteAll()
        settings.suggestedFoldersFollowedHash.set("", updateModifiedAt = false)
    }

    suspend fun useSuggestedFolders(suggestedFolders: List<SuggestedFolder>) {
        val foldersWithPodcasts = withContext(Dispatchers.Default) {
            suggestedFolders.groupBy(SuggestedFolder::name).toList().mapIndexed { index, (name, folders) ->
                Folder(
                    uuid = uuidProvider.generateUUID().toString(),
                    name = name,
                    color = index % 12,
                    addedDate = Date.from(clock.instant()),
                    sortPosition = 0,
                    podcastsSortType = settings.podcastsSortType.value,
                    deleted = false,
                    syncModified = clock.millis(),
                ) to folders.map { it.podcastUuid }
            }
        }

        database.withTransaction {
            folderDao.updateAllDeleted(deleted = true, syncModified = clock.millis())
            folderDao.insertAll(foldersWithPodcasts.map { (folder, _) -> folder })
            foldersWithPodcasts.forEach { (folder, podcastIds) ->
                podcastDao.updateFolderUuid(folder.uuid, podcastIds)
            }
            deleteAllSuggestedFolders()
        }
        settings.podcastsSortType.set(PodcastsSortType.NAME_A_TO_Z, updateModifiedAt = true)
    }

    suspend fun deleteAllSuggestedFolders() {
        suggestedFoldersDao.deleteAll()
    }
}
