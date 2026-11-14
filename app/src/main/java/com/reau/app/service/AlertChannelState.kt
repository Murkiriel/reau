package com.reau.app.service

import android.app.NotificationManager
import android.content.Context

/**
 * Whether the rider would HEAR the alerts of the alert channel ([ProjectionNotification.ALERT_CHANNEL_ID]: the stuck dash, the refused network, the
 * hot phone): Android lets the rider mute a channel (no sound, no vibration, or an importance too low to make either) or block it, or all of the
 * app's notifications, and then those alerts arrive silent or not at all. The main screen says so ([Level]) and the ride log writes it ([line]).
 *
 * The decision is pure ([levelOf]); [read] only asks Android. A channel that does not exist yet is the default one it will be made with (loud),
 * so only the app-wide switch can silence it.
 */
object AlertChannelState {
    enum class Level { LOUD, MUTED, BLOCKED }

    /** The importance from which Android plays a sound for the channel (NotificationManager.IMPORTANCE_DEFAULT is 3). */
    private const val IMPORTANCE_SOUND = NotificationManager.IMPORTANCE_DEFAULT

    /**
     * [appEnabled]: Android lets the app notify at all. [importance]: the channel's, null when it does not exist yet. [sound]: the channel has a
     * sound. [vibrates]: it vibrates. Blocked beats muted: no notifications, or importance NONE (0); muted is an importance below the one that
     * makes a sound, or neither sound nor vibration.
     */
    fun levelOf(appEnabled: Boolean, importance: Int?, sound: Boolean, vibrates: Boolean): Level = when {
        !appEnabled -> Level.BLOCKED
        importance == null -> Level.LOUD
        importance == NotificationManager.IMPORTANCE_NONE -> Level.BLOCKED
        importance == NotificationManager.IMPORTANCE_UNSPECIFIED -> Level.LOUD
        importance < IMPORTANCE_SOUND -> Level.MUTED
        !sound && !vibrates -> Level.MUTED
        else -> Level.LOUD
    }

    fun read(context: Context): Level = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(ProjectionNotification.ALERT_CHANNEL_ID)
        levelOf(manager.areNotificationsEnabled(), channel?.importance, channel?.sound != null, channel?.shouldVibrate() == true)
    }.getOrDefault(Level.LOUD)

    /** The ride log's line when the alerts would not be heard; null when they would. */
    fun line(level: Level): String? = when (level) {
        Level.LOUD -> null
        Level.MUTED -> "alerts: the alert channel is muted in Android (no sound, no vibration, or an importance too low): the stuck-dash, " +
            "refused-network and hot-phone alerts would not be heard; the main screen says so"
        Level.BLOCKED -> "alerts: Android blocks the alert channel or all of REau's notifications: the stuck-dash, refused-network and " +
            "hot-phone alerts would not appear; the main screen says so"
    }
}
