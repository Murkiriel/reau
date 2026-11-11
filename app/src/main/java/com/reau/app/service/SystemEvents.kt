package com.reau.app.service

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.reau.app.data.RideDiagnostics

/**
 * `[system] …` lines for what the phone does around REau, for as long as the app lives (started by
 * REauApp, the log being the process's): the screen, doze, the battery saver, the charger, a low battery,
 * the default (data) network, and the app going to the background and back. The bike's network and Android Auto's
 * audio focus are written where REau handles them ([bikeNetwork], AapControl). No new permission.
 */
@Suppress("TooManyFunctions") // one small function per broadcast and per line; splitting them would only scatter the same thing
object SystemEvents {
    private const val TAG = "system"
    @Volatile private var started = false

    @Suppress("DEPRECATION") // the Wi-Fi's broadcasts are deprecated and still sent: the only way to hear the supplicant without the location permission
    private val ACTIONS = listOf(
        Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF,
        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
        Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
        Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY,
        // The unlock: Android Auto was seen to close its link together with the screen going off and on (20/10/2026).
        Intent.ACTION_USER_PRESENT,
        // The USB data link (a computer or a car: connected, configured, with which functions): Android Auto was seen to close its
        // link at each plug and unplug of the cable (26/10/2026), which the charger's own broadcast does not tell apart from a wall charger.
        USB_STATE,
        // The phone's own word on its Wi-Fi (no permission beyond the ones held): the radio, the supplicant, the network state.
        WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.SUPPLICANT_STATE_CHANGED_ACTION, WifiManager.NETWORK_STATE_CHANGED_ACTION,
    )

    /** The system's broadcast of the phone's USB data link (UsbManager.ACTION_USB_STATE, hidden from the SDK). */
    internal const val USB_STATE = "android.hardware.usb.action.USB_STATE"

    /** Starts listening, once per process. */
    @Synchronized
    @Suppress("DEPRECATION") // see ACTIONS
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        val toggles = Toggles(runCatching { power.isDeviceIdleMode }.getOrNull(), runCatching { power.isPowerSaveMode }.getOrNull())
        val battery = LowBattery()
        val supplicant = WifiEvents.Supplicant()
        val networkState = WifiEvents.NetworkState()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dozing = runCatching { power.isDeviceIdleMode }.getOrNull()
                val saver = runCatching { power.isPowerSaveMode }.getOrNull()
                com.reau.app.data.PhoneNow.heard(intent.action.orEmpty(), dozing)
                when (val action = intent.action.orEmpty()) {
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> log(toggles.doze(dozing))
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> log(toggles.saver(saver))
                    Intent.ACTION_BATTERY_LOW -> battery.low(batteryLevel(app), saverLevel(app))?.let(::log)
                    Intent.ACTION_BATTERY_OKAY -> log(battery.okay())
                    WifiManager.WIFI_STATE_CHANGED_ACTION ->
                        log(WifiEvents.radioLine(com.reau.app.dash.PhoneSide.radioName(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1))))
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> supplicantLine(intent, supplicant)?.let(::log)
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> networkStateLine(intent, networkState)?.let(::log)
                    USB_STATE -> log(usbLine(intent.extras?.keySet().orEmpty().associateWith { intent.extras?.getBoolean(it) == true }))
                    else -> {
                        if (action == Intent.ACTION_POWER_CONNECTED) battery.charger()
                        // The plug said with the line: what the charger is (USB, a wall charger, wireless), as the sticky battery broadcast says.
                        describe(action, dozing, saver)?.let { log(it + plugSuffix(action, plugged(app))) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { log("listening to the phone's broadcasts failed: $it") }
        watchTheDataNetwork(app)
        watchTheWifiNetworks(app)
        (app as? Application)?.registerActivityLifecycleCallbacks(Screens(AppVisibility(standing = ::standing, say = ::log)))
    }

    /** What Android says is plugged in (BatteryManager.EXTRA_PLUGGED), from the sticky battery broadcast; null when it cannot be read. */
    private fun plugged(app: Context): Int? = runCatching {
        ContextCompat.registerReceiver(app, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }
    }.getOrNull()

    /** The suffix of a charger line: ` (usb)`, ` (ac)`, ` (wireless)` or ` (dock)`, nothing for other lines or an unknown plug. */
    internal fun plugSuffix(action: String, plugged: Int?): String {
        if (action != Intent.ACTION_POWER_CONNECTED && action != Intent.ACTION_POWER_DISCONNECTED) return ""
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> " (usb)"
            BatteryManager.BATTERY_PLUGGED_AC -> " (ac)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " (wireless)"
            BatteryManager.BATTERY_PLUGGED_DOCK -> " (dock)"
            else -> ""
        }
    }

