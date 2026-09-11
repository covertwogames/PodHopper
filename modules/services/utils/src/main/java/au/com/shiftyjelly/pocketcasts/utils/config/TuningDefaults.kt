package au.com.shiftyjelly.pocketcasts.utils.config

/**
 * PodHopper: fixed tuning values for the app.
 *
 * These used to be served through Firebase Remote Config, which was registered with exactly these
 * numbers as its defaults. PodHopper never had a Firebase project that published different values,
 * so the defaults were the values in use on every install. They are now read directly.
 */
object TuningDefaults {
    const val PERIODIC_SAVE_TIME_MS = 60000L
    const val PLAYER_RELEASE_TIME_OUT_MS = 500L
    const val PODCAST_SEARCH_DEBOUNCE_MS = 2000L
    const val EPISODE_SEARCH_DEBOUNCE_MS = 2000L
    const val CLOUD_STORAGE_LIMIT_GB = 10L
    const val SLEEP_TIMER_DEVICE_SHAKE_THRESHOLD = 30L
    const val REFRESH_PODCASTS_BATCH_SIZE = 200L
    const val EXOPLAYER_CACHE_ENTIRE_PLAYING_EPISODE_SIZE_IN_MB = 500L
    const val EXOPLAYER_CACHE_ENTIRE_PLAYING_EPISODE_SETTING_DEFAULT = true
    const val PLAYBACK_EPISODE_POSITION_CHANGED_ON_SYNC_THRESHOLD_SECS = 5L
}
