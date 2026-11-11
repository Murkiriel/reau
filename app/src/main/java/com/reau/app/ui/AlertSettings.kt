package com.reau.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.ProjectionNotification

/**
 * Android's own page for the alert channel, from the main screen's card when the alerts are muted or blocked: the channel's page when there is
 * one to open, else the app's notifications (a blocked app, or a channel not made yet). Needs no permission: it is an ordinary settings page.
 */
object AlertSettings {
    /** The intent for the channel's page, or for the app's whole notification page when [channel] is false. */
    internal fun intent(context: Context, channel: Boolean): Intent =
        Intent(if (channel) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .apply { if (channel) putExtra(Settings.EXTRA_CHANNEL_ID, ProjectionNotification.ALERT_CHANNEL_ID) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context) {
        RideDiagnostics.log("ui", "the rider opened the settings of the alert channel from the main screen's card")
        if (runCatching { context.startActivity(intent(context, channel = true)) }.isFailure) {
            runCatching { context.startActivity(intent(context, channel = false)) }
        }
    }
}
