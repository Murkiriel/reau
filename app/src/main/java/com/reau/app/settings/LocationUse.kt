package com.reau.app.settings

/**
 * Privacy: REau reads the phone's position only with the switch "Usar a localização" on ([REauSettings.useLocation],
 * on by default) and a location permission given: before that it reads nothing, and it starts on its own once the permission is
 * there (each return to the screen asks again), for the theme by the sun and the weather. The location permission is also asked for, and used, to read the Wi-Fi
 * list alone, to find the dash's whole name: Android gives the list with it only (on Android 14 the nearby-devices
 * permission gave an empty list), and REau never reads the position for it.
 */
object LocationUse {
    /** The Wi-Fi list, to learn the dash's whole name: by the location permission, whatever the switch. */
    fun mayScan(locationGranted: Boolean): Boolean = locationGranted

    /**
     * The last known position is read for the sun and the weather only with the switch on and either location permission given.
     * The same rule decides whether the place kept earlier may still be used ([com.reau.app.service.SunPlace.saved]): a permission
     * revoked in Android stops the kept place from reaching the weather request, though nothing kept is erased.
     */
    fun mayReadPlace(useLocation: Boolean, fineGranted: Boolean, coarseGranted: Boolean): Boolean = useLocation && (fineGranted || coarseGranted)
}
