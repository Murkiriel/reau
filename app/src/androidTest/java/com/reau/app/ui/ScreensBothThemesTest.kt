package com.reau.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.ServerResult
import com.reau.app.service.AaServerStarter
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.settings.AppPermissions
import com.reau.app.settings.REauSettings
import com.reau.app.ui.theme.LocalREauNight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The redesign: every screen drawn in the dark and in the light theme, each saved as a picture
 * in the app's files ("screens/<screen>_<theme>.png", read with `adb exec-out run-as com.reau.app cat …`) to compare
 * with the prototypes; and a screen that cannot be drawn in either theme fails here.
 */
@RunWith(AndroidJUnit4::class)
class ScreensBothThemesTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.filesDir, "screens").apply { mkdirs() }

    private val logs = File(context.cacheDir, "screens-logs").apply {
        deleteRecursively()
        mkdirs()
        File(this, "ride-20261010-073000.log").writeText((1..400).joinToString("") { "07:30:00.000  [ui] line $it\n" })
        File(this, "ride-20261009-121300.log").writeText("an older ride\n")
    }

    private val rows = AppPermissions.rows(
        requested = listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"),
        granted = { it != "android.permission.RECORD_AUDIO" },
        accessibilityOn = true,
        batteryExempt = false,
    )

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "main_stopped" to { MainScreen(MainUiState(Projection(), "17.9.664004", battery = BatteryUi(exempt = false)), {}, {}, {}) },
        "main_streaming" to {
            val running = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY)
            MainScreen(MainUiState(running, "17.9.664004", startedAtMs = 0, dashName = "RE_AB12_345678"), {}, {}, {}, clock = { 252_000L })
        },
        "main_problem" to {
            val serverOff = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF, dash = DashLink.READY)
            val server = ServerUi(auto = true, result = ServerResult.LOCKED, accessibility = AaServerStarter.Accessibility.CONNECTED)
            MainScreen(MainUiState(serverOff, "17.9.664004", server = server, startedAtMs = 0), {}, {}, {}, clock = { 61_000L })
        },
        "settings" to { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) },
        "android_auto" to {
            val s = REauSettings()
            AndroidAutoScreen({}, touch = true, mask = AaScreenMask(true, s.mapping, 800, 480) {}, fit = AaScreenFit(s) {})
        },
        "joystick" to { JoystickScreen(REauSettings(), {}, {}) },
        "permissions" to { PermissionsScreen(rows, PermissionsActions(), sdk = 34) },
        "about_data" to { AboutDataScreen(onBack = {}) },
        "terms" to { TermsScreen(gate = true, onAccept = {}, onDecline = {}, onBack = {}) },
        "credits" to { CreditsScreen({}, {}) },
        "log" to { LogViewerScreen(logs, {}) },
    )

    @Test
    fun everyScreenIsDrawnInBothThemes() {
        var screen by mutableStateOf(screens.first().second)
        var night by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalREauNight provides night) { screen() } }
        for ((name, content) in screens) {
            for (dark in listOf(true, false)) {
                screen = content
                night = dark
                compose.waitForIdle()
                val picture = compose.onRoot().captureToImage().asAndroidBitmap()
                assertTrue(name, picture.width > 0 && picture.height > 0)
                File(out, "${name}_${if (dark) "escuro" else "claro"}.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
