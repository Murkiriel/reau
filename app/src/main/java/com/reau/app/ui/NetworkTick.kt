package com.reau.app.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * A number that goes up each time a network of the phone comes, goes or changes its addresses (Wi-Fi joined, hotspot turned on, cable
 * plugged), so a screen that shows the phone's addresses asks for them again. It is a hint: the screen reads the addresses itself.
 */
@Composable
internal fun rememberNetworkTick(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bump()
            override fun onLost(network: Network) = bump()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = bump()
            private fun bump() {
                tick += 1
            }
        }
        runCatching { manager?.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return tick
}
