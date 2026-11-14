package com.reau.app.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.reau.app.dash.DashSsid.maskSsid
import com.reau.app.dash.DashSsid.matchesPendingSsid
import com.reau.app.dash.DashSsid.scanVerdict
import com.reau.app.service.ProjectionService

/** The scan lookups of every manager in the process: one at a time, and not again within a second (the managers of the projection and of the screen). */
private val dashSearches = RecentSearch<DashFound>()

/** What a scan lookup found: the name and whether the dash was seen recently (the scan list keeps a bike that left for minutes). */
private data class DashFound(val name: String?, val fresh: Boolean)

/**
 * What [DashWifiManager] reads from Android's list of Wi-Fi networks (the last scan). It keeps nothing of the request: the name asked
 * for and the network in use are given at each call, read by the manager at that moment. One per manager (its [ScanLookLog] is the
 * manager's own), while [dashSearches] is one for the whole process.
 *
 * The list is read from the system in four places on purpose: [search] and [read] note the reading in [ScanHistory.trail], [verdict]
 * and [newestSightingUs] do not.
 */
internal class DashScan(
    private val context: Context,
    private val wifi: WifiManager,
    private val cm: ConnectivityManager,
) {
    private companion object {
        /** The manager's own tag: these lines were written from it, and are read under that name. */
        const val TAG = "DashWifiManager"
        const val MICROS_PER_MILLI = 1_000L
    }

    // The looks of the watches at the Wi-Fi list that went to the log (ScanLookLog).
    private val scanLooks = ScanLookLog()

    /** The dash's name in the last scan ([DashWifiManager.findDashSsid]): one search at a time in the process, and a recent sighting is told. */
    fun find(prefix: String): String? =
        dashSearches.get(prefix, after = { if (it.fresh) DashSighting.sighted() }) { search(prefix) }.name

    @SuppressLint("MissingPermission")
    // ScanResult.SSID is deprecated: the WifiSsid replacing it needs Android 13, and this app starts at Android 10 (minSdk 29)
    @Suppress("TooGenericExceptionCaught", "DEPRECATION") // a failed scan lookup is logged and means no SSID
    private fun search(prefix: String): DashFound = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val seen = wifi.scanResults.mapNotNull { r ->
            r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { ScanSeen(it, r.level, r.frequency, r.timestamp) }
        }
        // With several dashes in sight (a meet, a parking lot) the one with the best recent signal is taken, not the first listed.
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        ScanHistory.trail.note(seen.size, SystemClock.elapsedRealtime(), newestStamp(seen))
        val choice = DashPick.strongest(seen, prefix, nowUs)
        val said = choice?.describe() ?: "not found"
        Log.i(TAG, "Scan lookup for '${maskSsid(prefix)}*' → $said")
        // Happens BEFORE any connect attempt is logged -- if the dash is never found here,
        // nothing else explains why connect() never even started.
        com.reau.app.data.RideDiagnostics.log("wifi", "scan lookup for '${maskSsid(prefix)}*' → $said")
        // Told by find, outside the search's lock, and only for a dash seen recently: an old entry of the scan list is no news.
        DashFound(choice?.name, fresh = choice != null && DashJoin.seenRecently(seen, choice.name, false, nowUs))
    } catch (e: Exception) {
        Log.w(TAG, "Scan lookup failed: ${e.message}")
        com.reau.app.data.RideDiagnostics.warn("wifi", "scan lookup failed: ${e.javaClass.simpleName}: ${e.message}")
        DashFound(null, fresh = false)
    }

    /** Whether the last scan has [ssid] (or, with [prefix], a network starting with it) seen recently ([DashWifiManager.seenInLastScan]). */
    @SuppressLint("MissingPermission")
    fun seenRecently(ssid: String, prefix: Boolean): Boolean {
        val seen = read()
        val nowUs = SystemClock.elapsedRealtime() * MICROS_PER_MILLI
        // Every look of every watch passes here: the log says when the list moved, so the dash's age at REau's look is on record (ScanLookLog).
        scanLooks.line(seen, ssid, prefix, nowUs)?.let { com.reau.app.data.RideDiagnostics.log("wifi", it) }
        return DashJoin.seenRecently(seen, ssid, prefix, nowUs)
    }

    /** Log only: what the last Wi-Fi scan says of the dash, and how old that sighting is. [network] is the one in use, if any. */
    @SuppressLint("MissingPermission")
    fun evidence(ssid: String, prefix: Boolean, network: Network?): String =
        scanEvidence(read(), ssid, prefix, SystemClock.elapsedRealtime() * MICROS_PER_MILLI) { emptyListState(network) }

    /** Log only, read when the list came back empty: the state that explains it (see [emptyScanState]). */
    private fun emptyListState(network: Network?): String {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return emptyScanState(
            locationType = ProjectionService.acceptedTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
            permission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            systemLocationOn = locationManager?.isLocationEnabled,
            importance = me.importance,
            more = EmptyScanFacts(
                lastNonEmpty = ScanHistory.trail.describe(SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime() * MICROS_PER_MILLI),
                wifiJoined = joinedWifi(network),
            ),
        )
    }

    /** Log only: whether the phone is joined to a Wi-Fi, and whether the dash's own network is the one ([joinedWifiWords]). */
    @Suppress("DEPRECATION") // allNetworks: the callbacks that replace it do not answer a one-off question
    private fun joinedWifi(network: Network?): String? = runCatching {
        val wifiNetworks = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        joinedWifiWords(wifiNetworks.size, network?.let { it in wifiNetworks } == true)
    }.getOrNull()

    /** Does [ssid] show up in the latest scan? Same scan API [find] already
     * uses — reused here only to disambiguate the final give-up message in the manager's
     * `onUnavailable()`. Null (not false) if the scan itself couldn't be read (e.g. no location
     * permission) — a caller must NOT treat "couldn't check" the same as "confirmed absent". */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // ScanResult.SSID, as in search
    fun verdict(ssid: String, prefix: Boolean): Boolean? = scanVerdict(
        runCatching { wifi.scanResults.mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } } }.getOrNull(),
        ssid, prefix,
    )

    /** The last scan as [ScanSeen]s; null when it cannot be read. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // ScanResult.SSID, as in search
    fun read(): List<ScanSeen>? = runCatching {
        wifi.scanResults.mapNotNull { r ->
            r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { ScanSeen(it, r.level, r.frequency, r.timestamp) }
        }
    }.getOrNull()?.also { ScanHistory.trail.note(it.size, SystemClock.elapsedRealtime(), newestStamp(it)) }

    /** The newest stamp of the dash in the last scan; null for none, an empty scan or one that cannot be read. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // ScanResult.SSID, as in search
    fun newestSightingUs(ssid: String, prefix: Boolean): Long? = runCatching {
        wifi.scanResults.filter { r ->
            r.SSID?.trim('"')?.let { it.isNotBlank() && matchesPendingSsid(it, ssid, prefix) } == true
        }.maxOfOrNull { it.timestamp }
    }.getOrNull()
}
