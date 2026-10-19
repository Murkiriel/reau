package com.reau.app.aa

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** The Android Auto buttons: its settings when it is installed, else its page in the store, and never silence. */
object AndroidAutoStore {
    /** Where the rider is sent from the Android Auto buttons: its settings, its page in the store, or nowhere (a notice). */
    enum class Route { SETTINGS, STORE_APP, STORE_WEB, NOTICE }

    /**
     * What the Android Auto buttons do: the settings when it is [installed]; otherwise its page in the store app
     * ([storeApp]), else on the web ([storeWeb]), else only a notice, never silence.
     */
    fun route(installed: Boolean, storeApp: Boolean, storeWeb: Boolean): Route = when {
        installed -> Route.SETTINGS
        storeApp -> Route.STORE_APP
        storeWeb -> Route.STORE_WEB
        else -> Route.NOTICE
    }

    /** Android Auto's page in the store app (market:), where the rider installs it. */
    fun appIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=${AndroidAuto.PACKAGE}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android Auto's page on the Play Store website, for a phone with no store app. */
    fun webIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${AndroidAuto.PACKAGE}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * [AndroidAuto.openSettings] when it is installed, else its page in the store. Never throws and never stays silent:
     * [AndroidAuto.Opened.NOTHING] means the caller owes the rider a notice.
     */
    fun open(context: Context, start: (Intent) -> Unit = context::startActivity): AndroidAuto.Opened {
        val pm = context.packageManager
        val app = appIntent()
        val web = webIntent()
        val route = route(AndroidAuto.installedVersion(context) != null, pm.resolveActivity(app, 0) != null, pm.resolveActivity(web, 0) != null)
        return when (route) {
            Route.SETTINGS -> AndroidAuto.openSettings(context, start)
            Route.STORE_APP -> when {
                AndroidAuto.opens(app, start) -> AndroidAuto.Opened.STORE_APP
                // The store app named itself and then failed: the web page is still a way.
                AndroidAuto.opens(web, start) -> AndroidAuto.Opened.STORE_WEB
                else -> AndroidAuto.Opened.NOTHING
            }
            Route.STORE_WEB -> if (AndroidAuto.opens(web, start)) AndroidAuto.Opened.STORE_WEB else AndroidAuto.Opened.NOTHING
            Route.NOTICE -> AndroidAuto.Opened.NOTHING
        }
    }
}
