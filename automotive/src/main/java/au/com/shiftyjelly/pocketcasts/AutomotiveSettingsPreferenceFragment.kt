package au.com.shiftyjelly.pocketcasts

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import au.com.shiftyjelly.pocketcasts.localization.BuildConfig
import au.com.shiftyjelly.pocketcasts.localization.extensions.getStringPluralSeconds
import au.com.shiftyjelly.pocketcasts.localization.extensions.getStringPluralSecondsMinutesHoursDaysOrYears
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.models.type.AutoDownloadLimitSetting
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadQueue
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperCarDiagnostics
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import au.com.shiftyjelly.pocketcasts.views.extensions.setInputAsSeconds
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import java.io.File
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class AutomotiveSettingsPreferenceFragment : PreferenceFragmentCompat() {

    @Inject lateinit var settings: Settings

    @Inject lateinit var podcastManager: PodcastManager

    @Inject lateinit var episodeManager: EpisodeManager

    @Inject lateinit var downloadQueue: DownloadQueue

    @Inject lateinit var fileStorage: FileStorage

    @Inject lateinit var carDiagnostics: PodHopperCarDiagnostics

    private lateinit var preferenceAutoPlay: SwitchPreference
    private lateinit var preferenceAutoSubscribeToPlayed: SwitchPreference
    private lateinit var preferenceAutoShowPlayed: SwitchPreference
    private lateinit var preferenceAutoSwitchPlayer: SwitchPreference
    private lateinit var preferenceSkipForward: EditTextPreference
    private lateinit var preferenceSkipBackward: EditTextPreference
    private lateinit var preferenceRefreshNow: Preference
    private lateinit var about: Preference
    private lateinit var preferenceAutoDownload: SwitchPreference
    private lateinit var preferenceAutoDownloadLimit: ListPreference
    private lateinit var preferenceStorageBar: StorageBarPreference
    private lateinit var preferenceDeleteDownloads: Preference
    private lateinit var preferenceUploadLogs: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_auto)

        preferenceAutoPlay = findPreference("autoUpNextEmpty")!!
        preferenceAutoSubscribeToPlayed = findPreference("autoSubscribeToPlayed")!!
        preferenceAutoShowPlayed = findPreference("autoShowPlayed")!!
        preferenceAutoSwitchPlayer = findPreference("autoSwitchPlayerToCurrentPodcast")!!
        preferenceRefreshNow = findPreference("refresh_now")!!
        preferenceSkipForward = findPreference(Settings.PREFERENCE_SKIP_FORWARD)!!
        preferenceSkipBackward = findPreference(Settings.PREFERENCE_SKIP_BACKWARD)!!
        about = findPreference("about")!!
        preferenceAutoDownload = findPreference("podhopperAutoDownload")!!
        preferenceAutoDownloadLimit = findPreference("podhopperAutoDownloadLimit")!!
        preferenceStorageBar = findPreference("podhopperStorageBar")!!
        preferenceDeleteDownloads = findPreference("podhopperDeleteDownloads")!!
        preferenceUploadLogs = findPreference("podhopperUploadLogs")!!

        setupAutoPlay()
        setupAutoSubscribeToPlayed()
        setupAutoShowPlayed()
        setupAutoSwitchPlayer()
        setupSkipForward()
        setupSkipBackward()
        setupRefreshNow()
        setupAbout()
        setupAutoDownload()
        setupAutoDownloadLimit()
        setupDeleteDownloads()
        setupUploadLogs()
        refreshStorageBar()
    }

    private fun setupAutoPlay() {
        preferenceAutoPlay.setOnPreferenceChangeListener { _, newValue ->
            settings.autoPlayNextEpisodeOnEmpty.set(newValue as Boolean, updateModifiedAt = true)
            true
        }
        settings.autoPlayNextEpisodeOnEmpty.flow
            .onEach { preferenceAutoPlay.isChecked = it }
            .launchIn(lifecycleScope)
    }

    private fun setupAutoSubscribeToPlayed() {
        preferenceAutoSubscribeToPlayed.setOnPreferenceChangeListener { _, newValue ->
            settings.autoSubscribeToPlayed.set(newValue as Boolean, updateModifiedAt = true)
            true
        }
        settings.autoSubscribeToPlayed.flow
            .onEach { preferenceAutoSubscribeToPlayed.isChecked = it }
            .launchIn(lifecycleScope)
    }

    private fun setupAutoShowPlayed() {
        preferenceAutoShowPlayed.setOnPreferenceChangeListener { _, newValue ->
            settings.autoShowPlayed.set(newValue as Boolean, updateModifiedAt = true)
            true
        }
        settings.autoShowPlayed.flow
            .onEach { preferenceAutoShowPlayed.isChecked = it }
            .launchIn(lifecycleScope)
    }

    private fun setupAutoSwitchPlayer() {
        preferenceAutoSwitchPlayer.setOnPreferenceChangeListener { _, newValue ->
            settings.autoSwitchPlayerToCurrentPodcast.set(newValue as Boolean, updateModifiedAt = true)
            true
        }
        settings.autoSwitchPlayerToCurrentPodcast.flow
            .onEach { preferenceAutoSwitchPlayer.isChecked = it }
            .launchIn(lifecycleScope)
    }

    private fun setupSkipForward() {
        preferenceSkipForward.setInputAsSeconds()
        preferenceSkipForward.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue.toString().toIntOrNull() ?: 0
            if (value > 0) {
                settings.skipForwardInSecs.set(value, updateModifiedAt = true)
                true
            } else {
                false
            }
        }
        settings.skipForwardInSecs.flow
            .onEach {
                preferenceSkipForward.text = it.toString()
                preferenceSkipForward.summary = resources.getStringPluralSeconds(settings.skipForwardInSecs.value)
            }
            .launchIn(lifecycleScope)
    }

    private fun setupSkipBackward() {
        preferenceSkipBackward.setInputAsSeconds()
        preferenceSkipBackward.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue.toString().toIntOrNull() ?: 0
            if (value > 0) {
                settings.skipBackInSecs.set(value, updateModifiedAt = true)
                true
            } else {
                false
            }
        }
        settings.skipBackInSecs.flow
            .onEach {
                preferenceSkipBackward.text = it.toString()
                preferenceSkipBackward.summary = resources.getStringPluralSeconds(settings.skipBackInSecs.value)
            }
            .launchIn(lifecycleScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setupRefreshNow() {
        preferenceRefreshNow.setOnPreferenceClickListener {
            podcastManager.refreshPodcasts(fromLog = "Automotive")
            updateRefreshSummary(RefreshState.Refreshing)
            true
        }
        settings.refreshStateFlow
            .flatMapLatest { state ->
                flow {
                    while (true) {
                        emit(state)
                        delay(500.milliseconds)
                    }
                }
            }
            .onEach { updateRefreshSummary(it) }
            .launchIn(lifecycleScope)
    }

    private fun updateRefreshSummary(state: RefreshState) {
        val status = when (state) {
            is RefreshState.Success -> {
                val time = Date().time - state.date.time
                val timeAmount = resources.getStringPluralSecondsMinutesHoursDaysOrYears(time)
                getString(LR.string.profile_last_refresh, timeAmount)
            }

            is RefreshState.Never -> getString(LR.string.profile_refreshed_never)

            is RefreshState.Refreshing -> getString(LR.string.profile_refreshing)

            is RefreshState.Failed -> getString(LR.string.profile_refresh_failed)
        }
        preferenceRefreshNow.summary = status
    }

    private fun setupAbout() {
        about.summary = getString(LR.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString())
        about.setOnPreferenceClickListener {
            (activity as? FragmentHostListener)?.addFragment(AutomotiveAboutFragment())
            true
        }
    }

    // PodHopper: the toggle reflects the shared auto-download engine's global setting and, when
    // flipped, applies the matching per-podcast flag to every subscribed podcast. The engine
    // requires BOTH (verified: the provider filters on the per-podcast flag, so the global
    // setting alone downloads nothing). autoDownloadOnFollowPodcast makes podcasts followed
    // later inherit the choice through the existing on-follow path.
    private fun setupAutoDownload() {
        settings.autoDownloadNewEpisodes.flow
            .onEach { preferenceAutoDownload.isChecked = it == Podcast.AUTO_DOWNLOAD_NEW_EPISODES }
            .launchIn(lifecycleScope)
        preferenceAutoDownload.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            lifecycleScope.launch(Dispatchers.IO) {
                val status = if (enabled) Podcast.AUTO_DOWNLOAD_NEW_EPISODES else Podcast.AUTO_DOWNLOAD_OFF
                settings.autoDownloadNewEpisodes.set(status, updateModifiedAt = true)
                settings.autoDownloadOnFollowPodcast.set(enabled, updateModifiedAt = true)
                podcastManager.findSubscribedNoOrder().forEach { podcast ->
                    podcastManager.updateAutoDownloadStatusBlocking(podcast, status)
                }
            }
            true
        }
    }

    private fun setupAutoDownloadLimit() {
        settings.autoDownloadLimit.flow
            .onEach { limit ->
                preferenceAutoDownloadLimit.value = limit.id.toString()
                preferenceAutoDownloadLimit.summary = preferenceAutoDownloadLimit.entry
            }
            .launchIn(lifecycleScope)
        preferenceAutoDownloadLimit.setOnPreferenceChangeListener { _, newValue ->
            val setting = AutoDownloadLimitSetting.fromPreferenceString(newValue.toString())
            if (setting != null) {
                settings.autoDownloadLimit.set(setting, updateModifiedAt = true)
                true
            } else {
                false
            }
        }
    }

    private fun setupDeleteDownloads() {
        preferenceDeleteDownloads.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.podhopper_delete_downloads_confirm))
                .setPositiveButton(getString(R.string.podhopper_delete)) { _, _ -> deleteAllDownloads() }
                .setNegativeButton(getString(R.string.podhopper_cancel), null)
                .show()
            true
        }
    }

    private fun deleteAllDownloads() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val downloaded = episodeManager.findDownloadedEpisodesRxFlowable().firstOrError().await()
                if (downloaded.isNotEmpty()) {
                    downloadQueue.cancelAll(downloaded.map { it.uuid }, SourceView.DOWNLOADS).join()
                }
            }
            preferenceDeleteDownloads.summary = getString(R.string.podhopper_delete_downloads_done)
            refreshStorageBar()
        }
    }

    private fun setupUploadLogs() {
        preferenceUploadLogs.setOnPreferenceClickListener {
            preferenceUploadLogs.summary = getString(R.string.podhopper_upload_logs_uploading)
            lifecycleScope.launch {
                val ok = carDiagnostics.uploadNow(reason = "manual")
                preferenceUploadLogs.summary = getString(
                    if (ok) R.string.podhopper_upload_logs_done else R.string.podhopper_upload_logs_failed,
                )
            }
            true
        }
    }

    // PodHopper: green = PodHopper's downloads plus its streaming cache, red = everything else
    // used on the device partition, grey = free. Measured off the main thread; folder walks on a
    // head unit are small but not free.
    private fun refreshStorageBar() {
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) {
                val episodesDirBytes = fileStorage.getOrCreateEpisodesDir()?.let(::directorySizeBytes) ?: 0L
                val streamCacheBytes = directorySizeBytes(File(requireContext().cacheDir, "pocketcasts-exoplayer-cache"))
                val appBytes = episodesDirBytes + streamCacheBytes
                val dataDir = requireContext().filesDir
                val totalBytes = dataDir.totalSpace
                val freeBytes = dataDir.usableSpace
                val otherUsedBytes = (totalBytes - freeBytes - appBytes).coerceAtLeast(0)
                Triple(appBytes, otherUsedBytes, freeBytes)
            }
            preferenceStorageBar.setUsage(usage.first, usage.second, usage.third)
        }
    }

    private fun directorySizeBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
