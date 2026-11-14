package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule for every (i) of the app (the owner's, 27/10/2026): its text gives the general idea in at most 3 sentences and at most 100 words
 * in all, in English, Portuguese and Spanish alike. The title above it and the "Understood" button are not counted; the detail that does
 * not fit lives in the documents (README, the site), not behind the (i).
 *
 * A sentence ends in `.`, `!` or `?` followed by a space or by the end, so no abbreviation with a period ("e.g.", "p. ej.", "EE. UU.") in an
 * info text: it would count as two. A colon or a semicolon does not end a sentence. The texts are found where the screens use them (an
 * `Info(title, stringResource(R.string.NAME))`, the third text of a `SettingSwitch`, the data of the info ring), so a new (i) is checked
 * without anyone remembering to list it.
 */
class InfoTextsTest {
    private val maxSentences = 3
    private val maxWords = 100

    private val src = File("src").takeIf { it.isDirectory } ?: File("app/src")

    private val uiSource: String = File(src, "main/java/com/reau/app/ui").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

    /** The names of the texts the screens show behind an (i). */
    private val infoNames: Set<String> = buildSet {
        Regex("""Info\(\s*[^,]+?,\s*stringResource\(\s*R\.string\.([a-z0-9_]+)""").findAll(uiSource).forEach { add(it.groupValues[1]) }
        Regex("""SettingSwitch\(\s*R\.string\.\w+,\s*R\.string\.\w+,\s*R\.string\.(\w+)""").findAll(uiSource).forEach { add(it.groupValues[1]) }
        Regex("""R\.string\.(info_ring_data_\w+_info)""").findAll(uiSource).forEach { add(it.groupValues[1]) }
    }

    private val resourceFiles = listOf("main").flatMap { set ->
        listOf("values", "values-pt", "values-es").flatMap { folder ->
            File(src, "$set/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().toList()
        }
    }

    /** Every (file, name, text) of an info text with something in it. */
    private val texts: List<Triple<File, String, String>> = resourceFiles.flatMap { file ->
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .filter { it.groupValues[1] in infoNames && it.groupValues[2].isNotBlank() }
            .map { Triple(file, it.groupValues[1], it.groupValues[2]) }
    }

    private fun words(text: String) = Regex("""\S+""").findAll(text.replace("\\n", " ")).count()

    private fun sentences(text: String) = text.trim().replace("\\n", " ").split(Regex("""(?<=[.!?])["”’)]?\s+""")).count { it.isNotBlank() }

    @Test
    fun `the screens have their info texts and the test finds them`() {
        // A guard: if the way the texts are found stopped working, the rule below would pass for nothing.
        assertTrue("${infoNames.size} info texts", infoNames.size >= 44)
        val inEnglish = texts.filter { it.first.path.contains("/values/") }.map { it.second }.toSet()
        assertEquals("not in the English texts: ${infoNames - inEnglish}", emptySet<String>(), infoNames - inEnglish)
        for (name in listOf("bike_range_info", "info_ring_info", "weather_info", "use_location", "dash_music", "web_view_long", "profiles_info")) {
            assertTrue("$name is not found", name in infoNames)
        }
    }

    @Test
    fun `an info text has at most 3 sentences`() {
        val over = texts.filter { sentences(it.third) > maxSentences }.map { "${it.first.parentFile?.name}/${it.second}: ${sentences(it.third)}" }
        assertEquals("more than $maxSentences sentences: $over", emptyList<String>(), over)
    }

    @Test
    fun `an info text has at most 100 words`() {
        val over = texts.filter { words(it.third) > maxWords }.map { "${it.first.parentFile?.name}/${it.second}: ${words(it.third)}" }
        assertEquals("more than $maxWords words: $over", emptyList<String>(), over)
    }

    /**
     * The same rule for every explanatory text of the "Joystick do painel" screen (the owner's, 28/10/2026): the line that opens a tab, the notes of
     * the locked buttons, of the info ring's button and of the music card, the call tab's lines. They are every `joystick_*` string the screen
     * reads, and the `call_*` ones of its call tab; the titles and the short names of the actions are far under the limits, so they pass without being told apart.
     */
    private val joystickNames: Set<String> =
        Regex("""R\.string\.((?:joystick|call)_\w+)""").findAll(File(src, "main/java/com/reau/app/ui/JoystickScreen.kt").readText() + File(src, "main/java/com/reau/app/ui/CallPage.kt").readText())
            .map { it.groupValues[1] }.toSet()

    private val joystickTexts: List<Triple<File, String, String>> = resourceFiles.flatMap { file ->
        Regex("""<string name="((?:joystick|call)_[^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .filter { it.groupValues[1] in joystickNames && it.groupValues[2].isNotBlank() }
            .map { Triple(file, it.groupValues[1], it.groupValues[2]) }
    }

    @Test
    fun `the joystick screen's texts are found, in the three languages`() {
        assertTrue("${joystickNames.size} joystick strings", joystickNames.size >= 30)
        for (name in listOf("joystick_intro", "joystick_info_ring_note", "joystick_locked_up_note", "joystick_call_intro", "joystick_music_off")) {
            assertTrue("$name is not read by the screen", name in joystickNames)
            for (folder in listOf("values", "values-pt", "values-es")) {
                assertTrue("$folder/$name", joystickTexts.any { it.second == name && it.first.parentFile?.name == folder })
            }
        }
    }

    @Test
    fun `a joystick text shows no hexadecimal code`() {
        // The owner's, 28/10/2026: a code such as 0x15 is too technical for the screen (it stays in the debug log and the debug overlay).
        val coded = joystickTexts.filter { Regex("""\b0x[0-9A-Fa-f]{2}\b""").containsMatchIn(it.third) }.map { "${it.first.parentFile?.name}/${it.second}" }
        assertEquals("joystick texts with a hexadecimal code: $coded", emptyList<String>(), coded)
    }

    @Test
    fun `a joystick text has at most 3 sentences and at most 100 words`() {
        val over = joystickTexts.filter { sentences(it.third) > maxSentences || words(it.third) > maxWords }
            .map { "${it.first.parentFile?.name}/${it.second}: ${sentences(it.third)} sentences, ${words(it.third)} words" }
        assertEquals("joystick texts over the rule: $over", emptyList<String>(), over)
    }

    @Test
    fun `the info ring's note keeps what the rider must know, in the three languages`() {
        val note = joystickTexts.filter { it.second == "joystick_info_ring_note" }.associate { it.first.parentFile?.name to it.third }
        assertEquals(setOf("values", "values-pt", "values-es"), note.keys)
        for ((folder, text) in note) assertFalse("$folder: no button code in the text (too technical for the screen)", "0x15" in text)
        assertTrue(note.getValue("values").let { "info ring" in it && "maneuver ring" in it && "another action" in it && "stops sending" in it })
        assertTrue(note.getValue("values-pt").let { "anel de informações" in it && "anel de manobras" in it && "outra ação" in it && "deixa de mandar" in it })
        assertTrue(note.getValue("values-es").let { "anillo de información" in it && "anillo de maniobras" in it && "otra acción" in it && "deja de enviar" in it })
    }

    @Test
    fun `the names of the zoom actions are only the name, with no explanation in parentheses`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val file = File(src, "main/res/$folder/strings.xml").readText()
            for (name in listOf("joystick_action_zoom_in", "joystick_action_zoom_out")) {
                val label = Regex("""<string name="$name">(.*?)</string>""").find(file)!!.groupValues[1]
                assertTrue("$folder/$name is \"$label\": the dash's button shows it cut", '(' !in label && label.length <= 12)
            }
        }
    }

    @Test
    fun `the counting counts what the rule means`() {
        assertEquals(3, sentences("One. Two: still two; here! Three?"))
        assertEquals(2, sentences("Above 7.6 mm/h it is heavy. It ends with ~."))
        assertEquals(1, sentences("""Tap \"Start server\". """.trim()))
        assertEquals(1, sentences("No final stop"))
        assertEquals(2, sentences("e.g. an abbreviation breaks it"))
        assertEquals(4, words("one two  three\\nfour"))
    }
}
