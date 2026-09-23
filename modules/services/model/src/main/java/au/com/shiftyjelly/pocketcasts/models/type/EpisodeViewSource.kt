package au.com.shiftyjelly.pocketcasts.models.type

import com.automattic.eventhorizon.EpisodeViewSourceType

enum class EpisodeViewSource(
    val key: String,
    val analyticsValue: EpisodeViewSourceType,
) {
    DISCOVER(
        key = "discover",
        analyticsValue = EpisodeViewSourceType.Discover,
    ),
    FILES(
        key = "files",
        analyticsValue = EpisodeViewSourceType.Files,
    ),
    FILTERS(
        key = "filters",
        analyticsValue = EpisodeViewSourceType.Filters,
    ),
    PODCAST_SCREEN(
        key = "podcast_screen",
        analyticsValue = EpisodeViewSourceType.PodcastScreen,
    ),
    STARRED(
        key = "starred",
        analyticsValue = EpisodeViewSourceType.Starred,
    ),
    DOWNLOADS(
        key = "downloads",
        analyticsValue = EpisodeViewSourceType.Downloads,
    ),
    LISTENING_HISTORY(
        key = "listening_history",
        analyticsValue = EpisodeViewSourceType.ListeningHistory,
    ),
    UP_NEXT(
        key = "up_next",
        analyticsValue = EpisodeViewSourceType.UpNext,
    ),
    SHARE(
        key = "share",
        analyticsValue = EpisodeViewSourceType.Share,
    ),
    NOTIFICATION(
        key = "notification",
        analyticsValue = EpisodeViewSourceType.Notification,
    ),
    NOTIFICATION_BOOKMARK(
        key = "notification_bookmark",
        analyticsValue = EpisodeViewSourceType.NotificationBookmark,
    ),
    SEARCH(
        key = "search",
        analyticsValue = EpisodeViewSourceType.Search,
    ),
    SEARCH_HISTORY(
        key = "search_history",
        analyticsValue = EpisodeViewSourceType.SearchHistory,
    ),
    NOW_PLAYING(
        key = "now_playing",
        analyticsValue = EpisodeViewSourceType.NowPlaying,
    ),
    UNKNOWN(
        key = "unknown",
        analyticsValue = EpisodeViewSourceType.Unknown,
    ),
    ;

    companion object {
        fun fromString(source: String?) = EpisodeViewSource.entries.find { it.key == source } ?: UNKNOWN
    }
}
