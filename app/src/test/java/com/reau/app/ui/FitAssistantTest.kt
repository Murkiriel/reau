package com.reau.app.ui

import com.reau.app.aa.AaScreen
import com.reau.app.settings.Fit
import com.reau.app.settings.REauSettings
import com.reau.app.ui.FitAssistant.State
import com.reau.app.ui.FitAssistant.Step
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-time fit assistant of the "Tela do Android Auto" (13/11/2026): four short steps, a question for the maneuver and one for the
 * speed, and behind each "no" only counters the navigation tab already has. It sets nothing of the fit by itself; it opens by itself only
 * when the screen is told so (a phone that never saved a fit), and by hand from its quiet button.
 */
class FitAssistantTest {
    @Test
    fun `four steps, the start, the maneuver, the speed and the end, and only the two in the middle ask`() {
        assertEquals(listOf(Step.START, Step.MANEUVER, Step.SPEED, Step.END), Step.entries.toList())
        assertEquals(listOf(Step.MANEUVER, Step.SPEED), Step.entries.filter { it.asks })
    }

    @Test
    fun `it starts at the first step, with no counters open`() {
        assertEquals(State(Step.START, adjusting = false), State())
    }

    @Test
    fun `yes all the way goes through the four steps and stays at the end`() {
        var state = State()
        val seen = mutableListOf(state.step)
        repeat(5) {
            state = state.forward()
            seen += state.step
            assertFalse(state.adjusting)
        }
        assertEquals(listOf(Step.START, Step.MANEUVER, Step.SPEED, Step.END, Step.END, Step.END), seen)
    }

    @Test
    fun `a no opens the counters of the same step, and the next step starts at its question`() {
        val no = State(Step.MANEUVER).adjust()
        assertEquals(State(Step.MANEUVER, adjusting = true), no)
        assertEquals(State(Step.SPEED, adjusting = false), no.forward())
        assertEquals(State(Step.SPEED, adjusting = true), State(Step.SPEED).adjust())
        assertEquals(State(Step.END), State(Step.SPEED, adjusting = true).forward())
    }

    @Test
    fun `a step that asks nothing has no counters to open`() {
        assertEquals(State(Step.START), State(Step.START).adjust())
        assertEquals(State(Step.END), State(Step.END).adjust())
    }

    @Test
    fun `back closes the counters first, then goes a step back, and stays at the start`() {
        assertEquals(State(Step.SPEED), State(Step.SPEED, adjusting = true).back())
        assertEquals(State(Step.MANEUVER), State(Step.SPEED).back())
        assertEquals(State(Step.START), State(Step.MANEUVER).back())
        assertEquals(State(Step.START), State(Step.START).back())
        assertEquals(State(Step.SPEED), State(Step.END).back())
    }

    @Test
    fun `a fit has the counters of its mode, the crop's size and place or the move`() {
        assertEquals(listOf(FitControl.CROP_SIZE, FitControl.CROP_X, FitControl.CROP_Y), FitControl.of(Fit.CROP))
        assertEquals(listOf(FitControl.MOVE_X, FitControl.MOVE_Y), FitControl.of(Fit.WHOLE_VISIBLE))
    }

    @Test
    fun `the maneuver offers the place, the speed the size and the height, and the other steps nothing`() {
        assertEquals(listOf(FitControl.CROP_X, FitControl.CROP_Y), FitAssistant.controls(Step.MANEUVER, Fit.CROP))
        assertEquals(listOf(FitControl.CROP_SIZE, FitControl.CROP_Y), FitAssistant.controls(Step.SPEED, Fit.CROP))
        for (step in listOf(Step.MANEUVER, Step.SPEED)) {
            assertEquals("$step", listOf(FitControl.MOVE_X, FitControl.MOVE_Y), FitAssistant.controls(step, Fit.WHOLE_VISIBLE))
        }
        for (fit in Fit.entries) {
            assertEquals(emptyList<FitControl>(), FitAssistant.controls(Step.START, fit))
            assertEquals(emptyList<FitControl>(), FitAssistant.controls(Step.END, fit))
        }
    }

    @Test
    fun `the assistant never offers a counter the fit's own tab does not have`() {
        for (step in Step.entries) for (fit in Fit.entries) {
            assertTrue("$step $fit", FitControl.of(fit).containsAll(FitAssistant.controls(step, fit)))
        }
    }

    @Test
    fun `starting turns the mask on and touches nothing else`() {
        val off = REauSettings(dashMask = false, density = 200, cropPercent = 70, navFit = REauSettings.DEFAULT_NAV_FIT.copy(cropX = 10, cropPercent = 60))
        assertEquals(off.copy(dashMask = true), FitAssistant.started(off))
        val on = REauSettings(density = 200)
        assertEquals(on, FitAssistant.started(on))
        assertEquals("the owner's defaults are left as they are", REauSettings(), FitAssistant.started(REauSettings()))
    }

    @Test
    fun `only a navigation app on Android Auto is the screen the questions are about`() {
        assertTrue(FitAssistant.navigationShown(AaScreen.NAV))
        for (screen in AaScreen.entries - AaScreen.NAV) assertFalse("$screen", FitAssistant.navigationShown(screen))
    }

