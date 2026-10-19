package com.reau.app.settings

/**
 * Privacy: REau reads the phone's position only with the switch "Usar a localização" on ([REauSettings.useLocation],
 * off by default), for the theme by the sun. The location permission is also asked for, and used, to read the Wi-Fi
 * list alone, to find the dash's whole name: Android gives the list with it only (on Android 14 the nearby-devices
 * permission gave an empty list), and REau never reads the position for it.
 */
object LocationUse {
    /** The Wi-Fi list, to learn the dash's whole name: by the location permission, whatever the switch. */
    fun mayScan(locationGranted: Boolean): Boolean = locationGranted
}
