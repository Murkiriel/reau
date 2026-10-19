package com.reau.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.reau.app.dash.DashFinder

/**
 * Finds and fills in the dash's Wi-Fi by itself. While REau is on screen and the dash's whole Wi-Fi name is not known
 * ([DashFinder]): with the Wi-Fi list allowed, a scan is asked of Android and every new list is read ([learn]) until
 * the dash's name is in it; without it, Android is asked once ([ask]). Nothing runs with REau off screen (Android gives
 * the list only to an app in use).
 */
internal class DashSearch(
    private val activity: ComponentActivity,
    private val step: () -> DashFinder.Step,
    private val ask: () -> Unit,
    private val learn: () -> Unit,
) : DefaultLifecycleObserver {
    private var listening = false

    private val results = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (step() == DashFinder.Step.SEARCH) learn() else stop()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        when (step()) {
            DashFinder.Step.SEARCH -> search()
            DashFinder.Step.ASK -> ask()
            DashFinder.Step.KNOWN, DashFinder.Step.CANNOT -> stop()
        }
    }

    override fun onPause(owner: LifecycleOwner) = stop()

    private fun search() {
        if (!listening) {
            ContextCompat.registerReceiver(
                activity, results, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            listening = true
        }
        // A fresh list, as Android allows (a few a minute); the system's own scans arrive here too.
        @Suppress("DEPRECATION") // the only way to ask for a scan; throttled by Android, which is fine here
        runCatching { activity.getSystemService(WifiManager::class.java)?.startScan() }
    }

    private fun stop() {
        if (!listening) return
        runCatching { activity.unregisterReceiver(results) }
        listening = false
    }
}
