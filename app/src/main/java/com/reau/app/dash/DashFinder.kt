package com.reau.app.dash

/**
 * What REau does on screen about the dash's whole Wi-Fi name, so the app finds and fills it in by itself with as few
 * steps as possible: nothing once it is known; search the Wi-Fi list by itself while it may read it; else ask Android
 * once for the location, only to read that list; else nothing (the Wi-Fi screen offers the question again, and a
 * field).
 */
object DashFinder {
    enum class Step { KNOWN, SEARCH, ASK, CANNOT }

    /** [termsAccepted] false: nothing is asked or searched yet, the terms of use come first (the first thing the rider meets). */
    fun step(nameKnown: Boolean, scanAllowed: Boolean, askedAlready: Boolean, termsAccepted: Boolean = true): Step = when {
        !termsAccepted -> Step.CANNOT
        nameKnown -> Step.KNOWN
        scanAllowed -> Step.SEARCH
        !askedAlready -> Step.ASK
        else -> Step.CANNOT
    }
}
