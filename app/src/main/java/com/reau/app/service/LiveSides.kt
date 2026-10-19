package com.reau.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Network
import android.os.PowerManager
import androidx.core.net.toUri
import com.reau.app.R
import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaLog
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.AaReceiver
import com.reau.app.aa.JoystickBindings
import com.reau.app.aa.JoystickMap
import com.reau.app.phone.PhoneCalls
import com.reau.app.phone.PhoneVoice
import com.reau.app.aa.VoiceSource
import com.reau.app.phone.CallState
import com.reau.app.aa.AaCommand
import com.reau.app.aa.NavGuidance
import com.reau.app.card.CardPainter
import com.reau.app.dash.DashPairing
import com.reau.app.dash.DashPorts
import com.reau.app.dash.DashSession
import com.reau.app.dash.DashSocket
import com.reau.app.dash.DashWifiManager
import com.reau.app.dash.DashWifiSuggestions
import com.reau.app.dash.nav.AaManeuvers
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import com.reau.app.video.AaMargins
import com.reau.app.stream.VideoLoop
import com.reau.app.video.DashCompositor
import com.reau.app.video.DashGlass
import com.reau.app.video.FrameFit
import com.reau.app.video.PhonePreview
import com.reau.app.video.RectPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.util.TimeZone

/**
 * The real sides of the projection, all inside the service:
 *
 *     Android Auto ──▶ AaReceiver (decoder) ──▶ DashCompositor ──▶ VideoLoop (encoder, RTP) ──▶ DashSession ──▶ dash
 *
 * The compositor is made once, with the sides, and lives as long as they do: Android Auto's decoder draws into it
 * whether or not a dash is there, and the video loop takes from it whether or not Android Auto is (it repeats the
 * last picture). So each side comes and goes on its own, as [ProjectionMachine] says.
 *
 * Both sides report to the machine through [report]: the receiver its [AaReceiver] states, the dash session its
 * states read by [ProjectionMachine.linkOf]. All the methods are called on the service's worker, one at a time.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
internal class LiveSides(
    context: Context,
    private val report: (ProjectionEvent) -> Unit,
    private val setup: Setup,
) : ProjectionSides {
    /**
     * What differs between the phone and a test: [dashSocket] makes the dash session's socket (the real one sends to
     * 192.168.1.1), [linkUp] gets onto the bike's Wi-Fi.
     */
    data class Setup(
        val aaConfig: AaConfig = AaConfig(),
        val aaOptions: AaReceiver.Options = AaReceiver.Options(),
        val video: VideoLoop.Settings = VideoLoop.Settings(),
        val dashSocket: (Network?) -> DashSocket = { DashSocket(it) },
        val linkUp: (Context, CoroutineScope) -> DashLinkUp,
        /** Which part of Android Auto's picture goes where on the dash; null keeps the compositor's own (fill the dome). */
        val mapping: FrameFit.Mapping? = null,
        val card: (() -> Bitmap)? = null,
        /** Whether no other app holds the dash's port; checked before every try to reach the dash. */
        val dashPortFree: () -> Boolean = { DashPorts.isFree(DashPorts.DASH_RX_PORT) },
        /** Day or night for Android Auto's night sensor, asked every [nightCheckMs] while the sides live. */
        val night: () -> Boolean = { false },
        /** Whether the phone has a working network, for the lost-signal icon while recalculating. */
        val online: () -> Boolean = { true },
        val nightCheckMs: Long = NIGHT_CHECK_MS,
        /** The phone's thermal status (PowerManager's), for the video loop's hot ceiling. */
        val thermalStatus: () -> Int = { VideoLoop.THERMAL_UNKNOWN },
        /** The call as the joystick sees it, and answering or ending it (behind a switch). */
        val calls: Calls = Calls(),
        /** The rider's settings these sides were built from: what a change while running is told apart from. */
        val settings: REauSettings? = null,
        /** Android Auto's head unit server switched on and off by itself (behind its switch). */
        val server: AaServerHooks = AaServerHooks.None,
        /** How long after the joystick Android Auto's blue focus is touched away (shorter in a test). */
        val focusIdleMs: Long = com.reau.app.aa.FocusClearer.IDLE_MS,
        /** The phone's own state for the dash's heartbeat (behind the switch "Mandar o estado do celular ao painel"). */
        val phoneStatus: () -> com.reau.app.dash.protocol.DashCommands.PhoneStatus? = { null },
        /** The weather for the dash's heartbeat (behind "Clima no painel"), and the refresh of it; none in a test. */
        val weather: () -> com.reau.app.dash.protocol.DashCommands.Weather? = { null },
        val refreshWeather: suspend () -> Unit = {},
        /** Where what the dash says is kept for the screen "Dados da moto"; null in a test. */
        val readings: com.reau.app.dash.DashReadingSink? = null,
    ) {
        class Calls(
            val state: () -> CallState = { CallState.IDLE },
            val answer: () -> Unit = {},
            val end: () -> Unit = {},
            val volume: (Int) -> Unit = {},
        )

        companion object {
            /**
             * How many units of each stream the video recording keeps: about 15 min at the dash's 4 fps (2000, about
             * 10 min, once stopped a ride's recording 6 min before its end).
             */
            private const val RECORD_FRAMES = 3_000

            /** How often day and night are asked again: dusk moves a degree of the sun in about four minutes. */
            private const val NIGHT_CHECK_MS = 60_000L

            /**
             * On the phone, with the rider's saved settings ([SettingsStore]): the bike's Wi-Fi by [DashWifiManager],
             * the dash's name from [DashPairing], the debug recordings in the app's own external folder (`video`,
             * `sessions`), where `adb pull` reaches them.
             */
            /** The rider's switch read at each press (it applies at once), the permissions and the call from [PhoneCalls]. */
            private fun phoneCalls(context: Context): Calls {
                val phone = PhoneCalls(context)
                val store = SettingsStore.open(context)
                return Calls(
                    state = { PhoneCalls.visibleState(store.load().answerCalls, phone.permitted(), phone.state()) },
                    answer = { phone.answer() },
                    end = { phone.end() },
                    volume = { phone.adjustCallVolume(it) },
                )
            }

            fun forPhone(context: Context): Setup {
                // The browser server is always off at the start of a run, whatever the last one left.
                com.reau.app.web.WebSwitch.atStart(SettingsStore.open(context)) { RideDiagnostics.log("projection", it) }
                val settings = SettingsStore.open(context).load()
                val folder: File? = context.filesDir // for privacy: internal storage (AppFolders)
                val recording = folder?.takeIf { settings.recordSessions }?.let { AaReceiver.Recording(File(it, "sessions")) }
                // Gemini (behind its switch): the voice from the headset or the phone.
                val voice: (() -> VoiceSource)? = { PhoneVoice(context) { RideDiagnostics.log("aa", it) } }
                    .takeIf { settings.gemini }
                return Setup(
                    aaConfig = settings.aaConfig,
                    aaOptions = AaReceiver.Options(
                        // The Lite edition with Android Auto 17.3 or older: it dials in, asked by a broadcast. With a newer
                        // one the receiver dials the developer server, which the rider switches on by hand (no accessibility).
                        callIn = if (com.reau.app.Edition.lite &&
                            com.reau.app.aa.AndroidAuto.dialsIn(com.reau.app.aa.AndroidAuto.installedVersion(context))
                        ) {
                            AaReceiver.CallIn { com.reau.app.aa.AaCallsIn.poke(context) { RideDiagnostics.log("aa", it) } }
                        } else {
                            null
                        },
                        recording = recording,
                        voice = voice,
                        // Only the first projection of this process: the exit Android keeps stays the newest until the
                        // next one, and a clean Desligar and Ligar a minute later was taken for a fresh install again.
                        quickUnresponsive = com.reau.app.aa.FirstOfProcess.projection.take() &&
                            com.reau.app.data.ExitReasons.leftAndroidAutoHanging(
                                com.reau.app.data.ExitHistory.newest(context), System.currentTimeMillis(),
                            ),
                    ),
                    video = VideoLoop.Settings(
                        ceilingFps = settings.fpsCeiling,
                        clockSlowPercent = settings.clockSlowPercent,
                        bitrateBps = settings.streamBitrateBps,
                        bitrateMode = settings.bitrateMode,
                        keyframeSec = settings.keyframeSec,
                        recordFrames = if (settings.recordVideo && folder != null) RECORD_FRAMES else 0,
                        recordDir = folder?.let { File(it, "video") },
                    ),
                    linkUp = { ctx, scope -> phoneLinkUp(ctx, scope) },
                    mapping = settings.mappingFor(com.reau.app.aa.AaScreens.watch.screen.value),
                    online = { phoneOnline(context) },
                    // The theme read at each ask, so a change while running is followed.
                    night = {
                        val now = SettingsStore.open(context).load()
                        DayNight.Clock(
                            now.mapTheme,
                            // The place learned on the screen, with "Usar a localização" on; else the time zone.
                            place = { SunPlace.saved(context, now.useLocation) },
                            offsetMs = { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
                        ).isNight()
                    },
                    thermalStatus = { phoneThermalStatus(context) },
                    calls = phoneCalls(context),
                    settings = settings,
                    server = AaServerStarter.hooks(context),
                    phoneStatus = PhoneStatusReader(context)::read,
                    weather = { com.reau.app.weather.WeatherKeeper.forDash() },
                    refreshWeather = { com.reau.app.weather.WeatherKeeper.refreshIfStale(context) },
                    readings = com.reau.app.dash.DashReadings,
                )
            }

            private fun phoneThermalStatus(context: Context): Int =
                runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(VideoLoop.THERMAL_UNKNOWN)

            /**
             * The card mode on the phone: as [forPhone], with the picture picked in the settings, or the
             * legibility card if none was picked or it cannot be read.
             */
            fun forCard(context: Context): Setup {
                val image = SettingsStore.open(context).load().cardImage
                return forPhone(context).copy(card = { cardPicture(context, image) })
            }

            private fun cardPicture(context: Context, image: String?): Bitmap {
                // The default card is named with the full edition's id; it is this app's own drawable, whichever edition runs.
                val uri = if (image == com.reau.app.settings.REauSettings.DEFAULT_CARD) {
                    "android.resource://${context.packageName}/drawable/grade_medicao"
                } else {
                    image
                }
                val picked = uri?.let { CardPainter.load(context, it.toUri()) }
                if (image != null && picked == null) {
                    RideDiagnostics.warn("projection", "the picked card picture could not be read, the legibility card goes: $image")
                }
                return picked?.let(CardPainter::picture) ?: CardPainter.legibility(context.getString(R.string.card_legibility_sample))
            }

            private fun phoneLinkUp(context: Context, scope: CoroutineScope): DashLinkUp {
                val pairing = DashPairing.open(context)
                val wifi = DashWifiManager(context, scope, pairing)
                return WifiDashLinkUp(
                    scope, wifi.state, { wifi.network },
                    { ssid, password, prefix -> wifi.connect(ssid, password, prefix) },
                    wifi::disconnect,
                    object : WifiDashLinkUp.Pairing {
                        override val ssid: String get() = pairing.ssid
                        override val ssidPrefix: String get() = pairing.ssidPrefix
                        override val password: String get() = pairing.password
                        override val joinedSsid: String get() = pairing.joinedSsid
                        override fun remember(ssid: String) {
                            pairing.ssid = ssid
                            DashWifiSuggestions.sync(context) // the suggestion follows the name learned
                        }

                        // The Wi-Fi list by location only with the rider's switch on (LocationUse).
                        override fun discover(prefix: String): String? =
                            if (PhonePlace.mayScan(context)) wifi.findDashSsid(prefix) else null

                        override fun seenInScan(wanted: String, isPrefix: Boolean): Boolean =
                            PhonePlace.mayScan(context) && wifi.seenInLastScan(wanted, isPrefix)
                    },
                ).apply {
                    wifi.onNetworkAvailable = { bikeNetworkAvailable() }
                    // A reconnection the manager has under way is joined, not replaced.
                    joining = WifiDashLinkUp.Joining({ wifi.reconnectUnderWay() }, log = { RideDiagnostics.log("wifi", it) })
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // When the joystick last went to Android Auto, to touch its blue focus away a minute later.
    private val focus = com.reau.app.aa.FocusClearer(setup.focusIdleMs)
    private val compositor = DashCompositor { RideDiagnostics.log("compositor", it) }

    // The stats text of the dash's video and of the preview: Android Auto's own numbers ("app") until the
    // dash is connected, then the stream's to it (labelled "dash"); it reaches the compositor and the preview screen.
    // Its few words come from the service's resources: the language the rider chose.
    private val statsFeed = com.reau.app.video.StatsFeed(
        com.reau.app.video.StatsWords(
            panel = context.getString(R.string.stats_dash), defaultMode = context.getString(R.string.stats_default_mode),
            frameUnit = context.getString(R.string.stats_frame_unit), keyframe = context.getString(R.string.stats_keyframe),
        ),
    ) {
        com.reau.app.video.StatsOverlay.latest = it
        compositor.setOverlayText(it)
    }
    private val receiver: AaReceiver?
    // The phone's real state in the heartbeat while the rider's switch is on; `tuned` is read at each beat.
    private val session = DashSession(
        scope, socketFactory = setup.dashSocket, linkProbe = com.reau.app.dash.PhoneLinkProbe(context)::read,
        phoneStatusProvider = { if (tuned?.dashPhoneStatus == true) setup.phoneStatus() else null },
        weatherProvider = { if (tuned?.dashWeather == true) setup.weather() else null },
        readings = setup.readings,
    )
    private val linkUp = setup.linkUp(context, scope).also { up ->
        up.onBikeNetwork { report(ProjectionEvent.BikeNetworkAvailable) }
        up.onFirstJoin { report(ProjectionEvent.DashFirstJoin(it)) }
    }
    // The album's picture for the dash's music card (behind its switch), converted once per picture.
    private val albumArt = com.reau.app.dash.AlbumArt()
    private var loop: VideoLoop? = null
    private val appContext = context.applicationContext

    // The bike's speed for the stream's log, from Android Auto's route.
    private val navSpeed = com.reau.app.stream.NavSpeed()

    // The latest guidance snapshot, for the maneuver distance (navSpeed above already reads the same feed).
    @Volatile private var guidanceNow: NavGuidance = NavGuidance.INACTIVE
    private val started: Boolean

    // What changes while running (retune): all on the service's worker thread, except
    // the pending fit, which the receiver's thread takes when Android Auto's new session is up.
    @Volatile private var tuned: REauSettings? = setup.settings
    @Volatile private var aaConfig: AaConfig = setup.aaConfig
    @Volatile private var videoSettings: VideoLoop.Settings = setup.video
    @Volatile private var pendingMapping: FrameFit.Mapping? = null
    private val live = Live()

    /** A finger on the preview screen, scaled into the video and sent to Android Auto as a touch. */
    private val stroke = com.reau.app.aa.PhoneTouch.Stroke()
    private val touchTarget: (com.reau.app.aa.PhoneTouch, PhonePreview.Shown?) -> Unit = { touch, shown ->
        val (w, h) = com.reau.app.aa.AaVideo.WIDTH to com.reau.app.aa.AaVideo.HEIGHT
        val viewport = shown?.let { com.reau.app.video.CompositorMath.previewViewport(w, h, it.width, it.height) }
        val sent = viewport?.let { stroke.next(touch, it, w, h) }
        if (sent != null) receiver?.sendInput(AaCommand.Touch(sent.action, sent.x, sent.y))
    }

    /** What the preview screen shows goes to the compositor (or leaves it). */
    private val previewTarget: (PhonePreview.Shown?) -> Unit = { shown ->
        val surface = shown?.surface as? android.view.Surface
        if (shown == null || surface == null) compositor.clearPreview() else compositor.setPreview(surface, shown.width, shown.height)
    }

    // The joystick of the running projection, for a page changed while it runs. Declared BEFORE the init
    // block that sets it: Kotlin runs initializers in the order of the text, so below it the `= null` could run after
    // the init and clear the map, and a page changed while the projection runs would be dropped.
    @Volatile private var joystick: JoystickMap? = null

    // The web server and what it needs: declared before the init block for the same reason as above.
    @Volatile private var web: com.reau.app.web.WebBridge? = null
    private val webDelayer = com.reau.app.web.ExecutorDelayer()
    @Volatile private var audioPlayer: com.reau.app.aa.AaAudioPlayer? = null
    private val soundHeard = java.util.concurrent.ConcurrentHashMap.newKeySet<com.reau.app.aa.AaAudioFeed.Stream>()

    /** The phone's own speaker for Android Auto's sound, made with the first sound that is to play on it. */
    private fun phoneSpeaker(): com.reau.app.aa.AaAudioPlayer = audioPlayer ?: synchronized(this) {
        audioPlayer ?: com.reau.app.aa.AaAudioPlayer { RideDiagnostics.log("audio", it) }.also { audioPlayer = it }
    }
    private val webPage by lazy { com.reau.app.web.WebPage.load(appContext) }

    init {
        AaLog.sink = { RideDiagnostics.log("aa", it) }
        AaLog.phoneNow = { com.reau.app.data.PhoneNow.text() }
        val card = setup.card
        if (card != null) {
            // The card is drawn at the dash's own size and goes to it pixel for pixel.
            started = compositor.start(DashGlass.FRAME_W, DashGlass.FRAME_H) && compositor.setMapping(WHOLE_FRAME)
            receiver = null
        } else {
            started = compositor.start(com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT)
            val surface = compositor.inputSurface.takeIf { started }
            setup.mapping?.let {
                if (started && !compositor.setMapping(it)) RideDiagnostics.warn("projection", "the saved fit was refused, the dome is filled: $it")
            }
            // Behind the switch: Android Auto's guidance goes to the dash's own nav bubble.
            // The route also gives the speed for the stream's log.
            val noNetwork = NoNetworkWatch(clockMs = { android.os.SystemClock.elapsedRealtime() }, online = setup.online)
            val guidance: (com.reau.app.aa.NavGuidance) -> Unit = {
                guidanceNow = it
                navSpeed.onGuidance(it, android.os.SystemClock.elapsedRealtime())
                val extras = tuned?.dashRouteExtras == true
                session.showGuidance(it, extras, noNetwork = extras && noNetwork.without())
            }
            var options = if (setup.aaConfig.navigation) setup.aaOptions.copy(onNavigation = guidance) else setup.aaOptions
            // Behind their switches: the song and the ringing call go to the dash's own cards.
            if (setup.aaConfig.media) {
                options = options.copy(onNowPlaying = { session.showNowPlayingCard(it, if (tuned?.dashAlbumArt == true) albumArt else null) })
            }
            // Each change of the call also says where its sound goes (the phone, the speaker, a Bluetooth headset),
            // for testing a helmet communicator.
            if (setup.aaConfig.phone) {
                options = options.copy(onCaller = { caller ->
                    session.showCallerCard(caller, context.getString(R.string.caller_private))
                    RideDiagnostics.log("call", com.reau.app.phone.CallAudioRoute.describe(context))
                })
            }
            // The web server's sound: with the media and guidance sinks announced, Android Auto sends REau its sound;
            // it goes on the phone (both) and/or to the browser, as the mode says.
            // Always listening, since the switch can be turned on while running (a new session then announces the sinks): sound only comes
            // when they are announced, and the phone's speaker is only made with the first sound that is to play on it.
            // Android Auto's session open with no picture: the screen and the notification say so (the dash is black).
            options = options.copy(onVideoSilent = { silent -> report(ProjectionEvent.AndroidAutoVideo(silent)) })
            options = options.copy(onAudio = { pcm ->
                if (soundHeard.add(pcm.stream)) RideDiagnostics.log("audio", "the first sound of ${pcm.stream} arrived: ${pcm.data.size} bytes")
                val now = tuned
                if (now?.soundOnPhoneViaReau == true) phoneSpeaker().play(pcm)
                if (now?.audioSinks == true) web?.audio(pcm)
            })
            receiver = surface?.let {
                AaReceiver(context, setup.aaConfig, it, { state ->
                    if (state == AaLinkState.ACTIVE) live.applyPendingFit()
                    report(ProjectionEvent.AndroidAutoChanged(state))
                    setup.server.linkChanged(state)
                    web?.androidAutoChanged(state == AaLinkState.ACTIVE)
                }, options)
            }
            receiver?.let { live.watchNight(it) }
            // The web server, behind its switch: Android Auto's picture and touch for a browser on the local network.
            if (started && receiver != null) {
                web = com.reau.app.web.WebBridge(
                    com.reau.app.web.WebBridge.Env(
                        page = { webPage },
                        video = { out ->
                            com.reau.app.web.WebVideoOutput(compositor, out) { RideDiagnostics.log("web", it) }
                        },
                        sendTouch = { receiver?.sendInput(AaCommand.Touch(it.action, it.x, it.y)) },
                        delayer = webDelayer,
                        log = { RideDiagnostics.log("web", it) },
                    ),
                ).also { bridge -> setup.settings?.let { bridge.apply(it.webView, it.webTouch, it.webAudio) } }
            }
            // The screen "Tela do Android Auto" shows the picture while this runs.
            if (started) PhonePreview.attach(previewTarget)
            // Behind the switch: a finger on that screen touches Android Auto.
            if (started && setup.aaConfig.touch) PhonePreview.attachTouch(touchTarget)
        }
        if (!started) RideDiagnostics.error("projection", "the compositor did not start: no picture, no video")
        setup.settings?.let { if (started) compositor.setOverlay(it.overlay) }
        if (started && receiver != null) scope.launch { watchAppVideo() }
        scope.launch { ProjectionMachine.dashLinks(session.state).collect { report(ProjectionEvent.DashChanged(it)) } }
        // The weather for the dash: looked at every minute, fetched at most every quarter of an hour.
        scope.launch {
            while (isActive) {
                if (tuned?.dashWeather == true) runCatching { setup.refreshWeather() }
                delay(WEATHER_POLL_MS)
            }
        }
        // The app menu's own fit, as Android Auto changes screen (the first value is the screen now)
        val follower = FitFollower({ tuned }, ::remap) { RideDiagnostics.log("projection", it) }
        if (setup.card == null) scope.launch { com.reau.app.aa.AaScreens.watch.screen.drop(1).collect { follower.on(it) } }
        // The dash's joystick drives Android Auto (keys and the wheel); every button is logged, known or not.
        // Gemini only where its microphone was given (the switch "Gemini pelo joystick").
        // The rider's joystick page, one tap one action; the calls on the same sides.
        val joystick = JoystickMap(
            bindings = JoystickBindings.decode(setup.settings?.joystick.orEmpty()),
            assistant = setup.aaOptions.voice != null,
            callState = setup.calls.state,
        ).also { this.joystick = it }
        // What the joystick asked for: the call to the phone, the rest to Android Auto.
        val send: (List<AaCommand>) -> Unit = { commands ->
            commands.forEach { command ->
                when (command) {
                    AaCommand.AnswerCall, AaCommand.EndCall -> callByKeys(command, receiver, setup.calls)
                    is AaCommand.CallVolume -> setup.calls.volume(command.step)
                    else -> receiver?.sendInput(command)
                }
            }
        }
        session.onButton = { event ->
            // On the dash's receive thread (DashSession): sent first, the log line after.
            val press = joystick.press(event, System.currentTimeMillis())
            send(press.sent)
            if (com.reau.app.aa.FocusClearer.toAndroidAuto(press.sent)) focus.inputSent(android.os.SystemClock.elapsedRealtime())
            val call = runCatching { setup.calls.state().name }.getOrDefault("?")
            RideDiagnostics.log("dash", JoystickMap.logLine(event::class.simpleName.orEmpty(), event.code, press.lines, call))
        }
        // Behind its switch, and only with Android Auto told of a touch screen.
        if (setup.card == null && setup.aaConfig.touch && setup.settings?.clearAaFocus == true) {
            clearFocusWhenIdle(scope, focus, setup.focusIdleMs) { receiver?.sendInput(it) }
        }
    }

    override fun startAndroidAuto() {
        val card = setup.card
        if (card != null) return showCard(card)
        val running = receiver ?: return
        setup.server.started { running.dialSoon() }
        running.start()
        // While Android Auto's side runs, its screen is read again every second (AaScreenRereads).
        com.reau.app.aa.AaScreens.projection(true)
    }

    override fun stopAndroidAuto() {
        val running = receiver ?: return
        com.reau.app.aa.AaScreens.projection(false)
        running.stop()
        // After the receiver: the server's end would drop a session still going.
        setup.server.stopped()
    }

    /** The picture's fit changed in the settings; the compositor takes a new mapping while running. */
    override val bikeNetwork: Network? get() = linkUp.bikeNetwork

    override val dashVisible: Boolean get() = linkUp.dashVisible

    /**
     * Every video setting applies while running, with no switching off ([LiveTuning]).
     * Without the settings these sides were built from (a test's), only the fit.
     */
    override fun retune(settings: REauSettings) = live.retune(settings)

    override fun remap(mapping: FrameFit.Mapping) {
        if (setup.card != null || !started) return
        if (!compositor.setMapping(mapping)) RideDiagnostics.warn("projection", "the new fit was refused, the picture stays as it was: $mapping")
    }

    /**
     * Draws the card once into the compositor's input, which keeps repeating it to the dash; then the picture side
     * counts as up. The compositor's input has no other producer in the card mode.
     */
    private fun showCard(card: () -> Bitmap) {
        val surface = compositor.inputSurface.takeIf { started } ?: return
        val picture = runCatching(card).getOrElse {
            RideDiagnostics.warn("projection", "the card could not be drawn: $it")
            return
        }
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(picture, null, Rect(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H), null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        report(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE))
    }

    override fun startDash() {
        // Another app holding the dash's port (another dash app, streaming): both would get the dash's packets. Not
        // even the bike's Wi-Fi is asked for; the machine tries again later.
        if (!setup.dashPortFree()) {
            RideDiagnostics.warn("projection", "the dash's port ${DashPorts.DASH_RX_PORT} is held by another app: not connecting")
            report(ProjectionEvent.DashChanged(DashLink.TAKEN))
            return
        }
        linkUp.start(
            { ssid, network -> session.connect(ssid, network) },
            { why ->
                RideDiagnostics.warn("projection", "no way to the dash: $why")
                report(ProjectionEvent.DashChanged(WifiDashLinkUp.linkAfterFailure(why)))
            },
        )
    }

    override fun stopDash() {
        session.disconnect()
        linkUp.stop()
    }

    /** The service refuses to start with no compositor (ProjectionService's prepare). */
    override val videoReady: Boolean get() = started

    override fun startVideo() {
        if (!started) {
            // No compositor, so no video can go out. Returning with the dash session left READY would leave the
            // machine reading "streaming" for good (no 4 Hz projection packet, no watchdog). The session is
            // closed instead: the machine sees the dash drop and says so, and tries again on its own pace.
            RideDiagnostics.log("projection", "video not started: no compositor; the dash session is closed so the projection does not read as streaming")
            session.disconnect()
            return
        }
        session.startStreaming()
        loop = live.newLoop()
    }

    override fun stopVideo() {
        loop?.stop()
        loop = null
    }

    /** Every second, the pictures and bytes Android Auto sent the phone in it: the "app" numbers of the stats text. */
    private suspend fun kotlinx.coroutines.CoroutineScope.watchAppVideo() {
        var units = com.reau.app.aa.AaVideoMeter.units.get()
        var bytes = com.reau.app.aa.AaVideoMeter.bytes.get()
        var at = android.os.SystemClock.elapsedRealtime()
        statsFeed.app(null)
        val stale = com.reau.app.aa.StaleImage()
        while (isActive) {
            delay(com.reau.app.stream.StreamGovernor.STATS_MS)
            val nowUnits = com.reau.app.aa.AaVideoMeter.units.get()
            val nowBytes = com.reau.app.aa.AaVideoMeter.bytes.get()
            val now = android.os.SystemClock.elapsedRealtime()
            statsFeed.app(com.reau.app.video.StatsOverlay.reading(nowUnits - units, nowBytes - bytes, now - at))
            statsFeed.speed(navSpeed.displayKmhAt(now))
            // A picture that looks alive and is not: the bike moves on the route's speed and Android Auto sends nothing new.
            stale.check(now, nowUnits, navSpeed.kmhAt(now))?.let { isStale ->
                val staleFor = com.reau.app.aa.StaleImage.STALE_AFTER_MS / 1000
                RideDiagnostics.log(
                    "projection",
                    if (isStale) "Android Auto's picture is stuck: no new picture for $staleFor s with the bike moving"
                    else "Android Auto's picture is alive again",
                )
                compositor.setStaleBadge(if (isStale) appContext.getString(R.string.stale_badge) else null)
                report(ProjectionEvent.AndroidAutoImageStale(isStale))
            }
            units = nowUnits
            bytes = nowBytes
            at = now
        }
    }

    override fun release() {
        com.reau.app.video.StatsOverlay.latest = com.reau.app.video.StatsText(com.reau.app.video.StatsOverlay.UNKNOWN, com.reau.app.video.StatsOverlay.APP)
        PhonePreview.detach(previewTarget)
        PhonePreview.detachTouch(touchTarget)
        web?.release()
        audioPlayer?.release()
        webDelayer.shutdown()
        stopVideo()
        receiver?.stop()
        session.disconnect()
        linkUp.stop()
        scope.cancel()
        compositor.release()
    }


    /** The bike's speed (from the route) and Wi-Fi now, for the stream's 5 s line. */
    private val linkSample: () -> com.reau.app.stream.LinkSample = {
        val wifi = DeviceStateReader.bikeWifi(appContext, linkUp.bikeNetwork)
        com.reau.app.stream.LinkSample(navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()), wifi?.dbm, wifi?.mbps)
    }

    /** The maneuver distance and the route's own speed now, for [com.reau.app.stream.StreamGovernor]'s
     *  live override — both null together without an active route (same feed as [linkSample]'s speed). */
    private val navSample: () -> com.reau.app.stream.StreamGovernor.NavSample = {
        val s = tuned
        val g = guidanceNow
        com.reau.app.stream.StreamGovernor.NavSample(
            maneuverProfileEnabled = s?.maneuverFpsProfile == true,
            speedProfileEnabled = s?.speedFpsProfile == true,
            distanceToManeuverMeters = g.distanceToManeuverMeters.takeIf { g.active && it >= 0 },
            speedKmh = navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()),
            mode = s?.navProfileMode ?: com.reau.app.dash.NavProfileMode.PESSIMISTA,
        )
    }

    /** The settings changed while running, and the video loop made with the latest of them. */
    private inner class Live {
        // The new sessions asked for by the settings wait for the burst of changes to end.
        private val sessionSettle = SessionSettle { delayMs, action ->
            val job = scope.launch {
                delay(delayMs)
                action()
            }
            ({ job.cancel() })
        }

        /** Asks day or night now and then for as long as the sides live; the receiver passes on only a change. */
        fun watchNight(receiver: AaReceiver) = scope.launch {
            while (isActive) {
                runCatching { receiver.setNight(setup.night()) }.onFailure { RideDiagnostics.warn("projection", "day/night not known: $it") }
                delay(setup.nightCheckMs)
            }
        }

        @Suppress("CyclomaticComplexMethod") // one branch per field or case of the format it reads or writes
        fun retune(settings: REauSettings) {
            val before = tuned
            if (setup.card != null || !started || before == null) return remap(settings.mappingFor(com.reau.app.aa.AaScreens.watch.screen.value))
            tuned = settings
            val steps = LiveTuning.steps(before, settings)
            if (steps.isNotEmpty()) RideDiagnostics.log("projection", "settings changed while running: ${steps.joinToString()}")
            if (LiveTuning.Step.REFIT in steps) remap(settings.mappingFor(com.reau.app.aa.AaScreens.watch.screen.value))
            // A burst of changes opens one session, a second after the last, with its values.
            if (LiveTuning.Step.NEW_ANDROID_AUTO_SESSION in steps) {
                sessionSettle.offer {
                    RideDiagnostics.log("projection", "a new Android Auto session for the settings, after 1s with no other change")
                    newAndroidAutoSession(settings)
                }
            }
            if (LiveTuning.Step.NEW_STREAM in steps) newStream(settings)
            if (LiveTuning.Step.NIGHT_NOW in steps) receiver?.let { r -> scope.launch { runCatching { r.setNight(setup.night()) } } }
            if (LiveTuning.Step.JOYSTICK in steps) joystick?.bindings = JoystickBindings.decode(settings.joystick)
            if (LiveTuning.Step.STATS_OVERLAY in steps) compositor.setOverlay(settings.overlay)
            // `tuned` (set above) is what the beat, the music card and the route read; the weather, just switched on, is fetched now.
            if (LiveTuning.Step.DASH_EXTRAS in steps && settings.dashWeather && !before.dashWeather) {
                scope.launch { runCatching { setup.refreshWeather() } }
            }
            if (LiveTuning.Step.WEB in steps) {
                web?.apply(settings.webView, settings.webTouch, settings.webAudio)
                // With the network server on the dash is not looked for; off, it is again.
                if (before.webView != settings.webView) report(ProjectionEvent.NetworkServer(settings.webView))
            }
        }

        /** What Android Auto is told when a session begins: a new session, told the new values; the fit waits for it. */
        fun newAndroidAutoSession(settings: REauSettings) {
            val config = LiveTuning.sessionConfig(aaConfig, settings)
            soundHeard.clear()
            aaConfig = config
            pendingMapping = settings.mappingFor(com.reau.app.aa.AaScreens.watch.screen.value)
            receiver?.reconfigure(config)
        }

        /** Android Auto's new session is up: the compositor takes its picture's size and the fit made for it. */
        fun applyPendingFit() {
            val mapping = pendingMapping ?: return
            pendingMapping = null
            compositor.setSource(com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT)
            remap(mapping)
        }

        /** The pace: the stream to the dash starts again with it (a keyframe first), the dash staying connected. */
        fun newStream(settings: REauSettings) {
            videoSettings = videoSettings.copy(
                ceilingFps = settings.fpsCeiling, clockSlowPercent = settings.clockSlowPercent,
                bitrateBps = settings.streamBitrateBps, bitrateMode = settings.bitrateMode, keyframeSec = settings.keyframeSec,
            )
            val running = loop ?: return
            running.stop()
            loop = newLoop()
        }

        /**
         * A video loop started on the dash session, or null when it could not start (no encoder). A loop that did
         * not start would leave the session streaming projection packets with no video behind them, which only
         * the dash's first-ack watchdog would end 15 s later; the session is closed at once instead, so the machine
         * sees a dropped dash and tries again (a new session, a new encoder) on its own pace.
         */
        fun newLoop(): VideoLoop? {
            val dash = object : VideoLoop.Dash {
                override fun sendRtp(packet: ByteArray) = session.sendRtp(packet)
                override val firstFrameConfirmed: Boolean get() = session.firstFrameConfirmed
                override val decodedFrameCount: Long get() = session.decodedFrameCount
            }
            val fresh = VideoLoop(compositor, dash, videoSettings, setup.thermalStatus, link = linkSample, navSample = navSample, stats = statsFeed)
            if (fresh.start()) return fresh
            RideDiagnostics.error("projection", "the video loop did not start; the dash session is closed to be tried again")
            session.disconnect()
            return null
        }
    }

    private companion object {
        val WHOLE_FRAME = RectPx(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H).let { FrameFit.Mapping(it, it) }
    }
}

/**
 * The quiet spell after the joystick is checked a few times per [idleMs] (at most every second); once it passed,
 * the touch ([com.reau.app.aa.FocusClearer.TAP]) goes to Android Auto through [send], one log line each time.
 */
private fun clearFocusWhenIdle(scope: CoroutineScope, focus: com.reau.app.aa.FocusClearer, idleMs: Long, send: (AaCommand) -> Unit) {
    val tick = (idleMs / FOCUS_CHECKS).coerceIn(FOCUS_TICK_MIN_MS, FOCUS_TICK_MAX_MS)
    scope.launch {
        while (isActive) {
            delay(tick)
            if (focus.tapDue(android.os.SystemClock.elapsedRealtime())) {
                com.reau.app.aa.FocusClearer.TAP.forEach(send)
                RideDiagnostics.log("aa", "focus: a touch cancelled at once, ${idleMs / FOCUS_LOG_S} s after the joystick")
            }
        }
    }
}

/** How often the weather is looked at (the fetch itself waits [com.reau.app.weather.WeatherKeeper.REFRESH_MS]). */
private const val WEATHER_POLL_MS = 60_000L
private const val FOCUS_CHECKS = 4
private const val FOCUS_TICK_MIN_MS = 50L
private const val FOCUS_TICK_MAX_MS = 1_000L
private const val FOCUS_LOG_S = 1_000

/** Android Auto's song on the dash's own music card; none stops repeating it. */
private fun DashSession.showNowPlayingCard(playing: com.reau.app.aa.AaNowPlaying?, art: com.reau.app.dash.AlbumArt?) {
    if (playing == null) {
        clearNowPlaying()
    } else {
        updateNowPlaying(playing.song, playing.album, playing.artist, playing.playing, art?.forDash(playing.art))
    }
}

/** The ringing call on the dash's own call card (a call with no name and no number shows [privateName]); none clears it. */
private fun DashSession.showCallerCard(caller: com.reau.app.aa.AaCaller?, privateName: String) {
    if (caller == null) clearCaller() else showCaller(caller.name.ifEmpty { privateName }, caller.inCall)
}

/** Whether the phone's default network works (validated, with the internet): the lost-signal icon asks. */
private fun phoneOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(true)

/** Android Auto's guidance on the dash's own nav bubble; none hides the bubble again. */
private fun DashSession.showGuidance(guidance: NavGuidance, extras: Boolean, noNetwork: Boolean) {
    val nav = AaManeuvers.toDash(guidance, noNetwork)
    if (nav == null) {
        clearNavInfo()
    } else {
        updateNavInfo(
            nav.maneuver, nav.primaryDist, nav.primaryUnit, nav.totalDist, nav.totalUnit, nav.eta, nav.road,
            nav.remainingSeconds, nav.next, extras,
        )
    }
}

/**
 * A call answered or ended by the joystick goes to Android Auto as the car's phone key first (it answers the voice
 * apps' calls too, on its own call card), then to the system's Telecom as before (which answers the phone's).
 */
private fun callByKeys(command: AaCommand, running: AaReceiver?, calls: LiveSides.Setup.Calls) {
    val keys = com.reau.app.aa.AaCallKeys.toAndroidAuto(command, running?.state == AaLinkState.ACTIVE)
    keys.forEach { key -> running?.sendInput(key) }
    val what = if (command == AaCommand.AnswerCall) "answer" else "end"
    val how = keys.firstOrNull()?.let { "key ${(it as AaCommand.Key).keycode} to Android Auto, then Telecom" } ?: "no Android Auto session, Telecom only"
    RideDiagnostics.log("call", "$what: $how")
    if (command == AaCommand.AnswerCall) calls.answer() else calls.end()
}
