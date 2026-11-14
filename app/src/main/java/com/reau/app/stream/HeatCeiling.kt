package com.reau.app.stream

import java.util.Locale

/**
 * The battery's heat as a second source for the video's frame ceiling ([ThermalCeiling]). Android's thermal status follows the skin, and on some
 * phones it never leaves `none` (a thermal HAL that does not answer; ride log of 01/11/2026: 40 to 42 C on the battery for 90 minutes with the
 * charger in, status `none` all along). The battery's temperature the app already reads every 5 s ([com.reau.app.data.PhoneNow.batteryTempNow],
 * the `temp=` of the `[device]` line) says it: at [ENTER_C] it counts as the status 'moderate' (3/4 of the ceiling), at 46 C as 'severe' (5/8), at
 * 48 C as 'critical' (1/2). Each step is left only [HYSTERESIS_C] degrees under the one it was entered at, so 43.9 and 44.0 are not a step each.
 * The result is the higher of this and Android's own status.
 *
 * With the experimental switch off [status] returns Android's status untouched and keeps no state. One object per projection's sides; the video
 * loop's thread and the one that starts it both ask, hence synchronized.
 */
class HeatCeiling(private val log: (String) -> Unit = {}) {
    private var step = 0

    /**
     * The status the video loop uses: [system] (Android's) with the switch [on] off; with it on, the higher of [system] and the status the battery's
     * [tempC] gives (null: the battery did not say, which keeps the step as it was).
     */
    @Synchronized
    fun status(system: Int, on: Boolean, tempC: Float?): Int {
        if (!on) {
            step = 0
            return system
        }
        if (tempC != null) move(tempC)
        return if (step == 0) system else maxOf(system, STATUSES[step - 1])
    }

    private fun move(tempC: Float) {
        val before = step
        val reached = ENTER_C.count { tempC >= it }
        if (reached > step) {
            step = reached
        } else {
            while (step > 0 && tempC < ENTER_C[step - 1] - HYSTERESIS_C) step--
        }
        if (step == before) return
        val text = String.format(Locale.US, "%.1f", tempC)
        log(
            if (step > before) {
                "battery at $text C: the frame ceiling is held under the ${ThermalCeiling.nameOf(STATUSES[step - 1])} step (experiment)"
            } else if (step == 0) {
                "battery back to $text C: the frame ceiling no longer follows the battery (experiment)"
            } else {
                "battery down to $text C: the frame ceiling is held under the ${ThermalCeiling.nameOf(STATUSES[step - 1])} step (experiment)"
            },
        )
    }

    companion object {
        /** The battery temperatures that enter 'moderate', 'severe' and 'critical'; 44 is where [com.reau.app.service.BatteryHeatWatch] tells the rider the phone is hot. */
        val ENTER_C = floatArrayOf(44f, 46f, 48f)

        /** How far under a step's entry the battery has to fall to leave it. */
        const val HYSTERESIS_C = 3f

        /** PowerManager's THERMAL_STATUS_MODERATE, SEVERE and CRITICAL. */
        private val STATUSES = intArrayOf(2, 3, 4)
    }
}
