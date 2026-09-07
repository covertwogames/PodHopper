package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.Settings.MediaNotificationControls

@UnstableApi
internal class Media3AutomotiveStrategy : AutomotiveSessionStrategy {

    override fun buildLayout(
        playbackManager: PlaybackManager,
        settings: Settings,
        context: Context,
        buildCustomActionButton: (MediaNotificationControls, BaseEpisode?) -> CommandButton?,
    ): AutomotiveSessionStrategy.ButtonLayout {
        val buttons = mutableListOf<CommandButton>()
        val currentEpisode = playbackManager.getCurrentEpisode()

        // PodHopper: when a newer cross-device position arrived after playback started locally
        // (the pre-play sync pull timed out), surface a one-tap catch-up action. Non-blocking by
        // design: playback is already running, this is an offer, and it clears itself when
        // ignored, tapped, or the episode changes.
        // PodHopper: the jump-to-synced button that used to be built here was removed. The car UI
        // either rendered it without context or let it displace the skip-forward control, and the
        // auto-switch-after-resume behaviour in PlaybackManager replaced the need for it.

        // PodHopper: leave the backward and forward transport slots empty here. Media3 fills them
        // with its default skip-to-previous/next buttons, which keeps ACTION_SKIP_TO_PREVIOUS and
        // ACTION_SKIP_TO_NEXT published in the legacy PlaybackState the car reads. The forwarding
        // player's seekToPrevious/seekToNext overrides route those to the configured skip-back and
        // skip-forward amounts. Placing custom seek buttons in these slots makes Media3 strip the
        // skip actions to avoid duplicate forward/back controls, which is what stopped the car's
        // hardware/steering-wheel forward key from working.
        val visibleCount = if (settings.customMediaActionsVisibility.value) MediaNotificationControls.MAX_VISIBLE_OPTIONS else 0
        settings.mediaControlItems.value.take(visibleCount).forEach { mediaControl ->
            buildCustomActionButton(mediaControl, currentEpisode)?.let(buttons::add)
        }

        return AutomotiveSessionStrategy.ButtonLayout(primaryButtons = buttons, overflowButtons = emptyList())
    }
}
