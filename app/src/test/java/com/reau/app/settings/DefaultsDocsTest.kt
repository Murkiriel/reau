package com.reau.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comments on the settings said "off by default" for options that come on (music and caller on the dash, the dash's
 * Wi-Fi by itself, the profiles and more), and the FAQ and the screen "REau and your data" said
 * "if you turn it on" for what comes on: whoever audits by reading was told the opposite of what the app does (audit of
 * 20/10/2026). The comment of a Boolean setting says its default the way the code gives it, and the privacy texts do not
 * call an option that comes on "optional if you turn it on".
 */
class DefaultsDocsTest {
    private val source = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()

    /** (name, default, KDoc text) of every Boolean setting of [REauSettings]. */
    private fun booleans(): List<Triple<String, Boolean, String>> {
        val found = mutableListOf<Triple<String, Boolean, String>>()
        val lines = source.lines()
        for ((i, line) in lines.withIndex()) {
            val m = Regex("""^\s+val (\w+): Boolean = (true|false),""").find(line) ?: continue
            val doc = StringBuilder()
            var j = i - 1
            if (j >= 0 && lines[j].trim().endsWith("*/")) {
                while (j >= 0) {
                    doc.insert(0, lines[j].trim() + " ")
                    if (lines[j].trim().startsWith("/**")) break
                    j--
                }
            }
            found += Triple(m.groupValues[1], m.groupValues[2] == "true", doc.toString())
        }
        return found
    }

    @Test
    fun `a comment that says on or off by default agrees with the value in the code`() {
        val settings = booleans()
        assertTrue("found only ${settings.size} Boolean settings", settings.size > 20)
        val wrong = settings.mapNotNull { (name, value, doc) ->
            val off = Regex("""\boff by default""", RegexOption.IGNORE_CASE).containsMatchIn(doc)
            val on = Regex("""\bon by default""", RegexOption.IGNORE_CASE).containsMatchIn(doc)
            when {
                off && value -> "$name is on, its comment says off by default"
                on && !value -> "$name is off, its comment says on by default"
                else -> null
            }
        }
        assertEquals("comments that contradict the defaults: $wrong", emptyList<String>(), wrong)
    }

    /**
     * The same, for the places outside the settings class that say a switch's default (found in the audit's second pass: the
     * server's starter, the settings screen and the Wi-Fi screen said "off by default" for two switches that come on). Each row
     * is a file, a phrase that is near the claim, and the setting it is about; the claim on the phrase's line or the next has to
     * agree with [REauSettings].
     */
    private val claims = listOf(
        Triple("ui/WifiScreen.kt", "the switch makes Android suggest the bike's Wi-Fi", "autoJoinWifi"),
        Triple("settings/LocationUse.kt", "REauSettings.useLocation", "useLocation"),
        Triple("data/DetailedLog.kt", "Registro detalhado", "detailedLog"),
        Triple("REauApp.kt", "The detailed log as the rider left it", "detailedLog"),
        Triple("REauApp.kt", "The log as the rider left it", "rideLog"),
        Triple("REauApp.kt", "The browser server is off by default", "webView"),
        Triple("ui/SettingsDebug.kt", "The ride log (on by default)", "rideLog"),
        Triple("dash/protocol/DashCommands.kt", "Mandar o estado do celular ao painel", "dashPhoneStatus"),
    )

    private fun defaultOf(name: String): Boolean =
        REauSettings::class.java.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(REauSettings()) as Boolean

