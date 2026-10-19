package com.reau.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AndroidAuto
import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerTexts
import com.reau.app.data.RideDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On the emulator: REau's accessibility service is declared with what the walk needs and no more, and,
 * switched on, it walks a pretend settings screen built like Android Auto's (a "Mais opções" button in the activity's
 * window, the menu's items in a popup window of their own) and touches only the server's item. The emulator's
 * Android Auto is a stub without that menu, so the walk is pointed at this screen.
 */
@RunWith(AndroidJUnit4::class)
class AaServerAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val shell by lazy { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val component = ComponentName(context, AaServerAccessibilityService::class.java).flattenToString()
    private var before = ""
    private val clicked = Collections.synchronizedList(mutableListOf<String>())
    private var scenario: ActivityScenario<ComponentActivity>? = null
    @Volatile private var coverUpAtStart = false

    @Before
    fun setUp() {
        before = run("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        // On the rider's phone this service is switched on for good, and a running service answers the system's questions
        // with what it narrowed itself to (the calls switch), not with what the manifest declares. Every test starts
        // from the service not running, as on the emulator, and the tearDown puts the list back as it was.
        val others = before.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != component }
        if (others.size != before.split(':').count { it.isNotBlank() }) {
            run("settings put secure enabled_accessibility_services ${others.joinToString(":").ifEmpty { "null" }}")
            assertNotNull("the service did not stop", waitFor(SERVICE_WAIT_MS) { true.takeIf { AaServerStarter.driver == null } })
        }
    }

    @After
    fun tearDown() {
        AaServerAccessibilityService.target = AaServerAccessibilityService.Target(AndroidAuto.PACKAGE) { AndroidAuto.settingsIntent() }
        run("settings put secure enabled_accessibility_services ${before.ifEmpty { "null" }}")
        scenario?.close()
    }

    @Test
    fun theRunningServiceHearsTheVoiceAppsOnlyWithTheCallsSwitch() {
        // The manifest declares the most; the running service narrows it.
        run("settings put secure enabled_accessibility_services $component")
        run("settings put secure accessibility_enabled 1")
        assertNotNull("the accessibility service did not connect", waitFor(SERVICE_WAIT_MS) { AaServerStarter.driver })
        AaServerAccessibilityService.followCallsSwitch(false)
        val off = AccessibilityScope.packages(answerCalls = false)
        assertEquals(off, waitFor(SERVICE_WAIT_MS) { AaServerAccessibilityService.testOnlyHeard()?.takeIf { it == off } })
        AaServerAccessibilityService.followCallsSwitch(true)
        val on = AccessibilityScope.packages(answerCalls = true)
        assertEquals(on, waitFor(SERVICE_WAIT_MS) { AaServerAccessibilityService.testOnlyHeard()?.takeIf { it == on } })
    }

    @Test
    fun theServiceHearsAndroidAutoAndTheVoiceAppsAndReadsItsWindows() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        // Android writes the id short ("com.reau.app/.service…"): compared as components.
        val mine = manager.installedAccessibilityServiceList.firstOrNull { ComponentName.unflattenFromString(it.id)?.flattenToString() == component }
        assertNotNull("REau's accessibility service is not installed", mine)
        val info = checkNotNull(mine)
        // Changed and declared (it was Android Auto alone): the voice apps too, for their call notifications,
        // and Google Maps, whose screen is a display of its own
        assertEquals(setOf(AndroidAuto.PACKAGE) + com.reau.app.phone.CallNotice.VOICE_APPS + com.reau.app.aa.AaScreenRule.OWN_DISPLAY_APPS, info.packageNames?.toSet())
        assertTrue(info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0)
        assertEquals(0, info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
        assertTrue(info.loadDescription(context.packageManager).toString().contains("não lê nem toca em outros apps"))
    }

