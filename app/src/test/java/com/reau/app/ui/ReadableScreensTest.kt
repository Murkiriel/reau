package com.reau.app.ui

import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.permissionTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a rider who cannot look closely, or who listens to the screen (TalkBack), is given: a permission that is only optional is not painted
 * as an error; every page's title and the terms' sections are headings; a warning that appears is announced. (The name of the dash not being
 * written to the encrypted store three times for every letter typed is SavePairingTest's.) The semantics of a composable are class (c):
 * only a test of the screen on a device reads them, so the source is.
 */
class ReadableScreensTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `a permission is fine when granted, an error when it is missing, and quiet when it is only optional`() {
        assertEquals(StatusKind.OK, permissionTone(granted = true, optional = false))
        assertEquals(StatusKind.OK, permissionTone(granted = true, optional = true))
        assertEquals(StatusKind.ERROR, permissionTone(granted = false, optional = false))
        assertEquals(StatusKind.IDLE, permissionTone(granted = false, optional = true))
        assertTrue(source("ui/PermissionsScreen.kt").contains("askTag = \"ask_\${e.permission}\", optional = optional,"))
        assertTrue(source("ui/components/InfoRows.kt").contains("val tone = permissionTone(granted, optional).color()"))
    }

    @Test
    fun `the page's title and the sections of the terms are headings for a screen reader`() {
        assertTrue(source("ui/components/TopBar.kt").contains("Modifier.weight(1f).semantics { heading() }.testTag(titleTag)"))
        val terms = source("ui/TermsScreen.kt")
        assertEquals("the title and each section", 2, Regex("""\.semantics \{ heading\(\) \}""").findAll(terms).count())
    }

    @Test
    fun `a warning that comes up on the main screen is announced`() {
        assertTrue(source("ui/components/Cards.kt").contains("semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }"))
    }

    @Test
    fun `the warning that the dash's name is missing opens the page that has the field, and the notification names it`() {
        val warning = source("ui/MainParts.kt").substringAfter("Warning.NAME_NEEDED ->").substringBefore("tag = \"dash_name_warning\"")
        assertTrue(warning.contains("R.string.action_open_bike_data_short"))
        assertTrue(warning.contains("extras.onOpenWifi"))
        for ((folder, page) in listOf("values" to "Bike data", "values-pt" to "Dados da moto", "values-es" to "Datos de la moto")) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]
            assertTrue("$folder: the button", text("action_open_bike_data_short").endsWith(page))
            assertTrue("$folder: the notification", text("notif_text_dash_name_needed").endsWith(page))
            assertEquals("$folder: the page is called that", page, text("tile_wifi"))
        }
    }

    @Test
    fun `the copy button says Copied for a moment, not for good`() {
        val web = source("ui/WebSection.kt")
        assertTrue(web.contains("delay(COPIED_SHOWN_MS)\n            copied = false"))
        assertTrue(web.contains("private const val COPIED_SHOWN_MS = 2_000L"))
    }
}
