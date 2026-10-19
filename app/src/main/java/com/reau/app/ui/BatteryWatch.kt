package com.reau.app.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.reau.app.data.RideDiagnostics

/**
 * Battery saver and REau's exemption from battery optimization, for the main screen's cards: read at each return to the
 * screen, and again whenever battery saver is switched (the system's notice, while the screen is shown).
 */
object BatteryWatch {
    fun read(context: Context): BatteryUi {
        val power = context.getSystemService(PowerManager::class.java) ?: return BatteryUi()
        return BatteryUi(
            saverOn = runCatching { power.isPowerSaveMode }.getOrDefault(false),
            exempt = runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true),
        )
    }

    /** Calls [onChange] with the new state each time battery saver is switched, while the composable is shown. */
    @Composable
    fun Follow(context: Context, onChange: (BatteryUi) -> Unit) {
        DisposableEffect(context) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = onChange(read(context))
            }
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Android's own dialog to leave REau out of battery optimization. A projection app that must keep the dash's video
     * going with the screen off is the use this exemption is for; REau is not on the Play Store.
     */
    @SuppressLint("BatteryLife")
    fun askExemption(context: Context) {
        RideDiagnostics.log("ui", "the rider asked to leave REau out of battery optimization")
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        if (runCatching { context.startActivity(ask) }.isFailure) {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Battery saver's settings, to switch it off. */
    fun openSaver(context: Context) {
        RideDiagnostics.log("ui", "the rider opened battery saver's settings")
        if (runCatching { context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }.isFailure) {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}