    @Test
    fun theWalkTouchesStartAndNothingElse() {
        val outcome = walk(listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Iniciar servidor da unidade principal", "Sair do modo de desenvolvedor"))
        assertEquals(ServerMenuRun.Outcome.TAPPED_START, outcome)
        assertEquals(listOf("Iniciar servidor da unidade principal"), clicked.toList())
    }

    @Test
    fun aMenuWithoutTheServerItemIsLeftUntouched() {
        val outcome = walk(listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Sair do modo de desenvolvedor"))
        assertEquals(ServerMenuRun.Outcome.UNKNOWN_MENU, outcome)
        assertTrue("touched: $clicked", clicked.isEmpty())
    }

    @Test
    fun switchingOffTouchesStop() {
        val outcome = walk(listOf("Ajuda e feedback", "Parar servidor da unid. princ.", "Sair do modo de desenvolvedor"), ServerMenuRun.Mode.STOP)
        assertEquals(ServerMenuRun.Outcome.TAPPED_STOP, outcome)
        assertEquals(listOf("Parar servidor da unid. princ."), clicked.toList())
        assertFalse(clicked.contains("Sair do modo de desenvolvedor"))
    }

    /**
     * The cover must not be slow to appear: it is up the moment the walk begins, before Android Auto's
     * settings are even asked to open, and the ride log says how long it took to be drawn, to measure it on the phone.
     */
    @Test
    fun theCoverIsUpAsTheWalkBeginsAndTheLogSaysWhenItWasDrawn() {
        val logOn = RideDiagnostics.enabled
        RideDiagnostics.enabled = true
        try {
            RideDiagnostics.startProcess("test") // what REauApp does; nothing if the app's file is already open
            RideDiagnostics.log("ui", "before the cover")
            coverWalkLogged()
        } finally {
            RideDiagnostics.enabled = logOn
        }
    }

    private fun coverWalkLogged() {
        assertEquals(ServerMenuRun.Outcome.TAPPED_START, walk(listOf("Configurações do desenvolvedor", "Iniciar servidor da unidade principal")))
        assertTrue("the cover was up when the walk began", coverUpAtStart)
        RideDiagnostics.logAndWait("ui", "after the cover")
        val log = File(context.filesDir, "diag").listFiles { f -> f.name.startsWith("ride-") }?.maxByOrNull { it.lastModified() }?.readText().orEmpty()
        val after = log.substringAfterLast("before the cover")
        assertTrue("the ride log says when the cover was drawn: $after", Regex("the cover drawn \\d+ ms after the walk began").containsMatchIn(after))
    }

    // ── the cover at the rider's tap (it has to look quick) ──────────────────────────────────────────────────────

    private fun connected(): AaServerStarter.MenuDriver {
        run("settings put secure enabled_accessibility_services $component")
        run("settings put secure accessibility_enabled 1")
        return checkNotNull(waitFor(SERVICE_WAIT_MS) { AaServerStarter.driver }) { "the accessibility service did not connect" }
    }

    private fun coverShown(): Boolean {
        var shown = false
        instrumentation.runOnMainSync { shown = AaServerAccessibilityService.testOnlyCoverShown() }
        return shown
    }

    private fun coverGoneWithin(ms: Long): Boolean = waitFor(ms) { true.takeIf { !coverShown() } } == true

    @Test
    fun aCoverHeldAtTheTapIsUpAtOnceAndGoesWhenDropped() {
        val driver = connected()
        var up = false
        instrumentation.runOnMainSync {
            driver.holdCover(ServerMenuRun.Mode.STOP, HOLD_MS)
            up = AaServerAccessibilityService.testOnlyCoverShown()
        }
        assertTrue("up in the same instant as the tap's call", up)
        instrumentation.runOnMainSync { driver.dropCover() }
        assertTrue("gone once no walk comes", coverGoneWithin(GONE_MS))
    }

    @Test
    fun aCoverHeldAtTheTapGoesByItselfWhenNoWalkComes() {
        val driver = connected()
        instrumentation.runOnMainSync { driver.holdCover(ServerMenuRun.Mode.START, SHORT_HOLD_MS) }
        assertTrue(coverShown())
        assertTrue("gone after its own wait", coverGoneWithin(SHORT_HOLD_MS + GONE_MS))
    }

    @Test
    fun aWalkTakesOverTheCoverHeldAtTheTapAndItGoesAfterTheWalk() {
        val driver = connected()
        instrumentation.runOnMainSync { driver.holdCover(ServerMenuRun.Mode.START, HOLD_MS) }
        assertEquals(ServerMenuRun.Outcome.TAPPED_START, walk(listOf("Configurações do desenvolvedor", "Iniciar servidor da unidade principal")))
        assertTrue("the cover never left between the tap and the walk", coverUpAtStart)
        assertTrue("gone a second after the walk, not after the tap's wait", coverGoneWithin(GONE_AFTER_WALK_MS))
    }

    /** Opens the pretend screen with [items] in its menu, switches the service on and walks it; the walk's outcome. */
    private fun walk(items: List<String>, mode: ServerMenuRun.Mode = ServerMenuRun.Mode.START): ServerMenuRun.Outcome {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val overflow = Button(activity).apply {
                text = "⋮"
                contentDescription = "Mais opções"
                setOnClickListener { view ->
                    PopupMenu(activity, view).apply {
                        items.forEach { menu.add(it) }
                        setOnMenuItemClickListener { item -> clicked.add(item.title.toString()) }
                    }.show()
                }
            }
            activity.setContentView(LinearLayout(activity).apply { addView(overflow) })
        }
        val packageName = context.packageName
        AaServerAccessibilityService.target = AaServerAccessibilityService.Target(packageName) {
            Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        run("settings put secure enabled_accessibility_services $component")
        run("settings put secure accessibility_enabled 1")
        val driver = waitFor(SERVICE_WAIT_MS) { AaServerStarter.driver }
        assertNotNull("the accessibility service did not connect", driver)

        val outcome = AtomicReference<ServerMenuRun.Outcome>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val run = ServerMenuRun(mode, ServerTexts.table(), SystemClock.elapsedRealtime(), target = packageName)
            assertTrue(checkNotNull(driver).drive(run) { outcome.set(it); done.countDown() })
            coverUpAtStart = AaServerAccessibilityService.testOnlyCoverShown()
        }
        assertTrue("the walk did not end", done.await(WALK_WAIT_MS, TimeUnit.MILLISECONDS))
        return outcome.get()
    }

    private fun <T> waitFor(ms: Long, value: () -> T?): T? {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            value()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        return value()
    }

    private fun run(command: String): String =
        shell.executeShellCommand(command).let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() } }

    private companion object {
        const val SERVICE_WAIT_MS = 10_000L
        const val WALK_WAIT_MS = 20_000L
        const val POLL_MS = 100L
        const val HOLD_MS = 10_000L
        const val SHORT_HOLD_MS = 500L
        const val GONE_MS = 1_000L
        const val GONE_AFTER_WALK_MS = 3_000L
    }
}