    /**
     * The line for the USB data link: `usb connected, configured, adb`, from the extras of the broadcast (each a flag, true when on):
     * `connected`, `configured` and the functions (`adb`, `mtp`, `ptp`...). `usb disconnected` when the link is not there.
     */
    internal fun usbLine(flags: Map<String, Boolean>): String {
        val on = flags.filterValues { it }.keys
        val state = when {
            "connected" !in on -> return "usb disconnected"
            "configured" in on -> "configured"
            else -> "not configured"
        }
        val functions = on.filter { it != "connected" && it != "configured" }.sorted()
        return "usb connected, $state" + if (functions.isEmpty()) "" else ", " + functions.joinToString("+")
    }

    /** The level Android last announced (the sticky battery broadcast, the same the low-battery mark is checked on). */
    private fun batteryLevel(app: Context): Int? = runCatching {
        val sticky = ContextCompat.registerReceiver(app, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) null else level * 100 / scale
    }.getOrNull()

    /** The battery saver's automatic level (0 = not set); Android may refuse the read, then unknown. */
    private fun saverLevel(app: Context): Int? =
        runCatching { Settings.Global.getInt(app.contentResolver, "low_power_trigger_level", 0) }.getOrNull()

    private fun watchTheDataNetwork(app: Context) {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log(dataNetwork(kindOf(connectivity.getNetworkCapabilities(network))))
                }

