// PodHopper: this file is an intentional no-op shell (see the class comment). The retained
// constructor dependencies and parameters are deliberately unused, and the build compiles with
// allWarningsAsErrors, so suppress the unused-declaration diagnostics for the whole file.
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantSuspendModifier")

package au.com.shiftyjelly.pocketcasts.analytics.experiments

import au.com.shiftyjelly.pocketcasts.analytics.AccountStatusInfo
import com.automattic.android.experimentation.VariationsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class ExperimentProvider @Inject constructor(
    private val repository: VariationsRepository,
    private val accountStatusInfo: AccountStatusInfo,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val TAG = "ExperimentsProvider"
        const val PLATFORM = "pocketcasts"
    }

    // PodHopper: the ExPlat experiment platform fetched experiment assignments from Automattic
    // (public-api.wordpress.com) on every app start. PodHopper runs no remote experiments, so the
    // whole provider is a local no-op. The class, its constructor and its method signatures are
    // kept so the existing injection sites and callers (app startup, UserManager, upsell copy)
    // compile unchanged; getVariation returning null means every caller takes its default path.

    fun initialize() {
        // PodHopper: no-op, no remote experiment fetch.
    }

    fun initialize(uuid: String) {
        // PodHopper: no-op, no remote experiment fetch.
    }

    suspend fun refreshExperiments(uuid: String? = null) {
        // PodHopper: no-op, no remote experiment fetch.
    }

    fun getVariation(experiment: ExperimentType): Variation? {
        // PodHopper: no experiments are ever assigned; callers fall back to their defaults.
        return null
    }
}
