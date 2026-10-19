package com.reau.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.R
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.ui.theme.REauTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The redesign's components: what each one does when touched, in the Aurum theme.
 */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { REauTheme(dark = true, content = content) }

    @Test
    fun theFineCounterStepsByOneAndByTenAndStaysInsideItsRange() {
        var value by mutableIntStateOf(5)
        show { FineStepper("Tamanho do recorte", "%", value, 0..30, { value = it }, "crop") }
        fun press(tag: String) = compose.onNodeWithTag(tag).performClick()
        press("crop_plus10"); press("crop_plus10")
        compose.onNodeWithTag("crop_value").assertTextEquals("25")
        press("crop_plus10")
        compose.onNodeWithTag("crop_value").assertTextEquals("30")
        press("crop_minus")
        compose.onNodeWithTag("crop_value").assertTextEquals("29")
        press("crop_plus")
        compose.onNodeWithTag("crop_value").assertTextEquals("30")
        repeat(4) { press("crop_minus10") }
        compose.onNodeWithTag("crop_value").assertTextEquals("0")
    }

    @Test
    fun anUnavailableShortcutSaysWhyInsteadOfOpening() {
        var enabled by mutableStateOf(false)
        var opened = 0
        val reasons = mutableListOf<String>()
        show {
            ShortcutTile(
                R.drawable.ic_ms_smartphone, "Tela do Android Auto", "Ver e encaixar a imagem", { opened++ },
                enabled = enabled, reason = "Ligue a projeção para ver", onUnavailable = { reasons += it }, tag = "tile_aa",
            )
        }
        compose.onNodeWithTag("tile_aa_sub", useUnmergedTree = true).assertTextEquals("Ligue a projeção para ver")
        compose.onNodeWithTag("tile_aa").performClick()
        assertEquals(0, opened)
        assertEquals(listOf("Ligue a projeção para ver"), reasons)
        enabled = true
        compose.onNodeWithTag("tile_aa_sub", useUnmergedTree = true).assertTextEquals("Ver e encaixar a imagem")
        compose.onNodeWithTag("tile_aa").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theWholeRowSwitchesAndTheInfoShowsTheWholeTextWithoutSwitching() {
        var on by mutableStateOf(false)
        val text = "Só serve para ler a lista de redes Wi-Fi no Android 12 ou mais velho. O REau não lê a sua posição."
        show { SwitchRow("Usar a localização", on, { on = it }, "use_location", sub = "Desligada", info = Info("Usar a localização", text)) }
        compose.onNodeWithTag("use_location").assertIsOff().performClick()
        compose.onNodeWithTag("use_location").assertIsOn()
        compose.onNodeWithTag("use_location_info").performClick()
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithTag("info_sheet_ok").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("info_sheet").assertDoesNotExist()
        compose.onNodeWithTag("use_location").assertIsOn()
    }

    @Test
    fun aSegmentPicksItsOption() {
        var picked by mutableIntStateOf(0)
        show {
            SegmentedRow("Mapa", listOf("Automático" to "theme_AUTO", "Sempre dia" to "theme_DAY", "Sempre noite" to "theme_NIGHT"), picked, { picked = it })
        }
        compose.onNodeWithTag("theme_NIGHT").performClick()
        assertEquals(2, picked)
        compose.onNodeWithTag("theme_NIGHT").assertIsSelected()
    }

    @Test
    fun aLockedDirectionShowsTheLockAndTheOthersAreChosenByATap() {
        val chosen = mutableListOf<Control>()
        val labels = Control.entries.associateWith { it.name }
        show { DPadMap(labels, setOf(Control.UP), Control.LEFT, { chosen += it }) }
        compose.onNodeWithTag("dpad_up").assertContentDescriptionContains("Travado", substring = true)
        compose.onNodeWithTag("dpad_left").assertIsSelected()
        compose.onNodeWithTag("dpad_right").performClick()
        compose.onNodeWithTag("dpad_up").performClick()
        assertEquals(listOf(Control.RIGHT, Control.UP), chosen)
    }

    @Test
    fun aPermissionOpensWhatItIsForAndAsksOnlyWhenMissing() {
        var granted by mutableStateOf(false)
        var asked = 0
        show {
            PermissionRow("Localização", "ACCESS_FINE_LOCATION", granted, if (granted) "Liberada" else "Não liberada",
                "Só no Android 12 ou mais velho.", { asked++ }, "perm_location")
        }
        compose.onNodeWithTag("perm_location_why", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("perm_location").performClick()
        compose.onNodeWithTag("perm_location_why", useUnmergedTree = true).assertTextEquals("Só no Android 12 ou mais velho.")
        compose.onNodeWithTag("perm_location_ask").performClick()
        assertEquals(1, asked)
        granted = true
        compose.onNodeWithTag("perm_location_state", useUnmergedTree = true).assertTextEquals("Liberada")
        compose.onNodeWithTag("perm_location_ask").assertDoesNotExist()
    }

    @Test
    fun aBlockShowsItsWholeTextWhenOpen() {
        var open by mutableStateOf(false)
        show { ExpandableBlock(R.drawable.ic_ms_lock, "O que fica no celular", "Tudo o que o REau guarda.", open, { open = !open }, "block_kept") }
        compose.onNodeWithTag("block_kept_text").assertDoesNotExist()
        compose.onNodeWithTag("block_kept_head").performClick()
        compose.onNodeWithTag("block_kept_text").assertTextEquals("Tudo o que o REau guarda.")
    }

    @Test
    fun theMainButtonIsLigarAndThenDesligarWithItsTime() {
        var on by mutableStateOf(false)
        var pressed = 0
        show { PrimaryPowerButton(on, if (on) "04:12" else null, { pressed++ }) }
        compose.onNodeWithTag("toggle").assertTextContains("Ligar").performClick()
        assertEquals(1, pressed)
        on = true
        compose.onNodeWithTag("toggle").assertTextContains("Desligar")
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertTextEquals("04:12")
    }

    @Test
    fun theMainButtonWhileTurningOffIsDisabledAndSaysDesligando() {
        var pressed = 0
        show { PrimaryPowerButton(false, null, { pressed++ }, stopping = true) }
        compose.onNodeWithTag("toggle").assertTextContains("Desligando", substring = true).assertIsNotEnabled().performClick()
        assertEquals("a tap that cannot start anything is not taken", 0, pressed)
    }

    @Test
    fun theBridgeCardCarriesTheHeadlineAndBothSides() {
        show {
            BridgeCard(
                "Transmitindo", "Waze no painel · 6 quadros/s",
                BridgeSide("Android Auto", "Ativo", StatusKind.OK, "side_android_auto"),
                BridgeSide("Painel", "Pronto", StatusKind.OK, "side_dash"),
                on = true, elapsed = "00:07", onToggle = {},
            )
        }
        compose.onNodeWithTag("status").assertTextEquals("Transmitindo")
        compose.onNodeWithTag("side_android_auto").assertTextContains("ANDROID AUTO").assertTextContains("Ativo")
        compose.onNodeWithTag("side_dash").assertTextContains("PAINEL").assertTextContains("Pronto")
    }

    @Test
    fun aWarningsLoneSecondaryActionWorksAndThePrimaryToo() {
        val done = mutableListOf<String>()
        show {
            WarningCard(
                StatusKind.ERROR, R.drawable.ic_ms_error, "Servidor do Android Auto desligado", "A tentativa automática parou.",
                CardAction("Tentar de novo", "retry") { done += "retry" }, CardAction("Abrir Acessibilidade", "open") { done += "open" },
            )
        }
        compose.onNodeWithTag("retry").performClick()
        compose.onNodeWithTag("open").performClick()
        assertEquals(listOf("retry", "open"), done)
    }

    @Test
    fun thePasswordFieldTakesTheTextAndItsEyeIsThere() {
        var typed by mutableStateOf("")
        show { FieldRow("Senha do Wi-Fi", typed, { typed = it }, "dash_password", password = true) }
        compose.onNodeWithTag("dash_password").performTextInput("segredo")
        assertEquals("segredo", typed)
        compose.onNodeWithContentDescription("Mostrar a senha").performClick()
        compose.onNodeWithContentDescription("Esconder a senha").assertIsDisplayed()
    }
}
