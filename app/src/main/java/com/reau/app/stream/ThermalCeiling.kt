package com.reau.app.stream

import com.reau.app.dash.DashConfig

/**
 * The frame ceiling a hot phone streams under, after OpenCfMoto's
 * AdaptivePolicy.thermalFpsCap (AGPL-3.0, app/src/main/java/dev/zanderp/opencfmoto/AdaptiveVideoController.kt,
 * commit 0abbe2a): none or light, the rider's ceiling; moderate, severe and critical-or-worse each lower it. Kept:
 * the four steps by PowerManager's thermal status and "never above the base". Changed: OpenCfMoto's caps are
 * absolute rates for its 30 fps base (20, 15, 12); the dash runs at a fraction of that, so here the steps are
 * fractions of the rider's ceiling (3/4, 5/8, 1/2, like OpenCfMoto's bitrate factors 0.8, 0.6, 0.5), on even
 * rates and never under the dash's floor ([DashConfig.snapStreamFps]).
 *
 * [status] is a PowerManager.THERMAL_STATUS_* value: 0 none, 1 light, 2 moderate, 3 severe, 4 critical,
 * 5 emergency, 6 shutdown. Anything below 0 (status unknown) counts as cool.
 */
object ThermalCeiling {
    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val SEVERE = 3

    fun capFor(status: Int, ceiling: Int): Int {
        val base = DashConfig.snapStreamFps(ceiling)
        val hot = when {
            status <= LIGHT -> base
            status == MODERATE -> base * 3 / 4
            status == SEVERE -> base * 5 / 8
            else -> base / 2
        }
        return DashConfig.snapStreamFps(hot).coerceAtMost(base)
    }

    /** Whether streaming at [fps] is within what [status] allows under the rider's [ceiling]. */
    fun allows(status: Int, fps: Int, ceiling: Int): Boolean = fps <= capFor(status, ceiling)

    fun nameOf(status: Int): String = NAMES.getOrNull(status) ?: "?$status"

    private val NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
}
