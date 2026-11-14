package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network server is plain HTTP: the PIN, the picture and the touches go in the clear. So the screen says, next to the switch that
 * starts it (12/11/2026), to use it only on a network of one's own and never on a public or someone else's Wi-Fi, because anyone on the
 * network can see the PIN. A text only: what the server does did not change.
 */
class WebNetworkNoticeTest {
    private fun text(folder: String, name: String): String {
        val strings = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no $name in $folder")
    }

    @Test
    fun `the notice is in the three languages and says own network, never public, and that the PIN is seen`() {
        val said = mapOf(
            "values-pt" to listOf("só numa rede sua", "casa", "ponto de acesso", "Nunca em Wi-Fi público ou de outra pessoa", "não é cifrado", "PIN", "imagem", "toques"),
            "values" to listOf("only on a network of your own", "home", "hotspot", "Never on public or someone else", "not encrypted", "PIN", "picture", "touches"),
            "values-es" to listOf("solo en una red tuya", "casa", "punto de acceso", "Nunca en una Wi-Fi pública o ajena", "no está cifrado", "PIN", "imagen", "toques"),
        )
        for ((folder, words) in said) {
            val body = text(folder, "web_network_notice")
            for (word in words) assertTrue("$folder says \"$word\": $body", body.contains(word, ignoreCase = true))
            assertTrue("$folder: a notice, not a wall: ${body.length} letters", body.length in 120..320)
        }
    }

    private val section = File("src/main/java/com/reau/app/ui/WebSection.kt").readText()

    @Test
    fun `the notice is drawn once, right under the switch that starts the server, with the switch on or off and while it waits for Ligar`() {
        assertEquals(1, Regex("""WebNetworkNotice\(\)""").findAll(section).count { section.substring(it.range.first - 12, it.range.first) != "private fun " })
        val call = section.indexOf("            WebNetworkNotice()")
        assertTrue("called", call > 0)
        assertTrue("after the switch row", call > section.indexOf("SettingSwitch(R.string.web_view_title"))
        assertTrue("after the locked row too, so it is there before Ligar", call > section.indexOf("LockedRow("))
        assertTrue("before the rest of the section", call < section.indexOf("if (s.webView) {"))
        assertTrue("not inside a branch that only runs with the server on", call < section.indexOf("WebAccessBlock()"))
    }

    @Test
    fun `the notice shows what the old warning does not, and the old warning stays`() {
        // The old line (with the server on) says who sees the picture; the new one says where it is safe to switch it on.
        for (folder in listOf("values-pt", "values", "values-es")) {
            assertTrue(text(folder, "web_warning").isNotBlank())
            assertTrue(text(folder, "web_network_notice") != text(folder, "web_warning"))
        }
        assertTrue(section.contains("R.string.web_warning"))
    }
}
