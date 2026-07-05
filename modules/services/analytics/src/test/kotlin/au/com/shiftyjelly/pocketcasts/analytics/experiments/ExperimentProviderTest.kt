package au.com.shiftyjelly.pocketcasts.analytics.experiments

import au.com.shiftyjelly.pocketcasts.analytics.AccountStatusInfo
import au.com.shiftyjelly.pocketcasts.sharedtest.InMemoryFeatureFlagRule
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import com.automattic.android.experimentation.VariationsRepository
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.verifyNoInteractions

// PodHopper: ExperimentProvider is a local no-op. It must never talk to the Automattic ExPlat
// repository and must never assign a variation, no matter what the feature flag says.
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentProviderTest {
    private lateinit var repository: VariationsRepository
    private lateinit var experimentProvider: ExperimentProvider
    private lateinit var accountStatusInfo: AccountStatusInfo

    @get:Rule
    val featureFlagRule = InMemoryFeatureFlagRule()

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    @Before
    fun setUp() {
        repository = mock(VariationsRepository::class.java)
        accountStatusInfo = mock(AccountStatusInfo::class.java)
        experimentProvider = ExperimentProvider(repository, accountStatusInfo, coroutineRule.testDispatcher)
    }

    @Test
    fun `initialize never touches the repository`() {
        FeatureFlag.setEnabled(Feature.EXPLAT_EXPERIMENT, true)

        experimentProvider.initialize()
        experimentProvider.initialize("test-uuid")

        verifyNoInteractions(repository)
    }

    @Test
    fun `refreshExperiments never touches the repository`() = runTest {
        FeatureFlag.setEnabled(Feature.EXPLAT_EXPERIMENT, true)

        experimentProvider.refreshExperiments()
        experimentProvider.refreshExperiments("test-uuid")

        verifyNoInteractions(repository)
    }

    @Test
    fun `getVariation always returns null and never touches the repository`() {
        FeatureFlag.setEnabled(Feature.EXPLAT_EXPERIMENT, true)

        val variation = experimentProvider.getVariation(DummyExperiment.DUMMY_EXPERIMENT)

        assertNull(variation)
        verifyNoInteractions(repository)
    }

    @Test
    fun `getVariation returns null when feature flag is disabled`() {
        FeatureFlag.setEnabled(Feature.EXPLAT_EXPERIMENT, false)

        val variation = experimentProvider.getVariation(DummyExperiment.DUMMY_EXPERIMENT)

        assertNull(variation)
        verifyNoInteractions(repository)
    }
}

enum class DummyExperiment(override val identifier: String) : ExperimentType {
    DUMMY_EXPERIMENT("dummy_experiment"),
}
