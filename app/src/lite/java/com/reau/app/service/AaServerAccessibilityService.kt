package com.reau.app.service

/**
 * The Lite edition has no accessibility service (the full edition's lives in src/completa, and this edition's manifest
 * does not declare one). This is what the shared code still names: a class for the component lookups and the calls that
 * do nothing here.
 */
class AaServerAccessibilityService {
    companion object {
        /** The switch "Atender ligação" widens what the full edition's service hears; here there is nothing to widen. */
        @Suppress("UNUSED_PARAMETER")
        fun followCallsSwitch(on: Boolean) = Unit
    }
}
