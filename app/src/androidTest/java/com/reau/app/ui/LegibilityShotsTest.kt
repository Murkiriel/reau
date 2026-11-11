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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
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
 * The legibility pictures: the screens the legibility pass touches, on a 360 x 640 dp phone, in both themes and with the
 * font at 1.0 and at 1.3. They are saved in the app's files, "legibilidade/<label>/<screen>_<theme>_f<scale>.png", where
 * `<label>` is the instrumentation argument `fotos` ("depois" when absent), to be pulled with
 * `adb -s emulator-NNNN exec-out run-as com.reau.app cat …` and compared before and after. A screen that cannot be drawn fails.
 */
@RunWith(AndroidJUnit4::class)
class LegibilityShotsTest {
    @get:Rule val compose = createComposeRule()

    private val label = InstrumentationRegistry.getArguments().getString("fotos") ?: "depois"
    private val out = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "legibilidade/$label").apply { mkdirs() }

    private val rows = AppPermissions.rows(
        requested = listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"),
        granted = { it != "android.permission.RECORD_AUDIO" },
        batteryExempt = false,
    )

    /** A screen, and the tag to scroll to before the picture (the part the pass changed), or null to keep the top. */
    private class Shot(val name: String, val scrollTo: String?, val content: @Composable () -> Unit)

    private val shots = listOf(
        Shot("main_parado", null) { MainScreen(MainUiState(Projection(), "17.9.664004"), {}, {}, {}) },
        Shot("main_tres_avisos", null) {
            val serverOff = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF, dash = DashLink.READY)
            MainScreen(
                MainUiState(serverOff, "17.9.664004", startedAtMs = 0, battery = BatteryUi(exempt = false, saverOn = true), vpnHoldsREau = true),
                {}, {}, {}, clock = { 61_000L },
            )
        },
        Shot("main_ligado", null) {
            val running = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = DashLink.READY)
            MainScreen(MainUiState(running, "17.9.664004", startedAtMs = 0, dashName = "RE_AB12_345678"), {}, {}, {}, clock = { 252_000L })
        },
        Shot("ajustes_idioma", "language") { SettingsScreen(REauSettings(), SettingsActions({}, {}, {}, {})) },
        Shot("encaixe_contadores", "density_plus") {
            val s = REauSettings()
            AndroidAutoScreen({}, touch = true, mask = AaScreenMask(true, s.mapping, 800, 480) {}, fit = AaScreenFit(s) {})
        },
        Shot("permissoes", null) { PermissionsScreen(rows, PermissionsActions(), sdk = 34) },
        Shot("termos", null) { TermsScreen(gate = true, onAccept = {}, onDecline = {}, onBack = {}) },
        Shot("joystick", null) { JoystickScreen(REauSettings(), {}, {}) },
    )

    @Test
    fun theScreensAreDrawnOnASmallPhoneInBothThemesAndFontScales() {
        var shot by mutableStateOf(shots.first())
        var night by mutableStateOf(true)
        var scale by mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalREauNight provides night) {
                PhoneFrame(fontScale = scale) { shot.content() }
            }
        }
        for (s in shots) {
            for (fontScale in listOf(1f, 1.3f)) {
                for (dark in listOf(true, false)) {
                    shot = s
                    night = dark
                    scale = fontScale
                    compose.waitForIdle()
                    if (s.scrollTo != null) compose.onNodeWithTag(s.scrollTo).performScrollTo()
                    compose.waitForIdle()
                    val picture = compose.onNodeWithTag(PHONE_TAG).captureToImage().asAndroidBitmap()
                    assertTrue(s.name, picture.width > 0 && picture.height > 0)
                    val file = File(out, "${s.name}_${if (dark) "escuro" else "claro"}_f${if (fontScale == 1f) "1.0" else "1.3"}.png")
                    file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
