package com.reau.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.reau.app.phone.PhoneCalls
import com.reau.app.aa.AndroidAuto
import com.reau.app.aa.ServerResult
import com.reau.app.card.CardName
import com.reau.app.dash.DashName
import com.reau.app.dash.DashPairing
import com.reau.app.dash.DashWifiManager
import com.reau.app.dash.DashWifiSuggestions
import com.reau.app.data.DetailedLog
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.AaServerStarter
import com.reau.app.service.LiveTuning
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ProjectionService
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import com.reau.app.settings.StartAsks
import com.reau.app.ui.AaScreenMask
import com.reau.app.ui.AndroidAutoScreen
import com.reau.app.ui.MainExtras
import com.reau.app.ui.MainScreen
import com.reau.app.ui.MainUiState
import com.reau.app.ui.PairingUi
import com.reau.app.ui.ServerUi
import com.reau.app.ui.withNetwork
import com.reau.app.ui.SettingsActions
import com.reau.app.ui.SettingsScreen
import com.reau.app.util.DiagnosticsExport
import kotlin.concurrent.thread

/**
 * The app's one activity: the main screen and the settings screen. The projection itself lives in
 * [ProjectionService]; this only switches it on and off and shows where it stands.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
class MainActivity : ComponentActivity() {
    private var androidAutoVersion by mutableStateOf<String?>(null)

    // REau's accessibility service, read again at each return to the screen.
    private var accessibility by mutableStateOf(AaServerStarter.Accessibility.OFF)
    private var permissionRows by mutableStateOf(emptyList<com.reau.app.settings.AppPermissions.Row>())
    // The permissions page asks for what is missing; the rows are read again after.
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRows = PermissionRows.read(this, accessibility)
    }
    internal val askRuntime: (List<String>) -> Unit = { askPermissions.launch(it.toTypedArray()) }

    // Battery saver and the exemption, read again at each return to the screen and when battery saver flips.
    private var battery by mutableStateOf(com.reau.app.ui.BatteryUi())

    // Settings changed by picking a card picture, for the screen to read again.
    private var settingsVersion by mutableIntStateOf(0)

    // Which start waits for Android's questions: the projection or the card.
    private var startingCard = false

    // Whether REau may read the Wi-Fi list now (to learn the dash's full name), for the Wi-Fi screen.
    private var scanAllowed by mutableStateOf(true)

    // The location (only for the Wi-Fi list) answered: said in the log, and the dash's name learned now if it opened.
    private val wifiListAnswered: (Boolean) -> Unit = { granted ->
        RideDiagnostics.log("ui", "the location for the Wi-Fi list " + if (granted) "allowed" else "refused")
        permissionRows = PermissionRows.read(this, accessibility)
        learnDashName()
    }

    // Android 13+ asks before the projection's notification may show and, while the dash's whole name is not known,
    // for the location, only for the Wi-Fi list (StartAsks); the projection starts either way.
    private val askBeforeStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        answers[StartAsks.FINE_LOCATION]?.let { wifiListAnswered(it) }
        if (startingCard) ProjectionService.startCard(this) else ProjectionService.start(this)
    }

    // The Wi-Fi screen's "Permitir a localização" (only for the Wi-Fi list).
    private val askWifiList = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
        wifiListAnswered(answers[StartAsks.FINE_LOCATION] == true)
    }

    // Whether REau's own traffic goes into a VPN (read at each return to the screen), for the main screen's card.
    private var vpnHoldsREau by mutableStateOf(false)

    // Whether the other edition of REau is installed (read at each return to the screen), for the main screen's card.
    private var otherEditionInstalled by mutableStateOf(false)

    // The pairing read again by the screens once a search learned the dash's name.
    private var pairingVersion by mutableIntStateOf(0)

    // Asked once a run as REau opens with the dash's name unknown (DashFinder); the Wi-Fi screen offers it again.
    private var wifiListAsked = false

    // The dash's Wi-Fi found and filled in by itself: searched for while REau is on screen.
    private val dashSearch = DashSearch(
        this,
        step = {
            val known = DashPairing.open(this).ssid.isNotBlank()
            com.reau.app.dash.DashFinder.step(known, scanAllowed, wifiListAsked, termsAccepted = termsAcceptedNow())
        },
        ask = {
            wifiListAsked = true
            askWifiList.launch(StartAsks.LOCATION.toTypedArray())
        },
        learn = { learnDashName() },
    )

    // The terms of use come before every question of Android's (the location, for the Wi-Fi list, is one).
    private fun termsAcceptedNow() = !com.reau.app.settings.Terms.needsAcceptance(com.reau.app.settings.TermsStore.open(this).accepted())

    // Answering calls by the joystick: without both permissions the switch goes back off.
    private val askForCalls = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) return@registerForActivityResult
        val store = SettingsStore.open(this)
        store.save(store.load().copy(answerCalls = false))
        RideDiagnostics.log("ui", "the phone permissions were refused: answering by the joystick stays off")
        // The switch had already widened what the accessibility hears (the voice apps); take that back with it.
        com.reau.app.service.AaServerAccessibilityService.followCallsSwitch(false)
        settingsVersion++
    }

    // Gemini by the joystick: without the microphone the switch goes back off.
    private val askForMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) return@registerForActivityResult
        val store = SettingsStore.open(this)
        store.save(store.load().copy(gemini = false))
        RideDiagnostics.log("ui", "the microphone permission was refused: Gemini by the joystick stays off")
        settingsVersion++
    }

    // The system's own picker; the picture stays readable after a restart by keeping the permission it grants.
    private val pickCardImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val store = SettingsStore.open(this)
        store.save(store.load().copy(cardImage = uri.toString()))
        settingsVersion++
    }

    // Before Android 13 the chosen language is put on the context here (from 13 the system holds it).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.reau.app.settings.AppLanguages.wrap(newBase))
    }

    @Suppress("LongMethod") // one straight run of setup/serialization; cutting it adds indirection and no clarity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(dashSearch)
        // The accessibility service connects some seconds after the app starts (after an install): the state is read again then.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                AaServerStarter.serviceChanges.collect {
                    accessibility = AaServerStarter.accessibility(this@MainActivity)
                    permissionRows = PermissionRows.read(this@MainActivity, accessibility)
                }
            }
        }
        setContent {
            val projection by ProjectionService.projection.collectAsState()
            val startedAt by ProjectionService.startedAt.collectAsState()
            val serverResult by AaServerStarter.status.collectAsState()
            // The network server is up when it has published its address and PIN (the icon of the main screen blinks for it).
            val networkOn = com.reau.app.web.WebAccess.state.collectAsState().value != null
            var inSettings by rememberSaveable { mutableStateOf(false) }
            var inAndroidAuto by rememberSaveable { mutableStateOf(false) }
            var inJoystick by rememberSaveable { mutableStateOf(false) }
            var page by rememberSaveable { mutableStateOf(SettingsPage.NONE) }
            // A screen under another keeps its place (its scroll) until it is left.
            val kept = rememberSaveableStateHolder()
            val store = remember { SettingsStore.open(this) }
            val pairingStore = remember { DashPairing.open(this) }
            var settings by remember(settingsVersion) { mutableStateOf(store.load()) }
            var pairing by remember(pairingVersion) { mutableStateOf(pairingStore.toUi()) }
            com.reau.app.ui.BatteryWatch.Follow(this) { battery = it }

            val saveSettings: (REauSettings) -> Unit = { settings = saved(store, settings, it) }
            val reload = { settings = store.load(); pairing = pairingStore.toUi() }
            // The browser switch goes off with the projection (the service writes it): the screen follows when it ends.
            androidx.compose.runtime.LaunchedEffect(startedAt == null) { if (startedAt == null) settings = store.load() }
            val leaveSettings = { inSettings = false; kept.removeState(SETTINGS_SCREEN) }
            val actions = settingsActions(saveSettings, pairingStore, { pairing = it }, leaveSettings) { page = it }
            // Nothing of the app is reachable until the rider has accepted the current terms of use.
            val termsStore = remember { com.reau.app.settings.TermsStore.open(this) }
            var termsAccepted by remember { mutableStateOf(!com.reau.app.settings.Terms.needsAcceptance(termsStore.accepted())) }
            androidx.compose.runtime.CompositionLocalProvider(com.reau.app.ui.theme.LocalREauNight provides rememberNight(this, settings)) {
            if (!termsAccepted) {
                com.reau.app.ui.TermsScreen(
                    gate = true,
                    onAccept = {
                        termsStore.accept()
                        termsAccepted = true
                        dashSearch.onResume(this)
                        RideDiagnostics.log("ui", "the rider accepted the terms of use, version ${com.reau.app.settings.Terms.VERSION}")
                    },
                    onDecline = {
                        RideDiagnostics.log("ui", "the rider did not accept the terms of use")
                        finish()
                    },
                    onBack = {},
                )
            } else if (inAndroidAuto) {
                AndroidAutoFromSettings(settings, saveSettings) { inAndroidAuto = false }
            } else if (page == SettingsPage.WIFI) {
                BackHandler { page = SettingsPage.NONE }
                com.reau.app.ui.WifiScreen(settings, pairing, actions, scanAllowed) { page = SettingsPage.NONE }
            } else if (page == SettingsPage.WEATHER) {
                BackHandler { page = SettingsPage.NONE }
                com.reau.app.ui.WeatherScreen(settings.dashWeather, { saveSettings(settings.copy(dashWeather = it)) }) { page = SettingsPage.NONE }
            } else if (page == SettingsPage.SERVER) {
                // The server's screen, from its icon on the main screen.
                BackHandler { page = SettingsPage.NONE }
                com.reau.app.ui.ServerScreen(
                    mainState(projection, settings, serverResult, startedAt, pairing.ssid).withNetwork(networkOn), settings,
                    { saveSettings(it.sanitized()) },
                ) { page = SettingsPage.NONE }
            } else if (page != SettingsPage.NONE) {
                SettingsPageFromSettings(
                    this, page, permissionRows, settings.useLocation, com.reau.app.settings.AppPermissions.functionsOf(settings), { page = it },
                ) { page = SettingsPage.NONE }
            } else if (inJoystick) {
                // The joystick page, over the settings.
                BackHandler { inJoystick = false }
                com.reau.app.ui.JoystickScreen(settings, saveSettings) { inJoystick = false }
            } else if (inSettings) {
                BackHandler(onBack = leaveSettings)
                kept.SaveableStateProvider(SETTINGS_SCREEN) {
                    SettingsScreen(settings, actions, { inJoystick = true }, accessibility)
                }
            } else {
                kept.SaveableStateProvider(MAIN_SCREEN) {
                    MainScreen(
                        mainState(projection, settings, serverResult, startedAt, pairing.ssid).withNetwork(networkOn),
                        onToggle = { toggle(projection) },
                        onOpenAndroidAutoSettings = { openAndroidAutoSettings() },
                        onOpenSettings = { reload(); inSettings = true },
                        mainExtras { inAndroidAuto = true.also { RideDiagnostics.log("ui", "the rider opened the Android Auto screen") } }.copy(
                            onOpenPermissions = { page = SettingsPage.PERMISSIONS }, onOpenAboutData = { page = SettingsPage.ABOUT_DATA },
                            onOpenWifi = { reload(); page = SettingsPage.WIFI },
                            onOpenWeather = { page = SettingsPage.WEATHER },
                            onOpenServer = { page = SettingsPage.SERVER },
                        ),
                    )
                }
            }
            }
        }
    }

    /** What the settings and the Wi-Fi screen do; [onBack] leaves the settings, [open] a page over them (the Wi-Fi's too). */
    private fun settingsActions(
        saveSettings: (REauSettings) -> Unit,
        pairingStore: DashPairing,
        setPairing: (PairingUi) -> Unit,
        onBack: () -> Unit,
        open: (SettingsPage) -> Unit,
    ) = SettingsActions(
        onSettings = saveSettings,
        onPairing = { setPairing(savePairing(pairingStore, it)) },
        onForgetDash = { setPairing(forgetDash(this, pairingStore).toUi()) },
        onBack = onBack,
        onPickCardImage = { pickCardImage.launch(arrayOf("image/*")) },
        onShareLogs = { DiagnosticsExport.shareFrom(this, withRecordings = it) },
        onOpenAccessibility = { AaServerStarter.openAccessibilitySettings(this) },
        onLanguage = { com.reau.app.settings.AppLanguages.set(this, it) },
        onViewLogs = { open(SettingsPage.LOGS) },
        onAskWifiList = { askWifiList.launch(StartAsks.LOCATION.toTypedArray()) },
        onDeleteLogs = { withRecordings, done -> deleteLogs(this, withRecordings, done) },
    )

    /** What the main screen shows: the projection, its start, the card's picture, the server, battery, dash and permissions. */
    private fun mainState(projection: com.reau.app.service.Projection, settings: REauSettings, server: ServerResult, startedAt: Long?, dashName: String) =
        MainUiState(
            projection, androidAutoVersion, settings.cardImage?.let { CardName.shown(this, it) },
            ServerUi(
                // Never in the Lite edition (no accessibility service): its screens do not speak of the automatic switch.
                settings.aaServerAuto && com.reau.app.Edition.hasAccessibility, server, accessibility,
            ),
            battery,
            startedAtMs = startedAt, dashName = dashName, permissionsMissing = permissionsMissing(settings), vpnHoldsREau = vpnHoldsREau,
            otherEditionInstalled = otherEditionInstalled,
            weatherOn = settings.dashWeather,
        )

    /** "Faltam N": only what the functions switched on need (the others are optional on the permissions page). */
    private fun permissionsMissing(settings: REauSettings) = com.reau.app.settings.AppPermissions.missing(
        permissionRows, Build.VERSION.SDK_INT, settings.useLocation, com.reau.app.settings.AppPermissions.functionsOf(settings),
    )

    /** The main screen's other actions: the card mode, the Android Auto screen ([onShowAndroidAuto]), the server. */
    private fun mainExtras(onShowAndroidAuto: () -> Unit) = MainExtras(
        onCard = { start(card = true) },
        onShowAndroidAuto = onShowAndroidAuto,
        onRetryServer = { AaServerStarter.retry(this) },
        onOpenAccessibility = { AaServerStarter.openAccessibilitySettings(this) },
        onAskBatteryExemption = { com.reau.app.ui.BatteryWatch.askExemption(this) },
        onOpenBatterySaver = { com.reau.app.ui.BatteryWatch.openSaver(this) },
    )

    /** What follows a change in the settings: the log, the Wi-Fi suggestion, a running refit. */
    @Suppress("CyclomaticComplexMethod") // one branch per field or case of the format it reads or writes
    private fun settingsChanged(before: REauSettings, after: REauSettings) {
        before.changesTo(after).forEach { RideDiagnostics.log("ui", "setting $it") }
        // The bike Wi-Fi suggestion follows the switch.
        if (after.autoJoinWifi != before.autoJoinWifi) DashWifiSuggestions.syncSoon(this)
        if (after.answerCalls && !before.answerCalls && !PhoneCalls(this).permitted()) askForCalls.launch(PhoneCalls.PERMISSIONS)
        // Privacy: the accessibility hears the voice apps only with this switch on.
        if (after.answerCalls != before.answerCalls) com.reau.app.service.AaServerAccessibilityService.followCallsSwitch(after.answerCalls)
        if (after.gemini && !before.gemini && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
        // The detailed log follows its switch at once.
        DetailedLog.enabled = after.detailedLog
        // So do the ride log and the logcat copy.
        if (after.rideLog != before.rideLog) switchRideLog(after.rideLog)
        if (after.logKeepDays != before.logKeepDays) thread(name = "log-age") { RideDiagnostics.applyKeepDays(after.logKeepDays) }
        // A running projection takes a new fit at once, to adjust it looking at the dash; so does every video
        // setting (LiveTuning).
        val refit = LiveTuning.steps(before, after).isNotEmpty()
        if (after.useLocation != before.useLocation) {
            // The place is learned here, on the screen (the service cannot read it); the refit goes after, so the dash's day or night uses it.
            learnSunPlace(this, then = { if (refit) ProjectionService.refit(this) })
        } else if (refit) {
            ProjectionService.refit(this)
        }
    }

    /** Saves [after], reads it back and lets everything that follows a change know; the settings now. */
    private fun saved(store: SettingsStore, before: com.reau.app.settings.REauSettings, after: com.reau.app.settings.REauSettings) =
        store.run {
            save(after)
            load()
        }.also { settingsChanged(before, it) }

    private fun toggle(p: Projection) {
        // The button is disabled meanwhile; this is the second guard against a start that would queue behind the stop.
        if (p.stopping) {
            RideDiagnostics.log("ui", "the rider tapped while the projection was turning off: nothing done")
            return
        }
        val phase = p.phase
        RideDiagnostics.log("ui", if (phase == Phase.STOPPED) "the rider switched the projection on" else "the rider switched the projection off")
        if (phase == Phase.STOPPED) start(card = false) else ProjectionService.stop(this)
    }

    // When the rider left for Android Auto's settings (elapsed realtime), 0 if not: only for the log.
    private var leftForAaSettingsAtMs = 0L

    private fun openAndroidAutoSettings() {
        // Without Android Auto the button leads to its page in the store; whatever fails says so, never in silence.
        val installed = AndroidAuto.installedVersion(this) != null
        val opened = com.reau.app.aa.AndroidAutoStore.open(this)
        RideDiagnostics.log("aa-server", "rider opened Android Auto settings (installed: $installed) → $opened")
        if (opened != AndroidAuto.Opened.NOTHING) {
            leftForAaSettingsAtMs = android.os.SystemClock.elapsedRealtime()
        } else {
            android.widget.Toast.makeText(
                this, if (installed) R.string.aa_settings_failed else R.string.aa_store_none, android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (leftForAaSettingsAtMs != 0L) {
            val away = (android.os.SystemClock.elapsedRealtime() - leftForAaSettingsAtMs) / 1_000
            RideDiagnostics.log("aa-server", "rider back from Android Auto settings after $away s")
            leftForAaSettingsAtMs = 0L
        }
        // Read again each time: the rider may have just installed or updated Android Auto.
        androidAutoVersion = AndroidAuto.installedVersion(this)
        accessibility = AaServerStarter.accessibility(this)
        battery = com.reau.app.ui.BatteryWatch.read(this)
        vpnHoldsREau = com.reau.app.dash.NetworkBlock.vpnHolds(this)
        otherEditionInstalled = OtherEdition.installed(this)
        permissionRows = PermissionRows.read(this, accessibility)
        learnDashName()
        learnSunPlace(this, thenWeather = true)
    }



    /**
     * Android lets REau read the Wi-Fi scan only while it is on screen, and a projection usually starts in
     * the background, when Android Auto connects. So the dash's full name is learned here, whenever the rider opens
     * REau near the bike, and is already known by then. Off the main thread: it reads the system's last scan.
     */
    private fun learnDashName() {
        // By the location permission only with the rider's switch "Usar a localização" on.
        scanAllowed = com.reau.app.service.PhonePlace.mayScan(this)
        if (!scanAllowed) return
        val pairing = DashPairing.open(this)
        val wifi = DashWifiManager(this, lifecycleScope)
        thread(name = "dash-name") {
            DashName.Learner({ pairing.ssid }, { pairing.ssidPrefix }, wifi::findDashSsid, { pairing.ssid = it }).learn()?.let {
                RideDiagnostics.log("wifi", "the dash's whole name found by the search on screen")
                DashWifiSuggestions.sync(this)
                runOnUiThread { pairingVersion++ }
            }
        }
    }

    private fun start(card: Boolean) {
        fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val asks = StartAsks.permissions(
            Build.VERSION.SDK_INT, card, granted(StartAsks.NOTIFICATIONS), granted(StartAsks.FINE_LOCATION), DashPairing.open(this).ssid.isNotBlank(),
        )
        startingCard = card
        if (card) RideDiagnostics.log("ui", "the rider switched the card mode on")
        when {
            asks.isNotEmpty() -> askBeforeStart.launch(asks.toTypedArray())
            card -> ProjectionService.startCard(this)
            else -> ProjectionService.start(this)
        }
    }

}

/** The pairing as the settings screen shows it. */
private fun DashPairing.toUi() = PairingUi(ssid = ssid, ssidPrefix = ssidPrefix, password = password)

/** The ride log and the logcat copy switched on or off from the screen. */
private fun switchRideLog(on: Boolean) {
    if (on) {
        RideDiagnostics.switchOn("the rider switched the log on")
        com.reau.app.data.OwnLogcat.resume()
    } else {
        com.reau.app.data.OwnLogcat.pause()
        RideDiagnostics.switchOff("the rider switched it off")
    }
}

/** Ajustes' delete buttons: off the main thread, then how much went, for the screen's Snackbar. */
private fun deleteLogs(activity: MainActivity, withRecordings: Boolean, done: (files: Int, kb: Long) -> Unit) {
    thread(name = "delete-logs") {
        val cleared = com.reau.app.data.LogsCleanup.run(activity, withRecordings)
        activity.runOnUiThread { done(cleared.files, cleared.bytes / KB) }
    }
}

private const val KB = 1024

/** How often the screens' theme asks the sun again. */

/** The dash's name and password as the rider typed them; the pairing now. */
private fun savePairing(pairingStore: DashPairing, typed: PairingUi): PairingUi {
    pairingStore.ssidPrefix = typed.ssidPrefix
    pairingStore.password = typed.password
    // On a clean install the whole name may be typed when REau cannot see the Wi-Fi list.
    if (typed.ssid != pairingStore.ssid) pairingStore.ssid = typed.ssid
    return typed
}

/** The credits page's tap: the project's address to the clipboard (REau opens no browser). */
private fun copyAddress(context: android.content.Context, url: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("REau", url))
}

/** The rider forgot the dash: the pairing goes, and the bike Wi-Fi suggestion with it (off the main thread). */
private fun forgetDash(context: android.content.Context, pairingStore: DashPairing): DashPairing {
    RideDiagnostics.log("ui", "the rider forgot the dash")
    pairingStore.forget()
    // What that dash said (its identity among it) goes too: it is not the next dash's.
    com.reau.app.dash.DashReadings.clear()
    DashWifiSuggestions.syncSoon(context)
    return pairingStore
}

/**
 * The pages over the settings that hold no setting, and the bike's Wi-Fi (from the settings or the main screen). The
 * permissions page shows the rows read at the last onResume, read again whenever REau comes back to the front.
 */
private enum class SettingsPage { NONE, PERMISSIONS, ABOUT_DATA, CREDITS, TERMS, LOGS, WIFI, WEATHER, SERVER }

/** The keys under which the main screen and the settings keep their place while another screen is over them. */
private const val MAIN_SCREEN = "main"
private const val SETTINGS_SCREEN = "settings"

@androidx.compose.runtime.Composable
private fun SettingsPageFromSettings(
    activity: MainActivity,
    page: SettingsPage,
    rows: List<com.reau.app.settings.AppPermissions.Row>,
    locationAllowed: Boolean,
    functions: com.reau.app.settings.AppPermissions.Functions,
    goTo: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    // The credits and the terms are opened from "O REau e os seus dados": back goes to it, not to the main screen.
    val back = if (page == SettingsPage.CREDITS || page == SettingsPage.TERMS) ({ goTo(SettingsPage.ABOUT_DATA) }) else onBack
    BackHandler(onBack = back)
    when (page) {
        SettingsPage.ABOUT_DATA -> com.reau.app.ui.AboutDataScreen(
            onBack, onOpenCredits = { goTo(SettingsPage.CREDITS) }, onOpenTerms = { goTo(SettingsPage.TERMS) },
        )
        SettingsPage.CREDITS -> com.reau.app.ui.CreditsScreen(back) { copyAddress(activity, it) }
        SettingsPage.TERMS -> com.reau.app.ui.TermsScreen(gate = false, onAccept = {}, onDecline = {}, onBack = back)
        SettingsPage.LOGS -> com.reau.app.ui.LogViewerScreen(RideDiagnostics.logDir(), onBack) {
            DiagnosticsExport.shareFrom(activity, withRecordings = false)
        }
        else -> PermissionsPage(activity, rows, locationAllowed, functions, onBack)
    }
}

@androidx.compose.runtime.Composable
private fun PermissionsPage(
    activity: MainActivity,
    rows: List<com.reau.app.settings.AppPermissions.Row>,
    locationAllowed: Boolean,
    functions: com.reau.app.settings.AppPermissions.Functions,
    onBack: () -> Unit,
) {
    com.reau.app.ui.PermissionsScreen(
        rows,
        com.reau.app.ui.PermissionsActions(
            onBack = onBack,
            onOpenAppSettings = { PermissionRows.openAppSettings(activity) },
            onOpenAccessibility = { AaServerStarter.openAccessibilitySettings(activity) },
            onAsk = { ask ->
                when (ask) {
                    is com.reau.app.settings.AppPermissions.Ask.Runtime -> activity.askRuntime(ask.permissions)
                    com.reau.app.settings.AppPermissions.Ask.Accessibility -> AaServerStarter.openAccessibilitySettings(activity)
                    com.reau.app.settings.AppPermissions.Ask.Battery -> com.reau.app.ui.BatteryWatch.askExemption(activity)
                }
            },
            onAskAll = activity.askRuntime,
        ),
        locationAllowed = locationAllowed,
        functions = functions,
    )
}

/** The permissions page's rows as Android has them now, and Android's page of REau's own permissions. */
private object PermissionRows {
    fun read(context: android.content.Context, accessibility: AaServerStarter.Accessibility): List<com.reau.app.settings.AppPermissions.Row> {
        val requested = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        }.getOrDefault(emptyList())
        val power = context.getSystemService(android.os.PowerManager::class.java)
        return com.reau.app.settings.AppPermissions.rows(
            requested = requested,
            granted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            accessibilityOn = accessibility == AaServerStarter.Accessibility.CONNECTED,
            batteryExempt = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false),
            accessibilityStuck = accessibility == AaServerStarter.Accessibility.ENABLED,
            accessibilityRestricted = com.reau.app.settings.AppPermissions.restrictedSettings(Build.VERSION.SDK_INT, installer(context)),
            withAccessibility = com.reau.app.Edition.hasAccessibility,
        )
    }

    /** Who installed REau (null: unknown, as with adb or before Android 11). */
    private fun installer(context: android.content.Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        } else {
            null
        }

    fun openAppSettings(context: android.content.Context) {
        val uri = android.net.Uri.fromParts("package", context.packageName, null)
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** The Android Auto screen with the touch, the mask and the fit (which lives there now) of the settings. */
@androidx.compose.runtime.Composable
private fun AndroidAutoFromSettings(settings: REauSettings, save: (REauSettings) -> Unit, onBack: () -> Unit) {
    // The screen Android Auto shows; the mask shows the fit in force on it, the fit opens on its tab
    val screen = com.reau.app.aa.AaScreens.watch.screen.collectAsState().value
    AndroidAutoScreen(
        onBack = onBack, touch = settings.phoneTouch, mask = maskOf(settings, save, screen),
        fit = com.reau.app.ui.AaScreenFit(settings, screen, save),
        stats = settings.overlay,
    )
}

/** The dash's mask on the Android Auto screen follows its switch and the fit saved in the settings. */
private fun maskOf(settings: REauSettings, save: (REauSettings) -> Unit, screen: com.reau.app.aa.AaScreen) =
    AaScreenMask(settings.dashMask, settings.mappingFor(screen), com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT) {
        save(settings.copy(dashMask = it))
    }