    @Test
    fun `the rider is told when the density asked is not the one the factory fit was measured at`() {
        assertTrue(FitAssistant.densityMeasured(REauSettings()))
        assertTrue(FitAssistant.densityMeasured(REauSettings(density = 180)))
        for (density in listOf(80, 170, 190, 240)) assertFalse("$density", FitAssistant.densityMeasured(REauSettings(density = density)))
    }

    private val panel = File("src/main/java/com/reau/app/ui/FitPanel.kt").readText()
    private val card = File("src/main/java/com/reau/app/ui/FitAssistantCard.kt").readText()
    private val activity = File("src/main/java/com/reau/app/MainActivity.kt").readText()

    @Test
    fun `the panel opens it by itself only when it is told so, and by default it is not`() {
        assertTrue(panel.contains("var assisting by rememberSaveable { mutableStateOf(assistant.pending) }"))
        assertFalse("nobody is asked unless the screen says so", FitAssistantAsk().pending)
        assertTrue(panel.contains("assistant: FitAssistantAsk = FitAssistantAsk()"))
        assertTrue(File("src/main/java/com/reau/app/ui/AaScreenFit.kt").readText().contains("val assistant: FitAssistantAsk = FitAssistantAsk(),"))
    }

    @Test
    fun `the app asks the store whether it is pending, and tells the store when it was seen`() {
        assertTrue(activity.contains("val assistantPending = remember { store.fitAssistantPending() }"))
        assertTrue(activity.contains("FitAssistantAsk(assistantPending, store::fitAssistantSeen)"))
    }

    @Test
    fun `it is one card under Voltar ao padrão and over the fit, or its quiet button`() {
        val reset = panel.indexOf("\"fit_reset\"")
        val shown = panel.indexOf("if (assisting) {")
        val open = panel.indexOf("\"fit_assistant_open\", { assisting = true })")
        assertTrue(reset in 1 until shown)
        assertTrue("the button is the other branch", shown < open && panel.substring(shown, open).contains("} else {"))
        assertTrue(open < panel.indexOf("FitBlocks(tab"))
        assertEquals("one button", 1, Regex("\"fit_assistant_open\"").findAll(panel).count())
        assertEquals("one card", 1, Regex("""FitAssistantCard\(s, change, screen\)""").findAll(panel).count())
    }

    @Test
    fun `finishing or skipping closes the card and tells the screen once`() {
        val closing = panel.substring(panel.indexOf("FitAssistantCard(s, change, screen) {"), panel.indexOf("\"fit_assistant_open\""))
        assertTrue(closing.contains("assisting = false"))
        assertTrue(closing.contains("assistant.seen()"))
        assertEquals(1, Regex("""assistant\.seen\(\)""").findAll(panel).count())
        assertTrue("skip", card.contains("QuietButton(stringResource(R.string.fit_assistant_skip), \"fit_assistant_skip\", onClose)"))
        assertTrue("finish", card.contains("CardAction(stringResource(R.string.fit_assistant_finish), \"fit_assistant_finish\", onClose)"))
        assertEquals("nothing else closes it", 2, Regex("""[ ,(]onClose\)""").findAll(card.substringAfter("private fun AssistantButtons")).count())
    }

    @Test
    fun `the card changes the settings in two places only, the navigation fit's counters and the mask at the start`() {
        val changes = Regex("""change\(([^)]*)""").findAll(card).map { it.groupValues[1] }.toList()
        assertEquals(listOf("it", "s.copy(navFit = it"), changes)
        assertTrue(card.contains("FitCounters(s.navFit, s.content, controls, { change(s.copy(navFit = it)) }, tags = \"fit_assistant_\")"))
        assertTrue(card.contains("{ FitAssistant.started(s).let { if (it != s) change(it) } }"))
        assertTrue("the counters are the ones of the step", card.contains("val controls = FitAssistant.controls(step, s.navFit.fit)"))
        for (direct in listOf("FineStepper(", "cropX =", "cropY =", "cropPercent =", "moveX =", "moveY =", "zoom =", "density =", "FrameFit.")) {
            assertFalse("the card sets no value of its own: $direct", card.contains(direct))
        }
    }

    @Test
    fun `the tab and the assistant draw the same counters`() {
        assertTrue(panel.contains("Card { FitCounters(p, s.content, FitControl.of(p.fit), set) }"))
        assertEquals("the counters are written once", 1, Regex("""fun FitCounters\(""").findAll(panel).count())
        for (tag in listOf("crop", "crop_center_x", "crop_center_y", "move_x", "move_y")) {
            assertTrue(tag, panel.contains("\"\${tags}$tag\""))
        }
    }

