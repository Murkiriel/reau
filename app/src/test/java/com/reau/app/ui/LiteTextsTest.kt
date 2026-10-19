package com.reau.app.ui

import com.reau.app.Edition
import com.reau.app.R
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.ServerResult
import com.reau.app.service.AaServerStarter
import com.reau.app.service.DashLink
import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ProjectionNotification
import com.reau.app.ui.MainLook.Warning
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Lite edition has no accessibility service and never speaks of it: not in the server card (the one seen with the
 * server off, which used to send the rider to Accessibility), not in a dialog, not in any text of the connection flow.
 * Read from the files, so both editions' test runs prove it about the Lite: the texts the Lite ships are the ones of
 * src/main with src/lite's over them.
 */
class LiteTextsTest {
    private val src = listOf(File("src"), File("app/src")).first { File(it, "main/res").isDirectory }

    /** Every `<string>` of a resource file, comments left out. */
    private fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val found = Regex("""<string\s+name="([^"]+)"[^>]*?(?:/>|>(.*?)</string>)""", RegexOption.DOT_MATCHES_ALL).findAll(text)
        return found.associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun strings(flavor: String, folder: String) = read(File(src, "$flavor/res/$folder/strings.xml"))

    /** The texts [folder] ("values" or "values-pt") shows in the Lite: main's, and over them the Lite's own. */
    private fun lite(folder: String): Map<String, String> = strings("main", folder) + strings("lite", folder)

    /** What the phone shows in Portuguese: the Portuguese texts over the default (English) ones. */
    private fun liteIn(folder: String): Map<String, String> = if (folder == "values") lite("values") else lite("values") + lite(folder)

    private val folders = listOf("values", "values-pt")
    private val words = Regex("accessib|acessibil|restricted setting|configura[çc][õo]es? restritas?", RegexOption.IGNORE_CASE)

    private val names: Map<Int, String> = R.string::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    private fun name(id: Int) = names[id] ?: error("no string with the id $id")

    @Test
    fun `no text of the Lite edition mentions accessibility`() {
        for (folder in folders) {
            val offenders = liteIn(folder).filterValues { words.containsMatchIn(it) }.keys
            assertEquals("texts of the Lite ($folder) that speak of accessibility", emptySet<String>(), offenders)
        }
    }

    @Test
    fun `every text of the full edition about accessibility is overridden by the Lite, and the Lite adds no text of its own name`() {
        for (folder in folders) {
            val main = strings("main", folder)
            val own = strings("lite", folder)
            val aboutIt = main.filterValues { words.containsMatchIn(it) }.keys
            for (name in aboutIt) assertTrue("$name ($folder) is overridden in the Lite", name in own)
            assertEquals("the Lite overrides only texts main has ($folder)", emptySet<String>(), own.keys - main.keys)
            // What it empties is the accessibility service's own dialog and rows (some of them, like the dialog's button, do
            // not name it): nothing else, or a real text would be hidden.
            val emptied = own.filterValues { it.isEmpty() }.keys
            val ofTheService = aboutIt + setOf("accessibility_ask_confirm", "perm_accessibility_why")
            assertTrue("emptied without being about the service ($folder): ${emptied - ofTheService}", ofTheService.containsAll(emptied))
        }
    }

    /** Every combination the projection can be in, so that every headline and line of the main screen is met. */
    private fun projections(): List<Projection> = buildList {
        add(Projection())
        add(Projection(problem = "x"))
        add(Projection(phase = Phase.PREPARING))
        for (aa in AaLinkState.entries) for (dash in DashLink.entries) for (card in listOf(false, true)) {
            for (network in listOf(false, true)) for (noVideo in listOf(false, true)) for (stale in listOf(false, true)) {
                for (firstJoin in listOf(false, true)) {
                    add(
                        Projection(
                            phase = Phase.RUNNING, androidAuto = aa, dash = dash, card = card, networkServer = network,
                            aaNoVideo = noVideo, aaImageStale = stale, dashFirstJoin = firstJoin,
                        ),
                    )
                }
            }
        }
    }

