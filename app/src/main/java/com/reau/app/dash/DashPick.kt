package com.reau.app.dash

/**
 * Which dash to take when the Wi-Fi scan shows more than one whose name begins with the factory prefix (`RE_`): several
 * Royal Enfield bikes at a meet, a parking lot, a workshop. The first in the scan's list used to be taken, and kept (the
 * name is remembered until the rider taps "Esquecer"), so the rider could end up paired to the bike next to theirs (audit of
 * 20/10/2026). The one that is seen recently and strongest is the one under the rider's own seat. Pure.
 */
internal object DashPick {
    /** The name taken and how many different dashes the scan showed, with their signal in dBm, strongest first. */
    data class Choice(val name: String, val signals: List<Pair<String, Int>>) {
        /** For the ride log: the dash names masked ([DashSsid.maskSsid]); with more than one in sight, all of them and why. */
        fun describe(): String {
            val taken = "'${DashSsid.maskSsid(name)}'"
            if (signals.size < 2) return taken
            val all = signals.joinToString(", ") { (ssid, dbm) -> "'${DashSsid.maskSsid(ssid)}' $dbm dBm" }
            return "$taken, the strongest and most recent of ${signals.size} dashes in sight: $all"
        }
    }

    /**
     * The dash of [seen] whose name begins with [prefix] to take, or null with none: sightings that are recent first
     * ([DashJoin.SIGHTING_FRESH_MS], as the scan list keeps a bike that left for minutes), and among those the strongest signal.
     * A dash seen by several access points counts once, by its best signal; a tie keeps the scan's own order. [nowUs]: microseconds
     * since boot, as the scan's own timestamps.
     */
    fun strongest(seen: List<ScanSeen>, prefix: String, nowUs: Long): Choice? {
        val byName = LinkedHashMap<String, ScanSeen>()
        for (candidate in seen) {
            if (!candidate.ssid.startsWith(prefix)) continue
            val best = byName[candidate.ssid]
            if (best == null || candidate.level > best.level) byName[candidate.ssid] = candidate
        }
        if (byName.isEmpty()) return null
        val ranked = byName.values.sortedWith(
            compareByDescending<ScanSeen> { DashJoin.seenRecently(listOf(it), it.ssid, false, nowUs) }.thenByDescending { it.level },
        )
        return Choice(ranked.first().ssid, ranked.map { it.ssid to it.level })
    }
}
