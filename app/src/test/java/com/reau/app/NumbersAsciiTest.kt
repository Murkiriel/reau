package com.reau.app

import com.reau.app.dash.DashReport
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.web.WebGuard
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Numbers that go to the dash, the network, the PIN and the log must be ASCII whatever the phone's language. Found by running
 * the code (audit of 20/10/2026): with the phone in Arabic (Egypt), Persian, Marathi or Burmese, `"%04d".format(42)` is
 * "٠٠٤٢", so the web PIN never matched and the dash got "??????" as the time left.
 */
class NumbersAsciiTest {
    private val saved = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(saved)

    private val locales = listOf("pt-BR", "en-US", "ar-EG", "fa-IR", "mr-IN", "my-MM", "hi-IN", "bn-BD", "ne-NP")

    private fun <T> eachLocale(check: (String) -> T) = locales.forEach { tag ->
        Locale.setDefault(Locale.forLanguageTag(tag))
        check(tag)
    }

    @Test
    fun `the time left for the dash is ASCII digits in every language`() = eachLocale { tag ->
        val remaining = DashCommands.remainingTime(3 * 3600L + 7 * 60)
        assertEquals(tag, "030700", remaining.text)
        assertTrue(tag, remaining.text.all { it.code < 128 })
    }

    @Test
    fun `the web PIN is four ASCII digits in every language`() = eachLocale { tag ->
        assertEquals(tag, "0042", WebGuard.format(42))
        assertEquals(tag, "9999", WebGuard.format(9999))
    }

    @Test
    fun `the dash firmware version is ASCII in every language`() = eachLocale { tag ->
        assertEquals(tag, "13.10", DashReport.Partition(block = 1, major = 13, minor = 10).version)
    }

    // ── a guard: a number formatted with %d/%f in code that is not screen text names its Locale ────────────────────

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private val plain = Regex("""String\.format\((?!\s*(?:java\.util\.)?Locale|\s*locale)""")
    private val extension = Regex(""""[^"\n]*%[-#+ 0,(]*\d*(?:\.\d+)?[dfeEgG][^"\n]*"\.format\((?!\s*(?:java\.util\.)?Locale|\s*locale)""")

    @Test
    fun `numbers formatted outside the screens name their Locale`() {
        val main = File(root(), "app/src/main/java/com/reau/app")
        val editions = listOf("debug").map { File(root(), "app/src/$it/java/com/reau/app") }
        // The screens (ui) show numbers in the rider's own language on purpose; the protobuf code is generated. Everything else
        // is walked (not a list of folders, which left new ones, like util, out of the guard).
        // Relative to each root, so a project that sits under a folder called "ui" or "proto" is still scanned.
        val files = (listOf(main) + editions).filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { val rel = "/" + it.relativeTo(root).path; !rel.contains("/proto/") && !rel.contains("/ui/") }.toList()
        }
        assertTrue("the guard found only ${files.size} files", files.size > 200)
        val offenders = files.flatMap { file ->
            val text = file.readText()
            (plain.findAll(text) + extension.findAll(text)).map { m ->
                "${file.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
            }.toList()
        }
        assertTrue("format a number with Locale.US: $offenders", offenders.isEmpty())
    }
}
