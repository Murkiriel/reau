package com.reau.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.reau.app.MainActivity
import com.reau.app.R

/**
 * The way in from the launcher's shortcut (a long press on the icon) and from the Quick Settings tile: REau opens and, if it is off,
 * switches the projection on, so a rider can do it from the notification shade at home, before the helmet.
 */
object QuickStart {
    const val ACTION = "com.reau.app.QUICK_START"

    /**
     * Whether an intent with [action] switches the projection on: only a quick start, only a projection that is stopped and not still
     * being stopped, only once the terms of use are accepted (nothing of the app is reachable before that, and a shortcut must not be
     * the way round them) and only with the phone unlocked ([unlocked], from `KeyguardManager`: a shortcut that reaches the app over a
     * lock screen, or another app's intent with this action, must not start the projection, its microphone and its location). The icon's
     * shortcut and the tile still open the app when the phone is locked; they only do not switch it on.
     */
    fun switchesOn(action: String?, projection: Projection, termsAccepted: Boolean, unlocked: Boolean): Boolean =
        action == ACTION && projection.phase == Phase.STOPPED && !projection.stopping && termsAccepted && unlocked

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/**
 * The Quick Settings tile "REau": a touch opens REau, which switches on. Opening an activity from the tile is how Android lets the
 * switch-on ask for its permissions and show its warnings; the tile itself starts nothing in the background.
 */
class QuickStartTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (ProjectionService.projection.value.phase == Phase.STOPPED) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_reau)
        tile.updateTile()
    }

    // The Intent form runs only before Android 14, where it is the only one; from there the PendingIntent one is used.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = QuickStart.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") // the Intent form is the only one before Android 14
            startActivityAndCollapse(intent)
        }
    }
}
