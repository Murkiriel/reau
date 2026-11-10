package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.AaLinkState
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REau has no accessibility service, and no screen, text, permission, manifest entry or line of code speaks of one (the service it had
 * until 0.3.0, and what lived only for it, are no longer in this repository, only in the git history up to the commit of 20/10/2026):
 * not in the server card, not in a dialog, not in the
 * permissions page, not in the settings, not in any text of the connection flow, not in the terms. Read from the files and the code, so
 * the unit test run proves it.
 *
 * Also no text, icon or name of the old "lite" edition is left in the app: there is one app, "REau".
 */
class NoAccessibilityTest {
    private val src = listOf(File("src"), File("app/src")).first { File(it, "main/res").isDirectory }

    /** Every `<string>` of a resource file, comments left out. */
    private fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val found = Regex("""<string\s+name="([^"]+)"[^>]*?(?:/>|>(.*?)</string>)""", RegexOption.DOT_MATCHES_ALL).findAll(text)
        return found.associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** Every text of [folder] ("values", "values-pt" or "values-es"), whatever the file of res/ it is in. */
    private fun strings(folder: String): Map<String, String> =
        File(src, "main/res/$folder").listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.fold(emptyMap()) { all, f -> all + read(f) }

    /** What the phone shows in [folder]'s language: its texts over the default (English) ones. */
    private fun shownIn(folder: String): Map<String, String> = if (folder == "values") strings("values") else strings("values") + strings(folder)

    private val folders = listOf("values", "values-pt", "values-es")
    private val words = Regex("accessib|acessibil|accesibil|restricted setting|configura[çc][õo]es? restritas?|ajustes? restringidos?", RegexOption.IGNORE_CASE)

    /** The word lite alone ("satellite" and "satélite" are not it). */
    private val lite = Regex("(?<!\\p{L})lite(?!\\p{L})", RegexOption.IGNORE_CASE)

    private val names: Map<Int, String> = R.string::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    private fun name(id: Int) = names[id] ?: error("no string with the id $id")

    @Test
    fun `no text of the app mentions accessibility`() {
        for (folder in folders) {
            val offenders = strings(folder).filterValues { words.containsMatchIn(it) }.keys
            assertEquals("texts ($folder) that speak of accessibility", emptySet<String>(), offenders)
        }
    }

    @Test
    fun `the texts of the ask are gone from the resources`() {
        val gone = listOf(
            "aa_server_auto", "aa_server_accessibility_connected", "aa_server_accessibility_enabled", "aa_server_accessibility_off",
            "aa_server_service_off", "action_open_accessibility", "accessibility_ask_title", "accessibility_ask_text", "accessibility_ask_confirm",
            "perm_accessibility_title", "perm_accessibility_why", "permission_special_restricted", "action_allow_restricted",
            "permission_special_stuck", "permission_special_stuck_short", "server_title", "server_sub_on", "server_sub_off", "group_android_auto",
            "aa_server_accessibility_label", "aa_server_accessibility_description", "action_retry_aa_server", "aa_server_cover", "aa_server_cover_cancel",
            "aa_server_cover_stopping", "aa_server_cover_waiting", "aa_server_working", "aa_server_stopping", "aa_server_started", "aa_server_locked",
            "aa_server_left_on", "cover_step_open", "cover_step_menu", "cover_step_start", "cover_step_stop", "cover_hint",
            "aa_unresponsive_by_hand_warning", "sub_unresponsive_by_hand", "notif_text_unresponsive_by_hand",
        )
        for (folder in folders) assertEquals("$folder still has", emptyList<String>(), gone.filter { it in strings(folder) })
    }

    @Test
    fun `no code, resource or manifest entry of the app has anything of an accessibility service`() {
        val forbidden = listOf(
            "android.accessibilityservice", "android.view.accessibility", "AccessibilityService", "AccessibilityManager", "AccessibilityNodeInfo",
            "AccessibilityEvent", "AccessibilityWindowInfo", "ACTION_ACCESSIBILITY_SETTINGS", "ACCESSIBILITY_DETAILS_SETTINGS", "openAccessibilitySettings",
            "AaServerAccessibilityService", "aa_server_accessibility", "BIND_ACCESSIBILITY_SERVICE",
        )
        val files = File(src, "main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "md", "txt", "pro") }.toList()
        assertTrue("${files.size} files read", files.size > 100)
        val offenders = files.flatMap { f -> forbidden.filter { it in f.readText() }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), offenders)
        assertFalse("the dialog's file is gone", File(src, "main/java/com/reau/app/ui/AccessibilityAsk.kt").exists())
        assertFalse("the service's file is gone", File(src, "main/java/com/reau/app/service/AaServerAccessibilityService.kt").exists())
        assertFalse("the service's settings file is gone", File(src, "main/res/xml/aa_server_accessibility.xml").exists())
    }

    @Test
    fun `the manifest declares no accessibility service, so Android's Accessibility settings never list the app`() {
        val manifest = File(src, "main/AndroidManifest.xml").readText()
        assertFalse(Regex("accessib", RegexOption.IGNORE_CASE).containsMatchIn(manifest))
        assertFalse(manifest.contains("android.accessibilityservice.AccessibilityService"))
        assertEquals("the app's services: the tile and the projection", 2, Regex("<service\\b").findAll(manifest).count())
    }