    /** Every text of the connection flow: headlines, lines, notification, links, the server card and its buttons. */
    private fun flowTexts(): Set<Int> = buildSet {
        for (headline in Headline.entries) {
            add(ScreenText.headline(headline))
            // The Lite always shows the by-hand line of the mute server; both are met, as the full edition shows either.
            for (byRider in listOf(false, true)) ProjectionNotification.contentFor(headline, byRider).let {
                add(it.first)
                add(it.second)
            }
        }
        for (state in AaLinkState.entries) add(ScreenText.androidAuto(state))
        for (link in DashLink.entries) add(ScreenText.dash(link))
        for (result in ServerResult.entries) ScreenText.server(result)?.let(::add)
        for (state in AaServerStarter.Accessibility.entries) add(ScreenText.accessibility(state))
        for (p in projections()) for (byRider in listOf(false, true)) MainLook.sub(p, byRider)?.let(::add)
        // The server card: its title, text and buttons (all the ways, with no accessibility among them).
        addAll(
            listOf(
                R.string.warn_server_title, R.string.server_warning, R.string.action_open_aa_settings,
                R.string.action_retry_aa_server, R.string.notif_title_server_off, R.string.notif_text_server_off,
                // The previous session closing: its warning card too (the headline, line and notification come from the states).
                R.string.warn_closing_previous_title, R.string.aa_closing_previous_warning,
                // The mute server: what the rider does by hand in Android Auto's menu.
                R.string.warn_unresponsive_title, R.string.aa_unresponsive_by_hand_warning,
                // The button while the stop is carried out.
                R.string.action_stopping,
            ),
        )
    }

    @Test
    fun `no text of the connection flow mentions accessibility in the Lite, state by state`() {
        val ids = flowTexts()
        assertTrue("${ids.size} texts met", ids.size > 40)
        for (folder in folders) {
            val shown = liteIn(folder)
            for (id in ids) {
                val text = shown[name(id)] ?: error("${name(id)} has no text in $folder")
                assertFalse("${name(id)} ($folder): $text", words.containsMatchIn(text))
            }
        }
    }

    @Test
    fun `a mute server sends the Lite rider to the menu of Android Auto, never to the force stop`() {
        val keys = listOf("aa_unresponsive_by_hand_warning", "sub_unresponsive_by_hand", "notif_text_unresponsive_by_hand")
        val force = mapOf("values" to Regex("force", RegexOption.IGNORE_CASE), "values-pt" to Regex("for[\u00e7c]", RegexOption.IGNORE_CASE))
        val head = mapOf("values" to "head unit server", "values-pt" to "servidor")
        for (folder in folders) {
            val shown = liteIn(folder)
            for (key in keys) {
                val text = shown[key] ?: error("$key has no text in $folder")
                assertTrue("$key ($folder) names the menu: $text", "\u22EE" in text)
                assertFalse("$key ($folder) suggests a force stop: $text", force.getValue(folder).containsMatchIn(text))
            }
            assertTrue(shown.getValue("aa_unresponsive_by_hand_warning"), head.getValue(folder) in shown.getValue("aa_unresponsive_by_hand_warning"))
        }
        // The full edition with the service connected keeps its text: REau restarts the server itself.
        assertTrue("Force stop" in strings("main", "values").getValue("aa_unresponsive_warning"))
        assertTrue("For\u00e7ar parada" in strings("main", "values-pt").getValue("aa_unresponsive_warning"))
        // And the Lite is always "by hand".
        for (state in AaServerStarter.Accessibility.entries) {
            assertTrue(state.name, MainLook.muteByRider(ServerUi(auto = true, accessibility = state), hasAccessibility = false))
        }
    }

