package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of the notices of this round, in the three languages: the permission texts the owner approved word for word, the three new alerts, the
 * notice about what the recordings hold, and the privacy answer about the crash report. The expected texts are typed out here, not read from the code.
 */
class RiderNoticesTextTest {
    private val src = File("src").takeIf { it.isDirectory } ?: File("app/src")
    private val docs = File("docs").takeIf { it.isDirectory } ?: File("../docs")
    private val folders = mapOf("en" to "values", "pt" to "values-pt", "es" to "values-es")

    private fun string(lang: String, name: String): String {
        val xml = File(src, "main/res/${folders.getValue(lang)}/strings.xml").readText()
        return Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: error("no $name in $lang")
    }

    // -- the permission texts (approved by the owner, 11/11/2026, exactly like this) ---------------------------------------

    @Test
    fun `the phone state and location texts are the owner's, word for word, in the three languages`() {
        val expected = mapOf(
            ("pt" to "perm_phone_state_why") to "Diz ao REau se o telefone toca ou está em ligação, para o joystick agir na hora certa, e a geração da rede (4G, 5G) para o painel, sem ler números nem ligações.",
            ("en" to "perm_phone_state_why") to "Tells REau whether the phone rings or is in a call, so the joystick acts at the right time, and the network generation (4G, 5G) for the dash, without reading numbers or calls.",
            ("es" to "perm_phone_state_why") to "Dice a REau si el teléfono suena o está en llamada, para que el joystick actúe a tiempo, y la generación de red (4G, 5G) para el tablero, sin leer números ni llamadas.",
            ("pt" to "perm_location_why") to "Deixa o REau achar o painel no Wi-Fi atrás de outro app; com \\\"Usar a localização\\\", a posição arredondada a 11 km para sol e clima, e o rumo pelo GPS no anel.",
            ("en" to "perm_location_why") to "Lets REau find the dash in the Wi-Fi list behind another app; with \\\"Use location\\\", the position rounded to 11 km for sun and weather, and the GPS heading on the ring.",
            ("es" to "perm_location_why") to "Deja que REau halle el tablero en el Wi-Fi tras otra app; con \\\"Usar la ubicación\\\", la posición redondeada a 11 km para sol y clima, y el rumbo GPS del anillo.",
        )
        for ((key, text) in expected) assertEquals(key.toString(), text, string(key.first, key.second))
    }

    // -- the three alerts ----------------------------------------------------------------------------------------------------

    private val newNames = listOf(
        "notif_title_bind_refused", "notif_text_bind_refused", "warn_alerts_muted_title", "warn_alerts_muted", "warn_alerts_blocked_title",
        "warn_alerts_blocked", "action_open_alert_settings", "recordings_notice", "share_recordings_notice",
    )

    @Test
    fun `every new text exists in the three languages and is not left in English in the other two`() {
        for (name in newNames) {
            val en = string("en", name)
            assertTrue(name, en.isNotBlank())
            for (lang in listOf("pt", "es")) {
                val text = string(lang, name)
                assertTrue("$lang/$name", text.isNotBlank())
                assertTrue("$lang/$name is the English text", text != en)
            }
        }
    }

    @Test
    fun `the alert texts are short and in the style of the stuck dash and hot phone ones`() {
        for (lang in folders.keys) {
            for (name in listOf("notif_text_bind_refused")) {
                val text = string(lang, name)
                assertTrue("$lang/$name has ${text.length} characters", text.length <= 260)
                assertFalse("ends without a full stop like the others", text.endsWith("."))
            }
            for (name in listOf("notif_title_bind_refused", "warn_alerts_muted_title", "warn_alerts_blocked_title")) {
                assertTrue("$lang/$name: ${string(lang, name)}", string(lang, name).length <= 50)
            }
        }
    }

    @Test
    fun `the refused network text says what to try, in each language`() {
        assertTrue(string("pt", "notif_text_bind_refused").let { "VPN" in it && "Wi-Fi" in it && "dados móveis" in it })
        assertTrue(string("en", "notif_text_bind_refused").let { "VPN" in it && "Wi-Fi" in it && "mobile data" in it })
        assertTrue(string("es", "notif_text_bind_refused").let { "VPN" in it && "Wi-Fi" in it && "datos móviles" in it })
    }

