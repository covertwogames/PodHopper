package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.Settings.MediaNotificationControls
import au.com.shiftyjelly.pocketcasts.localization.R as LR

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

        // PodHopper: the jump-to-synced button that used to be built here was removed. The car UI
        // either rendered it without context or let it displace the skip-forward control, and the
        // auto-switch-after-resume behaviour in PlaybackManager replaced the need for it.

        // PodHopper: skip back and skip forward fill the car's two transport slots beside play, as
        // the app's own buttons with skip-by-seconds icons. The car draws next/previous-track icons
        // for the standard skip-to-next/previous actions, which is the wrong picture for a 10 or 30
        // second skip. Putting these buttons in the slots makes Media3 leave those standard actions
        // out of the state the car's screen reads, so the car shows these buttons there instead.
        // They must come first and in this order: a car that places buttons by position puts the
        // first in the left slot and the second in the right slot.
        //
        // The steering-wheel keys do not depend on what is on screen. Wheel presses that arrive as
        // key presses are handled directly in Media3SessionCallback.onMediaButtonEvent. A car media
        // service that calls skip-to-next/previous directly still reaches the forwarding player,
        // because those commands stay available there and are routed to skip forward/back.
        buttons.add(
            CommandButton.Builder(skipBackIcon(settings.skipBackInSecs.value))
                .setSessionCommand(SessionCommand(APP_ACTION_SKIP_BACK, Bundle.EMPTY))
                .setDisplayName(context.getString(LR.string.skip_back))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
        )
        buttons.add(
            CommandButton.Builder(skipForwardIcon(settings.skipForwardInSecs.value))
                .setSessionCommand(SessionCommand(APP_ACTION_SKIP_FWD, Bundle.EMPTY))
                .setDisplayName(context.getString(LR.string.skip_forward))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )

        val visibleCount = if (settings.customMediaActionsVisibility.value) MediaNotificationControls.MAX_VISIBLE_OPTIONS else 0
        settings.mediaControlItems.value.take(visibleCount).forEach { mediaControl ->
            buildCustomActionButton(mediaControl, currentEpisode)?.let { button -> buttons.add(overflowOnly(button)) }
        }

        return AutomotiveSessionStrategy.ButtonLayout(primaryButtons = buttons, overflowButtons = emptyList())
    }

    /**
     * PodHopper: the icon that shows the skip-back amount. Media3 has numbered icons for 5, 10, 15
     * and 30 seconds; any other amount gets the plain skip-back arrow rather than a wrong number.
     */
    private fun skipBackIcon(seconds: Int): @CommandButton.Icon Int = when (seconds) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        30 -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    /** PodHopper: the skip-forward counterpart of [skipBackIcon]. */
    private fun skipForwardIcon(seconds: Int): @CommandButton.Icon Int = when (seconds) {
        5 -> CommandButton.ICON_SKIP_FORWARD_5
        10 -> CommandButton.ICON_SKIP_FORWARD_10
        15 -> CommandButton.ICON_SKIP_FORWARD_15
        30 -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }

    /**
     * PodHopper: keeps an extra action out of the two skip slots. Media3 picks a button's slot from
     * its icon, so the Play next action (a next-track icon) would otherwise claim the forward slot
     * and, with the skip-forward button already there, be dropped from the car entirely. Moving it
     * to the overflow keeps it reachable and leaves the skip buttons where they are.
     */
    private fun overflowOnly(button: CommandButton): CommandButton {
        val sessionCommand = button.sessionCommand ?: return button
        if (button.slots.contains(CommandButton.SLOT_OVERFLOW)) {
            return button
        }
        return CommandButton.Builder(button.icon)
            .setSessionCommand(sessionCommand)
            .setCustomIconResId(button.iconResId)
            .setDisplayName(button.displayName)
            .setExtras(button.extras)
            .setEnabled(button.isEnabled)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    }
}