                override fun onLost(network: Network) = log(dataNetwork(null))
            })
        }.onFailure { log("watching the data network failed: $it") }
    }

    /**
     * The Wi-Fi networks the phone has up, as they come and go (any Wi-Fi network, the dash's included, with or without the internet). The
     * data network callback above hears only the default one. A callback the system refuses (too many) costs one line and nothing else.
     */
    @Suppress("TooGenericExceptionCaught") // registerNetworkCallback throws a hidden RuntimeException when the app holds too many callbacks
    private fun watchTheWifiNetworks(app: Context) {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val networks = WifiEvents.Networks()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = log(networks.available(network.hashCode()))

                override fun onLosing(network: Network, maxMsToLive: Int) = log(networks.losing(network.hashCode(), maxMsToLive))

                override fun onLost(network: Network) = log(networks.lost(network.hashCode()))

                override fun onUnavailable() = log(WifiEvents.Networks.UNAVAILABLE)
            })
        } catch (e: RuntimeException) {
            log("watching the Wi-Fi networks failed: ${e.javaClass.simpleName}")
        }
    }

    /** The supplicant's new state and its error, from the broadcast; the SSID and BSSID that come with it are not read. */
    @Suppress("DEPRECATION") // the broadcast is the only way to hear the supplicant's states without the location permission
    private fun supplicantLine(intent: Intent, supplicant: WifiEvents.Supplicant): String? {
        val state = androidx.core.content.IntentCompat.getParcelableExtra(
            intent, WifiManager.EXTRA_NEW_STATE, android.net.wifi.SupplicantState::class.java,
        )
        return supplicant.line(state?.name, intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0))
    }

    /** The network state's new detailed state, from the broadcast; the network info's text (it carries the name) is never written. */
    @Suppress("DEPRECATION") // NetworkInfo is deprecated, and still what this broadcast carries
    private fun networkStateLine(intent: Intent, state: WifiEvents.NetworkState): String? {
        val info = androidx.core.content.IntentCompat.getParcelableExtra(intent, WifiManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
        return state.line(info?.detailedState?.name)
    }

    private fun kindOf(caps: NetworkCapabilities?): String = when {
        caps == null -> "?"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    /** The line for a broadcast, null for one not listened for; [dozing] and [saver] as read when it came. */
    internal fun describe(action: String, dozing: Boolean?, saver: Boolean?): String? = when (action) {
        Intent.ACTION_SCREEN_ON -> "screen on"
        Intent.ACTION_SCREEN_OFF -> "screen off"
        Intent.ACTION_USER_PRESENT -> "screen unlocked"
        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze " + onOff(dozing)
        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "saver " + onOff(saver)
        Intent.ACTION_POWER_CONNECTED -> "charger in"
        Intent.ACTION_POWER_DISCONNECTED -> "charger out"
        Intent.ACTION_BATTERY_LOW -> "battery low"
        Intent.ACTION_BATTERY_OKAY -> "battery okay"
        else -> null
    }

    /** The default network became [kind] (wifi, cell…), or was lost (null). */
    internal fun dataNetwork(kind: String?): String = if (kind == null) "data network lost" else "data network available: $kind"

    /** The bike's network, held by REau, came or went. */
    fun bikeNetwork(available: Boolean): String = "bike network " + if (available) "available" else "lost"

    /** Writes [line] under the `system` tag. */
    fun log(line: String) = RideDiagnostics.log(TAG, line)

    private fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "?" }

    /**
     * Doze and the saver as last heard. Android may hand a broadcast over late (a frozen process gets it when it thaws):
     * one that finds the state REau last heard says so, instead of passing for a change that did not happen.
     */
    internal class Toggles(private var lastDoze: Boolean? = null, private var lastSaver: Boolean? = null) {
        @Synchronized
        fun doze(now: Boolean?): String = line("doze", now, lastDoze).also { lastDoze = now }

        @Synchronized
        fun saver(now: Boolean?): String = line("saver", now, lastSaver).also { lastSaver = now }

        private fun line(name: String, now: Boolean?, last: Boolean?): String =
            "$name ${onOff(now)}" + if (now != null && now == last) " (came late: it changed and came back before REau heard)" else ""
    }

    /**
     * The low battery as last heard (once dozens of lines in one ride at 51-60%). Android's low-battery mark follows the
     * battery saver's automatic level when the rider set one, and the phone repeats the broadcast at each 1% it drops:
     * one line per stretch below the mark, with the level and the mark, and again only after the charger or "okay".
     */
    internal class LowBattery {
        private var said = false

        @Synchronized
        fun low(level: Int?, saverAt: Int?): String? {
            if (said) return null
            said = true
            val mark = when {
                saverAt == null -> ""
                saverAt > 0 -> " (Android's low mark follows the battery saver's automatic level: $saverAt%; said once until it charges or comes back)"
                else -> " (said once until it charges or comes back)"
            }
            return "battery low at ${level ?: "?"}%$mark"
        }

        @Synchronized
        fun okay(): String {
            said = false
            return "battery okay"
        }

        @Synchronized
        fun charger() {
            said = false
        }
    }

    private class Screens(private val visibility: AppVisibility) : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = visibility.started()
        override fun onActivityStopped(activity: Activity) = visibility.stopped()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/**
 * The app is in the foreground while at least one of its screens is started; [say] is told when the
 * first starts (`app foreground`) and when the last stops (`app background`).
 */
/**
 * How the process stands as the app goes to the background; the network was once seen blocked a fraction
 * of a second after an `app background` whenever the projection had lost its foreground.
 */
internal fun standing(): String {
    val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
    return ForegroundHandover.standing(
        importance = me.importance,
        projectionForeground = ProjectionService.inForeground,
        projectionOn = ProjectionService.projection.value.phase != Phase.STOPPED,
    )
}

class AppVisibility(private val standing: (() -> String)? = null, private val say: (String) -> Unit) {
    private var shown = 0

    @Synchronized
    fun started() {
        if (shown++ == 0) say("app foreground")
    }

    @Synchronized
    fun stopped() {
        if (shown == 0) return
        if (--shown == 0) say(standing?.let { "app background — ${it()}" } ?: "app background")
    }
}
