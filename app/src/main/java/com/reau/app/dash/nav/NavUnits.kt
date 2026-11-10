package com.reau.app.dash.nav

import com.reau.app.dash.DashReport

/**
 * Whether the maneuver distances go to the dash in miles and feet ([AaManeuvers.distance]'s `imperial`): the unit the dash says it is set to
 * (`03 04`, [DashReport.Settings.distance]) wins, so the bubble and the dash's own screens agree; before the dash has said it (the first seconds of
 * a connection, or never) the phone's region decides.
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
