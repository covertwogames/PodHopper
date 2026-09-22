package au.com.shiftyjelly.pocketcasts.wear

import android.content.Context
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.wear.networking.ConnectivityStateManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

/**
 * Unit tests for WearMainActivityViewModel: the watch is signed in exactly when a PodHopper session
 * is stored, and follows that session as pairing sets it and signing out clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearMainActivityViewModelTest {
    @get:Rule
    val coroutineRule = MainCoroutineRule(StandardTestDispatcher())

    @Mock
    private lateinit var podcastManager: PodcastManager

    @Mock
    private lateinit var settings: Settings

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var supabaseClient: SupabaseClient

    @Mock
    private lateinit var connectivityStateManager: ConnectivityStateManager

    private val loginStateFlow = MutableStateFlow(false)

    // Use MutableStateFlow for connectivity so the debounce doesn't block
    private val connectivityFlow = MutableStateFlow(true)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(supabaseClient.loginState).thenReturn(loginStateFlow)
        whenever(connectivityStateManager.isConnected).thenReturn(connectivityFlow)
    }

    @After
    fun tearDown() = runTest {
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `starts signed in when a PodHopper session is stored`() = runTest {
        whenever(supabaseClient.isLoggedIn()).thenReturn(true)
        loginStateFlow.value = true

        val viewModel = createViewModel()

        assertTrue(viewModel.state.value.isSignedIn)
    }

    @Test
    fun `starts signed out when no PodHopper session is stored`() = runTest {
        whenever(supabaseClient.isLoggedIn()).thenReturn(false)

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isSignedIn)
    }

    @Test
    fun `pairing flips the watch to signed in`() = runTest {
        whenever(supabaseClient.isLoggedIn()).thenReturn(false)
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        loginStateFlow.value = true
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.isSignedIn)
    }

    @Test
    fun `signing out flips the watch to signed out`() = runTest {
        whenever(supabaseClient.isLoggedIn()).thenReturn(true)
        loginStateFlow.value = true
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        loginStateFlow.value = false
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isSignedIn)
    }

    @Test
    fun `connectivity changes are reflected after the debounce`() = runTest {
        whenever(supabaseClient.isLoggedIn()).thenReturn(true)
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        connectivityFlow.value = false
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isConnected)
    }

    private fun createViewModel() = WearMainActivityViewModel(
        podcastManager = podcastManager,
        settings = settings,
        context = context,
        supabaseClient = supabaseClient,
        connectivityStateManager = connectivityStateManager,
    )
}
