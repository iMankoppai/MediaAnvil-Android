package com.imankoppai.mediaanvil.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Orders the notification actions without changing the session's standard transport commands. */
@UnstableApi
internal class TransportNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {
    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> = ImmutableList.copyOf(
        super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton)
            .sortedBy { button ->
                // Media3 converts transport commands to session commands while keeping
                // their icons. Match those icons to the advertised order, including pause.
                val icon = if (button.icon == CommandButton.ICON_PAUSE) CommandButton.ICON_PLAY else button.icon
                mediaButtonPreferences.indexOfFirst { it.icon == icon }
                    .takeIf { it >= 0 } ?: Int.MAX_VALUE
            },
    )
}