    @Test
    fun `the places outside the settings class that say a default agree with it`() {
        val wrong = claims.mapNotNull { (file, phrase, setting) ->
            val lines = File("src/main/java/com/reau/app/$file").readLines()
            val at = lines.indexOfFirst { phrase in it }
            if (at < 0) return@mapNotNull "$file: the phrase \"$phrase\" is gone (update this table)"
            val near = lines.subList(at, minOf(lines.size, at + 2)).joinToString(" ")
            val off = Regex("""\boff by default""", RegexOption.IGNORE_CASE).containsMatchIn(near)
            val on = Regex("""\bon by default""", RegexOption.IGNORE_CASE).containsMatchIn(near)
            val value = defaultOf(setting)
            when {
                !off && !on -> "$file: no on/off by default near \"$phrase\""
                off && value -> "$file says off by default, $setting is on"
                on && !value -> "$file says on by default, $setting is off"
                else -> null
            }
        }
        assertEquals("comments that contradict the defaults: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `the comments of the two settings that are not switches say their default as the code gives it`() {
        val d = REauSettings()
        val lines = source.lines()
        fun doc(property: String): String {
            val at = lines.indexOfFirst { it.contains("val $property:") }
            val from = (at - 1 downTo 0).first { lines[it].trim().startsWith("/**") }
            return lines.subList(from, at).joinToString(" ")
        }
        assertTrue("noDashStopMinutes: ${doc("noDashStopMinutes")}", "${d.noDashStopMinutes} by default" in doc("noDashStopMinutes"))
        assertEquals(com.reau.app.dash.NavProfileMode.PESSIMISTA, d.navProfileMode)
        assertTrue("navProfileMode: ${doc("navProfileMode")}", "Pessimista, and fixed so" in doc("navProfileMode"))
    }

    /** The switches of the owner's phone (28/10/2026) come on, the maneuver ring comes off: their comments say so, as the code gives it. */
    @Test
    fun `the switches the owner's phone has on come on, the maneuver ring off, and their comments say so`() {
        val byName = booleans().associate { it.first to it }
        for (name in listOf("gemini", "answerCalls", "useLocation", "dashPhoneStatus", "dashCaller", "dashMusic", "dashAlbumArt")) {
            val (_, value, doc) = byName.getValue(name)
            assertEquals("$name comes on on a new install", true, value)
            assertTrue("$name's comment says on by default: $doc", "on by default" in doc.lowercase())
        }
        val (_, ring, ringDoc) = byName.getValue("maneuverRing")
        assertEquals("the maneuver ring comes off on a new install", false, ring)
        assertTrue("maneuverRing's comment says off by default: $ringDoc", "off by default" in ringDoc)
    }

    /** The texts behind the (i) of the switches whose default changed say it, and that they need the permission, in the three languages. */
    @Test
    fun `the info texts of the location, calls, Gemini and the ring say the default of today in the three languages`() {
        val en = text("src/main/res/values/strings.xml")
        val pt = text("src/main/res/values-pt/strings.xml")
        val es = text("src/main/res/values-es/strings.xml")
        fun string(file: String, name: String) = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(file)!!.groupValues[1]
        assertTrue(string(en, "use_location").startsWith("On by default"))
        assertTrue(string(pt, "use_location").startsWith("Ligada por padrão"))
        assertTrue(string(es, "use_location").startsWith("Activado por defecto"))
        for ((file, word) in listOf(en to "off by default", pt to "desligado por padrão", es to "apagado por defecto")) {
            assertTrue(word, word in string(file, "dash_ring"))
        }
        for (name in listOf("use_location", "answer_calls", "gemini")) {
            assertTrue("$name: only works with the permission", "permission" in string(en, name) && "permiss" in string(pt, name) && "permiso" in string(es, name))
        }
    }

    // ── the privacy texts: what comes on is not "optional, if you turn it on" ─────────────────────────────────────

    private fun text(path: String) = File(path).readText()

    @Test
    fun `the FAQ and the data screen do not call the dash switches that come on by default optional`() {
        val faqPt = text("../docs/pt/faq.html")
        val faqEn = text("../docs/en/faq.html")
        assertTrue("se você ligar as chaves" !in faqPt)
        assertTrue("if you turn the switches on" !in faqEn)
        assertTrue("que vêm ligadas de fábrica" in faqPt)
        assertTrue("come on by default" in faqEn)
        val strings = text("src/main/res/values/strings.xml") + text("src/main/res/values-pt/strings.xml")
        assertTrue("if you want, the music" !in strings)
        assertTrue("se você quiser, a música" !in strings)
        assertTrue("with the switches on, the music" !in strings)
    }

    /**
     * Every time the FAQ names a switch and says, in the same breath, whether it comes on or off ("Gemini" (off by default), "Atender
     * ligação" (vem desligada)), it agrees with [REauSettings]. The FAQ said Gemini and "Atender ligação" came on in six places after
     * they stopped doing so (found comparing the first published code with the current one, 21/10/2026); each switch must be named
     * with its default at least once in each language, so the check cannot go quiet.
     */
    private val faqSwitches = listOf(
        Triple("answerCalls", "Answer calls", "Atender ligação"),
        Triple("gemini", "Gemini", "Gemini"),
        Triple("dashPhoneStatus", "Phone status", "Estado do celular"),
        Triple("dashCaller", "Who’s calling", "Quem está ligando"),
        Triple("useLocation", "Use location", "Usar a localização"),
        Triple("maneuverRing", "Maneuver ring", "Anel de manobras"),
    )

    @Test
    fun `what the FAQ says about the default of a switch it names agrees with the code`() {
        val faqEn = text("../docs/en/faq.html")
        val faqPt = text("../docs/pt/faq.html")
        val wrong = mutableListOf<String>()
        for ((setting, en, pt) in faqSwitches) {
            val value = defaultOf(setting)
            val enClaims = Regex("“" + Regex.escape(en) + "”(?:</strong>)?[^.“]{0,70}?(?:it is |they are )?\\b(on|off) by default").findAll(faqEn).map { it.groupValues[1] == "on" }.toList()
            val ptClaims = Regex("“" + Regex.escape(pt) + "”(?:</strong>)?[^.“]{0,70}?v[eê]m (ligad|desligad)").findAll(faqPt).map { it.groupValues[1] == "ligad" }.toList()
            if (enClaims.isEmpty()) wrong += "$setting: the English FAQ never says its default (update the table)"
            if (ptClaims.isEmpty()) wrong += "$setting: the Portuguese FAQ never says its default (update the table)"
            if (enClaims.any { it != value }) wrong += "$setting is ${if (value) "on" else "off"}, the English FAQ says ${enClaims.map { if (it) "on" else "off" }}"
            if (ptClaims.any { it != value }) wrong += "$setting is ${if (value) "on" else "off"}, the Portuguese FAQ says ${ptClaims.map { if (it) "ligada" else "desligada" }}"
        }
        assertEquals("FAQ statements that contradict the defaults: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `the FAQ tells the turn off by itself without the dash, with the title and the default of the setting`() {
        val default = REauSettings().noDashStopMinutes
        val en = text("../docs/en/faq.html")
        val pt = text("../docs/pt/faq.html")
        val titleEn = Regex("""name="no_dash_stop_title">([^<]*)<""").find(text("src/main/res/values/strings.xml"))!!.groupValues[1]
        val titlePt = Regex("""name="no_dash_stop_title">([^<]*)<""").find(text("src/main/res/values-pt/strings.xml"))!!.groupValues[1]
        assertTrue("the English FAQ names \"$titleEn\"", "“$titleEn”" in en)
        assertTrue("the Portuguese FAQ names \"$titlePt\"", "“$titlePt”" in pt)
        assertTrue("$default by default in the English FAQ", "<strong>$default by default</strong>" in en)
        assertTrue("$default por padrão in the Portuguese FAQ", "<strong>$default por padrão</strong>" in pt)
        for (minutes in REauSettings.NO_DASH_STOP_MINUTES.filter { it > 0 }.dropLast(1)) {
            assertTrue("$minutes in the FAQ", "$minutes," in en || "$minutes or" in en)
        }
    }

    /** Audit of 20/10/2026 (second pass): two FAQ statements that the code contradicts. */
    @Test
    fun `the FAQ does not give the official app the video profiles that are REau's own, and does not tag weather and joystick LIVE`() {
        val faqPt = text("../docs/pt/faq.html")
        val faqEn = text("../docs/en/faq.html")
        assertTrue("O app oficial da Royal Enfield também tem só" !in faqPt)
        assertTrue("Royal Enfield’s official app also has only" !in faqEn)
        assertTrue("2 quadros por segundo com 100 kbps" !in faqPt)
        assertTrue("2 frames per second at 100 kbps" !in faqEn)
        // The tag marks what the app's own text says it marks; weather and the joystick page apply live but have none.
        assertTrue("rota real, clima, usar a localização" !in faqPt)
        assertTrue("real route, weather, using the location" !in faqEn)
        assertTrue("sem o selo, a chave do clima" in faqPt)
        assertTrue("without the tag" in faqEn)
        // On the Lite the server is turned on by hand after Ligar, as section 4 and the install video say.
        assertTrue("ligue o servidor do Android Auto antes" !in faqPt)
        assertTrue("start the Android Auto server first" !in faqEn)
    }

    @Test
    fun `the terms say which things go to the dash by default and that the log comes on`() {
        for (file in listOf("../TERMS_OF_USE.md", "../TERMS_OF_USE.pt-br.md", "../TERMS_OF_USE.es.md")) {
            val terms = text(file)
            val goesToDash = when {
                file.contains("pt-br") -> "Por padrão o REau manda ao painel"
                file.contains(".es.") -> "Por defecto REau envía al tablero"
                else -> "By default REau sends the dash"
            }
            assertTrue("$file does not say what goes to the dash by default", goesToDash in terms)
            val logOn = when {
                file.contains("pt-br") -> "O registro de diagnóstico vem ligado"
                file.contains(".es.") -> "El registro de diagnóstico viene activado"
                else -> "The diagnostic log is on by default"
            }
            assertTrue("$file does not say the log is on by default", logOn in terms)
        }
    }

    /** The terms (version 3) say what the new defaults are, in the three texts of the root and of the app (28/10/2026; the info ring off since 30/10/2026). */
    @Test
    fun `the terms say that Gemini, the location and the weather come on, that the info ring is off, and that the recordings are optional, off in the app you ride with and on in the test build`() {
        val files = mapOf(
            "en" to listOf("../TERMS_OF_USE.md", "src/main/assets/terms/terms.en.md"),
            "pt" to listOf("../TERMS_OF_USE.pt-br.md", "src/main/assets/terms/termos.pt.md"),
            "es" to listOf("../TERMS_OF_USE.es.md", "src/main/assets/terms/terminos.es.md"),
        )
        val says = mapOf(
            "en" to listOf("for Gemini, which is on by default", "the location (also on by default)", "(with its album art)", "is on by default and the info ring is off", "are optional: off by default in the app you install to ride and on in the test build", "stay on the phone only", "the map, the route and your positions"),
            "pt" to listOf("para o Gemini, que vem ligado", "a localização (que também vem ligada)", "(com a capa do álbum)", "vem ligada e o anel de informações vem desligado", "são opcionais: vêm desligadas no app que você instala para andar e ligadas na build de teste", "ficam só no celular", "o mapa, a rota e as posições"),
            "es" to listOf("para Gemini, que viene activado", "la ubicación (que también viene activada)", "(con su carátula)", "viene activado y el anillo de información viene apagado", "son opcionales: vienen desactivadas en la app que instalas para rodar y activadas en la compilación de prueba", "se quedan solo en el teléfono", "el mapa, la ruta y las posiciones"),
        )
        val old = mapOf(
            "en" to listOf("if you turn Gemini on", "info ring are optional and off by default", "optional and exist only if you turn them on", "exist only in the debug build"),
            "pt" to listOf("se você ligar o Gemini", "anel de informações são opcionais e vêm desligados", "opcionais e só existem se você ligar", "só existem na build de debug"),
            "es" to listOf("si activas Gemini", "anillo de información son opcionales y vienen apagados", "opcionales y solo existen si las activas", "solo existen en la compilación de depuración"),
        )
        for ((lang, paths) in files) for (path in paths) {
            val terms = text(path)
            for (phrase in says.getValue(lang)) assertTrue("$path says \"$phrase\"", phrase in terms)
            for (phrase in old.getValue(lang)) assertTrue("$path no longer says \"$phrase\"", phrase !in terms)
        }
    }
}
