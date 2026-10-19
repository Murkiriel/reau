package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One screen for Android Auto's local server, with the title "Servidor Local do Android Auto" and
 * an (i) that says what it is for. Its texts exist, and the (i) says what the rider must know: where the server is, that without it
 * there is no picture, and what the screen holds.
 */
class ServerScreenTextsTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()

    private fun text(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("sem o texto $name")

    @Test
    fun `the title is the chosen one`() {
        assertEquals("Android Auto na rede", text("server_screen_title"))
    }

    @Test
    fun `the bar and the info have one name, and there is no short one any more`() {
        // "Android Auto na rede" for both, "Servidor de rede do AA (ou Android Auto)" gone.
        assertFalse(strings.contains("name=\"server_screen_title_short\""))
        assertFalse(text("server_screen_info").contains("AA"))
    }

    @Test
    fun `the info says what the network server is for, the rule, and that Android Auto's own server is another thing`() {
        val info = text("server_screen_info")
        assertTrue("${info.length} letters", info.length in 200..720)
        assertTrue("the rule: $info", info.contains("não procura o painel"))
        assertTrue("says who opens it and the sound: $info", info.contains("navegador") && info.contains("som"))
    }

    @Test
    fun `the removed texts are gone, the line under the sound and the section of Android Auto's own server`() {
        assertFalse(strings.contains("name=\"web_audio_sub\""))
        for (name in listOf("server_group_own", "server_own_note", "server_action_check", "server_state_on", "server_state_off", "server_state_unknown")) {
            assertFalse(name, strings.contains("name=\"$name\""))
        }
        assertFalse("the info does not point to a section that is not there", text("server_screen_info").contains("Embaixo"))
    }

    @Test
    fun `the rule is said on the screen too, and what to do before the server can be switched on`() {
        assertTrue(text("server_rule").contains("não procura o painel"))
        assertTrue(text("server_network_sub_off_stopped").contains("Ligar"))
        assertTrue(text("web_view_wait_sub").contains("Ligar"))
        assertTrue(text("web_view_long").contains("não procura o painel"))
    }

    @Test
    fun `the notes of the server screen are short, two lines at most`() {
        // No text of three lines or more, and a little shorter still: a line and a half at most.
        for (name in listOf("server_rule", "server_network_sub_on", "server_network_sub_off_stopped", "web_view_wait_sub")) {
            assertTrue("$name is ${text(name).length} letters", text(name).length <= 60)
        }
        // The switch row is narrower (the switch takes the right side): about 38 letters a line.
        assertTrue("web_view_sub is ${text("web_view_sub").length} letters", text("web_view_sub").length <= 50)
    }

    @Test
    fun `every group of the screen has a name`() {
        for (name in listOf("server_group_network", "server_group_sound")) assertTrue(name, text(name).isNotBlank())
    }

    @Test
    fun `the old window's names for the stopped screen are gone, and the dialog's texts too`() {
        assertFalse(strings.contains("name=\"server_dialog_title\""))
        assertFalse(strings.contains("name=\"server_auto_label\""))
    }
}
