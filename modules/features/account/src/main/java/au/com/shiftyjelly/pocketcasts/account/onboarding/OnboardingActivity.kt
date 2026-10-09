package au.com.shiftyjelly.pocketcasts.account.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingFlow
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * PodHopper: this activity used to host the legacy onboarding (account creation, sign in, forgot
 * password, upgrade, win-back and promo flows), all of which authenticated against the old upstream
 * servers. Those screens have been deleted. PodHopper accounts live in Supabase via
 * PodHopperOnboardingActivity and there is no paid tier, so no flow that lands here is valid. The
 * activity stays only so the remaining legacy entry points still resolve to something that closes
 * immediately, exactly as before.
 */
@AndroidEntryPoint
class OnboardingActivity : AppCompatActivity() {
    @Inject
    lateinit var theme: Theme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        theme.setupThemeForConfig(this, resources.configuration)
    }

    companion object {
        fun newInstance(context: Context, onboardingFlow: OnboardingFlow): Intent {
            return Intent(context, OnboardingActivity::class.java).putExtra(ANALYTICS_FLOW_KEY, onboardingFlow)
        }

        private const val ANALYTICS_FLOW_KEY = "analytics_flow"
    }
}
