package com.reau.app.dash

/**
 * What the dash's route card says while there is no route in the navigation app (no guidance from Android Auto).
 *
 * Found in the ride logs: the dash passes its joystick on in its map mode (0x13 right, 0x14 left, 0x15 down, 0x12 up,
 * 0x18 middle) only while the route card carries a maneuver. With the bubble hidden by "no maneuver" (0xFF) no side
 * press arrives at all, and the middle puts the dash in its media mode for good. Every session with map-mode codes
 * had a route, or sent the captured card with its arrival pin (0x3C), as better-dash does.
 */
enum class DashIdleCard {
    /** The captured card's arrival pin, with the clock as the ETA: the joystick works in the map mode (the default). */
    PIN,

    /** The bubble hidden (0xFF): the dash does not pass the joystick on in its map mode. */
    HIDDEN,
    // FLAGS (only the projection flags, another project's keep-alive) was removed, never tried on the bike; one stored
    // reads as PIN.
}
