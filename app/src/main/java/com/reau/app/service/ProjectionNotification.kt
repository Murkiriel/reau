package com.reau.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.reau.app.MainActivity
import com.reau.app.R

/**
 * The projection's ongoing notification: what it says for each headline ([contentFor]), and the notification
 * itself, which opens the app and has a button that switches the projection off and one that marks the log;
 * [subText] says the last mark was taken.
 */
object ProjectionNotification {
    const val ID = 5301
    private const val CHANNEL_ID = "reau_projection"

    /** [muteByRider]: the rider, not REau, has to restart a mute Android Auto server ([AaServerStarter.muteServerByRider]). */
    fun contentFor(headline: Headline, muteByRider: Boolean = false): Pair<Int, Int> = when (headline) {
        Headline.PREPARING -> R.string.notif_title_preparing to R.string.notif_text_preparing
        Headline.CONNECTING -> R.string.notif_title_connecting to R.string.notif_text_connecting
        Headline.ANDROID_AUTO_SERVER_OFF -> R.string.notif_title_server_off to R.string.notif_text_server_off
        Headline.ANDROID_AUTO_UNRESPONSIVE ->
            R.string.notif_title_unresponsive to if (muteByRider) R.string.notif_text_unresponsive_by_hand else R.string.notif_text_unresponsive
        Headline.ANDROID_AUTO_CLOSING_PREVIOUS -> R.string.notif_title_closing_previous to R.string.notif_text_closing_previous
        Headline.ANDROID_AUTO_NOT_CALLING -> R.string.notif_title_not_calling to R.string.notif_text_not_calling
        Headline.ANDROID_AUTO_NO_VIDEO -> R.string.notif_title_no_video to R.string.notif_text_no_video
        Headline.ANDROID_AUTO_IMAGE_STALE -> R.string.notif_title_image_stale to R.string.notif_text_image_stale
        Headline.DASH_LOST -> R.string.notif_title_dash_lost to R.string.notif_text_dash_lost
        Headline.DASH_SEARCHING -> R.string.notif_title_dash_searching to R.string.notif_text_dash_searching
        Headline.DASH_JOIN_ASK -> R.string.notif_title_dash_searching to R.string.notif_text_dash_join_ask
        Headline.STREAMING -> R.string.notif_title_streaming to R.string.notif_text_streaming
        Headline.NETWORK_SERVER -> R.string.notif_title_network_server to R.string.notif_text_network_server
        // The notification only lives while the service does: stopped is the moment it goes.
        Headline.CARD -> R.string.notif_title_card to R.string.notif_text_card
        Headline.DASH_TAKEN -> R.string.notif_title_dash_taken to R.string.notif_text_dash_taken
        Headline.DASH_NAME_NEEDED -> R.string.notif_title_dash_name_needed to R.string.notif_text_dash_name_needed
        Headline.STOPPING -> R.string.notif_title_stopping to R.string.notif_text_stopping_now
        Headline.STOPPED, Headline.PROBLEM -> R.string.status_stopped to R.string.notif_text_stopping
    }

    /**
     * Two redesign proposals, each behind a switch that is easy to turn off: the
     * notification's colour by state, and the time since the start counted by the notification itself.
     */
    const val COLOR_BY_STATE = true
    const val CHRONOMETER = true

    private const val GOLD = 0xFFE1AD01.toInt()
    private const val WARNING = 0xFFC26A22.toInt()
    private const val ERROR = 0xFFD64A4A.toInt()

    /** Ouro on the happy path; Atenção while the dash is tried again; Erro while something waits for the rider. */
    fun colorFor(headline: Headline): Int = when (headline) {
        Headline.DASH_LOST, Headline.ANDROID_AUTO_IMAGE_STALE, Headline.ANDROID_AUTO_CLOSING_PREVIOUS -> WARNING
        Headline.ANDROID_AUTO_SERVER_OFF, Headline.ANDROID_AUTO_UNRESPONSIVE, Headline.ANDROID_AUTO_NOT_CALLING, Headline.ANDROID_AUTO_NO_VIDEO,
        Headline.DASH_TAKEN, Headline.DASH_NAME_NEEDED -> ERROR
        else -> GOLD
    }

    /** The wall-clock time the projection started, from its [startedElapsed] start and the clocks of now. */
    fun startedWall(startedElapsed: Long, nowElapsed: Long, nowWall: Long): Long = nowWall - (nowElapsed - startedElapsed)

    /** One channel, low importance (no sound, no pop-up). */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notif_channel_desc) },
        )
    }

    fun build(context: Context, headline: Headline, subText: String? = null, startedElapsed: Long? = null): Notification {
        // Who restarts a mute server is only worked out (settings read) for the headline that needs it.
        val byRider = headline == Headline.ANDROID_AUTO_UNRESPONSIVE && AaServerStarter.muteServerByRider(context)
        val (title, text) = contentFor(headline, byRider)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, ProjectionService::class.java).setAction(ProjectionService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val mark = PendingIntent.getService(
            context, 2, Intent(context, ProjectionService::class.java).setAction(ProjectionService.ACTION_MARK), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setSmallIcon(R.drawable.ic_stat_reau)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notif_action_stop), stop).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notif_action_mark), mark).build())
            .setSubText(subText)
            .apply {
                if (COLOR_BY_STATE) setColor(colorFor(headline))
                if (CHRONOMETER && startedElapsed != null) {
                    setUsesChronometer(true)
                    setShowWhen(true)
                    setWhen(startedWall(startedElapsed, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
                }
            }
            .build()
    }

    /** Shows [headline] in place of the last one; nothing for a stopped projection, whose service is going. */
    fun show(context: Context, headline: Headline, subText: String? = null, startedElapsed: Long? = null) {
        if (headline == Headline.STOPPED || headline == Headline.PROBLEM) return
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, headline, subText, startedElapsed))
    }
}