    @Test
    fun `the counters open only after a no, and the first step starts the mask`() {
        assertTrue(card.contains("if (state.adjusting) AssistantCounters(state.step, s, change)"))
        val begin = card.substring(card.indexOf("\"fit_assistant_begin\""), card.indexOf("state.adjusting -> GoldButton"))
        assertTrue(begin.contains("onStart()") && begin.contains("go(state.forward())"))
        assertTrue(card.contains("\"fit_assistant_no\") { go(state.adjust()) }"))
        assertTrue(card.contains("\"fit_assistant_yes\") { go(state.forward()) }"))
        assertTrue(card.contains("\"fit_assistant_good\") { go(state.forward()) }"))
        assertTrue(card.contains("\"fit_assistant_back\") { go(state.back()) }"))
    }

    @Test
    fun `every button of the assistant is at least 48 dp tall`() {
        assertTrue(card.contains("TextButton(onClick, Modifier.heightIn(min = REauSpace.touch).testTag(tag))"))
        assertEquals("no other text button", 1, Regex("""TextButton\(""").findAll(card).count())
        val cards = File("src/main/java/com/reau/app/ui/components/Cards.kt").readText()
        for (button in listOf("fun GoldButton", "fun SupportButton", "fun GhostButton")) {
            assertTrue(button, cards.substringAfter(button).substringBefore("\n}\n").contains("heightIn(min = REauSpace.touch)"))
        }
        assertEquals(48f, com.reau.app.ui.theme.REauSpace.touch.value, 0f)
        assertFalse("no bare clickable in the card", card.contains("clickable("))
    }

    @Test
    fun `it says what is in the way, no navigation on the screen and a density nobody measured`() {
        assertTrue(card.contains("if (!FitAssistant.navigationShown(screen) && state.step != FitAssistant.Step.END) {\n            Note(stringResource(R.string.fit_assistant_no_nav)"))
        assertTrue(card.contains("if (!FitAssistant.densityMeasured(s) && state.step == FitAssistant.Step.START) {"))
        assertTrue(card.contains("stringResource(R.string.fit_assistant_density, s.density, FitDefaults.MEASURED_DENSITY)"))
    }

    private fun text(folder: String, name: String): String {
        val strings = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no $name in $folder")
    }

    @Test
    fun `the texts are in the three languages and say what the rider is to look at`() {
        val words = mapOf(
            "values-pt" to mapOf(
                "fit_assistant_start_text" to listOf("Waze", "Maps", "rota", "parte clara", "Máscara"),
                "fit_assistant_maneuver_text" to listOf("seta da manobra", "inteiras", "?"),
                "fit_assistant_speed_text" to listOf("velocidade", "inteira", "?"),
                "fit_assistant_end_text" to listOf("já está salvo", "moto parada", "Assistente de encaixe", "aba Navegação"),
                "fit_assistant_no_nav" to listOf("Waze", "Maps"),
                "fit_assistant_adjust_tip" to listOf("na hora", "já fica salvo"),
                "fit_assistant_size_tip" to listOf("recorte menor mostra mais", "já fica salvo"),
            ),
            "values" to mapOf(
                "fit_assistant_start_text" to listOf("Waze", "Maps", "route", "clear part", "Mask"),
                "fit_assistant_maneuver_text" to listOf("maneuver arrow", "whole", "?"),
                "fit_assistant_speed_text" to listOf("speed", "whole", "?"),
                "fit_assistant_end_text" to listOf("already saved", "bike stopped", "Fit assistant", "Navigation tab"),
                "fit_assistant_no_nav" to listOf("Waze", "Maps"),
                "fit_assistant_adjust_tip" to listOf("at once", "already saved"),
                "fit_assistant_size_tip" to listOf("smaller crop shows more", "already saved"),
            ),
            "values-es" to mapOf(
                "fit_assistant_start_text" to listOf("Waze", "Maps", "ruta", "parte clara", "Máscara"),
                "fit_assistant_maneuver_text" to listOf("flecha de la maniobra", "enteras", "?"),
                "fit_assistant_speed_text" to listOf("velocidad", "entera", "?"),
                "fit_assistant_end_text" to listOf("ya está guardado", "moto parada", "Asistente de ajuste", "pestaña Navegación"),
                "fit_assistant_no_nav" to listOf("Waze", "Maps"),
                "fit_assistant_adjust_tip" to listOf("al instante", "ya queda guardado"),
                "fit_assistant_size_tip" to listOf("recorte más pequeño muestra más", "ya queda guardado"),
            ),
        )
        for ((folder, texts) in words) {
            for ((name, said) in texts) {
                val body = text(folder, name)
                for (word in said) assertTrue("$folder $name says $word: $body", body.contains(word))
            }
            for (name in listOf("fit_assistant_open", "fit_assistant_begin", "fit_assistant_skip", "fit_assistant_yes", "fit_assistant_no", "fit_assistant_good", "fit_assistant_finish")) {
                assertTrue("$folder $name", text(folder, name).isNotBlank())
            }
            assertTrue("$folder: the step and the count", text(folder, "fit_assistant_step").let { it.contains("%1\$d") && it.contains("%2\$d") })
            val density = text(folder, "fit_assistant_density")
            assertTrue("$folder: the density asked and the measured one: $density", density.contains("%1\$d") && density.contains("%2\$d"))
            assertTrue("$folder: the measured density comes first, as the sentence reads", density.indexOf("%2\$d") < density.indexOf("%1\$d"))
        }
    }
}
