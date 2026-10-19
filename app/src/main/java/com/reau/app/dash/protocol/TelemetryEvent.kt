package com.reau.app.dash.protocol

/**
 * One incoming TLV from the dash, captured for the Telemetry Lab (Settings → debug builds only)
 * — a live decode/correlate tool for whenever an unmapped field needs field-mapping against a
 * known real-world value (odometer, fuel, etc.), instead of only pulling a ride log after the
 * fact. Modelled on another project's `TelemetryPacket`/`TelemetryBus`, adapted onto
 * [com.reau.app.dash.DashSession]'s own
 * TLV dispatch instead of a separate global bus singleton.
 */
data class TelemetryEvent(
    val atMs: Long,
    val type: Int,
    val sub: Int,
    val raw: ByteArray,
    /** AES-decrypted payload, only for type 0x0F (vehicle-secure telemetry) — null otherwise, or
     *  when decryption failed (no session key yet / bad IV). */
    val decoded: ByteArray? = null,
) {
    val label: String get() = "type=0x%02X sub=0x%02X".format(type, sub)
    val hex: String get() = raw.joinToString(" ") { "%02X".format(it) }
    val decodedHex: String? get() = decoded?.joinToString(" ") { "%02X".format(it) }

    // ByteArray fields intentionally left out of equals/hashCode (content comparison isn't
    // meaningful here — every real event already differs by atMs, and the UI list below keys
    // items by index, not by TelemetryEvent equality).
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
