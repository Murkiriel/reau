package com.reau.app.ui

import com.reau.app.aa.ServerResult
import com.reau.app.service.AaServerStarter
import com.reau.app.service.Projection
import com.reau.app.settings.AppLanguage
import com.reau.app.settings.REauSettings

/** What the main screen shows. [cardName] is the picture the card mode sends; null is the legibility card. */
data class MainUiState(
    val projection: Projection = Projection(),
    val androidAutoVersion: String? = null,
    val cardName: String? = null,
    /** The switch of Android Auto's server, how its last try went, and REau's accessibility service. */
    val server: ServerUi = ServerUi(),
    /** Battery saver and REau's battery-optimization exemption. */
    val battery: BatteryUi = BatteryUi(),
    /** When the projection started (SystemClock.elapsedRealtime), for the time on Desligar; null while stopped. */
    val startedAtMs: Long? = null,
    /** The dash's full Wi-Fi name as learned, for the Wi-Fi shortcut; empty while none is known. */
    val dashName: String = "",
    /** How many of the permissions page's rows are still missing, for its shortcut. */
    val permissionsMissing: Int = 0,
    /** REau's traffic goes into a VPN, which keeps it off the bike's Wi-Fi. */
    val vpnHoldsREau: Boolean = false,
    /** The other edition of REau is installed too ([com.reau.app.OtherEdition]): it may be holding Android Auto's server. */
    val otherEditionInstalled: Boolean = false,
    /** REau's own version, under Android Auto's at the foot. */
    val reauVersion: String = com.reau.app.BuildConfig.VERSION_NAME,
    /** The switch "Clima no painel", which the weather section of the main screen shows. */
    val weatherOn: Boolean = false,
) {
    /** Whether Android Auto is installed; when not, its buttons lead to the store instead of to its settings. */
    val androidAutoInstalled: Boolean get() = androidAutoVersion != null
}

/**
 * Battery saver and REau's exemption from battery optimization, as the main screen shows them: both matter, since
 * battery optimization can kill a long-running projection.
 */
data class BatteryUi(val saverOn: Boolean = false, val exempt: Boolean = true) {
    /** The cards to show, in order: the exemption first (the one the rider fixes once), then battery saver. */
    val notices: List<BatteryNotice> get() = listOfNotNull(BatteryNotice.NOT_EXEMPT.takeIf { !exempt }, BatteryNotice.SAVER_ON.takeIf { saverOn })
}

enum class BatteryNotice { NOT_EXEMPT, SAVER_ON }

/** The state with the network server's flag set (the icon of the main screen and the server screen read it). */
fun MainUiState.withNetwork(on: Boolean): MainUiState = copy(server = server.copy(networkOn = on))

/** What the main screen shows of the automatic switch of Android Auto's server. */
data class ServerUi(
    val auto: Boolean = false,
    val result: ServerResult = ServerResult.NONE,
    val accessibility: AaServerStarter.Accessibility = AaServerStarter.Accessibility.OFF,
    /** The network server (the browser's, "Ver no navegador") is up: the icon beside Android Auto's side blinks. */
    val networkOn: Boolean = false,
)

/** The main screen's actions beyond start/stop and the settings: the card mode, the shortcuts, the warnings. */
data class MainExtras(
    val onCard: () -> Unit = {},
    val onShowAndroidAuto: () -> Unit = {},
    /** "Tentar de novo" (switch the server on again), and Android's accessibility settings. */
    val onRetryServer: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
    /** Android's dialog to leave REau out of battery optimization, and battery saver's settings. */
    val onAskBatteryExemption: () -> Unit = {},
    val onOpenBatterySaver: () -> Unit = {},
    /** The permissions page and "O REau e os seus dados", from the main screen. */
    val onOpenPermissions: () -> Unit = {},
    val onOpenAboutData: () -> Unit = {},
    /** The "Wi-Fi da moto" shortcut opens the Wi-Fi's own screen. */
    val onOpenWifi: () -> Unit = {},
    /** The weather's own screen, from its shortcut. */
    val onOpenWeather: () -> Unit = {},
    /** The network server's screen, from its icon. */
    val onOpenServer: () -> Unit = {},
)

/** What the settings screen shows of the dash pairing. */
data class PairingUi(val ssid: String = "", val ssidPrefix: String = "RE_", val password: String = "")

/**
 * What the settings screen does: hands over a changed setting or pairing, forgets the dash, goes back, asks for a
 * picture for the card mode.
 */
@Suppress("LongParameterList") // one callback per thing the screen hands back, each with its default
class SettingsActions(
    val onSettings: (REauSettings) -> Unit,
    val onPairing: (PairingUi) -> Unit,
    val onForgetDash: () -> Unit,
    val onBack: () -> Unit,
    val onPickCardImage: () -> Unit = {},
    /** Share the logs (with the debug recordings when true). */
    val onShareLogs: (withRecordings: Boolean) -> Unit = {},
    /** Android's accessibility settings, to switch REau's service on. */
    val onOpenAccessibility: () -> Unit = {},
    /** The language of the app's texts, chosen on the settings (the screens are redrawn in it). */
    val onLanguage: (AppLanguage) -> Unit = {},
    /** "Ver o registro": the logs, inside the app. */
    val onViewLogs: () -> Unit = {},
    /**
     * Delete the logs (and the debug recordings when true), after a confirmation; [done] gets how many files and KB
     * went, for the Snackbar.
     */
    val onDeleteLogs: (withRecordings: Boolean, done: (files: Int, kb: Long) -> Unit) -> Unit = { _, _ -> },
    /** Ask Android for the location, only for the Wi-Fi list (the dash's name). */
    val onAskWifiList: () -> Unit = {},
)

/** Where the permissions page sends the rider. */
data class PermissionsActions(
    val onBack: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
    /** Ask for one row's permission ([com.reau.app.settings.AppPermissions.ask]). */
    val onAsk: (com.reau.app.settings.AppPermissions.Ask) -> Unit = {},
    /** "Pedir as que faltam": the runtime permissions still missing, in one dialog. */
    val onAskAll: (List<String>) -> Unit = {},
)