    @Test
    fun `the server card of the Lite says the manual step and that the server must be turned on again every time`() {
        val en = liteIn("values")
        val pt = liteIn("values-pt")
        val card = "server_warning"
        assertTrue(en.getValue(card), "Start head unit server" in en.getValue(card))
        assertTrue(pt.getValue(card), "Iniciar servidor da unidade principal" in pt.getValue(card))
        // The truth: Android Auto closes the server when the session ends, so it is turned on at each session.
        assertTrue(en.getValue(card), "turns off when the session ends" in en.getValue(card) && "every time" in en.getValue(card))
        assertTrue(pt.getValue(card), "desliga quando a sessão termina" in pt.getValue(card) && "a cada vez" in pt.getValue(card))
        assertEquals("the way to Android Auto's settings stays", "Abrir as configurações do Android Auto", pt.getValue("action_open_aa_settings"))
        assertTrue(en.getValue("action_open_aa_settings").isNotBlank())
        // The full edition keeps its own text (it turns the server on and off by itself).
        assertFalse(strings("main", "values-pt").getValue(card).contains("a cada vez"))
    }

    @Test
    fun `without the accessibility service the server card has no automatic switch and no way to Accessibility`() {
        for (result in ServerResult.entries) for (state in AaServerStarter.Accessibility.entries) {
            val server = ServerUi(auto = true, result, state)
            assertFalse("$result $state", MainLook.autoShown(server, hasAccessibility = false))
            assertNull("$result $state", MainLook.serverAction(server, hasAccessibility = false))
        }
    }

    @Test
    fun `with the service the switch still shows and still leads to Accessibility`() {
        val server = ServerUi(auto = true, ServerResult.SERVICE_OFF, AaServerStarter.Accessibility.OFF)
        assertTrue(MainLook.autoShown(server, hasAccessibility = true))
        assertEquals(MainLook.ServerAction.OPEN_ACCESSIBILITY, MainLook.serverAction(server, hasAccessibility = true))
        assertFalse("the rider's switch off", MainLook.autoShown(ServerUi(auto = false), hasAccessibility = true))
    }

    @Test
    fun `the running edition decides by its own Edition`() {
        val server = ServerUi(auto = true, ServerResult.SERVICE_OFF, AaServerStarter.Accessibility.OFF)
        assertEquals(Edition.hasAccessibility, MainLook.autoShown(server))
        if (Edition.lite) assertNull(MainLook.serverAction(server))
        assertEquals("the server warning is still shown", listOf(Warning.SERVER_OFF), warningsWithServerOff())
    }

    private fun warningsWithServerOff() =
        MainLook.warnings(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF), BatteryUi())

    @Test
    fun `the Lite has no accessibility service declared`() {
        for (manifest in listOf("main", "lite")) {
            val text = File(src, "$manifest/AndroidManifest.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertFalse(manifest, text.contains("BIND_ACCESSIBILITY_SERVICE") || text.contains("accessibilityservice"))
        }
    }

    /**
     * The terms the Lite shows are the full edition's with only the clause about the accessibility service cut: the same
     * version and nothing else changed, so a new term in src/main is not missed by the Lite, and the Lite's text never
     * speaks of accessibility (the full edition's still does, and is the one at the root of the repository).
     */
    @Test
    fun `the Lite terms are the full terms without the accessibility clause`() {
        val cuts = mapOf(
            "termos.pt.md" to "; a edição completa também usa um serviço de acessibilidade (a Lite não usa).",
            "terms.en.md" to "; the full edition also uses an accessibility service (the Lite edition does not).",
        )
        for ((name, clause) in cuts) {
            val full = File(src, "main/assets/terms/$name").readText()
            val lite = File(src, "lite/assets/terms/$name").readText()
            assertTrue("$name: the full text keeps the clause", clause in full)
            assertEquals(name, full.replace(clause, "."), lite)
            assertFalse("$name speaks of accessibility", lite.contains("acessib", ignoreCase = true) || lite.contains("accessib", ignoreCase = true))
        }
    }
}
