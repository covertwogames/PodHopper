package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * PodHopper: broadcast of episode played-status changes, keyed by podcast uuid.
 *
 * Exists for the car. Android Auto and Android Automotive cache every media browse node until
 * told that node changed, so an episode marked played kept showing as unplayed in the car's
 * cached lists; on 2026-07-23 that stale row baited repeated replays of a finished episode,
 * which un-completed it and fed a sync storm. MediaSessionManager collects this flow and
 * invalidates the affected browse nodes.
 *
 * A singleton object rather than an injected member so the emitter (the single status-write
 * choke point in EpisodeManagerImpl) and the collector need no new injection edges. Emission is
 * non-suspending and lossy by design under overflow: a dropped emission at worst delays a list
 * redraw, and the collector refreshes the top-level lists on every drain anyway.
 */
object PodHopperEpisodeStatusBus {
    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Podcast uuids whose episode played-status changed. */
    val changes: SharedFlow<String> = _changes

    fun notifyStatusChanged(podcastUuid: String) {
        _changes.tryEmit(podcastUuid)
    }
}