    @Test
    fun `the muted and blocked cards name the channel by the name Android shows`() {
        assertTrue(string("pt", "warn_alerts_muted").contains("“${string("pt", "notif_alert_channel_name")}”"))
        assertTrue(string("en", "warn_alerts_muted").contains("“${string("en", "notif_alert_channel_name")}”"))
        assertTrue(string("es", "warn_alerts_muted").contains("“${string("es", "notif_alert_channel_name")}”"))
        assertTrue(string("pt", "warn_alerts_blocked").contains("“${string("pt", "notif_alert_channel_name")}”"))
    }

    // -- what the recordings hold ------------------------------------------------------------------------------------------

    @Test
    fun `the notice on the recordings says they hold the map, where you rode, and the names of who calls`() {
        for (name in listOf("recordings_notice", "share_recordings_notice")) {
            assertTrue(string("pt", name), string("pt", name).let { "mapa" in it && "por onde você andou" in it && "nomes de quem liga" in it })
            assertTrue(string("en", name), string("en", name).let { "map" in it && "where you rode" in it && "names of who calls" in it })
            assertTrue(string("es", name), string("es", name).let { "mapa" in it && "por dónde rodaste" in it && "nombres de quien llama" in it })
        }
    }

    @Test
    fun `the notice is on the screen above the two recording switches and under the share buttons`() {
        val source = File(src, "main/java/com/reau/app/ui/SettingsDebug.kt").readText()
        val rows = source.substringAfter("private fun recordingRows").substringBefore("\n}\n")
        assertTrue("first in the recording rows", rows.indexOf("RecordingsNotice(R.string.recordings_notice") in 0 until rows.indexOf("\"record_video\""))
        assertTrue(rows.indexOf("\"record_video\"") < rows.indexOf("\"record_sessions\""))
        val group = source.substringAfter("return listOf<@Composable () -> Unit>(\n        { RideLog").substringBefore("{ LogKeepDays")
        val lines = group.lines().map { it.trim() }
        val share = lines.indexOfFirst { it.startsWith("{ ShareRow(actions) }") }
        assertTrue("right under the share buttons", share >= 0 && lines[share + 1].contains("RecordingsNotice(R.string.share_recordings_notice"))
        assertTrue("and before the delete buttons", lines[share + 2].startsWith("{ DeleteRow"))
        assertEquals("nothing else changes what is recorded: the switches still write the same settings", 2, Regex("""recordVideo = it|recordSessions = it""").findAll(rows).count())
    }

    // -- the privacy answer about the crash report -----------------------------------------------------------------------

    @Test
    fun `the site's privacy page says the crash report is written with the log off, stays on the phone and does not leave by itself`() {
        val pt = File(docs, "pt/privacy.html").readText()
        val en = File(docs, "en/privacy.html").readText()
        val es = File(docs, "es/privacy.html").readText()
        assertTrue(pt.contains("O relatório de queda do app é gravado no celular mesmo com o registro desligado; fica só no celular e não sai sozinho."))
        assertTrue(en.contains("The app’s crash report is written on the phone even with the log off; it stays only on the phone and does not leave by itself."))
        assertTrue(es.contains("El informe de caída de la app se graba en el teléfono aun con el registro apagado; queda solo en el teléfono y no sale por sí solo."))
    }

    @Test
    fun `the app's own texts say it too, and the log switch no longer promises that nothing at all is recorded`() {
        // One sentence of at most 33 words (AboutDataRuleTest), so the crash report goes into the same sentence as the log and the recordings.
        assertTrue(string("pt", "about_data_stays").contains("o relatório de queda mesmo sem registro; só saem em"))
        assertTrue(string("en", "about_data_stays").contains("the crash report even with log off, and leave only through"))
        assertTrue(string("es", "about_data_stays").contains("el informe de caída aun sin registro, y solo salen por"))
        assertTrue(string("pt", "ride_log").contains("exceto o relatório de queda do app"))
        assertTrue(string("en", "ride_log").contains("except the app’s crash report"))
        assertTrue(string("es", "ride_log").contains("salvo el informe de caída de la app"))
        assertTrue("the page of the data keeps its short paragraphs", string("pt", "about_data_stays").length <= 340)
    }

    @Test
    fun `the privacy text is true to the code, the crash handler is installed whatever the log switch says`() {
        val app = File(src, "main/java/com/reau/app/REauApp.kt").readText()
        val install = app.substringAfter("CrashHandler.install(this)").take(0)
        assertEquals("", install)
        val before = app.substringBefore("CrashHandler.install(this)")
        assertFalse("not behind the log switch", before.takeLast(160).contains("rideLog"))
    }
}