    @Test
    fun `the app is one REau, with nothing of the lite edition left in its texts, icons or names`() {
        for (folder in folders) {
            val offenders = strings(folder).filterValues { lite.containsMatchIn(it) }.keys
            assertEquals("texts ($folder) that say lite", emptySet<String>(), offenders)
        }
        assertEquals("REau", strings("values").getValue("app_name"))
        val stray = File(src, "main/res").walkTopDown().filter { it.isFile && Regex("(^|[_.-])lite([_.-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.map { it.path }.toList()
        assertEquals("resources named lite", emptyList<String>(), stray)
        val manifest = File(src, "main/AndroidManifest.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse("the label is the app's name", Regex("""android:label="[^"]*lite""", RegexOption.IGNORE_CASE).containsMatchIn(manifest))
        // The only mention of the old package is the <queries> entry that lets the app see it (OtherEdition).
        assertEquals(1, Regex("com\\.reau\\.app\\.lite").findAll(manifest).count())
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
            for (card in listOf(false, true)) ProjectionNotification.contentFor(headline, card).let {
                add(it.first)
                add(it.second)
            }
        }
        for (state in AaLinkState.entries) add(ScreenText.androidAuto(state))
        for (link in DashLink.entries) add(ScreenText.dash(link))
        for (p in projections()) MainLook.sub(p)?.let(::add)
        // The server card: its title, text and button.
        addAll(
            listOf(
                R.string.warn_server_title, R.string.server_warning, R.string.action_open_aa_settings,
                R.string.notif_title_server_off, R.string.notif_text_server_off,
                // The previous session closing: its warning card too (the headline, line and notification come from the states).
                R.string.warn_closing_previous_title, R.string.aa_closing_previous_warning,
                // The mute server: what the rider does by hand in Android Auto's menu.
                R.string.warn_unresponsive_title, R.string.aa_unresponsive_warning,
                // The button while the stop is carried out.
                R.string.action_stopping,
            ),
        )
    }

    @Test
    fun `no text of the connection flow mentions accessibility, state by state`() {
        val ids = flowTexts()
        assertTrue("${ids.size} texts met", ids.size > 40)
        for (folder in folders) {
            val shown = shownIn(folder)
            for (id in ids) {
                val text = shown[name(id)] ?: error("${name(id)} has no text in $folder")
                assertFalse("${name(id)} ($folder): $text", words.containsMatchIn(text))
            }
        }
    }

    @Test
    fun `a mute server sends the rider to the menu of Android Auto, never to the force stop`() {
        val keys = listOf("aa_unresponsive_warning", "sub_unresponsive", "notif_text_unresponsive")
        val force = mapOf(
            "values" to Regex("force", RegexOption.IGNORE_CASE), "values-pt" to Regex("for[\u00e7c]", RegexOption.IGNORE_CASE),
            "values-es" to Regex("fuerza", RegexOption.IGNORE_CASE),
        )
        val head = mapOf("values" to "head unit server", "values-pt" to "servidor", "values-es" to "servidor de unidad principal")
        for (folder in folders) {
            val shown = shownIn(folder)
            for (key in keys) {
                val text = shown[key] ?: error("$key has no text in $folder")
                assertTrue("$key ($folder) names the menu: $text", "\u22EE" in text)
                assertFalse("$key ($folder) suggests a force stop: $text", force.getValue(folder).containsMatchIn(text))
            }
            assertTrue(shown.getValue("aa_unresponsive_warning"), head.getValue(folder) in shown.getValue("aa_unresponsive_warning"))
        }
    }

    @Test
    fun `the server card says the manual step and that the server must be turned on again every time`() {
        val en = shownIn("values")
        val pt = shownIn("values-pt")
        val es = shownIn("values-es")
        val card = "server_warning"
        assertTrue(en.getValue(card), "Start head unit server" in en.getValue(card))
        assertTrue(pt.getValue(card), "Iniciar servidor da unidade principal" in pt.getValue(card))
        // The truth: Android Auto closes the server when the session ends, so it is turned on at each session.
        assertTrue(en.getValue(card), "turns off when the session ends" in en.getValue(card) && "every time" in en.getValue(card))
        assertTrue(pt.getValue(card), "desliga quando a sessão termina" in pt.getValue(card) && "a cada vez" in pt.getValue(card))
        assertTrue(es.getValue(card), "Iniciar servidor de unidad principal" in es.getValue(card))
        assertTrue(es.getValue(card), "se apaga cuando termina la sesión" in es.getValue(card) && "cada vez" in es.getValue(card))
        assertEquals("the way to Android Auto's settings stays", "Abrir as configurações do Android Auto", pt.getValue("action_open_aa_settings"))
        assertTrue(en.getValue("action_open_aa_settings").isNotBlank())
    }

    @Test
    fun `the server warning is shown with the server off, and it has no action but the way to Android Auto`() {
        assertEquals(listOf(Warning.SERVER_OFF), MainLook.warnings(Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.SERVER_OFF), BatteryUi()))
    }

    /** The terms: one text for the app, with no word of an accessibility service, nor of editions. */
    @Test
    fun `the terms, in the three languages, say nothing of an accessibility service nor of editions`() {
        for (name in listOf("termos.pt.md", "terms.en.md", "terminos.es.md")) {
            val text = File(src, "main/assets/terms/$name").readText()
            assertFalse("$name speaks of an accessibility service", words.containsMatchIn(text))
            assertFalse("$name still names the voice apps the service heard", Regex("WhatsApp|Telegram|Signal").containsMatchIn(text))
            assertFalse("$name still speaks of editions", Regex("edi[cç][ãa]o|edici[oó]n|edition|\\blite\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        assertFalse("no second set of terms for another edition", File(src, "lite").exists())
    }
}
