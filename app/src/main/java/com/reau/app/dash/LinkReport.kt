package com.reau.app.dash

/**
 * What the phone's Wi-Fi link to the dash looked like at one moment (so a `dash-rx` watchdog can tell the Wi-Fi from
 * the dash). Every field is null when
 * Android did not say (no signal reading, no link speed, ...). No SSID or BSSID: the log goes to the rider's e-mail.
 *
 * @property viaDashNetwork the reading is of the network REau asked for the dash; false when it had to fall back to
 *   the phone's active Wi-Fi (which may be another network).
 */
data class LinkSample(
    val rssiDbm: Int? = null,
    val txMbps: Int? = null,
    val rxMbps: Int? = null,
    val mhz: Int? = null,
    val powerSave: Boolean? = null,
    val screenOn: Boolean? = null,
    val viaDashNetwork: Boolean = true,
)

/** The text of the link in the ride log: one moment, and a short trend. All pure, so the JVM tests cover it. */
object LinkReport {
    /** How often a sample is taken while streaming, in 1 Hz heartbeat ticks. */
    const val SAMPLE_EVERY_TICKS = 5

    /** How many samples the history keeps: 12 × 5 s = the last 60 s. */
    const val HISTORY = 12

    /** How often the link is logged on its own while streaming, in 1 Hz heartbeat ticks (a baseline of the ride). */
    const val LOG_EVERY_TICKS = 60

    /** "2.4 GHz", "5 GHz" or "6 GHz" for the channel's frequency; "?" when unknown. */
    fun band(mhz: Int?): String = when {
        mhz == null || mhz <= 0 -> "?"
        mhz < 3_000 -> "2.4 GHz"
        mhz < 5_925 -> "5 GHz"
        else -> "6 GHz"
    }

    /** One moment: `rssi=-62 dBm tx=72 rx=72 Mbps 5 GHz powersave=off screen=on`, with `?` for what is unknown. */
    fun describe(s: LinkSample?): String {
        if (s == null) return "no reading"
        val rssi = s.rssiDbm?.let { "$it dBm" } ?: "?"
        val tx = s.txMbps?.toString() ?: "?"
        val rx = s.rxMbps?.toString() ?: "?"
        val where = if (s.viaDashNetwork) "" else " (NOT the dash network: the phone's active Wi-Fi)"
        return "rssi=$rssi tx=$tx rx=$rx Mbps ${band(s.mhz)} powersave=${flag(s.powerSave)} screen=${flag(s.screenOn, "on", "off")}$where"
    }

    /** The history's trend, oldest first: `last 60 s, every 5 s: rssi -58 -60 -70 | tx 72 72 24`. */
    fun trend(samples: List<LinkSample?>, everySeconds: Int = SAMPLE_EVERY_TICKS): String {
        if (samples.isEmpty()) return "no history"
        val rssi = samples.joinToString(" ") { it?.rssiDbm?.toString() ?: "?" }
        val tx = samples.joinToString(" ") { it?.txMbps?.toString() ?: "?" }
        return "last ${samples.size * everySeconds} s, every $everySeconds s: rssi $rssi | tx $tx"
    }

    /** What the log writes when a watchdog fires: the moment of the failure and the minute before it. */
    fun atFailure(now: LinkSample?, history: List<LinkSample?>): String = "link at failure: ${describe(now)} | ${trend(history)}"

    private fun flag(v: Boolean?, yes: String = "on", no: String = "off") = when (v) {
        null -> "?"
        true -> yes
        false -> no
    }
}

/** The last [capacity] samples, oldest first; thread-safe (the heartbeat writes, a watchdog reads on the same loop, a test elsewhere). */
class LinkHistory(private val capacity: Int = LinkReport.HISTORY) {
    private val items = ArrayDeque<LinkSample?>()

    @Synchronized fun add(sample: LinkSample?) {
        items.addLast(sample)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized fun snapshot(): List<LinkSample?> = items.toList()

    @Synchronized fun clear() = items.clear()
}
