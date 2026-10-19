package com.reau.app.dash

/**
 * A socket error for the ride log, with an EPERM said for what it is. Android answers EPERM when it does not
 * let the app use the network: in the background without a foreground service (worse with the battery saver on), or
 * on a network the app did not ask for and Android has set aside.
 */
object NetworkBlock {
    fun describe(e: Exception): String {
        val line = "${e.javaClass.simpleName}: ${e.message}"
        return if (e.message?.contains("EPERM") == true) "$line — network blocked by Android" else line
    }

    /**
     * Whether REau's own traffic goes into a VPN: Android gives each app its own default network, a VPN's while the VPN
     * covers it. A VPN that does not let its apps bypass it refuses them the bike's Wi-Fi, with EPERM; the way out is
     * to leave REau out of the VPN (split tunneling).
     */
    fun vpnHolds(context: android.content.Context): Boolean = runCatching {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
}
