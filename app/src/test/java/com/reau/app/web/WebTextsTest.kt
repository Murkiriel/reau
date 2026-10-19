package com.reau.app.web

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The texts of the browser section: they exist, they are short, and they say what the rider must know. */
class WebTextsTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()

    private fun text(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("sem o texto $name")

    @Test
    fun `every text of the section is there`() {
        for (name in listOf(
            "section_web", "web_view_title", "web_view_sub", "web_view_long", "web_touch_title", "web_touch_sub", "web_touch_long",
            "web_waiting", "web_no_address", "web_warning", "web_pin_label", "web_audio_title", "web_audio_phone", "web_audio_browser", "web_audio_both", "web_audio_long", "web_copy", "web_copied", "web_kick_all",
            "web_viewers_none", "web_viewers_one", "web_viewers_many",
        )) assertTrue(name, text(name).isNotBlank())
    }

    @Test
    fun `the one line texts are short and the long ones are not a wall`() {
        for (name in listOf("web_view_title", "web_touch_title", "web_audio_title", "web_audio_phone", "web_audio_browser", "web_audio_both", "web_pin_label", "web_copy", "web_copied", "web_kick_all", "web_viewers_none", "web_viewers_one")) {
            assertTrue("$name is ${text(name).length} letters", text(name).length <= 40)
        }
        for (name in listOf("web_view_sub", "web_touch_sub", "web_waiting", "web_warning", "web_no_address")) {
            assertTrue("$name is ${text(name).length} letters", text(name).length <= 140)
        }
        for (name in listOf("web_view_long", "web_touch_long", "web_audio_long")) assertTrue("$name is ${text(name).length} letters", text(name).length <= 520)
    }

    @Test
    fun `the warning says what the browser sees, and the long texts say the traffic is not encrypted and the touch is control`() {
        val warning = text("web_warning")
        assertTrue(warning, warning.contains("mapa") && warning.contains("rota") && warning.contains("quem liga"))
        val view = text("web_view_long")
        assertTrue(view, view.contains("sem criptografia") && view.contains("PIN") && view.contains("5 minutos"))
        val touch = text("web_touch_long")
        assertTrue(touch, touch.contains("liga") && touch.contains("rede de confiança"))
    }

    /** Shortened to fit a sheet on the phone (it was ten lines). */
    @Test
    fun `the long text of the web viewer fits in a short sheet and still says what must be said`() {
        val view = text("web_view_long")
        assertTrue("web_view_long is ${view.length} letters", view.length <= 420)
        assertTrue(view, view.contains("PIN de 4 dígitos") && view.contains("5 erros") && view.contains("dois navegadores"))
    }

    /** Shortened to fit a sheet on the phone (it was ten lines). */
    @Test
    fun `the long text of the Android Auto sound fits in a short sheet and still says what must be said`() {
        val audio = text("web_audio_long")
        assertTrue("web_audio_long is ${audio.length} letters", audio.length <= 340)
        assertTrue(audio, audio.contains("fone do capacete") && audio.contains("reinicia o Android Auto") && audio.contains("toque na página"))
    }

    @Test
    fun `the viewers text with a number has its place for it`() {
        assertTrue(text("web_viewers_many").contains("%1\$d"))
        assertFalse(text("web_viewers_one").contains("%"))
    }
}
