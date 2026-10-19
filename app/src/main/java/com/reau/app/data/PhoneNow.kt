package com.reau.app.data

import android.content.Intent

/**
 * What the phone is doing as far as the app knows (the charger, the screen, doze), kept from what it
 * already hears (the phone's broadcasts, [SystemEvents]; the device state read every 5 s in a projection) and read by the
 * line that says Android Auto's link ended. Nothing new is read for it; a part not heard yet is `?`.
 */
object PhoneNow {
    @Volatile private var charging: Boolean? = null
    @Volatile private var screenOn: Boolean? = null
    @Volatile private var dozing: Boolean? = null

    /** The device state just read (a null part leaves what was known). */
    fun update(charging: Boolean?, screenOn: Boolean?, dozing: Boolean?) {
        charging?.let { this.charging = it }
        screenOn?.let { this.screenOn = it }
        dozing?.let { this.dozing = it }
    }

    /** A broadcast of the phone: the screen, the charger or doze changed (the others say nothing about these). */
    fun heard(action: String, dozing: Boolean?) {
        when (action) {
            Intent.ACTION_SCREEN_ON -> screenOn = true
            Intent.ACTION_SCREEN_OFF -> screenOn = false
            Intent.ACTION_POWER_CONNECTED -> charging = true
            Intent.ACTION_POWER_DISCONNECTED -> charging = false
        }
        dozing?.let { this.dozing = it }
    }

    fun text(): String = "charger ${word(charging, "in", "out")}, screen ${word(screenOn, "on", "off")}, doze ${word(dozing, "on", "off")}"

    private fun word(value: Boolean?, yes: String, no: String) = when (value) {
        true -> yes
        false -> no
        null -> "?"
    }

    internal fun testOnlyReset() {
        charging = null
        screenOn = null
        dozing = null
    }
}
