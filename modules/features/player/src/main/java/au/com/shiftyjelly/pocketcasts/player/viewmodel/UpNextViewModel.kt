package au.com.shiftyjelly.pocketcasts.player.viewmodel

import androidx.lifecycle.ViewModel
import au.com.shiftyjelly.pocketcasts.models.type.UpNextSortType
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.UpNextSortEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class UpNextViewModel @Inject constructor(
    private val userManager: UserManager,
    private val upNextQueue: UpNextQueue,
    private val eventHorizon: EventHorizon,
) : ViewModel() {
    // PodHopper: Up Next shuffle is unlocked for every install (local entitlement, no Plus tier).
    private val _isSignedInAsPaidUser = MutableStateFlow(true)
    val isSignedInAsPaidUser: StateFlow<Boolean> get() = _isSignedInAsPaidUser

    fun sortUpNext(sortType: UpNextSortType) {
        eventHorizon.track(
            UpNextSortEvent(
                sortType = sortType.analyticsValue,
            ),
        )
        upNextQueue.sortUpNext(sortType)
    }
}
