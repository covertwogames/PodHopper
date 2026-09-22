package au.com.shiftyjelly.pocketcasts.repositories.podcast

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode

/**
 * PodHopper watch: keeps the [capacity] newest episodes offered to it, by published date, and drops
 * the rest as it goes. The watch keeps only a podcast's newest episodes, and this is what lets it read
 * a feed of any length, in any order, while holding no more than [capacity] episodes in memory.
 */
class NewestEpisodes(private val capacity: Int) {
    private val kept = ArrayList<PodcastEpisode>(capacity + 1)

    fun add(episode: PodcastEpisode) {
        if (capacity <= 0) {
            return
        }
        if (kept.size < capacity) {
            kept.add(episode)
            return
        }
        var oldestIndex = 0
        for (index in 1 until kept.size) {
            if (kept[index].publishedDate.before(kept[oldestIndex].publishedDate)) {
                oldestIndex = index
            }
        }
        if (episode.publishedDate.after(kept[oldestIndex].publishedDate)) {
            kept[oldestIndex] = episode
        }
    }

    /** The kept episodes, newest first. */
    fun toList(): List<PodcastEpisode> = kept.sortedByDescending { it.publishedDate }
}
