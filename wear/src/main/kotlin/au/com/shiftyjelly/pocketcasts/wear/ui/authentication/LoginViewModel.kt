package au.com.shiftyjelly.pocketcasts.wear.ui.authentication

import androidx.lifecycle.ViewModel
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.WearSigninEmailTappedEvent
import com.automattic.eventhorizon.WearSigninPhoneTappedEvent
import com.automattic.eventhorizon.WearSigninShownEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val eventHorizon: EventHorizon,
) : ViewModel() {
    fun onShown() {
        eventHorizon.track(WearSigninShownEvent)
    }

    fun onPhoneLoginClicked() {
        eventHorizon.track(WearSigninPhoneTappedEvent)
    }

    fun onEmailLoginClicked() {
        eventHorizon.track(WearSigninEmailTappedEvent)
    }
}
