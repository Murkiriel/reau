package com.reau.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the source can hold of the legibility floor, so that a new screen does not go under it: no loose font size under 12 sp
 * (a smaller text is set from the theme's styles, which are all 12 sp or more) and no touch target of 32 to 47 dp.
 */
class LegibilityFloorTest {
    private val ui = File("src/main/java/com/reau/app/ui")
    private val sources = ui.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `the sources were found`() {
        assertTrue("${sources.size} files", sources.size > 30)
    }

    @Test
    fun `no text is set under 12 sp`() {
        val found = sources.flatMap { file ->
            Regex("""fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp""").findAll(file.readText())
                .filter { it.groupValues[1].toDouble() < 12.0 }.map { "${file.name}: ${it.value}" }.toList()
        }
        assertTrue("text under 12 sp: $found", found.isEmpty())
    }

    @Test
    fun `no touch target is set from 32 to 47 dp`() {
        val found = sources.flatMap { file ->
            Regex("""(?:heightIn\(min\s*=\s*|\.size\(|\.height\(|\.width\()(3[2-9]|4[0-7])\.dp""").findAll(file.readText())
                .map { "${file.name}: ${it.value}" }.toList()
        }
        // Drawn things that are no target: a bridge-side avatar of 46 dp, a round icon of 40 dp, the sheet's grip of 36 dp, a shortcut's plate of 36 dp.
        val allowed = listOf("Bridge.kt: .size(46.dp", "Sheets.kt: .size(40.dp", "Sheets.kt: .size(36.dp", "Cards.kt: .size(36.dp")
        val rest = found.filterNot { f -> allowed.any { f.startsWith(it) } }
        assertTrue("touch targets under 48 dp: $rest", rest.isEmpty())
    }

    @Test
    fun `a state written as letters takes the tone made for text, never the icon's colour`() {
        fun source(name: String) = File(ui, name).readText()
        assertTrue(source("components/Bridge.kt").contains("color = kind.textColor(),"))
        assertTrue(source("components/Cards.kt").contains("color = kind.textColor())"))
        assertTrue(source("components/InfoRows.kt").contains("color = permissionTone(granted, optional).textColor(),"))
        assertTrue(source("ServerScreen.kt").contains("StatusKind.OK.textColor()"))
        assertFalse(source("ServerScreen.kt").contains("StatusKind.OK.color()"))
    }

    @Test
    fun `the main button is pinned under what scrolls, and the bridge card does not carry a second one`() {
        val main = File(ui, "MainScreen.kt").readText()
        assertTrue("the card is drawn without the button", main.contains("withButton = false"))
        val button = main.indexOf("PrimaryPowerButton(p.phase")
        assertTrue("the button is drawn", button > 0)
        assertTrue("and after the scrolling column, not inside it", button > main.indexOf("SnackbarHost(snackbar"))
    }
}
