package com.reau.app.data

import android.content.Intent
import com.reau.app.util.MonotonicClock

/**
 * What the phone is doing as far as the app knows (the charger, the screen, doze), kept from what it
 * already hears (the phone's broadcasts, [SystemEvents]; the device state read every 5 s in a projection) and read by the
 * line that says Android Auto's link ended. Nothing new is read for it; a part not heard yet is `?`.
 */
object PhoneNow {
    @Volatile private var charging: Boolean? = null
    @Volatile private var screenOn: Boolean? = null
    @Volatile private var dozing: Boolean? = null
    @Volatile private var screenEventMs = -1L
    @Volatile private var batteryTempC: Float? = null
    @Volatile private var batteryTempAtMs = Long.MIN_VALUE

    /** The device state just read (a null part leaves what was known). [batteryTempC] is the battery temperature of the same read ([batteryTempNow]). */
    fun update(
        charging: Boolean?, screenOn: Boolean?, dozing: Boolean?, batteryTempC: Float? = null, nowMs: Long = MonotonicClock.nowMs(),
    ) {
        charging?.let { this.charging = it }
        screenOn?.let { this.screenOn = it }
        dozing?.let { this.dozing = it }
        batteryTempC?.let {
            this.batteryTempC = it
            batteryTempAtMs = nowMs
        }
    }

    /**
     * The battery temperature of the last device read, in degrees Celsius; null when none was read or the last is older than [maxAgeMs] (the device
     * is read every 5 s while a projection lives, so an older one belongs to a projection that has ended).
     */
    fun batteryTempNow(nowMs: Long = MonotonicClock.nowMs(), maxAgeMs: Long = TEMP_FRESH_MS): Float? =
        batteryTempC?.takeIf { nowMs - batteryTempAtMs <= maxAgeMs }

    /** A broadcast of the phone: the screen, the charger or doze changed (the others say nothing about these). */
    fun heard(action: String, dozing: Boolean?, nowMs: Long = MonotonicClock.nowMs()) {
        when (action) {
            Intent.ACTION_SCREEN_ON -> screenOn = true.also { screenEventMs = nowMs }
            Intent.ACTION_SCREEN_OFF -> screenOn = false.also { screenEventMs = nowMs }
            Intent.ACTION_USER_PRESENT -> screenEventMs = nowMs
            Intent.ACTION_POWER_CONNECTED -> charging = true
            Intent.ACTION_POWER_DISCONNECTED -> charging = false
        }
        dozing?.let { this.dozing = it }
    }

    /** Whether the charger is in, as last heard (null: not heard yet). */
    fun chargingNow(): Boolean? = charging

    /** Whether the screen is on, as last heard (null: not heard yet). */
    fun screenOnNow(): Boolean? = screenOn

    /**
     * The words for the line that says Android Auto's link ended. The time since the last screen event (on, off, unlocked) is there
     * once one was heard, to tell a link that closed with the screen (the link closed 0.5 s before the screen went off, 20/10/2026).
     */
    fun text(nowMs: Long = MonotonicClock.nowMs()): String =
        "charger ${word(charging, "in", "out")}, screen ${word(screenOn, "on", "off")}, doze ${word(dozing, "on", "off")}" +
            if (screenEventMs >= 0) screenEventWords(nowMs - screenEventMs) else ""

    private fun screenEventWords(agoMs: Long) = String.format(java.util.Locale.US, ", screen event %.1f s before", agoMs.coerceAtLeast(0) / MS_PER_S)

    private fun word(value: Boolean?, yes: String, no: String) = when (value) {
        true -> yes
        false -> no
        null -> "?"
    }

    internal fun testOnlyReset() {
        charging = null
        screenOn = null
        dozing = null
        screenEventMs = -1L
        batteryTempC = null
        batteryTempAtMs = Long.MIN_VALUE
    }

    /** Six device looks (30 s): a temperature older than this is no longer what the battery says. */
    const val TEMP_FRESH_MS = 30_000L

    private const val MS_PER_S = 1_000.0
}
