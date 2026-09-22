package au.com.shiftyjelly.pocketcasts.repositories.podcast

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PodHopper: remembers each feed's version markers (ETag and Last-Modified) from its last full
 * refresh download, keyed by feed URL, so the next refresh can ask the host whether the feed changed
 * and skip the download when it has not.
 *
 * Markers are only written once the refresh that downloaded them has stored the feed's new episodes
 * (see RefreshPodcastsThread), so a refresh that is cut short never makes the next one skip episodes
 * it did not save. First-time adds (subscribing, and the Up Next and position sync lookups) always
 * download the whole feed and never read these.
 */
@Singleton
class FeedValidatorStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun get(feedUrl: String): FeedParser.FeedValidators? {
        val key = feedUrl.trim()
        val etag = prefs.getString(ETAG_PREFIX + key, null)
        val lastModified = prefs.getString(LAST_MODIFIED_PREFIX + key, null)
        return if (etag == null && lastModified == null) null else FeedParser.FeedValidators(etag, lastModified)
    }

    /** Records the markers for each feed URL, replacing what was stored before. */
    fun putAll(validatorsByFeedUrl: Map<String, FeedParser.FeedValidators>) {
        if (validatorsByFeedUrl.isEmpty()) {
            return
        }
        val editor = prefs.edit()
        for ((feedUrl, validators) in validatorsByFeedUrl) {
            val key = feedUrl.trim()
            putOrRemove(editor, ETAG_PREFIX + key, validators.etag)
            putOrRemove(editor, LAST_MODIFIED_PREFIX + key, validators.lastModified)
        }
        editor.apply()
    }

    /**
     * Forgets the markers for [feedUrl], so its next refresh downloads the whole feed. Used when a
     * podcast is unsubscribed: the unused-podcast cleanup can later delete its episodes while keeping
     * the podcast, and a resubscribe must then restore them rather than be told "not modified".
     */
    fun remove(feedUrl: String) {
        val key = feedUrl.trim()
        prefs.edit()
            .remove(ETAG_PREFIX + key)
            .remove(LAST_MODIFIED_PREFIX + key)
            .apply()
    }

    private fun putOrRemove(editor: SharedPreferences.Editor, key: String, value: String?) {
        if (value.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
    }

    private companion object {
        const val PREF_NAME = "podhopper_feed_validators"
        const val ETAG_PREFIX = "etag:"
        const val LAST_MODIFIED_PREFIX = "last_modified:"
    }
}
