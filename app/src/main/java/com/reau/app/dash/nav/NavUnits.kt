package com.reau.app.dash.nav

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.reau.app.dash.DashReport
import java.util.Locale

/**
 * Whether the maneuver distances go to the dash in miles and feet ([AaManeuvers.distance]'s `imperial`): the unit the dash says it is set to
 * (`03 04`, [DashReport.Settings.distance]) wins, so the bubble and the dash's own screens agree; before the dash has said it (the first seconds of
 * a connection, or never) the phone's region decides: the COUNTRY of the phone's own locales (what the rider set in Android's system settings), never the
 * language of the app, which is the rider's choice of text and says nothing about the road signs (an American with the app in Portuguese is in miles).
 */
object NavUnits {
    /**
     * The regions that road signs measure in miles: the United States and its territories, the United Kingdom, Liberia and Myanmar. A region
     * not here (or none) is metric, as before.
     */
    private val MILE_REGIONS = setOf("US", "GB", "LR", "MM", "PR", "GU", "VI", "AS", "MP")

    /** True for miles: the dash's own unit when it said one, else the phone's [region] (an ISO country code, `Locale.country`). */
    fun imperial(panel: DashReport.DistanceUnit?, region: String?): Boolean = when (panel) {
        DashReport.DistanceUnit.MILES -> true
        DashReport.DistanceUnit.KM -> false
        null -> region?.uppercase(java.util.Locale.ROOT) in MILE_REGIONS
    }

    /**
     * The country (ISO code) of the first of the phone's [locales], in the order the rider ranked them, that has one; "" when none does. Pure, so
     * it is tested with made-up lists: the app's own language is not among them (see [phoneRegion]).
     */
    fun regionOf(locales: List<Locale>): String = locales.firstOrNull { it.country.isNotBlank() }?.country.orEmpty()

    /**
     * The region of the phone itself: the system's locales (from Android 13 the system keeps them apart from the app's own language, which
     * changes [Locale.getDefault]; before that the app's language only lives in its wrapped contexts and the system configuration is the phone's).
     */
    fun phoneRegion(context: Context): String {
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }
        return regionOf((0 until system.size()).map { system[it] })
    }

    /** What the ride log says when the choice changes: the unit and why. */
    fun line(imperial: Boolean, panel: DashReport.DistanceUnit?, region: String?): String {
        val why = if (panel != null) {
            "the dash says ${panel.name.lowercase()}"
        } else {
            "the dash has not said yet, the phone's region is ${region?.ifBlank { "unknown" } ?: "unknown"}"
        }
        return "nav distances in ${if (imperial) "feet and tenths of a mile" else "metres and tenths of a kilometre"} ($why)"
    }
}
