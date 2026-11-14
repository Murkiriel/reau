package com.reau.app.dash

import com.reau.app.service.ForegroundHandover

// What the Wi-Fi list says about the dash, as text for the ride log only: no decision reads any of it.

/** One network of the last scan, as far as the log needs it ([timestampUs]: microseconds since boot, as ScanResult has it). */
internal data class ScanSeen(val ssid: String, val level: Int, val frequency: Int, val timestampUs: Long)

/**
 * What the last scan says about the dash, for the ride log only (no decision reads it): how many networks, and for
 * the dash its signal, band and how old that sighting is. A request the system's dialog keeps "searching" for,
 * while `seenInScan` is true, may be living on a cached scan that is minutes old; this line tells. [nowUs] in the
 * same microseconds since boot.
 */
internal fun scanEvidence(
    seen: List<ScanSeen>?,
    pendingSsid: String,
    pendingPrefix: Boolean,
    nowUs: Long,
    emptyState: () -> String = { "" },
): String {
    if (seen == null) return "scan unreadable" + emptyState()
    if (seen.isEmpty()) return "scan empty" + emptyState()
    val dash = seen.filter { DashSsid.matchesPendingSsid(it.ssid, pendingSsid, pendingPrefix) }
    // The newest result of the whole list says when Android last scanned, which a list without the dash does not otherwise show.
    val newest = newestAge(newestStamp(seen), nowUs)?.let { " (newest result $it s old)" } ?: ""
    if (dash.isEmpty()) return "scan of ${seen.size} networks, the dash not among them$newest"
    return dash.joinToString(prefix = "scan of ${seen.size} networks, the dash: ", separator = "; ", postfix = newest) {
        val age = ((nowUs - it.timestampUs) / MICROS_PER_SECOND).coerceAtLeast(0)
        "'${DashSsid.maskSsid(it.ssid)}' ${it.level} dBm ${it.frequency} MHz seen $age s ago"
    }
}

private const val MICROS_PER_SECOND = 1_000_000L

/**
 * What explains an empty (or unreadable) Wi-Fi list, for the ride log only: the foreground service carries the location type
 * ([locationType]; without it Android gives an app behind another one an empty list), the precise location permission is given, Android's own
 * Location switch is on (null: could not be read), and how the process stands ([importance], 100 foreground to 400 cached). No network names.
 * Since 08/11/2026 also what tells "no access" from "no network around": the last reading that had networks ([lastNonEmpty], from [ScanTrail])
 * and whether the phone is joined to a Wi-Fi ([wifiJoined], from [joinedWifiWords]); the reader's test of the empty list needs both.
 */
internal fun emptyScanState(
    locationType: Boolean,
    permission: Boolean,
    systemLocationOn: Boolean?,
    importance: Int,
    more: EmptyScanFacts? = null,
): String =
    " [location type=${onOff(locationType)}, permission=${if (permission) "given" else "missing"}, " +
        "system location=${systemLocationOn?.let(::onOff) ?: "unknown"}, " +
        "process importance ${ForegroundHandover.importance(importance)}" +
        (more?.lastNonEmpty?.let { ", $it" } ?: "") + (more?.wifiJoined?.let { ", wifi joined=$it" } ?: "") + "]"

/** What else the empty list's line says: the last reading with networks ([ScanTrail.describe]) and whether the phone is joined to a Wi-Fi ([joinedWifiWords]). */
internal data class EmptyScanFacts(val lastNonEmpty: String?, val wifiJoined: String?)

private fun onOff(on: Boolean) = if (on) "on" else "off"
