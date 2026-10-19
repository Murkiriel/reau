package com.reau.app.data

import java.util.Locale

/**
 * What the phone is like right now, for the `device …` line ([line]). What could not be read is
 * null and is written as `?`. Read on the phone by [DeviceStateReader][com.reau.app.service.DeviceStateReader]; no
 * place, street or network name here (privacy).
 */
data class DeviceState(
    val batteryPct: Int?,
    val charging: Boolean?,
    val batteryTempC: Float?,
    val batteryMv: Int?,
    /** PowerManager's thermal status, 0 (none) to 6 (shutdown). */
    val thermal: Int?,
    /** PowerManager's thermal headroom: 1.0 is where the phone starts to throttle. */
    val headroom: Float?,
    val appUsedMb: Int,
    val appMaxMb: Int,
    val phoneAvailMb: Int?,
    val lowMemory: Boolean?,
    /** Whether the display itself is lit (what the battery statistics call screen on). */
    val screenOn: Boolean?,
    /** PowerManager's interactive state: stays true through a projection with the display off, so it is not the screen. */
    val interactive: Boolean?,
    val dozing: Boolean?,
    val powerSave: Boolean?,
    /** Whether REau is exempt from the battery optimization. */
    val batteryExempt: Boolean?,
    /** The bike's Wi-Fi while REau holds it, or null. */
    val bikeWifi: BikeWifi?,
    /** The phone's default network, the one Waze gets its data through. */
    val data: Data,
    val dataValidated: Boolean,
) {
    data class BikeWifi(val dbm: Int, val mhz: Int, val mbps: Int)

    enum class Data { NONE, WIFI, CELL, OTHER }

    fun line(): String = (powerParts() + phoneParts()).joinToString(" ", prefix = "device ")

    // A phone that gives no thermal headroom (some always answer `?`) leaves the field out.
    private fun powerParts() = listOfNotNull(
        "battery=" + (batteryPct?.let { "$it%" } ?: "?"),
        "charging=" + yesNo(charging),
        "temp=" + (batteryTempC?.let { String.format(Locale.US, "%.1fC", it) } ?: "?"),
        "volt=" + (batteryMv?.let { String.format(Locale.US, "%.3fV", it / MV_PER_V) } ?: "?"),
        "thermal=" + (thermal?.let { THERMAL_NAMES.getOrElse(it) { n -> "status$n" } } ?: "?"),
        headroom?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "headroom=%.2f", it) },
    )

    private fun phoneParts() = listOf(
        "mem=$appUsedMb/${appMaxMb}MB",
        "phone=" + (phoneAvailMb?.let { "${it}MB" } ?: "?"),
        "low=" + yesNo(lowMemory),
        "screen=" + onOff(screenOn),
        "interactive=" + yesNo(interactive),
        "doze=" + yesNo(dozing),
        "saver=" + onOff(powerSave),
        "exempt=" + yesNo(batteryExempt),
        "bike=" + (bikeWifi?.let { "${it.dbm}dBm/${it.mhz}MHz/${it.mbps}Mbps" } ?: "none"),
        "data=" + dataPart(),
    )

    /** The parts that write a line at once when they change, named as in the line. */
    fun relevantChangesFrom(other: DeviceState): List<String> = buildList {
        if (screenOn != other.screenOn) add("screen")
        if (dozing != other.dozing) add("doze")
        if (charging != other.charging) add("charging")
        if (thermal != other.thermal) add("thermal")
        if (powerSave != other.powerSave) add("saver")
    }

    private fun dataPart(): String {
        if (data == Data.NONE) return "none"
        return data.name.lowercase(Locale.US) + if (dataValidated) "/ok" else "/not-validated"
    }

    private companion object {
        const val MV_PER_V = 1000f
        val THERMAL_NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

        fun yesNo(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "?" }

        fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "?" }
    }
}

/**
 * When a [DeviceState] is written. The first look at once (`[start]`), then every
 * [periodMs] (`[30 s]`), and at once when a relevant part changed from the look before (`[change: …]`); a line
 * written for a change starts the period again.
 */
class DeviceStateLog(private val periodMs: Long, private val write: (String) -> Unit) {
    private var last: DeviceState? = null
    private var lastWrittenMs = 0L

    fun offer(nowMs: Long, state: DeviceState) {
        val before = last
        last = state
        val why = when {
            before == null -> "start"
            state.relevantChangesFrom(before).isNotEmpty() -> "change: " + state.relevantChangesFrom(before).joinToString(", ")
            nowMs - lastWrittenMs >= periodMs -> "${periodMs / MS_PER_S} s"
            else -> return
        }
        lastWrittenMs = nowMs
        write("${state.line()} [$why]")
    }

    private companion object {
        const val MS_PER_S = 1000
    }
}
